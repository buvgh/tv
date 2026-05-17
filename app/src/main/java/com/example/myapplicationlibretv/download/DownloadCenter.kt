package com.example.myapplicationlibretv.download

import android.content.ContentUris
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.example.myapplicationlibretv.data.db.AppDatabase
import com.example.myapplicationlibretv.data.db.DownloadVideo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap

enum class DownloadStatus {
    QUEUED,
    RUNNING,
    PAUSED,
    COMPLETED,
    FAILED
}

data class DownloadTaskInfo(
    val id: String,
    val title: String,
    val status: DownloadStatus,
    val progressText: String,
    val rawUrl: String,
    val fileName: String? = null,
    val fileUri: String? = null,
    val errorMessage: String? = null
)

object DownloadCenter {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _tasks = MutableStateFlow<List<DownloadTaskInfo>>(emptyList())
    val tasks: StateFlow<List<DownloadTaskInfo>> = _tasks

    @Volatile
    private var appContext: Context? = null
    private var observeJob: Job? = null
    private var importedLegacy = false
    @Volatile
    private var playbackActive = false
    private val lastProgressWrites = ConcurrentHashMap<String, Long>()
    private val lastProgressTexts = ConcurrentHashMap<String, String>()

    fun setPlaybackActive(active: Boolean) {
        playbackActive = active
    }

    fun isPlaybackActive(): Boolean = playbackActive

    fun initialize(context: Context) {
        val targetContext = context.applicationContext
        appContext = targetContext
        if (observeJob == null) {
            val dao = AppDatabase.getDatabase(targetContext).videoDao()
            observeJob = scope.launch {
                dao.getDownloads().collect { downloads ->
                    _tasks.value = downloads.map { it.toTaskInfo() }
                }
            }
        }
        if (!importedLegacy) {
            importedLegacy = true
            scope.launch {
                importLegacyDownloads(targetContext)
                refreshStoredTitles(targetContext)
            }
        }
    }

    fun enqueue(context: Context, id: String, title: String, rawUrl: String) {
        initialize(context)
        val dao = AppDatabase.getDatabase(context.applicationContext).videoDao()
        scope.launch {
            dao.insertDownload(
                DownloadVideo(
                    taskId = id,
                    title = title,
                    status = DownloadStatus.QUEUED.name,
                    progressText = "等待开始",
                    rawUrl = rawUrl
                )
            )
        }
    }

    fun updateProgress(context: Context, id: String, progressText: String) {
        initialize(context)
        val dao = AppDatabase.getDatabase(context.applicationContext).videoDao()
        scope.launch {
            val current = dao.getDownloadById(id) ?: return@launch
            val now = System.currentTimeMillis()
            val previousText = lastProgressTexts[id]
            val previousWriteAt = lastProgressWrites[id] ?: 0L
            val importantChange = current.status != DownloadStatus.RUNNING.name ||
                progressText.startsWith("当前线路") ||
                progressText.startsWith("续传中")
            if (!importantChange && previousText == progressText && now - previousWriteAt < 1200L) {
                return@launch
            }
            if (!importantChange && now - previousWriteAt < 900L) {
                return@launch
            }
            lastProgressTexts[id] = progressText
            lastProgressWrites[id] = now
            dao.insertDownload(
                current.copy(
                    status = DownloadStatus.RUNNING.name,
                    progressText = progressText,
                    errorMessage = null,
                    updatedAt = now
                )
            )
        }
    }

    fun pause(context: Context, id: String) {
        initialize(context)
        val dao = AppDatabase.getDatabase(context.applicationContext).videoDao()
        scope.launch {
            val current = dao.getDownloadById(id) ?: return@launch
            dao.insertDownload(
                current.copy(
                    status = DownloadStatus.PAUSED.name,
                    progressText = "已暂停",
                    updatedAt = System.currentTimeMillis()
                )
            )
        }
    }

    fun complete(context: Context, id: String, fileName: String, fileUri: String) {
        initialize(context)
        val dao = AppDatabase.getDatabase(context.applicationContext).videoDao()
        scope.launch {
            lastProgressWrites.remove(id)
            lastProgressTexts.remove(id)
            val current = dao.getDownloadById(id) ?: return@launch
            val resolvedTitle = chooseDisplayTitle(current.title, fileName)
            dao.insertDownload(
                current.copy(
                    title = resolvedTitle,
                    status = DownloadStatus.COMPLETED.name,
                    progressText = "下载完成",
                    fileName = fileName,
                    fileUri = fileUri,
                    errorMessage = null,
                    updatedAt = System.currentTimeMillis()
                )
            )
        }
    }

    fun fail(context: Context, id: String, errorMessage: String) {
        initialize(context)
        val dao = AppDatabase.getDatabase(context.applicationContext).videoDao()
        scope.launch {
            lastProgressWrites.remove(id)
            lastProgressTexts.remove(id)
            val current = dao.getDownloadById(id) ?: return@launch
            dao.insertDownload(
                current.copy(
                    status = DownloadStatus.FAILED.name,
                    progressText = "下载失败",
                    errorMessage = errorMessage,
                    updatedAt = System.currentTimeMillis()
                )
            )
        }
    }

    fun delete(context: Context, id: String, removeFile: Boolean) {
        initialize(context)
        val targetContext = context.applicationContext
        val dao = AppDatabase.getDatabase(targetContext).videoDao()
        scope.launch {
            lastProgressWrites.remove(id)
            lastProgressTexts.remove(id)
            val current = dao.getDownloadById(id)
            if (current != null && removeFile) {
                deleteFile(targetContext, current.fileUri, current.fileName, current.title)
            }
            dao.deleteDownload(id)
        }
    }

    suspend fun getTask(context: Context, id: String): DownloadTaskInfo? {
        initialize(context)
        return AppDatabase.getDatabase(context.applicationContext).videoDao()
            .getDownloadById(id)
            ?.toTaskInfo()
    }

    private suspend fun importLegacyDownloads(context: Context) {
        val dao = AppDatabase.getDatabase(context).videoDao()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val projection = arrayOf(
                MediaStore.Downloads._ID,
                MediaStore.Downloads.DISPLAY_NAME,
                MediaStore.Downloads.MIME_TYPE
            )
            val selection = "${MediaStore.Downloads.MIME_TYPE} LIKE ? OR ${MediaStore.Downloads.DISPLAY_NAME} LIKE ? OR ${MediaStore.Downloads.DISPLAY_NAME} LIKE ? OR ${MediaStore.Downloads.DISPLAY_NAME} LIKE ?"
            val args = arrayOf("video/%", "%.mp4", "%.mkv", "%.ts")
            context.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                args,
                "${MediaStore.Downloads.DATE_ADDED} DESC"
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
                val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    val fileId = cursor.getLong(idIndex)
                    val fileName = cursor.getString(nameIndex) ?: continue
                    val uri = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, fileId)
                    if (dao.getDownloadByUri(uri.toString()) != null) continue
                    dao.insertDownload(
                        DownloadVideo(
                            taskId = "legacy_$fileId",
                            title = prettifyFileName(fileName),
                            status = DownloadStatus.COMPLETED.name,
                            progressText = "下载完成",
                            rawUrl = "",
                            fileName = fileName,
                            fileUri = uri.toString()
                        )
                    )
                }
            }
        } else {
            listOfNotNull(
                context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
                File(context.filesDir, "downloads").takeIf { it.exists() }
            ).forEach { dir ->
                dir.listFiles()
                    ?.filter { it.isFile && isVideoFile(it.name) }
                    ?.forEach { file ->
                        val uri = Uri.fromFile(file)
                        if (dao.getDownloadByUri(uri.toString()) != null) return@forEach
                        dao.insertDownload(
                            DownloadVideo(
                                taskId = "legacy_${file.absolutePath.hashCode()}",
                                title = prettifyFileName(file.name),
                                status = DownloadStatus.COMPLETED.name,
                                progressText = "下载完成",
                                rawUrl = "",
                                fileName = file.name,
                                fileUri = uri.toString()
                            )
                        )
                    }
            }
        }
    }

    private suspend fun refreshStoredTitles(context: Context) {
        val dao = AppDatabase.getDatabase(context).videoDao()
        dao.getDownloads().first().forEach { item ->
            val fileName = item.fileName ?: return@forEach
            val updatedTitle = chooseDisplayTitle(item.title, fileName)
            if (updatedTitle != item.title) {
                dao.insertDownload(
                    item.copy(
                        title = updatedTitle,
                        updatedAt = item.updatedAt
                    )
                )
            }
        }
    }

    private fun deleteFile(context: Context, fileUri: String?, fileName: String?, title: String) {
        var deleted = false
        if (!fileUri.isNullOrBlank()) {
            deleted = runCatching {
                val uri = Uri.parse(fileUri)
                when (uri.scheme) {
                    "content" -> context.contentResolver.delete(uri, null, null) > 0
                    "file" -> File(requireNotNull(uri.path)).delete()
                    else -> false
                }
            }.getOrDefault(false)
        }
        deleted = deleteFromMediaStoreByName(context, fileName, title) || deleted
        val deletedLocalPath = deleteFromLocalDownloadsByName(context, fileName, title)
        if (!deleted && deletedLocalPath == null) {
            return
        }
        deletedLocalPath?.let { deletedPath ->
            runCatching {
                MediaScannerConnection.scanFile(context, arrayOf(deletedPath), null, null)
            }
        }
    }

    private fun deleteFromMediaStoreByName(context: Context, fileName: String?, title: String): Boolean {
        if (fileName.isNullOrBlank()) return false
        val relativePaths = buildRelativePathCandidates(title)
        var deleted = false
        deleted = deleteFromCollectionByName(
            context = context,
            collectionUri = MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            idColumn = MediaStore.Downloads._ID,
            nameColumn = MediaStore.Downloads.DISPLAY_NAME,
            fileName = fileName,
            relativePaths = relativePaths
        ) || deleted
        deleted = deleteFromCollectionByName(
            context = context,
            collectionUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            idColumn = MediaStore.Video.Media._ID,
            nameColumn = MediaStore.Video.Media.DISPLAY_NAME,
            fileName = fileName,
            relativePaths = relativePaths
        ) || deleted
        deleted = deleteFromCollectionByName(
            context = context,
            collectionUri = MediaStore.Files.getContentUri("external"),
            idColumn = MediaStore.Files.FileColumns._ID,
            nameColumn = MediaStore.Files.FileColumns.DISPLAY_NAME,
            fileName = fileName,
            relativePaths = relativePaths
        ) || deleted
        return deleted
    }

    private fun deleteFromCollectionByName(
        context: Context,
        collectionUri: Uri,
        idColumn: String,
        nameColumn: String,
        fileName: String,
        relativePaths: List<String>
    ): Boolean {
        val projection = mutableListOf(idColumn, nameColumn).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.MediaColumns.RELATIVE_PATH)
            }
        }.toTypedArray()
        var deleted = false
        runCatching {
            context.contentResolver.query(
                collectionUri,
                projection,
                "$nameColumn = ?",
                arrayOf(fileName),
                null
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(idColumn)
                val relativePathIndex = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                } else {
                    -1
                }
                while (cursor.moveToNext()) {
                    val relativePath = if (relativePathIndex >= 0) cursor.getString(relativePathIndex) else null
                    val matchesPath = relativePaths.isEmpty() || relativePath == null || relativePaths.any { candidate ->
                        relativePath.equals(candidate, ignoreCase = true)
                    }
                    if (!matchesPath) continue
                    val uri = ContentUris.withAppendedId(collectionUri, cursor.getLong(idIndex))
                    deleted = context.contentResolver.delete(uri, null, null) > 0 || deleted
                }
            }
        }
        return deleted
    }

    private fun deleteFromLocalDownloadsByName(context: Context, fileName: String?, title: String): String? {
        if (fileName.isNullOrBlank()) return null
        val seriesTitle = inferSeriesTitle(title)
        val baseDirs = listOfNotNull(
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
            File(context.filesDir, "downloads").takeIf { it.exists() }
        )
        val candidateDirs = baseDirs.flatMap { base ->
            listOfNotNull(
                base,
                File(base, "枫林晚TV"),
                seriesTitle?.let { File(File(base, "枫林晚TV"), sanitizeFileNameForPath(it)) },
                seriesTitle?.let { File(base, sanitizeFileNameForPath(it)) }
            )
        }
        var deletedPath: String? = null
        candidateDirs.forEach { dir ->
            runCatching {
                File(dir, fileName).takeIf { it.exists() }?.let { file ->
                    if (file.delete()) {
                        deletedPath = file.absolutePath
                    }
                }
            }
        }
        return deletedPath
    }

    private fun buildRelativePathCandidates(title: String): List<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return emptyList()
        val appFolder = "枫林晚TV"
        val basePath = "${Environment.DIRECTORY_DOWNLOADS}/$appFolder"
        val seriesTitle = inferSeriesTitle(title)
            ?.let(::sanitizeFileNameForPath)
            ?.takeIf { it.isNotBlank() }
        return buildList {
            add("$basePath/")
            seriesTitle?.let { add("$basePath/$it/") }
        }
    }

    private fun inferSeriesTitle(title: String): String? {
        val trimmed = title.trim()
        return listOf(" · ", " - ", "_")
            .firstNotNullOfOrNull { separator ->
                trimmed.substringBefore(separator).takeIf { it != trimmed }
            }
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    private fun sanitizeFileNameForPath(name: String): String {
        return name.replace(Regex("[^a-zA-Z0-9._\\-\\u4e00-\\u9fa5]"), "_")
    }

    private fun isVideoFile(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".ts")
    }

    private fun chooseDisplayTitle(currentTitle: String, fileName: String): String {
        val normalizedCurrent = currentTitle.trim()
        return if (
            normalizedCurrent.isBlank() ||
            normalizedCurrent == "视频下载" ||
            normalizedCurrent.startsWith("video_", ignoreCase = true)
        ) {
            prettifyFileName(fileName)
        } else {
            normalizedCurrent
        }
    }

    private fun prettifyFileName(fileName: String): String {
        return fileName
            .substringBeforeLast('.')
            .replace('_', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
            .ifBlank { "已下载视频" }
    }

    private fun DownloadVideo.toTaskInfo(): DownloadTaskInfo {
        return DownloadTaskInfo(
            id = taskId,
            title = title,
            status = runCatching { DownloadStatus.valueOf(status) }.getOrDefault(DownloadStatus.FAILED),
            progressText = progressText,
            rawUrl = rawUrl,
            fileName = fileName,
            fileUri = fileUri,
            errorMessage = errorMessage
        )
    }
}

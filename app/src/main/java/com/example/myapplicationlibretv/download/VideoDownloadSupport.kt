package com.example.myapplicationlibretv.download

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.URLUtil
import com.example.myapplicationlibretv.data.api.NetworkTuning
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.URL
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import kotlin.coroutines.coroutineContext

private const val DIRECT_DOWNLOAD_PARALLELISM = 6
private const val M3U8_DOWNLOAD_PARALLELISM = 8
private const val MIN_PARALLEL_FILE_SIZE = 8L * 1024 * 1024

data class ParsedVideoUrl(val url: String, val headers: Map<String, String>)
data class DownloadResult(val fileName: String, val fileUri: String)

fun parseVideoUrl(input: String): ParsedVideoUrl {
    val trimmed = input.trim()
    val parts = trimmed.split("|", limit = 2)
    val url = parts.firstOrNull()?.trim().orEmpty()
    val headerPart = parts.getOrNull(1)?.trim().orEmpty()

    val headers = buildMap {
        if (headerPart.isNotBlank()) {
            headerPart.split("&")
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .forEach { pair ->
                    val idx = pair.indexOf("=")
                    if (idx <= 0 || idx >= pair.length - 1) return@forEach
                    val key = pair.substring(0, idx).trim()
                    val rawValue = pair.substring(idx + 1).trim()
                    if (key.isBlank() || rawValue.isBlank()) return@forEach
                    val value = runCatching {
                        URLDecoder.decode(rawValue, StandardCharsets.UTF_8.name())
                    }.getOrDefault(rawValue)
                    put(key, value)
                }
        }
    }

    return ParsedVideoUrl(url = url, headers = headers)
}

fun buildUnsafeOkHttpClient(): OkHttpClient {
    return NetworkTuning.createTunedClient(trustAllSsl = true)
}

fun buildRequest(
    parsed: ParsedVideoUrl,
    url: String = parsed.url,
    extraHeaders: Map<String, String> = emptyMap()
): Request {
    val builder = Request.Builder().url(url)
    (NetworkTuning.buildCommonHeaders(url, parsed.headers) + extraHeaders).forEach { (k, v) ->
        if (k.isNotBlank() && v.isNotBlank()) {
            builder.header(k, v)
        }
    }
    return builder.build()
}

fun sanitizeFileName(name: String): String {
    return name.replace(Regex("[^a-zA-Z0-9._\\-\\u4e00-\\u9fa5]"), "_")
}

suspend fun downloadVideoFile(
    context: Context,
    taskId: String,
    parsed: ParsedVideoUrl,
    displayTitle: String?,
    onProgress: (String) -> Unit = {}
): DownloadResult = withContext(Dispatchers.IO) {
    val client = buildUnsafeOkHttpClient()
    if (parsed.url.contains(".m3u8", ignoreCase = true)) {
        downloadM3u8AsTs(context, taskId, client, parsed, displayTitle, onProgress)
    } else {
        downloadDirectFile(context, taskId, client, parsed, displayTitle, onProgress)
    }
}

fun deleteDownloadArtifacts(context: Context, taskId: String) {
    downloadTaskDir(context, taskId).deleteRecursively()
}

private suspend fun downloadDirectFile(
    context: Context,
    taskId: String,
    client: OkHttpClient,
    parsed: ParsedVideoUrl,
    displayTitle: String?,
    onProgress: (String) -> Unit
): DownloadResult = coroutineScope {
    val taskDir = downloadTaskDir(context, taskId)
    val probe = client.newCall(buildRequest(parsed)).execute()
    probe.use { response ->
        ensureSuccessful(response)
        val body = response.body ?: error("Empty response body")
        val contentType = body.contentType()?.toString().orEmpty()
        val guessedName = URLUtil.guessFileName(parsed.url, null, contentType)
        val baseName = displayTitle?.takeIf { it.isNotBlank() } ?: guessedName
        val extension = guessedName.substringAfterLast('.', "")
        val fileName = sanitizeFileName(
            if (extension.isNotBlank() && !baseName.endsWith(".$extension", ignoreCase = true)) {
                "$baseName.$extension"
            } else {
                baseName
            }
        ).ifBlank { "video_${System.currentTimeMillis()}.mp4" }

        val contentLength = body.contentLength().coerceAtLeast(0L)
        val acceptRanges = response.header("Accept-Ranges").orEmpty().contains("bytes", ignoreCase = true)
        val mergedFile = File(taskDir, fileName)

        if (contentLength >= MIN_PARALLEL_FILE_SIZE && acceptRanges) {
            parallelDownloadByRange(
                client = client,
                parsed = parsed,
                totalBytes = contentLength,
                outputFile = mergedFile,
                onProgress = onProgress
            )
        } else if (acceptRanges && contentLength > 0L) {
            response.close()
            resumableSequentialDownload(
                client = client,
                parsed = parsed,
                outputFile = mergedFile,
                totalBytes = contentLength,
                onProgress = onProgress
            )
        } else {
            sequentialDownload(response, mergedFile, contentLength, onProgress)
        }

        val savedUri = persistTempFileToDownloads(context, mergedFile, fileName, contentType.ifBlank { "video/mp4" })
        taskDir.deleteRecursively()
        DownloadResult(fileName, savedUri.toString())
    }
}

private suspend fun parallelDownloadByRange(
    client: OkHttpClient,
    parsed: ParsedVideoUrl,
    totalBytes: Long,
    outputFile: File,
    onProgress: (String) -> Unit
) = coroutineScope {
    val chunkSize = (totalBytes / DIRECT_DOWNLOAD_PARALLELISM).coerceAtLeast(2L * 1024 * 1024)
    val ranges = buildList {
        var start = 0L
        while (start < totalBytes) {
            val end = (start + chunkSize - 1).coerceAtMost(totalBytes - 1)
            add(start to end)
            start = end + 1
        }
    }

    val partFiles = ranges.mapIndexed { index, _ -> File(outputFile.parentFile, "${outputFile.name}.part$index") }
    val existingBytes = partFiles.sumOf { it.takeIf(File::exists)?.length() ?: 0L }
    val downloadedBytes = AtomicLong(existingBytes.coerceAtMost(totalBytes))
    val startTime = System.currentTimeMillis()
    if (existingBytes > 0L) {
        onProgress(buildProgressText(downloadedBytes.get(), totalBytes, startTime, resumed = true))
    }

    ranges.mapIndexed { index, (start, end) ->
        async {
            coroutineContext.ensureActive()
            val partFile = partFiles[index]
            val expectedSize = end - start + 1
            val existingSize = partFile.takeIf(File::exists)?.length() ?: 0L
            if (existingSize >= expectedSize) return@async

            val rangeStart = start + existingSize
            val request = buildRequest(parsed, extraHeaders = mapOf("Range" to "bytes=$rangeStart-$end"))
            client.newCall(request).execute().use { response ->
                if (response.code !in listOf(200, 206)) error("HTTP ${response.code}")
                val body = response.body ?: error("Empty response body")
                FileOutputStream(partFile, existingSize > 0L).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var read: Int
                    body.byteStream().use { input ->
                        while (input.read(buffer).also { read = it } >= 0) {
                            coroutineContext.ensureActive()
                            output.write(buffer, 0, read)
                            val current = downloadedBytes.addAndGet(read.toLong())
                            onProgress(buildProgressText(current, totalBytes, startTime, resumed = existingBytes > 0L))
                        }
                    }
                }
            }
        }
    }.awaitAll()

    FileOutputStream(outputFile).use { merged ->
        partFiles.forEachIndexed { index, part ->
            val expectedSize = ranges[index].second - ranges[index].first + 1
            if (!part.exists() || part.length() < expectedSize) {
                error("分片 ${index + 1} 下载不完整")
            }
            FileInputStream(part).use { input -> input.copyTo(merged) }
        }
    }
}

private fun sequentialDownload(
    response: Response,
    outputFile: File,
    totalBytes: Long,
    onProgress: (String) -> Unit
) {
    if (outputFile.exists()) {
        outputFile.delete()
    }
    val body = response.body ?: error("Empty response body")
    val startTime = System.currentTimeMillis()
    var written = 0L
    FileOutputStream(outputFile).use { output ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var read: Int
        body.byteStream().use { input ->
            while (input.read(buffer).also { read = it } >= 0) {
                output.write(buffer, 0, read)
                written += read
                onProgress(buildProgressText(written, totalBytes, startTime))
            }
        }
    }
}

private fun resumableSequentialDownload(
    client: OkHttpClient,
    parsed: ParsedVideoUrl,
    outputFile: File,
    totalBytes: Long,
    onProgress: (String) -> Unit
) {
    val existingBytes = outputFile.takeIf(File::exists)?.length()?.coerceAtMost(totalBytes) ?: 0L
    if (existingBytes >= totalBytes && totalBytes > 0L) return

    val startTime = System.currentTimeMillis()
    if (existingBytes > 0L) {
        onProgress(buildProgressText(existingBytes, totalBytes, startTime, resumed = true))
    }

    val request = buildRequest(
        parsed,
        extraHeaders = if (existingBytes > 0L) mapOf("Range" to "bytes=$existingBytes-") else emptyMap()
    )
    client.newCall(request).execute().use { response ->
        if (response.code !in listOf(200, 206)) error("HTTP ${response.code}")
        val body = response.body ?: error("Empty response body")
        var written = existingBytes
        FileOutputStream(outputFile, existingBytes > 0L).use { output ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var read: Int
            body.byteStream().use { input ->
                while (input.read(buffer).also { read = it } >= 0) {
                    output.write(buffer, 0, read)
                    written += read
                    onProgress(buildProgressText(written, totalBytes, startTime, resumed = existingBytes > 0L))
                }
            }
        }
    }
}

private suspend fun downloadM3u8AsTs(
    context: Context,
    taskId: String,
    client: OkHttpClient,
    parsed: ParsedVideoUrl,
    displayTitle: String?,
    onProgress: (String) -> Unit
): DownloadResult = coroutineScope {
    val taskDir = downloadTaskDir(context, taskId)
    val mediaPlaylistUrl = resolveMediaPlaylistUrl(client, parsed)
    val playlistText = client.newCall(buildRequest(parsed, mediaPlaylistUrl)).execute().use { response ->
        ensureSuccessful(response)
        response.body?.string().orEmpty()
    }
    val segmentUrls = playlistText.lineSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .map { resolveUrl(mediaPlaylistUrl, it) }
        .toList()

    if (segmentUrls.isEmpty()) error("未解析到可下载分片")

    val title = sanitizeFileName(displayTitle?.takeIf { it.isNotBlank() } ?: "video_${System.currentTimeMillis()}")
    val fileName = if (title.endsWith(".ts", ignoreCase = true)) title else "$title.ts"
    val segmentFiles = List(segmentUrls.size) { index -> File(taskDir, "seg_$index.ts") }
    val existingSegments = segmentFiles.count { it.exists() && it.length() > 0L }.toLong()
    val completedSegments = AtomicLong(existingSegments)
    val limiter = Semaphore(M3U8_DOWNLOAD_PARALLELISM)

    if (existingSegments > 0L) {
        onProgress("续传分片 $existingSegments/${segmentUrls.size}")
    }

    segmentUrls.mapIndexed { index, segmentUrl ->
        async {
            coroutineContext.ensureActive()
            val segmentFile = segmentFiles[index]
            if (segmentFile.exists() && segmentFile.length() > 0L) return@async

            limiter.withPermit {
                client.newCall(buildRequest(parsed, segmentUrl)).execute().use { response ->
                    ensureSuccessful(response)
                    val body = response.body ?: error("分片内容为空")
                    FileOutputStream(segmentFile).use { output ->
                        body.byteStream().use { input -> input.copyTo(output) }
                    }
                }
                val done = completedSegments.incrementAndGet()
                onProgress(
                    if (existingSegments > 0L) {
                        "续传分片 $done/${segmentUrls.size}"
                    } else {
                        "分片 $done/${segmentUrls.size}"
                    }
                )
            }
        }
    }.awaitAll()

    val mergedFile = File(taskDir, fileName)
    FileOutputStream(mergedFile).use { merged ->
        segmentFiles.forEachIndexed { index, part ->
            if (!part.exists() || part.length() <= 0L) {
                error("分片 ${index + 1} 下载不完整")
            }
            FileInputStream(part).use { input -> input.copyTo(merged) }
        }
    }
    val savedUri = persistTempFileToDownloads(context, mergedFile, fileName, "video/mp2t")
    taskDir.deleteRecursively()
    DownloadResult(fileName, savedUri.toString())
}

private fun resolveMediaPlaylistUrl(client: OkHttpClient, parsed: ParsedVideoUrl): String {
    val playlistText = client.newCall(buildRequest(parsed)).execute().use { response ->
        ensureSuccessful(response)
        response.body?.string().orEmpty()
    }
    if (!playlistText.contains("#EXT-X-STREAM-INF")) {
        return parsed.url
    }

    val lines = playlistText.lines()
    var bestBandwidth = -1L
    var bestUrl: String? = null
    for (index in lines.indices) {
        val line = lines[index].trim()
        if (!line.startsWith("#EXT-X-STREAM-INF")) continue
        val bandwidth = Regex("BANDWIDTH=(\\d+)").find(line)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
        val nextLine = lines.drop(index + 1).firstOrNull { it.trim().isNotBlank() && !it.trim().startsWith("#") } ?: continue
        if (bandwidth >= bestBandwidth) {
            bestBandwidth = bandwidth
            bestUrl = resolveUrl(parsed.url, nextLine.trim())
        }
    }
    return bestUrl ?: parsed.url
}

private fun resolveUrl(baseUrl: String, path: String): String {
    return URL(URL(baseUrl), path).toString()
}

private fun downloadTaskDir(context: Context, taskId: String): File {
    val dir = File(context.cacheDir, "download_tasks/$taskId")
    if (!dir.exists()) dir.mkdirs()
    return dir
}

private fun persistTempFileToDownloads(
    context: Context,
    tempFile: File,
    fileName: String,
    mimeType: String
): Uri {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("无法创建下载文件")
        try {
            resolver.openOutputStream(uri)?.use { output ->
                FileInputStream(tempFile).use { input -> input.copyTo(output) }
            } ?: error("无法打开下载输出流")
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    } else {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: File(context.filesDir, "downloads")
        if (!dir.exists()) dir.mkdirs()
        val outputFile = File(dir, fileName)
        tempFile.copyTo(outputFile, overwrite = true)
        return Uri.fromFile(outputFile)
    }
}

private fun buildProgressText(
    downloadedBytes: Long,
    totalBytes: Long,
    startTimeMs: Long,
    resumed: Boolean = false
): String {
    val elapsedMs = (System.currentTimeMillis() - startTimeMs).coerceAtLeast(1L)
    val speedBytesPerSec = downloadedBytes * 1000 / elapsedMs
    val speedText = humanReadableBytes(speedBytesPerSec) + "/s"
    val prefix = if (resumed) "续传中" else "下载中"
    return if (totalBytes > 0L) {
        "$prefix ${(downloadedBytes * 100 / totalBytes).coerceIn(0, 100)}% · $speedText"
    } else {
        "$prefix · $speedText"
    }
}

private fun humanReadableBytes(bytes: Long): String {
    val kb = 1024.0
    val mb = kb * 1024
    return when {
        bytes >= mb -> String.format("%.1f MB", bytes / mb)
        bytes >= kb -> String.format("%.1f KB", bytes / kb)
        else -> "$bytes B"
    }
}

private fun ensureSuccessful(response: Response) {
    if (!response.isSuccessful) {
        error("HTTP ${response.code}")
    }
}

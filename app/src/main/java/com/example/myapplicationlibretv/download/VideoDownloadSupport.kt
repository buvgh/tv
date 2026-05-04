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
import java.io.IOException
import java.net.URL
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
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

private const val DIRECT_DOWNLOAD_PARALLELISM = 4
private const val M3U8_DOWNLOAD_PARALLELISM = 6
private const val PLAYBACK_DIRECT_DOWNLOAD_PARALLELISM = 2
private const val PLAYBACK_M3U8_DOWNLOAD_PARALLELISM = 2
private const val MIN_PARALLEL_FILE_SIZE = 8L * 1024 * 1024
private const val DOWNLOAD_REQUEST_RETRY_COUNT = 5

data class ParsedVideoUrl(val url: String, val headers: Map<String, String>)
data class DownloadResult(val fileName: String, val fileUri: String)
private data class HlsKeyInfo(val method: String, val uri: String?, val iv: String?)
private data class HlsSegmentItem(val url: String, val sequence: Long, val keyInfo: HlsKeyInfo?)
private enum class RemoteMediaKind {
    HLS,
    VIDEO,
    TEXT,
    UNKNOWN
}

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

suspend fun downloadVideoInput(
    context: Context,
    taskId: String,
    rawInput: String,
    displayTitle: String?,
    onProgress: (String) -> Unit = {}
): DownloadResult = withContext(Dispatchers.IO) {
    val candidates = rawInput
        .split("\n")
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()
    require(candidates.isNotEmpty()) { "下载地址为空" }

    var lastError: Throwable? = null
    candidates.forEachIndexed { index, rawUrl ->
        onProgress("嗅探线路 ${index + 1}/${candidates.size}")
        if (index > 0) {
            deleteDownloadArtifacts(context, taskId)
            onProgress("当前线路下载失败，尝试备用线路 ${index + 1}/${candidates.size}")
        }
        val parsed = parseVideoUrl(rawUrl)
        if (parsed.url.isBlank()) return@forEachIndexed
        val result = runCatching {
            downloadVideoFile(
                context = context,
                taskId = taskId,
                parsed = parsed,
                displayTitle = displayTitle,
                onProgress = onProgress
            )
        }
        if (result.isSuccess) {
            return@withContext result.getOrThrow()
        }
        lastError = result.exceptionOrNull()
        if (!shouldTryNextDownloadCandidate(lastError)) {
            throw lastError ?: IllegalStateException("下载失败")
        }
    }

    throw lastError ?: IllegalStateException("下载失败")
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
    val mediaKind = sniffRemoteMediaKind(client, parsed)
    if (parsed.url.contains(".m3u8", ignoreCase = true) || mediaKind == RemoteMediaKind.HLS) {
        downloadM3u8AsTs(context, taskId, client, parsed, displayTitle, onProgress)
    } else if (mediaKind == RemoteMediaKind.TEXT) {
        error("资源站返回的是文本/网页，不是视频文件")
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
    val probe = executeRequestWithRetry(client, buildRequest(parsed), label = "文件信息")
    probe.use { response ->
        ensureSuccessful(response)
        val body = response.body ?: error("Empty response body")
        val contentType = body.contentType()?.toString().orEmpty()
        val guessedName = URLUtil.guessFileName(parsed.url, null, contentType)
        if (isTextLikeResponse(contentType, guessedName)) {
            error("资源站返回的是文本/网页，不是视频文件")
        }
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
            response.close()
            runCatching {
                parallelDownloadByRange(
                    client = client,
                    parsed = parsed,
                    totalBytes = contentLength,
                    outputFile = mergedFile,
                    onProgress = onProgress
                )
            }.getOrElse {
                mergedFile.delete()
                partFilesFor(mergedFile).forEach { part -> part.delete() }
                onProgress("分段下载不可用，改用普通下载")
                resumableSequentialDownload(
                    client = client,
                    parsed = parsed,
                    outputFile = mergedFile,
                    totalBytes = contentLength,
                    onProgress = onProgress
                )
            }
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
            runCatching {
                sequentialDownload(response, mergedFile, contentLength, onProgress)
            }.getOrElse { firstError ->
                retrySequentialFromStart(
                    client = client,
                    parsed = parsed,
                    outputFile = mergedFile,
                    totalBytes = contentLength,
                    onProgress = onProgress,
                    firstError = firstError
                )
            }
        }

        if (!mergedFile.exists() || mergedFile.length() <= 0L) {
            error("下载文件为空")
        }
        val savedUri = persistTempFileToDownloads(
            context = context,
            tempFile = mergedFile,
            fileName = fileName,
            mimeType = contentType.ifBlank { "video/mp4" },
            seriesFolder = inferSeriesFolder(displayTitle)
        )
        taskDir.deleteRecursively()
        DownloadResult(fileName, savedUri.toString())
    }
}

private fun retrySequentialFromStart(
    client: OkHttpClient,
    parsed: ParsedVideoUrl,
    outputFile: File,
    totalBytes: Long,
    onProgress: (String) -> Unit,
    firstError: Throwable
) {
    var lastError = firstError
    repeat(DOWNLOAD_REQUEST_RETRY_COUNT - 1) { attempt ->
        if (!shouldRetryStreamRead(lastError)) throw lastError
        outputFile.delete()
        onProgress("连接中断，重新下载 ${attempt + 1}/${DOWNLOAD_REQUEST_RETRY_COUNT - 1}")
        try {
            executeRequestWithRetry(
                client = client,
                request = buildRequest(parsed),
                label = "重新下载"
            ).use { retryResponse ->
                ensureSuccessful(retryResponse)
                sequentialDownload(retryResponse, outputFile, totalBytes, onProgress)
            }
            return
        } catch (e: Throwable) {
            lastError = e
        }
    }
    throw lastError
}

private suspend fun parallelDownloadByRange(
    client: OkHttpClient,
    parsed: ParsedVideoUrl,
    totalBytes: Long,
    outputFile: File,
    onProgress: (String) -> Unit
) = coroutineScope {
    val parallelism = currentDirectDownloadParallelism()
    val chunkSize = (totalBytes / parallelism).coerceAtLeast(2L * 1024 * 1024)
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
            executeRequestWithRetry(
                client = client,
                request = request,
                acceptedCodes = setOf(206),
                label = "分片 ${index + 1}"
            ).use { response ->
                if (response.code != 206) {
                    error("分片 ${index + 1} 不支持断点下载")
                }
                val body = response.body ?: error("Empty response body")
                FileOutputStream(partFile, existingSize > 0L).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var read: Int
                    body.byteStream().use { input ->
                        while (input.read(buffer).also { read = it } >= 0) {
                            coroutineContext.ensureActive()
                            throttleForPlayback()
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
                throttleForPlayback()
                output.write(buffer, 0, read)
                written += read
                onProgress(buildProgressText(written, totalBytes, startTime))
            }
        }
    }
    if (totalBytes > 0L && written < totalBytes) {
        error("下载不完整：$written/$totalBytes")
    }
}

private fun partFilesFor(outputFile: File): List<File> {
    val parent = outputFile.parentFile ?: return emptyList()
    val prefix = "${outputFile.name}.part"
    return parent.listFiles()
        ?.filter { it.isFile && it.name.startsWith(prefix) }
        .orEmpty()
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
    executeRequestWithRetry(
        client = client,
        request = request,
        acceptedCodes = if (existingBytes > 0L) setOf(206) else setOf(200, 206),
        label = "续传"
    ).use { response ->
        if (existingBytes > 0L && response.code != 206) {
            outputFile.delete()
            sequentialDownload(response, outputFile, totalBytes, onProgress)
            return
        }
        val body = response.body ?: error("Empty response body")
        var written = existingBytes
        FileOutputStream(outputFile, existingBytes > 0L).use { output ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var read: Int
            body.byteStream().use { input ->
                while (input.read(buffer).also { read = it } >= 0) {
                    throttleForPlayback()
                    output.write(buffer, 0, read)
                    written += read
                    onProgress(buildProgressText(written, totalBytes, startTime, resumed = existingBytes > 0L))
                }
            }
        }
        if (totalBytes > 0L && written < totalBytes) {
            error("下载不完整：$written/$totalBytes")
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
    val playlistText = executeRequestWithRetry(client, buildRequest(parsed, mediaPlaylistUrl), label = "M3U8 列表").use { response ->
        ensureSuccessful(response)
        response.body?.string().orEmpty()
    }
    val segments = parseHlsSegments(mediaPlaylistUrl, playlistText)

    if (segments.isEmpty()) error("未解析到可下载分片")

    val title = sanitizeFileName(displayTitle?.takeIf { it.isNotBlank() } ?: "video_${System.currentTimeMillis()}")
    val fileName = if (title.endsWith(".ts", ignoreCase = true)) title else "$title.ts"
    val segmentFiles = List(segments.size) { index -> File(taskDir, "seg_$index.ts") }
    val existingSegments = segmentFiles.count { it.exists() && it.length() > 0L }.toLong()
    val completedSegments = AtomicLong(existingSegments)
    val limiter = Semaphore(currentM3u8DownloadParallelism())
    val keyCache = ConcurrentHashMap<String, ByteArray>()

    if (existingSegments > 0L) {
        onProgress("续传分片 $existingSegments/${segments.size}")
    }

    runCatching {
        downloadHlsSegments(
            client = client,
            parsed = parsed,
            segments = segments,
            segmentFiles = segmentFiles,
            limiter = limiter,
            keyCache = keyCache,
            completedSegments = completedSegments,
            existingSegments = existingSegments,
            onProgress = onProgress
        )
    }.getOrElse { firstError ->
        onProgress("分片并发失败，低速重试")
        downloadMissingHlsSegmentsSequentially(
            client = client,
            parsed = parsed,
            segments = segments,
            segmentFiles = segmentFiles,
            keyCache = keyCache,
            completedSegments = completedSegments,
            existingSegments = existingSegments,
            onProgress = onProgress
        )
        val missing = segmentFiles.indexOfFirst { !it.exists() || it.length() <= 0L }
        if (missing >= 0) {
            throw firstError
        }
    }

    val mergedFile = File(taskDir, fileName)
    FileOutputStream(mergedFile).use { merged ->
        segmentFiles.forEachIndexed { index, part ->
            if (!part.exists() || part.length() <= 0L) {
                error("分片 ${index + 1} 下载不完整")
            }
            FileInputStream(part).use { input -> input.copyTo(merged) }
        }
    }
    val savedUri = persistTempFileToDownloads(
        context = context,
        tempFile = mergedFile,
        fileName = fileName,
        mimeType = "video/mp2t",
        seriesFolder = inferSeriesFolder(displayTitle)
    )
    taskDir.deleteRecursively()
    DownloadResult(fileName, savedUri.toString())
}

private suspend fun downloadHlsSegments(
    client: OkHttpClient,
    parsed: ParsedVideoUrl,
    segments: List<HlsSegmentItem>,
    segmentFiles: List<File>,
    limiter: Semaphore,
    keyCache: ConcurrentHashMap<String, ByteArray>,
    completedSegments: AtomicLong,
    existingSegments: Long,
    onProgress: (String) -> Unit
) = coroutineScope {
    segments.mapIndexed { index, segment ->
        async {
            coroutineContext.ensureActive()
            val segmentFile = segmentFiles[index]
            if (segmentFile.exists() && segmentFile.length() > 0L) return@async

            limiter.withPermit {
                throttleForPlayback()
                writeHlsSegmentFile(
                    client = client,
                    parsed = parsed,
                    segment = segment,
                    index = index,
                    segmentFile = segmentFile,
                    keyCache = keyCache
                )
                reportHlsSegmentProgress(completedSegments, existingSegments, segments.size, onProgress)
            }
        }
    }.awaitAll()
}

private fun downloadMissingHlsSegmentsSequentially(
    client: OkHttpClient,
    parsed: ParsedVideoUrl,
    segments: List<HlsSegmentItem>,
    segmentFiles: List<File>,
    keyCache: ConcurrentHashMap<String, ByteArray>,
    completedSegments: AtomicLong,
    existingSegments: Long,
    onProgress: (String) -> Unit
) {
    segments.forEachIndexed { index, segment ->
        val segmentFile = segmentFiles[index]
        if (segmentFile.exists() && segmentFile.length() > 0L) return@forEachIndexed
        throttleForPlayback()
        writeHlsSegmentFile(
            client = client,
            parsed = parsed,
            segment = segment,
            index = index,
            segmentFile = segmentFile,
            keyCache = keyCache
        )
        reportHlsSegmentProgress(completedSegments, existingSegments, segments.size, onProgress)
    }
}

private fun writeHlsSegmentFile(
    client: OkHttpClient,
    parsed: ParsedVideoUrl,
    segment: HlsSegmentItem,
    index: Int,
    segmentFile: File,
    keyCache: ConcurrentHashMap<String, ByteArray>
) {
    val tempFile = File(segmentFile.parentFile, "${segmentFile.name}.download")
    tempFile.delete()
    val outputBytes = downloadHlsSegmentBytesWithRetry(
        client = client,
        parsed = parsed,
        segment = segment,
        index = index,
        keyCache = keyCache
    )
    FileOutputStream(tempFile).use { output ->
        output.write(outputBytes)
    }
    if (!tempFile.renameTo(segmentFile)) {
        tempFile.copyTo(segmentFile, overwrite = true)
        tempFile.delete()
    }
}

private fun reportHlsSegmentProgress(
    completedSegments: AtomicLong,
    existingSegments: Long,
    totalSegments: Int,
    onProgress: (String) -> Unit
) {
    val done = completedSegments.incrementAndGet()
    onProgress(
        if (existingSegments > 0L) {
            "续传分片 $done/$totalSegments"
        } else {
            "分片 $done/$totalSegments"
        }
    )
}

private fun downloadHlsSegmentBytesWithRetry(
    client: OkHttpClient,
    parsed: ParsedVideoUrl,
    segment: HlsSegmentItem,
    index: Int,
    keyCache: ConcurrentHashMap<String, ByteArray>
): ByteArray {
    var lastError: Throwable? = null
    repeat(DOWNLOAD_REQUEST_RETRY_COUNT) { attempt ->
        try {
            executeRequestWithRetry(client, buildRequest(parsed, segment.url), label = "分片 ${index + 1}").use { response ->
                ensureSuccessful(response)
                val body = response.body ?: error("分片内容为空")
                val bytes = body.bytes()
                return decryptHlsSegmentIfNeeded(
                    client = client,
                    parsed = parsed,
                    segment = segment,
                    encryptedBytes = bytes,
                    keyCache = keyCache
                )
            }
        } catch (e: Exception) {
            lastError = e
            if (!shouldRetryStreamRead(e) || attempt == DOWNLOAD_REQUEST_RETRY_COUNT - 1) throw e
            Thread.sleep(retryDelayMs(attempt))
        }
    }
    throw lastError ?: IOException("分片 ${index + 1} 下载失败")
}

private fun parseHlsSegments(mediaPlaylistUrl: String, playlistText: String): List<HlsSegmentItem> {
    val segments = mutableListOf<HlsSegmentItem>()
    var currentKey: HlsKeyInfo? = null
    var mediaSequence = 0L
    var nextSequence = 0L

    playlistText.lineSequence().forEach { rawLine ->
        val line = rawLine.trim()
        when {
            line.isBlank() -> Unit
            line.startsWith("#EXT-X-MEDIA-SEQUENCE", ignoreCase = true) -> {
                mediaSequence = line.substringAfter(':', "0").trim().toLongOrNull() ?: 0L
                nextSequence = mediaSequence
            }
            line.startsWith("#EXT-X-KEY", ignoreCase = true) -> {
                val attrs = parseHlsAttributes(line.substringAfter(':', ""))
                val method = attrs["METHOD"].orEmpty()
                currentKey = if (method.equals("NONE", ignoreCase = true)) {
                    null
                } else {
                    HlsKeyInfo(
                        method = method,
                        uri = attrs["URI"]?.let { resolveUrl(mediaPlaylistUrl, it) },
                        iv = attrs["IV"]
                    )
                }
            }
            line.startsWith("#") -> Unit
            else -> {
                segments += HlsSegmentItem(
                    url = resolveUrl(mediaPlaylistUrl, line),
                    sequence = nextSequence,
                    keyInfo = currentKey
                )
                nextSequence += 1
            }
        }
    }

    return segments
}

private fun parseHlsAttributes(input: String): Map<String, String> {
    val result = linkedMapOf<String, String>()
    var index = 0
    while (index < input.length) {
        while (index < input.length && (input[index] == ',' || input[index].isWhitespace())) index++
        val keyStart = index
        while (index < input.length && input[index] != '=') index++
        if (index >= input.length) break
        val key = input.substring(keyStart, index).trim().uppercase()
        index += 1
        val value = if (index < input.length && input[index] == '"') {
            index += 1
            val valueStart = index
            while (index < input.length && input[index] != '"') index++
            input.substring(valueStart, index).also {
                if (index < input.length && input[index] == '"') index++
            }
        } else {
            val valueStart = index
            while (index < input.length && input[index] != ',') index++
            input.substring(valueStart, index).trim()
        }
        if (key.isNotBlank()) result[key] = value
    }
    return result
}

private fun decryptHlsSegmentIfNeeded(
    client: OkHttpClient,
    parsed: ParsedVideoUrl,
    segment: HlsSegmentItem,
    encryptedBytes: ByteArray,
    keyCache: ConcurrentHashMap<String, ByteArray>
): ByteArray {
    val keyInfo = segment.keyInfo ?: return encryptedBytes
    if (!keyInfo.method.equals("AES-128", ignoreCase = true)) {
        error("不支持的 M3U8 加密方式: ${keyInfo.method}")
    }
    val keyUri = keyInfo.uri ?: error("M3U8 加密密钥地址为空")
    val keyBytes = keyCache.getOrPut(keyUri) {
        executeRequestWithRetry(client, buildRequest(parsed, keyUri), label = "M3U8 密钥").use { response ->
            ensureSuccessful(response)
            response.body?.bytes() ?: error("M3U8 密钥内容为空")
        }
    }
    val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
    cipher.init(
        Cipher.DECRYPT_MODE,
        SecretKeySpec(keyBytes, "AES"),
        IvParameterSpec(parseHlsIv(keyInfo.iv, segment.sequence))
    )
    return cipher.doFinal(encryptedBytes)
}

private fun parseHlsIv(rawIv: String?, sequence: Long): ByteArray {
    if (!rawIv.isNullOrBlank()) {
        val hex = rawIv.removePrefix("0x").removePrefix("0X").padStart(32, '0')
        return ByteArray(16) { index ->
            hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }
    val iv = ByteArray(16)
    var value = sequence
    for (index in 15 downTo 8) {
        iv[index] = (value and 0xff).toByte()
        value = value ushr 8
    }
    return iv
}

private fun resolveMediaPlaylistUrl(client: OkHttpClient, parsed: ParsedVideoUrl): String {
    val playlistText = executeRequestWithRetry(client, buildRequest(parsed), label = "M3U8 主列表").use { response ->
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

private fun sniffRemoteMediaKind(client: OkHttpClient, parsed: ParsedVideoUrl): RemoteMediaKind {
    val lower = parsed.url.lowercase()
    if (lower.contains(".m3u8")) return RemoteMediaKind.HLS
    if (listOf(".mp4", ".mkv", ".webm", ".mov", ".flv", ".ts").any { lower.contains(it) }) {
        return RemoteMediaKind.VIDEO
    }

    val request = buildRequest(
        parsed = parsed,
        extraHeaders = mapOf("Range" to "bytes=0-4095")
    )
    return runCatching {
        executeRequestWithRetry(
            client = client,
            request = request,
            acceptedCodes = setOf(200, 206),
            label = "资源嗅探"
        ).use { response ->
            val contentType = response.header("Content-Type").orEmpty().lowercase()
            val sample = response.body?.string().orEmpty().trimStart()
            when {
                contentType.contains("mpegurl") || sample.startsWith("#EXTM3U") -> RemoteMediaKind.HLS
                contentType.startsWith("video/") || contentType.contains("octet-stream") -> RemoteMediaKind.VIDEO
                contentType.startsWith("text/") ||
                    contentType.contains("html") ||
                    contentType.contains("json") ||
                    sample.startsWith("<!DOCTYPE", ignoreCase = true) ||
                    sample.startsWith("<html", ignoreCase = true) ||
                    sample.startsWith("{") -> RemoteMediaKind.TEXT
                else -> RemoteMediaKind.UNKNOWN
            }
        }
    }.getOrDefault(RemoteMediaKind.UNKNOWN)
}

private fun isTextLikeResponse(contentType: String, fileName: String): Boolean {
    val lowerType = contentType.lowercase()
    val lowerName = fileName.lowercase()
    return lowerName.endsWith(".txt") ||
        lowerName.endsWith(".html") ||
        lowerType.startsWith("text/") ||
        lowerType.contains("html") ||
        lowerType.contains("json")
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
    mimeType: String,
    seriesFolder: String? = null
): Uri {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(MediaStore.Downloads.RELATIVE_PATH, buildDownloadsRelativePath(seriesFolder))
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
        val baseDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: File(context.filesDir, "downloads")
        val dir = seriesFolder
            ?.let { File(baseDir, sanitizeFileName(it)) }
            ?: baseDir
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
        bytes >= mb -> String.format(Locale.ROOT, "%.1f MB", bytes / mb)
        bytes >= kb -> String.format(Locale.ROOT, "%.1f KB", bytes / kb)
        else -> "$bytes B"
    }
}

private fun ensureSuccessful(response: Response) {
    if (!response.isSuccessful) {
        error("HTTP ${response.code}${if (isTransientHttpCode(response.code)) "，资源站临时不可用" else ""}")
    }
}

private fun buildDownloadsRelativePath(seriesFolder: String?): String {
    val appFolder = "枫林晚TV"
    val safeSeries = seriesFolder
        ?.let(::sanitizeFileName)
        ?.takeIf { it.isNotBlank() }
    return if (safeSeries == null) {
        "${Environment.DIRECTORY_DOWNLOADS}/$appFolder"
    } else {
        "${Environment.DIRECTORY_DOWNLOADS}/$appFolder/$safeSeries"
    }
}

private fun inferSeriesFolder(displayTitle: String?): String? {
    val title = displayTitle?.trim().orEmpty()
    if (title.isBlank()) return null
    val separators = listOf(" · ", " - ", "_")
    val series = separators.firstNotNullOfOrNull { separator ->
        title.substringBefore(separator).takeIf { it != title }
    }?.trim()
    return series?.takeIf { it.length >= 2 }
}

private fun executeRequestWithRetry(
    client: OkHttpClient,
    request: Request,
    acceptedCodes: Set<Int>? = null,
    label: String = "请求"
): Response {
    var lastError: Throwable? = null
    repeat(DOWNLOAD_REQUEST_RETRY_COUNT) { attempt ->
        try {
            val response = client.newCall(request).execute()
            val accepted = acceptedCodes?.contains(response.code) ?: response.isSuccessful
            if (accepted) {
                return response
            }
            if (!isTransientHttpCode(response.code) || attempt == DOWNLOAD_REQUEST_RETRY_COUNT - 1) {
                val code = response.code
                response.close()
                throw IOException("$label HTTP $code")
            }
            lastError = IOException("HTTP ${response.code}")
            val retryAfterMs = response.header("Retry-After")
                ?.toLongOrNull()
                ?.coerceIn(1L, 8L)
                ?.times(1000L)
            response.close()
            Thread.sleep(retryAfterMs ?: retryDelayMs(attempt))
            return@repeat
        } catch (e: IOException) {
            lastError = e
            if (attempt == DOWNLOAD_REQUEST_RETRY_COUNT - 1) throw e
        }
        Thread.sleep(retryDelayMs(attempt))
    }
    throw lastError ?: IOException("$label 失败")
}

private fun retryDelayMs(attempt: Int): Long {
    return when (attempt) {
        0 -> 350L
        1 -> 800L
        2 -> 1_500L
        3 -> 2_500L
        else -> 4_000L
    }
}

private fun currentDirectDownloadParallelism(): Int {
    return if (DownloadCenter.isPlaybackActive()) {
        PLAYBACK_DIRECT_DOWNLOAD_PARALLELISM
    } else {
        DIRECT_DOWNLOAD_PARALLELISM
    }
}

private fun currentM3u8DownloadParallelism(): Int {
    return if (DownloadCenter.isPlaybackActive()) {
        PLAYBACK_M3U8_DOWNLOAD_PARALLELISM
    } else {
        M3U8_DOWNLOAD_PARALLELISM
    }
}

private fun throttleForPlayback() {
    if (DownloadCenter.isPlaybackActive()) {
        Thread.sleep(30L)
    }
}

private fun isTransientHttpCode(code: Int): Boolean {
    return code == 408 || code == 425 || code == 429 || code == 500 || code == 502 || code == 503 || code == 504
}

private fun shouldTryNextDownloadCandidate(error: Throwable?): Boolean {
    val message = error?.message.orEmpty()
    return message.contains("timeout", ignoreCase = true) ||
        message.contains("timed out", ignoreCase = true) ||
        message.contains("unexpected end of stream", ignoreCase = true) ||
        message.contains("unexpected end", ignoreCase = true) ||
        message.contains("stream was reset", ignoreCase = true) ||
        message.contains("connection", ignoreCase = true) ||
        message.contains("文本/网页", ignoreCase = true) ||
        message.contains("不是视频", ignoreCase = true) ||
        Regex("""HTTP\s+(408|425|429|500|502|503|504)""").containsMatchIn(message)
}

private fun shouldRetryStreamRead(error: Throwable): Boolean {
    val message = error.message.orEmpty()
    return error is IOException ||
        message.contains("timeout", ignoreCase = true) ||
        message.contains("unexpected end", ignoreCase = true) ||
        message.contains("stream was reset", ignoreCase = true)
}

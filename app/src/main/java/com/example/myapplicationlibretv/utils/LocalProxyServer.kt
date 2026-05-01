package com.example.myapplicationlibretv.utils

import android.content.Context
import android.util.Base64
import android.util.Log
import com.example.myapplicationlibretv.data.api.NetworkTuning
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request

class LocalProxyServer(private val context: Context, private val port: Int = 8888) : NanoHTTPD(port) {
    companion object {
        @Volatile
        private var instance: LocalProxyServer? = null

        fun getInstance(context: Context): LocalProxyServer {
            return instance ?: synchronized(this) {
                instance ?: LocalProxyServer(context.applicationContext).also { instance = it }
            }
        }
    }

    private val client = NetworkTuning.createTunedClient(trustAllSsl = true)
    private val cacheDir = File(context.cacheDir, "proxy_cache").apply { if (!exists()) mkdirs() }
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val semaphore = Semaphore(4)
    private val downloadingJobs = ConcurrentHashMap<String, Job>()
    private val playlistSegments = ConcurrentHashMap<String, List<String>>()
    private val tsToPlaylistMap = ConcurrentHashMap<String, String>()
    private val activeDownloads = ConcurrentHashMap<String, SharedDownload>()
    private val upstreamHeaders = ConcurrentHashMap<String, Map<String, String>>()
    private val directPrefetchStates = ConcurrentHashMap<String, DirectPrefetchState>()

    var wasStarted: Boolean = false

    private val maxCacheSize = 1024L * 1024L * 1024L
    private val directChunkSize = 2L * 1024L * 1024L
    private val directPrefetchParallelism = 4
    private val directPrefetchMinSize = 8L * 1024L * 1024L
    private val directPrefetchChunks = 12

    private inner class SharedDownload(val url: String, val file: File, val headers: Map<String, String>) {
        private val listeners = CopyOnWriteArrayList<java.io.PipedOutputStream>()
        @Volatile var isCompleted = false
        @Volatile var error: Exception? = null

        fun createInputStream(): InputStream {
            if (isCompleted && file.exists()) {
                return FileInputStream(file)
            }
            val pos = java.io.PipedOutputStream()
            val input = java.io.PipedInputStream(pos, 1024 * 64)
            listeners.add(pos)
            return input
        }

        fun start() {
            scope.launch {
                try {
                    val requestBuilder = Request.Builder().url(url)
                    headers.forEach { (k, v) ->
                        if (shouldForwardHeader(k)) requestBuilder.header(k, v)
                    }
                    client.newCall(requestBuilder.build()).execute().use { response ->
                        if (!response.isSuccessful) throw IOException("Unexpected code $response")
                        val body = response.body ?: throw IOException("Empty body")
                        FileOutputStream(file).use { output ->
                            body.byteStream().use { input ->
                                val buffer = ByteArray(16 * 1024)
                                var bytesRead: Int
                                while (input.read(buffer).also { bytesRead = it } != -1) {
                                    output.write(buffer, 0, bytesRead)
                                    listeners.forEach { listener ->
                                        try {
                                            listener.write(buffer, 0, bytesRead)
                                            listener.flush()
                                        } catch (_: IOException) {
                                            listeners.remove(listener)
                                            runCatching { listener.close() }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    isCompleted = true
                } catch (e: Exception) {
                    error = e
                    Log.e("LocalProxyServer", "Download failed for $url", e)
                } finally {
                    listeners.forEach { runCatching { it.close() } }
                    listeners.clear()
                    activeDownloads.remove(url)
                    evictCacheIfNeeded()
                }
            }
        }
    }

    private data class DirectPrefetchState(
        val url: String,
        val headers: Map<String, String>,
        val mimeType: String,
        val totalBytes: Long,
        val chunkSize: Long,
        val chunkCount: Int,
        val chunkFiles: List<File>
    ) {
        fun expectedSize(index: Int): Long {
            if (index !in 0 until chunkCount) return 0L
            val start = index * chunkSize
            val endExclusive = min(totalBytes, start + chunkSize)
            return (endExclusive - start).coerceAtLeast(0L)
        }
    }

    private data class DirectMediaMetadata(
        val mimeType: String,
        val totalBytes: Long,
        val acceptRanges: Boolean
    )

    private data class ByteRange(val start: Long, val endInclusive: Long) {
        val length: Long get() = (endInclusive - start + 1L).coerceAtLeast(0L)

        fun normalize(totalBytes: Long): ByteRange {
            val safeTotal = totalBytes.coerceAtLeast(1L)
            val safeStart = start.coerceIn(0L, safeTotal - 1)
            val safeEnd = endInclusive.coerceIn(safeStart, safeTotal - 1)
            return ByteRange(safeStart, safeEnd)
        }
    }

    override fun serve(session: IHTTPSession): Response {
        if (session.uri != "/proxy") {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
        }

        val encodedUrl = session.parameters["url"]?.getOrNull(0)
            ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Missing url")
        val originalUrl = runCatching {
            String(Base64.decode(encodedUrl, Base64.URL_SAFE))
        }.getOrElse {
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Invalid encoding")
        }

        val headers = buildForwardHeaders(originalUrl, session.headers)
        Log.d("LocalProxyServer", "Proxying: $originalUrl")
        return when {
            originalUrl.contains(".m3u8", ignoreCase = true) -> handleM3u8(originalUrl, headers)
            tsToPlaylistMap.containsKey(originalUrl) || originalUrl.endsWith(".ts", ignoreCase = true) ->
                handleTs(originalUrl, headers)
            else -> handleDirectMedia(originalUrl, headers, session.headers)
        }
    }

    private fun handleM3u8(originalUrl: String, headers: Map<String, String>): Response {
        return try {
            upstreamHeaders[originalUrl] = headers
            val requestBuilder = Request.Builder().url(originalUrl)
            headers.forEach { (k, v) ->
                if (shouldForwardHeader(k)) requestBuilder.header(k, v)
            }
            val body = client.newCall(requestBuilder.build()).execute().use { response ->
                response.body?.string().orEmpty()
            }

            val baseUrl = originalUrl.substringBeforeLast("/", originalUrl)
            val tsList = mutableListOf<String>()
            val rewritten = body.lines().joinToString("\n") { line ->
                val trimmed = line.trim()
                if (trimmed.isBlank() || trimmed.startsWith("#")) {
                    line
                } else {
                    val absoluteUrl = resolveUrl(baseUrl, trimmed)
                    tsList += absoluteUrl
                    tsToPlaylistMap[absoluteUrl] = originalUrl
                    upstreamHeaders[absoluteUrl] = headers
                    val encoded = Base64.encodeToString(
                        absoluteUrl.toByteArray(),
                        Base64.URL_SAFE or Base64.NO_WRAP
                    )
                    "http://127.0.0.1:$port/proxy?url=$encoded"
                }
            }

            playlistSegments[originalUrl] = tsList
            tsList.take(8).forEach { preloadTs(it, headers) }
            newFixedLengthResponse(Response.Status.OK, "application/vnd.apple.mpegurl", rewritten)
        } catch (e: Exception) {
            Log.e("LocalProxyServer", "Error handling M3U8", e)
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, e.message)
        }
    }

    private fun handleTs(tsUrl: String, headers: Map<String, String>): Response {
        val cacheFile = File(cacheDir, md5(tsUrl))
        if (cacheFile.exists() && cacheFile.length() > 0) {
            cacheFile.setLastModified(System.currentTimeMillis())
            triggerSlidingWindowPreload(tsUrl, headers)
            return newChunkedResponse(Response.Status.OK, "video/mp2t", FileInputStream(cacheFile))
        }

        val download = activeDownloads.getOrPut(tsUrl) {
            SharedDownload(tsUrl, cacheFile, headers).also { it.start() }
        }
        triggerSlidingWindowPreload(tsUrl, headers)
        return newChunkedResponse(Response.Status.OK, "video/mp2t", download.createInputStream())
    }

    private fun handleDirectMedia(
        url: String,
        headers: Map<String, String>,
        requestHeaders: Map<String, String>
    ): Response {
        val requestedRange = parseRangeHeader(requestHeaders["range"])
        val state = directPrefetchStates[url] ?: prepareDirectPrefetch(url, headers)
        if (state != null) {
            val range = requestedRange?.normalize(state.totalBytes) ?: ByteRange(0L, state.totalBytes - 1L)
            return newFixedLengthResponse(
                if (requestedRange != null) Response.Status.PARTIAL_CONTENT else Response.Status.OK,
                state.mimeType,
                DirectPrefetchInputStream(state, range.start, range.endInclusive),
                range.length
            ).apply {
                addHeader("Accept-Ranges", "bytes")
                if (requestedRange != null) {
                    addHeader("Content-Range", "bytes ${range.start}-${range.endInclusive}/${state.totalBytes}")
                }
            }
        }

        val cacheFile = File(cacheDir, md5(url))
        if (cacheFile.exists() && cacheFile.length() > 0) {
            cacheFile.setLastModified(System.currentTimeMillis())
            if (requestedRange == null) {
                return newFixedLengthResponse(
                    Response.Status.OK,
                    detectMimeType(url),
                    FileInputStream(cacheFile),
                    cacheFile.length()
                ).apply {
                    addHeader("Accept-Ranges", "bytes")
                }
            }
            val range = requestedRange.normalize(cacheFile.length())
            return newFixedLengthResponse(
                Response.Status.PARTIAL_CONTENT,
                detectMimeType(url),
                FileInputStream(cacheFile).apply { skip(range.start) },
                range.length
            ).apply {
                addHeader("Accept-Ranges", "bytes")
                addHeader("Content-Range", "bytes ${range.start}-${range.endInclusive}/${cacheFile.length()}")
            }
        }

        val download = activeDownloads.getOrPut(url) {
            SharedDownload(url, cacheFile, headers).also { it.start() }
        }
        return newChunkedResponse(Response.Status.OK, detectMimeType(url), download.createInputStream())
    }

    private fun triggerSlidingWindowPreload(tsUrl: String, headers: Map<String, String>) {
        val playlistUrl = tsToPlaylistMap[tsUrl] ?: return
        val segments = playlistSegments[playlistUrl] ?: return
        val index = segments.indexOf(tsUrl)
        if (index == -1) return
        repeat(10) { offset ->
            segments.getOrNull(index + offset + 1)?.let { preloadTs(it, headers) }
        }
    }

    private fun downloadSync(url: String, file: File, headers: Map<String, String>) {
        val download = activeDownloads.getOrPut(url) {
            SharedDownload(url, file, headers).also { it.start() }
        }
        while (!download.isCompleted && download.error == null) {
            Thread.sleep(100)
        }
        download.error?.let { throw it }
    }

    private fun preloadTs(url: String, headers: Map<String, String>) {
        val file = File(cacheDir, md5(url))
        if (file.exists()) {
            file.setLastModified(System.currentTimeMillis())
            return
        }
        if (downloadingJobs.containsKey(url)) return

        val job = scope.launch {
            semaphore.acquire()
            try {
                if (!file.exists()) {
                    downloadSync(url, file, headers)
                    evictCacheIfNeeded()
                }
            } catch (_: Exception) {
            } finally {
                semaphore.release()
                downloadingJobs.remove(url)
            }
        }
        downloadingJobs[url] = job
    }

    private fun prepareDirectPrefetch(url: String, headers: Map<String, String>): DirectPrefetchState? {
        directPrefetchStates[url]?.let { return it }
        upstreamHeaders[url] = headers
        val metadata = probeDirectMedia(url, headers) ?: return null
        if (!metadata.acceptRanges || metadata.totalBytes < directPrefetchMinSize) {
            return null
        }

        val chunkCount = ((metadata.totalBytes + directChunkSize - 1) / directChunkSize).toInt()
        val chunkFiles = List(chunkCount) { index ->
            File(cacheDir, "${md5(url)}.chunk$index")
        }
        val state = DirectPrefetchState(
            url = url,
            headers = headers,
            mimeType = metadata.mimeType,
            totalBytes = metadata.totalBytes,
            chunkSize = directChunkSize,
            chunkCount = chunkCount,
            chunkFiles = chunkFiles
        )
        directPrefetchStates[url] = state
        repeat(min(directPrefetchChunks, chunkCount)) { preloadDirectChunk(state, it) }
        return state
    }

    private fun preloadDirectChunk(state: DirectPrefetchState, index: Int) {
        val key = "${state.url}#chunk#$index"
        if (downloadingJobs.containsKey(key)) return
        val expected = state.expectedSize(index)
        val chunkFile = state.chunkFiles[index]
        if (chunkFile.exists() && chunkFile.length() >= expected) {
            chunkFile.setLastModified(System.currentTimeMillis())
            return
        }

        val job = scope.launch {
            semaphore.acquire()
            try {
                val start = index * state.chunkSize
                val end = (start + expected - 1).coerceAtLeast(start)
                val requestBuilder = Request.Builder().url(state.url)
                state.headers.forEach { (k, v) ->
                    if (shouldForwardHeader(k)) requestBuilder.header(k, v)
                }
                requestBuilder.header("Range", "bytes=$start-$end")
                client.newCall(requestBuilder.build()).execute().use { response ->
                    if (response.code !in listOf(200, 206)) {
                        throw IOException("Unexpected code ${response.code}")
                    }
                    val body = response.body ?: throw IOException("Empty body")
                    FileOutputStream(chunkFile, false).use { output ->
                        body.byteStream().use { input -> input.copyTo(output) }
                    }
                }
                chunkFile.setLastModified(System.currentTimeMillis())
                evictCacheIfNeeded()
            } catch (_: Exception) {
            } finally {
                semaphore.release()
                downloadingJobs.remove(key)
            }
        }
        downloadingJobs[key] = job
    }

    private inner class DirectPrefetchInputStream(
        private val state: DirectPrefetchState,
        startOffset: Long,
        private val endOffsetInclusive: Long
    ) : InputStream() {
        private var chunkIndex = (startOffset / state.chunkSize).toInt()
        private var offsetInChunk = (startOffset % state.chunkSize).toInt()
        private var bytesRemaining = (endOffsetInclusive - startOffset + 1L).coerceAtLeast(0L)
        private var currentInput: InputStream? = null

        override fun read(): Int {
            val one = ByteArray(1)
            val count = read(one, 0, 1)
            return if (count <= 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(buffer: ByteArray, off: Int, len: Int): Int {
            if (bytesRemaining <= 0L) return -1
            while (chunkIndex < state.chunkCount) {
                val input = currentInput ?: openChunkInput(chunkIndex)?.also { currentInput = it }
                if (input == null) return -1
                val allowed = min(len.toLong(), bytesRemaining).toInt()
                val read = input.read(buffer, off, allowed)
                if (read >= 0) {
                    bytesRemaining -= read.toLong()
                    return read
                }
                input.close()
                currentInput = null
                chunkIndex += 1
                val nextWarmIndex = chunkIndex + directPrefetchParallelism
                if (nextWarmIndex < state.chunkCount) {
                    preloadDirectChunk(state, nextWarmIndex)
                }
            }
            return -1
        }

        override fun close() {
            currentInput?.close()
            currentInput = null
        }

        private fun openChunkInput(index: Int): InputStream? {
            val chunkFile = state.chunkFiles.getOrNull(index) ?: return null
            val expected = state.expectedSize(index)
            val deadline = System.currentTimeMillis() + 20_000L
            while ((!chunkFile.exists() || chunkFile.length() < expected) && System.currentTimeMillis() < deadline) {
                Thread.sleep(60)
            }
            if (!chunkFile.exists() || chunkFile.length() <= 0L) return null
            return FileInputStream(chunkFile).apply {
                if (offsetInChunk > 0) {
                    skip(offsetInChunk.toLong())
                    offsetInChunk = 0
                }
            }
        }
    }

    private fun parseRangeHeader(header: String?): ByteRange? {
        val raw = header?.trim()?.takeIf { it.startsWith("bytes=") } ?: return null
        val token = raw.removePrefix("bytes=").substringBefore(",")
        val parts = token.split("-", limit = 2)
        if (parts.size != 2) return null
        val start = parts[0].trim().toLongOrNull()
        val end = parts[1].trim().toLongOrNull()
        return when {
            start != null && end != null -> ByteRange(start, end)
            start != null -> ByteRange(start, Long.MAX_VALUE)
            else -> null
        }
    }

    private fun probeDirectMedia(url: String, headers: Map<String, String>): DirectMediaMetadata? {
        return runCatching {
            val requestBuilder = Request.Builder().url(url)
            headers.forEach { (k, v) ->
                if (shouldForwardHeader(k)) requestBuilder.header(k, v)
            }
            requestBuilder.header("Range", "bytes=0-0")
            client.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val contentRange = response.header("Content-Range").orEmpty()
                val totalBytes = contentRange.substringAfterLast("/", "").toLongOrNull()
                    ?: response.header("Content-Length")?.toLongOrNull()
                    ?: response.body?.contentLength()
                    ?: 0L
                val acceptRanges = response.code == 206 ||
                    response.header("Accept-Ranges").orEmpty().contains("bytes", ignoreCase = true) ||
                    contentRange.startsWith("bytes", ignoreCase = true)
                DirectMediaMetadata(
                    mimeType = response.header("Content-Type").orEmpty().ifBlank { detectMimeType(url) },
                    totalBytes = totalBytes.coerceAtLeast(0L),
                    acceptRanges = acceptRanges
                )
            }
        }.getOrNull()
    }

    private fun evictCacheIfNeeded() {
        scope.launch {
            val files = cacheDir.listFiles()?.filter { it.isFile } ?: return@launch
            var totalSize = files.sumOf { it.length() }
            if (totalSize <= maxCacheSize) return@launch
            files.sortedBy { it.lastModified() }.forEach { file ->
                val size = file.length()
                if (file.delete()) {
                    totalSize -= size
                }
                if (totalSize <= maxCacheSize * 0.7) return@forEach
            }
        }
    }

    private fun resolveUrl(baseUrl: String, relativeUrl: String): String {
        return try {
            val base = baseUrl.toHttpUrlOrNull()
            if (base != null) {
                base.resolve(relativeUrl)?.toString() ?: relativeUrl
            } else {
                fallbackResolve(baseUrl, relativeUrl)
            }
        } catch (_: Exception) {
            fallbackResolve(baseUrl, relativeUrl)
        }
    }

    private fun fallbackResolve(baseUrl: String, relativeUrl: String): String {
        return when {
            relativeUrl.startsWith("http") -> relativeUrl
            relativeUrl.startsWith("/") -> {
                runCatching {
                    val urlObj = URL(baseUrl)
                    val portPart = if (urlObj.port != -1) ":${urlObj.port}" else ""
                    "${urlObj.protocol}://${urlObj.host}$portPart$relativeUrl"
                }.getOrDefault(relativeUrl)
            }
            else -> "${baseUrl.substringBeforeLast("/", baseUrl)}/$relativeUrl"
        }
    }

    private fun shouldForwardHeader(key: String): Boolean {
        val low = key.lowercase()
        return low != "host" &&
            low != "connection" &&
            low != "keep-alive" &&
            low != "proxy-connection" &&
            low != "accept-encoding"
    }

    private fun md5(str: String): String {
        val digest = MessageDigest.getInstance("MD5")
        digest.update(str.toByteArray())
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun getProxyUrl(originalUrl: String, headers: Map<String, String> = emptyMap()): String {
        if (headers.isNotEmpty()) {
            upstreamHeaders[originalUrl] = headers
        }
        val encoded = Base64.encodeToString(
            originalUrl.toByteArray(),
            Base64.URL_SAFE or Base64.NO_WRAP
        )
        return "http://127.0.0.1:$port/proxy?url=$encoded"
    }

    fun prefetch(originalUrl: String, headers: Map<String, String>) {
        scope.launch {
            try {
                upstreamHeaders[originalUrl] = headers
                if (originalUrl.contains(".m3u8", ignoreCase = true)) {
                    val requestBuilder = Request.Builder().url(originalUrl)
                    headers.forEach { (k, v) ->
                        if (shouldForwardHeader(k)) requestBuilder.header(k, v)
                    }
                    client.newCall(requestBuilder.build()).execute().use { response ->
                        val body = response.body?.string() ?: return@use
                        val baseUrl = originalUrl.substringBeforeLast("/", originalUrl)
                        val tsList = body.lines()
                            .filter { it.isNotBlank() && !it.startsWith("#") }
                            .map { resolveUrl(baseUrl, it.trim()) }
                        playlistSegments[originalUrl] = tsList
                        tsList.take(5).forEach { tsUrl ->
                            tsToPlaylistMap[tsUrl] = originalUrl
                            upstreamHeaders[tsUrl] = headers
                            preloadTs(tsUrl, headers)
                        }
                    }
                } else {
                    prepareDirectPrefetch(originalUrl, headers) ?: preloadTs(originalUrl, headers)
                }
                Log.i("LocalProxyServer", "Prefetch triggered for: $originalUrl")
            } catch (e: Exception) {
                Log.e("LocalProxyServer", "Prefetch failed: ${e.message}")
            }
        }
    }

    fun clearMetadata() {
        playlistSegments.clear()
        tsToPlaylistMap.clear()
        upstreamHeaders.clear()
        directPrefetchStates.clear()
        downloadingJobs.values.forEach { it.cancel() }
        downloadingJobs.clear()
        Log.i("LocalProxyServer", "Metadata and pending jobs cleared")
    }

    fun shutdown() {
        super.stop()
        scope.cancel()
    }

    private fun buildForwardHeaders(
        originalUrl: String,
        requestHeaders: Map<String, String>
    ): Map<String, String> {
        val base = upstreamHeaders[originalUrl].orEmpty().toMutableMap()
        requestHeaders.forEach { (key, value) ->
            if (shouldForwardHeader(key) && value.isNotBlank()) {
                base[key] = value
            }
        }
        return base
    }

    private fun detectMimeType(url: String): String {
        val lower = url.lowercase()
        return when {
            lower.contains(".mp4") -> "video/mp4"
            lower.contains(".mkv") -> "video/x-matroska"
            lower.contains(".mov") -> "video/quicktime"
            lower.contains(".flv") -> "video/x-flv"
            lower.contains(".m3u8") -> "application/vnd.apple.mpegurl"
            lower.contains(".ts") -> "video/mp2t"
            else -> "video/*"
        }
    }
}

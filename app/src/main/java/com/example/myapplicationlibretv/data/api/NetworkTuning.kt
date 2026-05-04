package com.example.myapplicationlibretv.data.api

import okhttp3.ConnectionPool
import okhttp3.ConnectionSpec
import okhttp3.Cache
import okhttp3.Dns
import okhttp3.Dispatcher
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.HttpUrl
import okhttp3.logging.HttpLoggingInterceptor
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

object NetworkTuning {
    const val DESKTOP_BROWSER_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    fun createTunedClient(
        cacheDirectory: File? = null,
        trustAllSsl: Boolean = false,
        bodyLogging: Boolean = false
    ): OkHttpClient {
        val dispatcher = Dispatcher().apply {
            maxRequests = 64
            maxRequestsPerHost = 24
        }
        val builder = OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(7, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .dns(AdaptiveDns())
            .proxySelector(ProxySelector.getDefault())
            .followRedirects(true)
            .followSslRedirects(true)
            .connectionPool(ConnectionPool(16, 5, TimeUnit.MINUTES))
            .connectionSpecs(
                listOf(
                    ConnectionSpec.MODERN_TLS,
                    ConnectionSpec.COMPATIBLE_TLS,
                    ConnectionSpec.CLEARTEXT
                )
            )
            .addInterceptor(CommonHeaderInterceptor())
            .addInterceptor(AdaptiveTimeoutInterceptor())
            .addInterceptor(SimpleRetryInterceptor())

        cacheDirectory?.let {
            builder.cache(Cache(it, 24L * 1024L * 1024L))
        }

        if (bodyLogging) {
            builder.addInterceptor(
                HttpLoggingInterceptor().apply {
                    level = HttpLoggingInterceptor.Level.BASIC
                }
            )
        }

        if (trustAllSsl) {
            val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            })
            val sslContext = SSLContext.getInstance("SSL").apply {
                init(null, trustAllCerts, SecureRandom())
            }
            builder.sslSocketFactory(sslContext.socketFactory, trustAllCerts.first() as X509TrustManager)
            builder.hostnameVerifier { _, _ -> true }
        }

        return builder.build()
    }

    fun buildCommonHeaders(
        url: String,
        sourceHeaders: Map<String, String> = emptyMap()
    ): Map<String, String> {
        val headers = linkedMapOf(
            "User-Agent" to DESKTOP_BROWSER_UA,
            "Accept" to "*/*",
            "Accept-Language" to "zh-CN,zh;q=0.9,en;q=0.8",
            "Connection" to "keep-alive"
        )
        sourceHeaders.forEach { (k, v) ->
            if (k.isNotBlank() && v.isNotBlank()) {
                headers[k] = v
            }
        }
        if (!headers.containsKey("Referer")) {
            val uri = android.net.Uri.parse(url)
            val scheme = uri.scheme.orEmpty()
            val host = uri.host.orEmpty()
            if (scheme.isNotBlank() && host.isNotBlank()) {
                val referer = "$scheme://$host/"
                headers["Referer"] = referer
                headers.putIfAbsent("Origin", "$scheme://$host")
            }
        }
        return headers
    }
}

private enum class NetworkRouteMode {
    DIRECT_CN,
    DIRECT_GLOBAL,
    PROXY
}

private object NetworkRouteClassifier {
    private val globalPriorityHosts = listOf(
        "wikipedia.org",
        "wikimedia.org",
        "githubusercontent.com",
        "github.com",
        "raw.githubusercontent.com",
        "tmdb.org",
        "themoviedb.org",
        "imdb.com"
    )

    fun classify(url: HttpUrl, proxy: Proxy?): NetworkRouteMode {
        if (proxy != null && proxy.type() != Proxy.Type.DIRECT) return NetworkRouteMode.PROXY
        val host = url.host.lowercase()
        if (globalPriorityHosts.any { host == it || host.endsWith(".$it") }) {
            return NetworkRouteMode.DIRECT_GLOBAL
        }
        val isCnLike = host.endsWith(".cn") ||
            host.endsWith(".tv") ||
            host.endsWith(".cc") ||
            host.endsWith(".me") ||
            host.endsWith(".hk") ||
            host.contains("zy") ||
            host.contains("api")
        return if (isCnLike) NetworkRouteMode.DIRECT_CN else NetworkRouteMode.DIRECT_GLOBAL
    }
}

private class CommonHeaderInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val builder = original.newBuilder()
        val headers = NetworkTuning.buildCommonHeaders(original.url.toString())
        builder.header("Cache-Control", original.header("Cache-Control") ?: "public, max-age=60")
        headers.forEach { (k, v) ->
            if (original.header(k).isNullOrBlank()) {
                builder.header(k, v)
            }
        }
        return chain.proceed(builder.build())
    }
}

private class AdaptiveTimeoutInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val routeMode = NetworkRouteClassifier.classify(request.url, chain.connection()?.route()?.proxy)
        val tunedChain = when (routeMode) {
            NetworkRouteMode.DIRECT_CN -> chain
                .withConnectTimeout(3, TimeUnit.SECONDS)
                .withReadTimeout(6, TimeUnit.SECONDS)
                .withWriteTimeout(9, TimeUnit.SECONDS)
            NetworkRouteMode.DIRECT_GLOBAL -> chain
                .withConnectTimeout(3, TimeUnit.SECONDS)
                .withReadTimeout(5, TimeUnit.SECONDS)
                .withWriteTimeout(10, TimeUnit.SECONDS)
            NetworkRouteMode.PROXY -> chain
                .withConnectTimeout(8, TimeUnit.SECONDS)
                .withReadTimeout(12, TimeUnit.SECONDS)
                .withWriteTimeout(12, TimeUnit.SECONDS)
        }
        return tunedChain.proceed(request)
    }
}

private class SimpleRetryInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        var lastError: IOException? = null
        val request = chain.request()
        val routeMode = NetworkRouteClassifier.classify(request.url, chain.connection()?.route()?.proxy)
        val maxAttempts = when (routeMode) {
            NetworkRouteMode.DIRECT_CN -> 2
            NetworkRouteMode.DIRECT_GLOBAL -> 1
            NetworkRouteMode.PROXY -> 2
        }
        repeat(maxAttempts) { attempt ->
            try {
                return chain.proceed(request)
            } catch (e: IOException) {
                lastError = e
                val retriable = e is SocketTimeoutException ||
                    e is SSLException ||
                    e.message.orEmpty().contains("unexpected end of stream", ignoreCase = true) ||
                    e.message.orEmpty().contains("connection reset", ignoreCase = true)
                if (!retriable || attempt == maxAttempts - 1) throw e
                val delayMs = when (routeMode) {
                    NetworkRouteMode.DIRECT_CN -> 180L * (attempt + 1)
                    NetworkRouteMode.DIRECT_GLOBAL -> 320L * (attempt + 1)
                    NetworkRouteMode.PROXY -> 450L * (attempt + 1)
                }
                Thread.sleep(delayMs)
            }
        }
        throw lastError ?: IOException("network request failed")
    }
}

private class AdaptiveDns : Dns {
    private data class CacheEntry(val addresses: List<InetAddress>, val expiresAtMs: Long)
    private val cache = ConcurrentHashMap<String, CacheEntry>()

    override fun lookup(hostname: String): List<InetAddress> {
        val now = System.currentTimeMillis()
        cache[hostname]?.takeIf { it.expiresAtMs > now }?.let { return it.addresses }

        val resolved = Dns.SYSTEM.lookup(hostname)
        val ordered = if (hostname.isLikelyCnHost()) {
            resolved.sortedWith(
                compareBy<InetAddress> { it !is Inet4Address }
                    .thenBy { it.hostAddress }
            )
        } else {
            val ipv4 = resolved.filterIsInstance<Inet4Address>()
            val others = resolved.filterNot { it is Inet4Address }
            (ipv4.zip(others) { a, b -> listOf(a, b) }.flatten() + ipv4.drop(others.size) + others.drop(ipv4.size))
                .ifEmpty { resolved }
        }
        cache[hostname] = CacheEntry(
            addresses = ordered,
            expiresAtMs = now + if (hostname.isLikelyCnHost()) 5 * 60_000L else 2 * 60_000L
        )
        return ordered
    }
}

private fun String.isLikelyCnHost(): Boolean {
    val host = lowercase()
    return host.endsWith(".cn") ||
        host.endsWith(".com.cn") ||
        host.endsWith(".tv") ||
        host.endsWith(".cc") ||
        host.endsWith(".me") ||
        host.endsWith(".hk") ||
        host.contains("zy") ||
        host.contains("api")
}

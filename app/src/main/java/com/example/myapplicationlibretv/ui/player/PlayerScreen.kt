package com.example.myapplicationlibretv.ui.player

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.graphics.Rect
import android.content.SharedPreferences
import android.os.Build
import android.util.Rational
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.OrientationEventListener
import android.widget.FrameLayout
import android.widget.ImageButton
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.ui.PlayerView
import androidx.media3.common.PlaybackException
import android.media.AudioManager
import androidx.compose.ui.platform.LocalDensity
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.common.util.Log
import androidx.media3.ui.AspectRatioFrameLayout
import com.example.myapplicationlibretv.data.api.NetworkTuning
import com.example.myapplicationlibretv.data.db.AppDatabase
import com.example.myapplicationlibretv.download.BackgroundDownloadService
import com.example.myapplicationlibretv.download.DownloadCenter
import com.example.myapplicationlibretv.download.parseVideoUrl
import com.example.myapplicationlibretv.ui.detail.PlayerEpisodePayload
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import androidx.media3.exoplayer.DefaultLoadControl
import com.example.myapplicationlibretv.utils.LocalProxyServer
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import okhttp3.Request
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.security.SecureRandom
import java.security.cert.X509Certificate

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    videoId: Int,
    displayTitle: String,
    videoUrl: String,
    episodes: List<PlayerEpisodePayload> = emptyList(),
    currentEpisodeIndex: Int = 0,
    historyRecordId: Int = 0,
    onPlayNext: (String, String, Int) -> Unit = { _, _, _ -> },
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val activity = context.findActivity()
    val progressPrefs = remember(context) {
        context.applicationContext.getSharedPreferences("player_progress", Context.MODE_PRIVATE)
    }
    val videoDao = remember(context) {
        AppDatabase.getDatabase(context.applicationContext).videoDao()
    }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isControllerVisible by remember { mutableStateOf(true) }
    var controllerEnabled by remember { mutableStateOf(true) }
    var resizeModeIndex by remember { mutableIntStateOf(0) }
    var toastMessage by remember { mutableStateOf<String?>(null) }
    var audioInfo by remember { mutableStateOf<String?>(null) }
    var hasAudioTrack by remember { mutableStateOf<Boolean?>(null) }
    var playerViewRef by remember { mutableStateOf<PlayerView?>(null) }
    var autoPlayNextTriggered by remember(videoUrl, currentEpisodeIndex) { mutableStateOf(false) }
    var shouldKeepScreenOn by remember { mutableStateOf(false) }
    var currentPositionMs by remember(videoId, displayTitle, videoUrl) { mutableLongStateOf(0L) }
    var durationMs by remember(videoId, displayTitle, videoUrl) { mutableLongStateOf(0L) }

    val decodedInput = remember(videoUrl) {
        URLDecoder.decode(videoUrl, StandardCharsets.UTF_8.name())
    }
    val candidates = remember(decodedInput) {
        decodedInput
            .split("\n")
            .map { it.trim() }
            .filter { it.isNotBlank() }
    }
    var sortedCandidates by remember(candidates) { mutableStateOf(candidates) }
    var currentIndex by remember(decodedInput) { mutableIntStateOf(0) }
    var reloadToken by remember(decodedInput) { mutableIntStateOf(0) }
    var isAutoSwitching by remember(decodedInput) { mutableStateOf(false) }
    var resumeApplied by remember(videoId, decodedInput) { mutableStateOf(false) }
    var isDetectingLatency by remember(candidates) { mutableStateOf(false) }
    var candidateMimeTypes by remember(decodedInput) { mutableStateOf<Map<String, String?>>(emptyMap()) }

    val parsed = remember(sortedCandidates, currentIndex, reloadToken) {
        parseVideoUrl(sortedCandidates.getOrNull(currentIndex).orEmpty())
    }
    var playbackReady by remember(parsed.url, reloadToken) { mutableStateOf(false) }
    var detectedMimeType by remember(parsed.url) { mutableStateOf(inferMimeTypeFromUrl(parsed.url)) }
    val isLocalPlayback = remember(parsed.url) {
        parsed.url.startsWith("content://") || parsed.url.startsWith("file://")
    }
    val nextEpisode = remember(episodes, currentEpisodeIndex) {
        episodes.getOrNull(currentEpisodeIndex + 1)
    }
    val progressKey = remember(videoId, displayTitle) {
        buildProgressKey(videoId, displayTitle)
    }
    val savedProgress = remember(progressKey) {
        progressPrefs.getLong(progressKey, 0L)
    }
    val proxyServer = remember(context) { LocalProxyServer.getInstance(context) }
    var useProxyFallback by remember(parsed.url) { mutableStateOf(false) }
    
    // 当资源 ID、线路索引或 URL 变化时，清理代理服务器的临时元数据，防止资源混淆
    LaunchedEffect(videoId, videoUrl, currentIndex) {
        proxyServer.clearMetadata()
    }

    LaunchedEffect(candidates) {
        if (candidates.size <= 1) {
            isDetectingLatency = false
            // 即使只有一条线路，也进行预取
            candidates.firstOrNull()
                ?.takeUnless { isLocalUri(it) }
                ?.let { url ->
                val p = parseVideoUrl(url)
                proxyServer.prefetch(p.url, NetworkTuning.buildCommonHeaders(p.url, p.headers))
            }
            return@LaunchedEffect
        }
        val remoteCandidates = candidates.filterNot(::isLocalUri)
        if (remoteCandidates.isEmpty()) {
            isDetectingLatency = false
            return@LaunchedEffect
        }
        isDetectingLatency = false
        val probeTargets = remoteCandidates.take(6)
        val results = probeTargets.map { url ->
            async(Dispatchers.IO) {
                val probe = probePlayableCandidate(url)
                Triple(url, probe.mimeType, probe)
            }
        }.awaitAll()

        candidateMimeTypes = results.associate { it.first to it.second }
        val sorted = results
            .sortedWith(
                compareByDescending<Triple<String, String?, CandidateProbeResult>> { it.third.playable }
                    .thenBy { it.third.latencyMs }
            )
            .map { it.first }
        sortedCandidates = (sorted + remoteCandidates.drop(probeTargets.size) + candidates.filter(::isLocalUri))
            .distinct()
        currentIndex = 0
        isDetectingLatency = false
        
        // 触发最优线路预载
        val firstCandidate = sorted.firstOrNull()
        if (firstCandidate != null) {
            val p = parseVideoUrl(firstCandidate)
            proxyServer.prefetch(p.url, NetworkTuning.buildCommonHeaders(p.url, p.headers))
        }
    }

    LaunchedEffect(parsed.url, parsed.headers) {
        detectedMimeType = candidateMimeTypes[sortedCandidates.getOrNull(currentIndex).orEmpty()]
            ?: inferMimeTypeFromUrl(parsed.url)
        if (detectedMimeType != null || parsed.url.isBlank() || isLocalPlayback) return@LaunchedEffect
        detectedMimeType = withContext(Dispatchers.IO) {
            detectMimeTypeFromNetwork(parsed.url, parsed.headers)
        }
    }

    fun switchToNextCandidate(reason: String, finalReason: String) {
        if (isAutoSwitching) return
        if (currentIndex < sortedCandidates.lastIndex) {
            isAutoSwitching = true
            errorMessage = reason
            currentIndex += 1
        } else {
            errorMessage = finalReason
        }
    }

    LaunchedEffect(errorMessage, currentIndex) {
        val msg = errorMessage ?: return@LaunchedEffect
        if (msg.contains("自动尝试下一线路")) {
            delay(1500)
            if (errorMessage == msg) {
                errorMessage = null
            }
        }
    }

    LaunchedEffect(currentIndex, reloadToken) {
        isAutoSwitching = false
        hasAudioTrack = null
        audioInfo = null
        resumeApplied = false
        playbackReady = false
    }

    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    fun buildDownloadCandidates(): String {
        val current = sortedCandidates.getOrNull(currentIndex)
        return (listOfNotNull(current) + sortedCandidates + candidates)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString("\n")
    }
    DisposableEffect(Unit) {
        DownloadCenter.setPlaybackActive(true)
        if (!proxyServer.wasStarted) {
            try {
                proxyServer.start()
                proxyServer.wasStarted = true
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        onDispose {
            DownloadCenter.setPlaybackActive(false)
            // 单例模式下，通常不在这里彻底 shutdown，或者根据需要引用计数
        }
    }
    
    val originalWindowBrightness = remember(activity) {
        activity?.window?.attributes?.screenBrightness ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
    }
    var brightness by remember {
        mutableFloatStateOf(
            (activity?.window?.attributes?.screenBrightness
                ?.takeIf { it >= 0f }
                ?: 0.5f)
        )
    }
    val maxMusicVolume = remember(audioManager) {
        audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    }
    var volume by remember {
        mutableFloatStateOf(
            audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxMusicVolume
        )
    }
    
    var showBrightnessOverlay by remember { mutableStateOf(false) }
    var showVolumeOverlay by remember { mutableStateOf(false) }
    var showSeekOverlay by remember { mutableStateOf(false) }
    var seekStartMs by remember { mutableLongStateOf(0L) }
    var seekTargetMs by remember { mutableLongStateOf(0L) }
    var dragAccumulatorX by remember { mutableFloatStateOf(0f) }
    var dragAccumulatorY by remember { mutableFloatStateOf(0f) }
    var dragStartVolume by remember { mutableFloatStateOf(volume) }
    var lastAppliedStreamVolume by remember {
        mutableIntStateOf(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
    }
    var gestureMode by remember { mutableIntStateOf(0) }
    var manualOrientation by remember { mutableIntStateOf(ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT) }
    var physicalOrientationDegrees by remember { mutableIntStateOf(OrientationEventListener.ORIENTATION_UNKNOWN) }
    var zoomScale by remember { mutableFloatStateOf(1f) }
    var zoomOffset by remember { mutableStateOf(Offset.Zero) }
    var playerViewportSize by remember { mutableStateOf(IntSize.Zero) }
    var suppressSingleFingerUntil by remember { mutableLongStateOf(0L) }
    val dragThresholdPx = with(LocalDensity.current) { 18.dp.toPx() }
    val ratioModes = remember {
        listOf(
            AspectRatioFrameLayout.RESIZE_MODE_FIT to "适应",
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM to "裁剪",
            AspectRatioFrameLayout.RESIZE_MODE_FILL to "拉伸"
        )
    }
    // 播放器只支持手动横竖屏切换，不跟随传感器自动旋转。
    fun maxZoomOffset(scale: Float): Offset {
        if (playerViewportSize == IntSize.Zero || scale <= 1f) return Offset.Zero
        val maxX = (playerViewportSize.width * (scale - 1f)) / 2f
        val maxY = (playerViewportSize.height * (scale - 1f)) / 2f
        return Offset(maxX, maxY)
    }

    fun clampZoomOffset(scale: Float, offset: Offset): Offset {
        val maxOffset = maxZoomOffset(scale)
        return Offset(
            x = offset.x.coerceIn(-maxOffset.x, maxOffset.x),
            y = offset.y.coerceIn(-maxOffset.y, maxOffset.y)
        )
    }

    fun syncZoomTransform() {
        val playerView = playerViewRef ?: return
        val contentFrameId = playerView.context.resources.getIdentifier(
            "exo_content_frame",
            "id",
            "androidx.media3.ui"
        )
        val contentFrame = playerView.findViewById<View?>(contentFrameId)
            ?: playerView.getChildAt(0)
            ?: return
        contentFrame.pivotX = contentFrame.width / 2f
        contentFrame.pivotY = contentFrame.height / 2f
        contentFrame.scaleX = zoomScale
        contentFrame.scaleY = zoomScale
        contentFrame.translationX = zoomOffset.x
        contentFrame.translationY = zoomOffset.y
    }

    fun resetZoom() {
        zoomScale = 1f
        zoomOffset = Offset.Zero
        syncZoomTransform()
    }

    fun suppressSingleFingerGestures(durationMs: Long = 180L) {
        suppressSingleFingerUntil = System.currentTimeMillis() + durationMs
    }

    fun shouldSuppressSingleFingerGestures(): Boolean {
        return System.currentTimeMillis() < suppressSingleFingerUntil
    }

    fun applyZoomPan(pan: Offset = Offset.Zero, zoomChange: Float = 1f) {
        suppressSingleFingerGestures()
        val newScale = (zoomScale * zoomChange).coerceIn(1f, 4f)
        if (newScale <= 1.01f) {
            resetZoom()
            return
        }
        zoomScale = newScale
        zoomOffset = clampZoomOffset(newScale, zoomOffset + pan)
        showSeekOverlay = false
        showBrightnessOverlay = false
        showVolumeOverlay = false
        syncZoomTransform()
    }

    LaunchedEffect(videoId, displayTitle, videoUrl, currentIndex, reloadToken) {
        resetZoom()
    }

    DisposableEffect(context) {
        val listener = object : OrientationEventListener(context.applicationContext) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation != ORIENTATION_UNKNOWN) {
                    physicalOrientationDegrees = orientation
                }
            }
        }
        if (listener.canDetectOrientation()) {
            listener.enable()
        }
        onDispose {
            listener.disable()
        }
    }

    DisposableEffect(manualOrientation, videoId) {
        activity?.apply {
            requestedOrientation = manualOrientation
            volumeControlStream = AudioManager.STREAM_MUSIC
            val window = window
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowInsetsControllerCompat(window, window.decorView).let { controller ->
                controller.hide(WindowInsetsCompat.Type.systemBars())
                controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
        onDispose {
            activity?.apply {
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                volumeControlStream = AudioManager.USE_DEFAULT_STREAM_TYPE
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                window.attributes = window.attributes.apply {
                    screenBrightness = originalWindowBrightness
                }

                val window = window
                WindowCompat.setDecorFitsSystemWindows(window, true)
                WindowInsetsControllerCompat(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {}
    }

    val exoPlayer = remember(parsed, currentIndex, reloadToken, isDetectingLatency, useProxyFallback) {
        if (isDetectingLatency) return@remember null
        // 允许所有 SSL 证书（解决部分资源站证书问题）
        val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })
        val sslContext = SSLContext.getInstance("SSL").apply {
            init(null, trustAllCerts, SecureRandom())
        }
        javax.net.ssl.HttpsURLConnection.setDefaultSSLSocketFactory(sslContext.socketFactory)
        javax.net.ssl.HttpsURLConnection.setDefaultHostnameVerifier { _, _ -> true }

        val requestProperties = NetworkTuning.buildCommonHeaders(parsed.url, parsed.headers)
        val canUseProxyFallback = shouldUseLocalProxy(parsed.url, isLocalPlayback)
        val playbackUrl = if (useProxyFallback && canUseProxyFallback) {
            proxyServer.getProxyUrl(parsed.url, requestProperties)
        } else {
            parsed.url
        }

        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(8_000)
            .setReadTimeoutMs(16_000)
            .setUserAgent(NetworkTuning.DESKTOP_BROWSER_UA)
            .setDefaultRequestProperties(requestProperties)
        val dataSourceFactory = DefaultDataSource.Factory(context, httpDataSourceFactory)

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(6000, 90000, 1500, 4500)
            .build()

        ExoPlayer.Builder(context)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(context).setDataSourceFactory(dataSourceFactory)
            )
            .setLoadControl(loadControl)
            .build()
            .apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                        .build(),
                    true
                )
                addAnalyticsListener(
                    object : AnalyticsListener {
                        override fun onAudioInputFormatChanged(
                            eventTime: AnalyticsListener.EventTime,
                            format: Format,
                            decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?
                        ) {
                            val mime = format.sampleMimeType
                            val channels = format.channelCount.takeIf { it > 0 }
                            val rate = format.sampleRate.takeIf { it > 0 }
                            audioInfo = listOfNotNull(
                                mime,
                                channels?.let { "${it}ch" },
                                rate?.let { "${it}Hz" }
                            ).joinToString(" ")
                        }

                        override fun onAudioSinkError(
                            eventTime: AnalyticsListener.EventTime,
                            audioSinkError: Exception
                        ) {
                            audioInfo = "音频异常"
                        }

                        override fun onLoadError(
                            eventTime: AnalyticsListener.EventTime,
                            loadEventInfo: androidx.media3.exoplayer.source.LoadEventInfo,
                            mediaLoadData: androidx.media3.exoplayer.source.MediaLoadData,
                            error: java.io.IOException,
                            wasCanceled: Boolean
                        ) {
                            if (wasCanceled) return
                            if (mediaLoadData.trackType != C.TRACK_TYPE_AUDIO) return
                            audioInfo = "音频加载中"
                        }
                    }
                )
                val isM3u8Stream = detectedMimeType == MimeTypes.APPLICATION_M3U8 ||
                    parsed.url.contains(".m3u8", ignoreCase = true)
                val mediaItemBuilder = MediaItem.Builder().setUri(playbackUrl)
                detectedMimeType?.let { mediaItemBuilder.setMimeType(it) }
                if (isM3u8Stream && detectedMimeType == null) {
                    mediaItemBuilder.setMimeType(MimeTypes.APPLICATION_M3U8)
                }
                setMediaItem(mediaItemBuilder.build())
                prepare()
                playWhenReady = true
                
                addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        Log.e("PlayerScreen", "Playback Error: ${error.errorCodeName}(${error.errorCode})", error)
                        if (canUseProxyFallback && !useProxyFallback) {
                            errorMessage = "当前线路直连失败，正在尝试兼容播放..."
                            useProxyFallback = true
                            reloadToken += 1
                            return
                        }
                        switchToNextCandidate(
                            reason = "线路${currentIndex + 1}播放失败，自动尝试下一线路...",
                            finalReason = "当前资源无法播放: ${error.localizedMessage ?: "网络错误"}"
                        )
                    }

override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_READY) {
                            playbackReady = true
                            isAutoSwitching = false
                            playerViewRef?.showController()
                            shouldKeepScreenOn = this@apply.playWhenReady
                            durationMs = this@apply.duration.takeIf { it > 0L } ?: durationMs
                            if (!resumeApplied && savedProgress > 5_000L) {
                                val duration = this@apply.duration.takeIf { it > 0L } ?: 0L
                                val safeProgress = if (duration > 0) {
                                    savedProgress.coerceIn(0L, (duration - 3_000L).coerceAtLeast(0L))
                                } else {
                                    savedProgress
                                }
                                if (safeProgress > 0L) {
                                    this@apply.seekTo(safeProgress)
                                    toastMessage = "已为你续播到 ${formatTime(safeProgress)}"
                                }
                            }
                            resumeApplied = true
                            errorMessage = null
                            autoPlayNextTriggered = false
                        } else if (
                            playbackState == Player.STATE_ENDED &&
                            !autoPlayNextTriggered
                        ) {
                            shouldKeepScreenOn = false
                            val nextEpisode = episodes.getOrNull(currentEpisodeIndex + 1)
                            if (nextEpisode != null && nextEpisode.playlist.isNotBlank()) {
                                autoPlayNextTriggered = true
                                onPlayNext(nextEpisode.title, nextEpisode.playlist, currentEpisodeIndex + 1)
                            }
                        } else if (playbackState == Player.STATE_IDLE) {
                            shouldKeepScreenOn = false
                            currentPositionMs = 0L
                            durationMs = 0L
                        }
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        shouldKeepScreenOn = isPlaying
                    }

                    override fun onEvents(player: Player, events: Player.Events) {
                        if (
                            events.contains(Player.EVENT_POSITION_DISCONTINUITY) ||
                            events.contains(Player.EVENT_TIMELINE_CHANGED) ||
                            events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)
                        ) {
                            currentPositionMs = player.currentPosition.coerceAtLeast(0L)
                            durationMs = player.duration.takeIf { it > 0L } ?: durationMs
                        }
                    }

                    override fun onTracksChanged(tracks: Tracks) {
                        val hasAudio = tracks.groups.any { group ->
                            group.type == C.TRACK_TYPE_AUDIO && (0 until group.length).any { i ->
                                group.isTrackSelected(i)
                            }
                        }
                        hasAudioTrack = hasAudio
                    }
                })
            }
    }

    val enterPipAction = rememberUpdatedState(
        newValue = {
            exoPlayer?.let {
                enterPictureInPictureIfPossible(
                    activity = activity,
                    exoPlayer = it,
                    playerView = playerViewRef
                )
            }
            Unit
        }
    )

        val controllerPlayer = remember(exoPlayer, nextEpisode, currentEpisodeIndex) {
        val p = exoPlayer ?: return@remember null
        object : ForwardingPlayer(p) {
            override fun getAvailableCommands(): Player.Commands {
                val builder = super.getAvailableCommands().buildUpon()
                if (nextEpisode != null) {
                    builder.add(COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                    builder.add(COMMAND_SEEK_TO_NEXT)
                } else {
                    builder.remove(COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                    builder.remove(COMMAND_SEEK_TO_NEXT)
                }
                return builder.build()
            }

            override fun seekToNextMediaItem() {
                val target = nextEpisode ?: return
                autoPlayNextTriggered = true
                onPlayNext(target.title, target.playlist, currentEpisodeIndex + 1)
            }

            override fun seekToNext() {
                seekToNextMediaItem()
            }

            override fun hasNextMediaItem(): Boolean = nextEpisode != null

            override fun getNextMediaItemIndex(): Int {
                return if (nextEpisode != null) currentEpisodeIndex + 1 else C.INDEX_UNSET
            }
        }
    }

    fun bindNextButton(playerView: PlayerView?) {
        if (playerView == null) return
        val context = playerView.context
        val btnId = context.resources.getIdentifier("exo_next", "id", "androidx.media3.ui")
            .takeIf { it != 0 }
            ?: context.resources.getIdentifier("exo_next", "id", context.packageName)
        
        if (btnId == 0) return
        val nextButton = playerView.findViewById<ImageButton?>(btnId) ?: return
        val enabled = nextEpisode != null
        nextButton.visibility = if (enabled) View.VISIBLE else View.GONE
        nextButton.isEnabled = enabled
        nextButton.isClickable = enabled
        nextButton.isFocusable = enabled
        nextButton.alpha = if (enabled) 1f else 0.35f
        nextButton.setOnClickListener(
            if (!enabled) {
                null
            } else {
                View.OnClickListener {
                    val target = nextEpisode ?: return@OnClickListener
                    autoPlayNextTriggered = true
                    onPlayNext(target.title, target.playlist, currentEpisodeIndex + 1)
                }
            }
        )
    }

    fun applyVideoSurfaceTransform(playerView: PlayerView?) {
        if (playerView == null) return
        val contentFrameId = playerView.context.resources.getIdentifier(
            "exo_content_frame",
            "id",
            "androidx.media3.ui"
        )
        val contentFrame = playerView.findViewById<View?>(contentFrameId)
            ?: playerView.getChildAt(0)
            ?: return
        contentFrame.pivotX = contentFrame.width / 2f
        contentFrame.pivotY = contentFrame.height / 2f
        contentFrame.scaleX = zoomScale
        contentFrame.scaleY = zoomScale
        contentFrame.translationX = zoomOffset.x
        contentFrame.translationY = zoomOffset.y
    }

    DisposableEffect(activity, exoPlayer) {
        val action: () -> Unit = { enterPipAction.value() }
        PlayerPipController.attach(action)
        onDispose {
            PlayerPipController.detach(action)
        }
    }

    DisposableEffect(lifecycleOwner, activity, exoPlayer) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && activity?.isInPictureInPictureMode != true) {
                exoPlayer?.pause()
                shouldKeepScreenOn = false
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    DisposableEffect(activity, shouldKeepScreenOn) {
        activity?.window?.let { window ->
            if (shouldKeepScreenOn) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    LaunchedEffect(exoPlayer) {
        exoPlayer?.volume = 1f
    }

    LaunchedEffect(exoPlayer, currentIndex, reloadToken) {
        if (exoPlayer == null) return@LaunchedEffect
        delay(45_000)
        if (exoPlayer.playbackState != Player.STATE_READY && exoPlayer.playbackState != Player.STATE_ENDED) {
            errorMessage = "当前线路加载较慢，可稍等或手动重试"
        }
    }

    LaunchedEffect(toastMessage) {
        if (toastMessage != null) {
            delay(1200)
            toastMessage = null
        }
    }

    DisposableEffect(exoPlayer) {
        onDispose {
            exoPlayer?.let {
                val progress = it.currentPosition.coerceAtLeast(0L)
                val duration = it.duration.takeIf { it > 0 } ?: 0L
                saveEpisodeProgress(progressPrefs, progressKey, progress, duration)
                it.release()
            }
        }
    }

    LaunchedEffect(exoPlayer, progressKey) {
        if (exoPlayer == null) return@LaunchedEffect
        while (true) {
            delay(500)
            currentPositionMs = exoPlayer.currentPosition.coerceAtLeast(0L)
            durationMs = exoPlayer.duration.takeIf { it > 0 } ?: durationMs
        }
    }

    LaunchedEffect(exoPlayer, progressKey) {
        if (exoPlayer == null) return@LaunchedEffect
        while (true) {
            delay(5_000)
            val duration = exoPlayer.duration.takeIf { it > 0 } ?: 0L
            val progress = exoPlayer.currentPosition.coerceAtLeast(0L)
            if (progress <= 0L) continue
            saveEpisodeProgress(progressPrefs, progressKey, progress, duration)
            if (historyRecordId != 0) {
                val normalized = normalizeProgress(progress, duration)
                withContext(Dispatchers.IO) {
                    videoDao.updateHistoryProgress(historyRecordId, normalized, duration)
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onSizeChanged { playerViewportSize = it }
            .pointerInput(exoPlayer, isControllerVisible) {
                detectDragGestures(
                    onDragStart = {
                        if (!isControllerVisible) return@detectDragGestures
                        if (shouldSuppressSingleFingerGestures()) return@detectDragGestures
                        dragAccumulatorX = 0f
                        dragAccumulatorY = 0f
                        gestureMode = 0
                        lastAppliedStreamVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                        volume = lastAppliedStreamVolume.toFloat() / maxMusicVolume
                        dragStartVolume = volume
                        showSeekOverlay = false
                    },
                    onDragEnd = {
                        if (!isControllerVisible) return@detectDragGestures
                        if (shouldSuppressSingleFingerGestures()) return@detectDragGestures
                        showBrightnessOverlay = false
                        showVolumeOverlay = false
                        if (showSeekOverlay && exoPlayer != null) {
                            val duration = exoPlayer.duration
                            val safeTarget = if (duration > 0) {
                                seekTargetMs.coerceIn(0L, duration)
                            } else {
                                seekTargetMs.coerceAtLeast(0L)
                            }
                            // 强制执行跳转，ExoPlayer 内部会处理不可寻址的情况
                            exoPlayer.seekTo(safeTarget)
                            showSeekOverlay = false
                        }
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        if (!isControllerVisible) {
                            return@detectDragGestures
                        }
                        if (shouldSuppressSingleFingerGestures()) {
                            return@detectDragGestures
                        }
                        dragAccumulatorX += dragAmount.x
                        dragAccumulatorY += dragAmount.y

                        if (gestureMode == 0 && exoPlayer != null) {
                            val absX = abs(dragAccumulatorX)
                            val absY = abs(dragAccumulatorY)
                            if (absX >= dragThresholdPx && absX > absY) {
                                gestureMode = 3
                                seekStartMs = exoPlayer.currentPosition
                                seekTargetMs = seekStartMs
                                showSeekOverlay = true
                                showBrightnessOverlay = false
                                showVolumeOverlay = false
                            } else if (absY >= dragThresholdPx && absY > absX) {
                                gestureMode = if (change.position.x < size.width / 2) 1 else 2
                                showSeekOverlay = false
                            } else {
                                return@detectDragGestures
                            }
                        }

                        when (gestureMode) {
                            1 -> {
                                val sensitivity = 0.001f
                                brightness = (brightness - dragAmount.y * sensitivity).coerceIn(0f, 1f)
                                activity?.window?.let { window ->
                                    val params = window.attributes
                                    params.screenBrightness = brightness
                                    window.attributes = params
                                }
                                showBrightnessOverlay = true
                                showVolumeOverlay = false
                            }
                            2 -> {
                                val sensitivity = 0.001f
                                val targetRatio = (dragStartVolume - dragAccumulatorY * sensitivity)
                                    .coerceIn(0f, 1f)
                                val targetStreamVolume = (targetRatio * maxMusicVolume)
                                    .roundToInt()
                                    .coerceIn(0, maxMusicVolume)
                                volume = targetRatio
                                if (targetStreamVolume != lastAppliedStreamVolume) {
                                    audioManager.setStreamVolume(
                                        AudioManager.STREAM_MUSIC,
                                        targetStreamVolume,
                                        0
                                    )
                                    lastAppliedStreamVolume = targetStreamVolume
                                    exoPlayer?.volume = 1f
                                }
                                showVolumeOverlay = true
                                showBrightnessOverlay = false
                            }
                            3 -> {
                                val width = size.width.toFloat().coerceAtLeast(1f)
                                val maxDeltaMs = 10 * 60 * 1000L
                                val fullSwipeMs = 5 * 60 * 1000L
                                val deltaMs = ((dragAccumulatorX / width) * fullSwipeMs.toFloat()).toLong()
                                    .coerceIn(-maxDeltaMs, maxDeltaMs)
                                val duration = exoPlayer?.duration ?: 0L
                                val target = seekStartMs + deltaMs
                                seekTargetMs = if (duration > 0) {
                                    target.coerceIn(0L, duration)
                                } else {
                                    target.coerceAtLeast(0L)
                                }
                                showSeekOverlay = true
                                showBrightnessOverlay = false
                                showVolumeOverlay = false
                            }
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = controllerPlayer
                    useController = controllerEnabled
                    setControllerShowTimeoutMs(5000)
                    setShowNextButton(!isLocalPlayback && nextEpisode != null)
                    setControllerVisibilityListener(
                        PlayerView.ControllerVisibilityListener { visibility ->
                            val visible = visibility == View.VISIBLE
                            isControllerVisible = visible
                            controllerEnabled = visible
                            post { bindNextButton(this) }
                        }
                    )
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    keepScreenOn = shouldKeepScreenOn
                    playerViewRef = this
                    post {
                        bindNextButton(this)
                        applyVideoSurfaceTransform(this)
                    }
                }
            },
            update = { view ->
                if (view.player !== controllerPlayer) {
                    view.player = controllerPlayer
                }
                view.useController = controllerEnabled
                view.resizeMode = ratioModes[resizeModeIndex].first
                view.setControllerShowTimeoutMs(5000)
                view.setShowNextButton(!isLocalPlayback && nextEpisode != null)
                view.post {
                    bindNextButton(view)
                    applyVideoSurfaceTransform(view)
                }
                view.keepScreenOn = shouldKeepScreenOn
            },
            modifier = Modifier.fillMaxSize()
        )

        if (!isControllerVisible) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(playerViewportSize) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            applyZoomPan(pan = pan, zoomChange = zoom)
                        }
                    }
                    .pointerInput(playerViewRef) {
                        detectTapGestures(
                            onDoubleTap = {
                                if (zoomScale > 1.01f) {
                                    resetZoom()
                                }
                            },
                            onTap = {
                                controllerEnabled = true
                                playerViewRef?.useController = true
                                playerViewRef?.showController()
                            }
                        )
                    }
                    .pointerInput(exoPlayer) {
                        detectDragGestures(
                            onDragStart = {
                                if (shouldSuppressSingleFingerGestures()) return@detectDragGestures
                                dragAccumulatorX = 0f
                                dragAccumulatorY = 0f
                                gestureMode = 0
                                lastAppliedStreamVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                                volume = lastAppliedStreamVolume.toFloat() / maxMusicVolume
                                dragStartVolume = volume
                                showSeekOverlay = false
                            },
                            onDragEnd = {
                                if (shouldSuppressSingleFingerGestures()) return@detectDragGestures
                                showBrightnessOverlay = false
                                showVolumeOverlay = false
                                if (showSeekOverlay && exoPlayer != null) {
                                    val duration = exoPlayer.duration
                                    val safeTarget = if (duration > 0) {
                                        seekTargetMs.coerceIn(0L, duration)
                                    } else {
                                        seekTargetMs.coerceAtLeast(0L)
                                    }
                                    exoPlayer.seekTo(safeTarget)
                                    showSeekOverlay = false
                                }
                            },
                            onDragCancel = {
                                showSeekOverlay = false
                                showBrightnessOverlay = false
                                showVolumeOverlay = false
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                if (shouldSuppressSingleFingerGestures()) {
                                    return@detectDragGestures
                                }
                                if (zoomScale > 1.01f) {
                                    applyZoomPan(pan = dragAmount, zoomChange = 1f)
                                    return@detectDragGestures
                                }
                                dragAccumulatorX += dragAmount.x
                                dragAccumulatorY += dragAmount.y

                                if (gestureMode == 0 && exoPlayer != null) {
                                    val absX = abs(dragAccumulatorX)
                                    val absY = abs(dragAccumulatorY)
                                    if (absX >= dragThresholdPx && absX > absY) {
                                        gestureMode = 3
                                        seekStartMs = exoPlayer.currentPosition
                                        seekTargetMs = seekStartMs
                                        showSeekOverlay = true
                                        showBrightnessOverlay = false
                                        showVolumeOverlay = false
                                    } else {
                                        return@detectDragGestures
                                    }
                                }

                                when (gestureMode) {
                                    3 -> {
                                        val width = size.width.toFloat().coerceAtLeast(1f)
                                        val maxDeltaMs = 10 * 60 * 1000L
                                        val fullSwipeMs = 5 * 60 * 1000L
                                        val deltaMs = ((dragAccumulatorX / width) * fullSwipeMs.toFloat()).toLong()
                                            .coerceIn(-maxDeltaMs, maxDeltaMs)
                                        val duration = exoPlayer?.duration ?: 0L
                                        val target = seekStartMs + deltaMs
                                        seekTargetMs = if (duration > 0) {
                                            target.coerceIn(0L, duration)
                                        } else {
                                            target.coerceAtLeast(0L)
                                        }
                                        showSeekOverlay = true
                                        showBrightnessOverlay = false
                                        showVolumeOverlay = false
                                    }
                                }
                            }
                        )
                    }
            )
        }

        // 顶部控制与标题
        if (isControllerVisible || errorMessage != null) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                val compactTopBar = this.maxWidth < 560.dp
                if (compactTopBar) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.TopStart)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = onBack,
                                modifier = Modifier
                                    .background(Color.Black.copy(alpha = 0.5f), androidx.compose.foundation.shape.CircleShape)
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "返回",
                                    tint = Color.White
                                )
                            }
                            TextButton(
                                onClick = {
                                    playerViewRef?.showController()
                                    manualOrientation = resolveToggledPlayerOrientation(
                                        activity = activity,
                                        currentOrientation = manualOrientation,
                                        physicalOrientationDegrees = physicalOrientationDegrees
                                    )
                                    toastMessage = if (isLandscapeOrientation(manualOrientation)) {
                                        "已切换横屏"
                                    } else {
                                        "已切换竖屏"
                                    }
                                },
                                modifier = Modifier
                                    .padding(start = 4.dp)
                                    .background(Color.Black.copy(alpha = 0.5f), MaterialTheme.shapes.small)
                            ) {
                                Text(
                                    text = if (isLandscapeOrientation(manualOrientation)) "竖屏" else "横屏",
                                    color = Color.White
                                )
                            }
                            Spacer(modifier = Modifier.weight(1f))
                            TextButton(
                                onClick = {
                                    playerViewRef?.showController()
                                    resizeModeIndex = (resizeModeIndex + 1) % ratioModes.size
                                    toastMessage = "画面比例：${ratioModes[resizeModeIndex].second}"
                                },
                                modifier = Modifier
                                    .background(Color.Black.copy(alpha = 0.5f), MaterialTheme.shapes.small)
                            ) {
                                Text(text = "比例", color = Color.White)
                            }
                            if (!isLocalPlayback) {
                                TextButton(
                                    onClick = {
                                        playerViewRef?.showController()
                                        val url = parsed.url.trim()
                                        if (url.isBlank()) {
                                            toastMessage = "下载失败：链接为空"
                                            return@TextButton
                                        }
                                        BackgroundDownloadService.start(
                                            context = context,
                                            rawUrl = buildDownloadCandidates(),
                                            title = displayTitle.ifBlank { null }
                                        )
                                        toastMessage = if (url.contains(".m3u8", ignoreCase = true)) {
                                            "已加入后台下载：m3u8 将自动合并为本地视频文件"
                                        } else {
                                            "已加入后台下载"
                                        }
                                    },
                                    modifier = Modifier
                                        .padding(start = 8.dp)
                                        .background(Color.Black.copy(alpha = 0.5f), MaterialTheme.shapes.small)
                                ) {
                                    Text(text = "下载", color = Color.White)
                                }
                            }
                        }
                        if (displayTitle.isNotBlank()) {
                            Text(
                                text = displayTitle,
                                color = Color.White,
                                maxLines = 2,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp)
                                    .background(Color.Black.copy(alpha = 0.5f), MaterialTheme.shapes.small)
                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                            )
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.TopStart),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier
                                .background(Color.Black.copy(alpha = 0.5f), androidx.compose.foundation.shape.CircleShape)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "返回",
                                tint = Color.White
                            )
                        }

                        TextButton(
                            onClick = {
                                playerViewRef?.showController()
                                manualOrientation = resolveToggledPlayerOrientation(
                                    activity = activity,
                                    currentOrientation = manualOrientation,
                                    physicalOrientationDegrees = physicalOrientationDegrees
                                )
                                toastMessage = if (isLandscapeOrientation(manualOrientation)) {
                                    "已切换横屏"
                                } else {
                                    "已切换竖屏"
                                }
                            },
                            modifier = Modifier
                                .padding(start = 4.dp)
                                .background(Color.Black.copy(alpha = 0.5f), MaterialTheme.shapes.small)
                        ) {
                            Text(
                                text = if (isLandscapeOrientation(manualOrientation)) "竖屏" else "横屏",
                                color = Color.White
                            )
                        }
                        if (displayTitle.isNotBlank()) {
                            Text(
                                text = displayTitle,
                                color = Color.White,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 8.dp)
                                    .background(Color.Black.copy(alpha = 0.5f), MaterialTheme.shapes.small)
                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                            )
                        } else {
                            Spacer(modifier = Modifier.weight(1f))
                        }

                        TextButton(
                            onClick = {
                                playerViewRef?.showController()
                                resizeModeIndex = (resizeModeIndex + 1) % ratioModes.size
                                toastMessage = "画面比例：${ratioModes[resizeModeIndex].second}"
                            },
                            modifier = Modifier
                                .background(Color.Black.copy(alpha = 0.5f), MaterialTheme.shapes.small)
                        ) {
                            Text(text = "比例", color = Color.White)
                        }

                        if (!isLocalPlayback) {
                            TextButton(
                                onClick = {
                                    playerViewRef?.showController()
                                    val url = parsed.url.trim()
                                    if (url.isBlank()) {
                                        toastMessage = "下载失败：链接为空"
                                        return@TextButton
                                    }
                                    BackgroundDownloadService.start(
                                        context = context,
                                        rawUrl = buildDownloadCandidates(),
                                        title = displayTitle.ifBlank { null }
                                    )
                                    toastMessage = if (url.contains(".m3u8", ignoreCase = true)) {
                                        "已加入后台下载：m3u8 将自动合并为本地视频文件"
                                    } else {
                                        "已加入后台下载"
                                    }
                                },
                                modifier = Modifier
                                    .padding(start = 8.dp)
                                    .background(Color.Black.copy(alpha = 0.5f), MaterialTheme.shapes.small)
                            ) {
                                Text(text = "下载", color = Color.White)
                            }
                        }
                    }
                }
            }
        }

        if (isControllerVisible && !audioInfo.isNullOrBlank()) {
            Text(
                text = audioInfo!!,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 24.dp)
                    .background(Color.Black.copy(alpha = 0.35f), MaterialTheme.shapes.small)
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            )
        }

        if (showSeekOverlay) {
            SeekOverlay(
                targetMs = seekTargetMs,
                durationMs = durationMs,
                deltaMs = seekTargetMs - seekStartMs,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        if (isDetectingLatency) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color.White)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("正在探测最优线路...", color = Color.White)
                }
            }
        }

        toastMessage?.let { msg ->
            Text(
                text = msg,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 48.dp)
                    .background(Color.Black.copy(alpha = 0.6f), MaterialTheme.shapes.small)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }

        if (showBrightnessOverlay) {
            OverlayIndicator(label = "亮度", value = brightness, modifier = Modifier.align(Alignment.CenterStart))
        }
        if (showVolumeOverlay) {
            OverlayIndicator(label = "音量", value = volume, modifier = Modifier.align(Alignment.CenterEnd))
        }

        errorMessage?.let { msg ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 48.dp)
                    .background(Color.Black.copy(alpha = 0.7f))
                    .padding(16.dp)
            ) {
                Text(text = msg, color = Color.White)
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = { 
                    errorMessage = null
                    reloadToken += 1
                }) {
                    Text("重试")
                }
            }
        }
    }
}

@Composable
fun OverlayIndicator(label: String, value: Float, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier = modifier.fillMaxHeight()) {
        val compact = this.maxHeight < 420.dp
        val indicatorHeight = if (compact) 128.dp else 168.dp
        val indicatorWidth = if (compact) 12.dp else 14.dp
        val indicatorPadding = if (compact) 18.dp else 24.dp
        val labelSize = if (compact) 11.sp else 12.sp

        Surface(
            color = Color.Black.copy(alpha = 0.5f),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = indicatorPadding, vertical = 24.dp)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 14.dp)
            ) {
                Text(text = label, color = Color.White, fontSize = labelSize)
                Spacer(modifier = Modifier.height(10.dp))
                Box(
                    modifier = Modifier
                        .width(indicatorWidth)
                        .height(indicatorHeight)
                        .background(Color.White.copy(alpha = 0.18f), RoundedCornerShape(999.dp)),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(value.coerceIn(0f, 1f))
                            .background(Color.White, RoundedCornerShape(999.dp))
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "${(value.coerceIn(0f, 1f) * 100).roundToInt()}%",
                    color = Color.White.copy(alpha = 0.92f),
                    fontSize = labelSize
                )
            }
        }
    }
}

@Composable
private fun SeekOverlay(
    targetMs: Long,
    durationMs: Long,
    deltaMs: Long,
    modifier: Modifier = Modifier
) {
    val sign = if (deltaMs >= 0) "+" else "-"
    val deltaText = sign + formatTime(abs(deltaMs))
    val positionText = if (durationMs > 0) {
        "${formatTime(targetMs)} / ${formatTime(durationMs)}"
    } else {
        formatTime(targetMs)
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.6f)),
        modifier = modifier
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp)
        ) {
            Text(text = deltaText, color = Color.White, fontSize = 16.sp)
            Spacer(modifier = Modifier.height(6.dp))
            Text(text = positionText, color = Color.White, fontSize = 12.sp)
        }
    }
}

private fun formatTime(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val seconds = (totalSeconds % 60).toInt()
    val minutes = ((totalSeconds / 60) % 60).toInt()
    val hours = (totalSeconds / 3600).toInt()
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}

private fun normalizeProgress(progress: Long, duration: Long): Long {
    if (duration <= 0L) return progress.coerceAtLeast(0L)
    return if (progress >= duration - 5_000L) 0L else progress.coerceIn(0L, duration)
}

private fun buildProgressKey(videoId: Int, displayTitle: String): String {
    return "progress_${videoId}_${displayTitle.trim().ifBlank { "default" }}"
}

private fun saveEpisodeProgress(
    prefs: SharedPreferences,
    key: String,
    progress: Long,
    duration: Long
) {
    prefs.edit()
        .putLong(key, normalizeProgress(progress, duration))
        .putLong("${key}_duration", duration.coerceAtLeast(0L))
        .apply()
}

private fun shouldUseLocalProxy(url: String, isLocalPlayback: Boolean): Boolean {
    if (isLocalPlayback) return false
    val lower = url.lowercase()
    if (lower.contains(".m3u8")) return true
    return lower.contains(".mp4") ||
        lower.contains(".mkv") ||
        lower.contains(".mov") ||
        lower.contains(".flv") ||
        lower.contains(".ts")
}

private fun isLocalUri(url: String): Boolean {
    val trimmed = url.trim()
    return trimmed.startsWith("content://") || trimmed.startsWith("file://")
}

@OptIn(UnstableApi::class)
private fun inferMimeTypeFromUrl(url: String): String? {
    val lower = url.lowercase()
    return when {
        lower.contains(".m3u8") -> MimeTypes.APPLICATION_M3U8
        lower.contains(".mp4") -> MimeTypes.VIDEO_MP4
        lower.contains(".mkv") -> MimeTypes.VIDEO_MATROSKA
        lower.contains(".webm") -> MimeTypes.VIDEO_WEBM
        lower.contains(".mp3") -> MimeTypes.AUDIO_MPEG
        lower.contains(".m4a") -> MimeTypes.AUDIO_MP4
        lower.contains(".ts") -> MimeTypes.VIDEO_MP2T
        else -> null
    }
}

@OptIn(UnstableApi::class)
private fun detectMimeTypeFromNetwork(url: String, sourceHeaders: Map<String, String>): String? {
    val client = NetworkTuning.createTunedClient(trustAllSsl = true)
    val headers = NetworkTuning.buildCommonHeaders(url, sourceHeaders)
    val requestBuilder = Request.Builder().url(url)
    headers.forEach { (k, v) -> requestBuilder.header(k, v) }
    requestBuilder.header("Range", "bytes=0-0")

    return runCatching {
        client.newCall(requestBuilder.build()).execute().use { response ->
            val contentType = response.header("Content-Type").orEmpty().lowercase()
            when {
                contentType.contains("mpegurl") || contentType.contains("x-mpegurl") -> MimeTypes.APPLICATION_M3U8
                contentType.contains("mp4") -> MimeTypes.VIDEO_MP4
                contentType.contains("matroska") || contentType.contains("x-matroska") -> MimeTypes.VIDEO_MATROSKA
                contentType.contains("webm") -> MimeTypes.VIDEO_WEBM
                contentType.contains("mp2t") -> MimeTypes.VIDEO_MP2T
                contentType.contains("audio/mpeg") -> MimeTypes.AUDIO_MPEG
                else -> null
            }
        }
    }.getOrNull()
}

private data class CandidateProbeResult(
    val playable: Boolean,
    val latencyMs: Long,
    val mimeType: String?
)

@OptIn(UnstableApi::class)
private fun probePlayableCandidate(rawUrl: String): CandidateProbeResult {
    val start = System.currentTimeMillis()
    val parsed = parseVideoUrl(rawUrl)
    val inferredMime = inferMimeTypeFromUrl(parsed.url)
    if (parsed.url.isBlank()) {
        return CandidateProbeResult(playable = false, latencyMs = 9999L, mimeType = inferredMime)
    }

    val client = NetworkTuning.createTunedClient(trustAllSsl = true)
    val headers = NetworkTuning.buildCommonHeaders(parsed.url, parsed.headers)
    val requestBuilder = Request.Builder().url(parsed.url)
    headers.forEach { (k, v) -> requestBuilder.header(k, v) }

    return try {
        var resolvedMime: String? = inferredMime
        val playable = runCatching {
            if (inferredMime == MimeTypes.APPLICATION_M3U8 || parsed.url.contains(".m3u8", ignoreCase = true)) {
                client.newCall(requestBuilder.build()).execute().use { response ->
                    if (!response.isSuccessful) return@use false
                    val body = response.body?.string().orEmpty()
                    val contentType = response.header("Content-Type").orEmpty().lowercase()
                    resolvedMime = when {
                        contentType.contains("mpegurl") || contentType.contains("x-mpegurl") -> MimeTypes.APPLICATION_M3U8
                        else -> inferredMime ?: MimeTypes.APPLICATION_M3U8
                    }
                    body.lineSequence().any { line ->
                        val trimmed = line.trim()
                        trimmed.isNotBlank() && !trimmed.startsWith("#")
                    }
                }
            } else {
                requestBuilder.header("Range", "bytes=0-0")
                client.newCall(requestBuilder.build()).execute().use { response ->
                    if (!response.isSuccessful) return@use false
                    val contentType = response.header("Content-Type").orEmpty().lowercase()
                    resolvedMime = when {
                        contentType.contains("mpegurl") || contentType.contains("x-mpegurl") -> MimeTypes.APPLICATION_M3U8
                        contentType.contains("mp4") -> MimeTypes.VIDEO_MP4
                        contentType.contains("matroska") || contentType.contains("x-matroska") -> MimeTypes.VIDEO_MATROSKA
                        contentType.contains("webm") -> MimeTypes.VIDEO_WEBM
                        contentType.contains("mp2t") -> MimeTypes.VIDEO_MP2T
                        else -> inferredMime
                    }
                    response.code in 200..299
                }
            }
        }.getOrDefault(false)
        CandidateProbeResult(
            playable = playable,
            latencyMs = if (playable) System.currentTimeMillis() - start else 9999L,
            mimeType = if (playable) resolvedMime else inferredMime
        )
    } catch (_: Exception) {
        CandidateProbeResult(playable = false, latencyMs = 9999L, mimeType = inferredMime)
    }
}

fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun resolveCurrentOrientationLock(activity: Activity?): Int {
    if (activity == null) return ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    val rotation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        activity.display?.rotation ?: Surface.ROTATION_0
    } else {
        @Suppress("DEPRECATION")
        activity.windowManager.defaultDisplay.rotation
    }
    return when (rotation) {
        Surface.ROTATION_90 -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        Surface.ROTATION_270 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
        Surface.ROTATION_180 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
        else -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }
}

private fun resolveToggledPlayerOrientation(
    activity: Activity?,
    currentOrientation: Int,
    physicalOrientationDegrees: Int
): Int {
    if (isLandscapeOrientation(currentOrientation)) {
        return ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
    }
    return resolvePreferredLandscapeOrientation(activity, physicalOrientationDegrees)
}

private fun isLandscapeOrientation(orientation: Int): Boolean {
    return orientation == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE ||
        orientation == ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE ||
        orientation == ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE ||
        orientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
}

private fun resolvePreferredLandscapeOrientation(
    activity: Activity?,
    physicalOrientationDegrees: Int
): Int {
    if (physicalOrientationDegrees != OrientationEventListener.ORIENTATION_UNKNOWN) {
        return when (physicalOrientationDegrees) {
            in 45..134 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
            in 225..314 -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            else -> resolveCurrentLandscapeOrientation(activity)
        }
    }
    return resolveCurrentLandscapeOrientation(activity)
}

private fun resolveCurrentLandscapeOrientation(activity: Activity?): Int {
    val current = resolveCurrentOrientationLock(activity)
    return when (current) {
        ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
        ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE -> current
        else -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
    }
}

private fun enterPictureInPictureIfPossible(
    activity: Activity?,
    exoPlayer: ExoPlayer,
    playerView: PlayerView?
) {
    if (activity == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    if (activity.isFinishing || activity.isDestroyed || activity.isInPictureInPictureMode) return

    val shouldEnterPip = exoPlayer.playbackState != Player.STATE_IDLE &&
        exoPlayer.playbackState != Player.STATE_ENDED &&
        (exoPlayer.isPlaying || exoPlayer.playWhenReady)
    if (!shouldEnterPip) return

    val aspectRatio = resolvePictureInPictureAspectRatio(exoPlayer, playerView)
    val paramsBuilder = PictureInPictureParams.Builder()
    aspectRatio?.let(paramsBuilder::setAspectRatio)
    playerView?.let { view ->
        val sourceRect = Rect()
        if (view.getGlobalVisibleRect(sourceRect) && !sourceRect.isEmpty) {
            paramsBuilder.setSourceRectHint(sourceRect)
        }
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        paramsBuilder.setAutoEnterEnabled(true)
        paramsBuilder.setSeamlessResizeEnabled(true)
    }
    activity.enterPictureInPictureMode(paramsBuilder.build())
}

private fun resolvePictureInPictureAspectRatio(
    exoPlayer: ExoPlayer,
    playerView: PlayerView?
): Rational? {
    val videoSize = exoPlayer.videoSize
    if (videoSize.width > 0 && videoSize.height > 0) {
        return Rational(videoSize.width, videoSize.height)
    }

    val width = playerView?.width ?: 0
    val height = playerView?.height ?: 0
    return if (width > 0 && height > 0) Rational(width, height) else null
}

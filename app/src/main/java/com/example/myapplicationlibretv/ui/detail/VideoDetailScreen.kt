package com.example.myapplicationlibretv.ui.detail

import androidx.compose.animation.*
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items as lazyItems
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myapplicationlibretv.data.api.fetchCmsResponse
import coil.compose.AsyncImage
import com.example.myapplicationlibretv.data.db.AppDatabase
import com.example.myapplicationlibretv.data.db.FavoriteVideo
import com.example.myapplicationlibretv.data.db.HistoryVideo
import com.example.myapplicationlibretv.data.model.Site
import com.example.myapplicationlibretv.data.model.VideoItem
import com.example.myapplicationlibretv.data.repository.SourceRepository
import com.example.myapplicationlibretv.download.BackgroundDownloadService
import com.example.myapplicationlibretv.utils.LocalProxyServer
import kotlinx.serialization.Serializable
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull
import java.util.LinkedHashMap

class DetailViewModel(context: android.app.Application) : androidx.lifecycle.AndroidViewModel(context) {
    companion object {
        private const val DETAIL_FETCH_TIMEOUT_MS = 3_500L
        private const val DETAIL_FETCH_CONCURRENCY = 6
        private const val MAX_DETAIL_SITES = 12
    }

    private val videoDao = AppDatabase.getDatabase(context).videoDao()
    private var detailFetchJob: kotlinx.coroutines.Job? = null
    private val detailCache = object : LinkedHashMap<String, Pair<VideoItem?, List<PlaySource>>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<VideoItem?, List<PlaySource>>>?): Boolean {
            return size > 12
        }
    }

    private val _videoDetail = MutableStateFlow<VideoItem?>(null)
    val videoDetail: StateFlow<VideoItem?> = _videoDetail

    private val _playSources = MutableStateFlow<List<PlaySource>>(emptyList())
    val playSources: StateFlow<List<PlaySource>> = _playSources

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading
    private var currentSiteKey: String = ""
    private var currentVideoId: Int = 0
    private val currentSavedRecordId = MutableStateFlow(0)
    private val proxyServer = LocalProxyServer.getInstance(context)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val isFavorite: StateFlow<Boolean> = currentSavedRecordId.flatMapLatest { recordId ->
        recordId.takeIf { it != 0 }?.let { videoDao.isFavorite(it) } ?: flowOf(false)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun fetchDetail(siteKey: String, id: Int, videoTitle: String) {
        currentSiteKey = siteKey
        currentVideoId = id
        currentSavedRecordId.value = buildSavedRecordId(siteKey, id)
        val cacheKey = "$siteKey|$id|$videoTitle"
        detailCache[cacheKey]?.let { cached ->
            _videoDetail.value = cached.first
            _playSources.value = cached.second
        }
        detailFetchJob?.cancel()
        detailFetchJob = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            _isLoading.value = true
            _playSources.value = emptyList()
            try {
                val sites = SourceRepository.getSitesSnapshot()
                val preferredSite = sites.firstOrNull { it.key == siteKey || it.api == siteKey }
                    ?: SourceRepository.currentSite.value
                    ?: sites.firstOrNull()

                if (preferredSite != null) {
                    SourceRepository.selectSite(preferredSite)
                }

                val candidateSites = buildList {
                    preferredSite?.let { add(it) }
                    addAll(sites.filter { site -> site.api != preferredSite?.api }.take(MAX_DETAIL_SITES - size))
                }
                val limiter = Semaphore(DETAIL_FETCH_CONCURRENCY)
                val detailResults = supervisorScope {
                    buildList {
                        preferredSite?.let { site ->
                            add(async {
                                limiter.withPermit {
                                    withTimeoutOrNull(DETAIL_FETCH_TIMEOUT_MS) {
                                        fetchDetailFromPreferredSite(site, id, videoTitle)
                                    }
                                }
                            })
                        }
                        candidateSites.filter { site -> site.api != preferredSite?.api }
                            .forEach { site ->
                                add(async {
                                    limiter.withPermit {
                                        withTimeoutOrNull(DETAIL_FETCH_TIMEOUT_MS) {
                                            fetchDetailFromSearch(site, videoTitle)
                                        }
                                    }
                                })
                            }
                    }.mapNotNull { it.await() }
                }

                val mergedResults = detailResults
                    .distinctBy { it.site.api }
                    .filterStrictMatches(videoTitle, preferredSite?.api)
                val preferredResult = mergedResults.firstOrNull { it.site.api == preferredSite?.api }
                val baseVideo = preferredResult?.video ?: mergedResults.firstOrNull()?.video

                _videoDetail.value = baseVideo
                val sources = mergedResults.flatMap { result ->
                    parseSources(result.video.playFrom, result.video.playUrl).map { source ->
                        source.copy(name = "${result.site.name} · ${source.name}")
                    }
                }
                _playSources.value = sources
                detailCache[cacheKey] = baseVideo to sources
                
                // 预加载：在详情页即触发首个资源的 M3U8 解析与 TS 分片预热
                sources.firstOrNull()?.episodes?.firstOrNull()?.let { firstEpisode ->
                    val firstUrl = firstEpisode.url
                    if (firstUrl.isNotBlank()) {
                        val p = com.example.myapplicationlibretv.download.parseVideoUrl(firstUrl)
                        proxyServer.prefetch(p.url, com.example.myapplicationlibretv.data.api.NetworkTuning.buildCommonHeaders(p.url, p.headers))
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                _videoDetail.value = null
                _playSources.value = emptyList()
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun toggleFavorite() {
        val video = _videoDetail.value ?: return
        val savedRecordId = currentSavedRecordId.value.takeIf { it != 0 } ?: buildSavedRecordId(currentSiteKey, currentVideoId)
        viewModelScope.launch {
            if (isFavorite.value) {
                videoDao.deleteFavorite(savedRecordId)
            } else {
                videoDao.insertFavorite(
                    FavoriteVideo(
                        id = savedRecordId,
                        name = video.name,
                        pic = video.pic,
                        siteKey = currentSiteKey,
                        sourceVideoId = currentVideoId
                    )
                )
            }
        }
    }

    fun addToHistory(progress: Long = 0, duration: Long = 0) {
        val video = _videoDetail.value ?: return
        val savedRecordId = currentSavedRecordId.value.takeIf { it != 0 } ?: buildSavedRecordId(currentSiteKey, currentVideoId)
        viewModelScope.launch {
            videoDao.insertHistory(
                HistoryVideo(
                    id = savedRecordId,
                    name = video.name,
                    pic = video.pic,
                    siteKey = currentSiteKey,
                    sourceVideoId = currentVideoId,
                    progress = progress,
                    duration = duration
                )
            )
        }
    }

    private suspend fun fetchDetailFromPreferredSite(
        site: Site,
        id: Int,
        videoTitle: String
    ): SiteVideoDetail? {
        val detail = runCatching {
            fetchCmsResponse(
                baseUrl = site.api,
                ids = id.toString()
            ).list.firstOrNull()
        }.getOrNull()
        if (detail != null) {
            return SiteVideoDetail(site, detail)
        }
        return fetchDetailFromSearch(site, videoTitle)
    }

    private suspend fun fetchDetailFromSearch(site: Site, videoTitle: String): SiteVideoDetail? {
        val title = videoTitle.trim()
        if (title.isEmpty()) return null

        val candidates = runCatching {
            fetchCmsResponse(
                baseUrl = site.api,
                keyword = title
            ).list
        }.getOrNull().orEmpty()

        val matched = pickBestVideoMatch(candidates, title) ?: return null
        val detail = runCatching {
            fetchCmsResponse(
                baseUrl = site.api,
                ids = matched.id.toString()
            ).list.firstOrNull() ?: matched
        }.getOrDefault(matched)

        return SiteVideoDetail(site, detail)
    }

    private fun pickBestVideoMatch(candidates: List<VideoItem>, videoTitle: String): VideoItem? {
        val normalizedTarget = normalizeTitle(videoTitle)
        if (normalizedTarget.isBlank()) return null
        return candidates.firstOrNull { candidate ->
            normalizeTitle(candidate.name) == normalizedTarget
        }
    }

    private fun normalizeTitle(value: String): String {
        return value.lowercase()
            .replace(Regex("\\s+"), "")
            .replace(Regex("[^\\p{L}\\p{N}]"), "")
    }

    private fun buildSavedRecordId(siteKey: String, sourceVideoId: Int): Int {
        return "$siteKey#$sourceVideoId".hashCode()
    }

    private fun List<SiteVideoDetail>.filterStrictMatches(
        videoTitle: String,
        preferredApi: String?
    ): List<SiteVideoDetail> {
        val normalizedTarget = normalizeTitle(videoTitle)
        if (normalizedTarget.isBlank()) return this
        return filter { result ->
            normalizeTitle(result.video.name) == normalizedTarget
        }.ifEmpty {
            // 原始源按 ID 拉回来的详情优先保留，避免标题别名造成空详情。
            firstOrNull { it.site.api == preferredApi }?.let(::listOf).orEmpty()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoDetailScreen(
    siteKey: String,
    videoId: Int,
    videoTitle: String,
    onBack: () -> Unit,
    onPlayClick: (Int, String, String, List<PlayerEpisodePayload>, Int) -> Unit,
    viewModel: DetailViewModel = viewModel()
) {
    val video by viewModel.videoDetail.collectAsState()
    val sources by viewModel.playSources.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val isFavorite by viewModel.isFavorite.collectAsState()
    val context = LocalContext.current

    var selectedSourceIndex by remember { mutableIntStateOf(0) }
    var showDownloadSheet by remember { mutableStateOf(false) }
    var downloadToast by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(siteKey, videoId, videoTitle) {
        viewModel.fetchDetail(siteKey, videoId, videoTitle)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(video?.name ?: "Loading...", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    video?.let {
                        IconButton(onClick = { viewModel.toggleFavorite() }) {
                            Icon(
                                if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                contentDescription = "Favorite",
                                tint = if (isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        if (isLoading) {
            DetailLoadingSkeleton(modifier = Modifier.padding(paddingValues))
        } else {
            video?.let { item ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                ) {
                    BoxWithConstraints(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        val sidePadding = when {
                            maxWidth >= 900.dp -> 32.dp
                            maxWidth >= 700.dp -> 24.dp
                            else -> 16.dp
                        }
                        val compactLayout = maxWidth < 520.dp
                        val posterWidth = when {
                            maxWidth >= 900.dp -> 200.dp
                            maxWidth >= 700.dp -> 168.dp
                            maxWidth >= 520.dp -> 140.dp
                            else -> (maxWidth * 0.34f).coerceIn(104.dp, 148.dp)
                        }

                        val contentHorizontalPadding = sidePadding
                        if (compactLayout) {
                            Column(
                                modifier = Modifier.padding(horizontal = contentHorizontalPadding, vertical = 12.dp)
                            ) {
                                AsyncImage(
                                    model = item.pic,
                                    contentDescription = item.name,
                                    modifier = Modifier
                                        .width(posterWidth)
                                        .aspectRatio(0.7f),
                                    contentScale = ContentScale.Crop
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                DetailHeaderText(
                                    item = item,
                                    sourceCount = sources.size
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                DetailPlayButton(
                                    item = item,
                                    sources = sources,
                                    selectedSourceIndex = selectedSourceIndex,
                                    videoId = videoId,
                                    viewModel = viewModel,
                                    onPlayClick = onPlayClick,
                                    onDownloadClick = { showDownloadSheet = true }
                                )
                            }
                        } else {
                            Row(
                                modifier = Modifier.padding(horizontal = contentHorizontalPadding, vertical = 12.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                AsyncImage(
                                    model = item.pic,
                                    contentDescription = item.name,
                                    modifier = Modifier
                                        .width(posterWidth)
                                        .aspectRatio(0.7f),
                                    contentScale = ContentScale.Crop
                                )
                                Spacer(modifier = Modifier.width(16.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    DetailHeaderText(
                                        item = item,
                                        sourceCount = sources.size
                                    )
                                    Spacer(modifier = Modifier.height(10.dp))
                                    DetailPlayButton(
                                        item = item,
                                        sources = sources,
                                        selectedSourceIndex = selectedSourceIndex,
                                        videoId = videoId,
                                        viewModel = viewModel,
                                        onPlayClick = onPlayClick,
                                        onDownloadClick = { showDownloadSheet = true }
                                    )
                                }
                            }
                        }
                    }

                    // Source Tabs
                    if (sources.isNotEmpty()) {
                        ScrollableTabRow(
                            selectedTabIndex = selectedSourceIndex,
                            edgePadding = 0.dp,
                            containerColor = MaterialTheme.colorScheme.surface,
                            divider = {}
                        ) {
                            sources.forEachIndexed { index, source ->
                                Tab(
                                    selected = selectedSourceIndex == index,
                                    onClick = { selectedSourceIndex = index },
                                    text = { Text(source.name) }
                                )
                            }
                        }
                    }

                    // Episode Grid
                    val currentEpisodes = sources.getOrNull(selectedSourceIndex)?.episodes ?: emptyList()
                    
                    AnimatedContent(
                        targetState = currentEpisodes,
                        transitionSpec = {
                            fadeIn() togetherWith fadeOut()
                        },
                        label = "episodeContent",
                        modifier = Modifier.weight(1f)
                    ) { episodes ->
                        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                            val sidePadding = when {
                                maxWidth >= 900.dp -> 32.dp
                                maxWidth >= 700.dp -> 24.dp
                                else -> 16.dp
                            }
                            LazyVerticalGrid(
                                columns = GridCells.Adaptive(minSize = 100.dp),
                                contentPadding = PaddingValues(horizontal = sidePadding, vertical = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                items(episodes) { episode ->
                                    OutlinedButton(
                                        onClick = {
                                            val episodePayload = buildPlayerEpisodesPayload(
                                                sources = sources,
                                                selectedSourceIndex = selectedSourceIndex,
                                                videoName = item.name
                                            )
                                            val episodeIndex = episodes.indexOfFirst { it.name == episode.name }
                                            val playlist = collectFailoverUrls(
                                                sources = sources,
                                                selectedSourceIndex = selectedSourceIndex,
                                                episodeName = episode.name
                                            )
                                            viewModel.addToHistory()
                                            onPlayClick(
                                                videoId,
                                                buildPlayerTitle(item.name, episode.name),
                                                playlist.joinToString("\n"),
                                                episodePayload,
                                                episodeIndex.coerceAtLeast(0)
                                            )
                                        },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                        shape = MaterialTheme.shapes.small
                                    ) {
                                        Text(
                                            text = episode.name,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                }

                                item {
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Text(
                                        text = "简介: " + (item.content ?: "暂无介绍"),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(top = 8.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    video?.let { item ->
        val episodes = sources.getOrNull(selectedSourceIndex)?.episodes.orEmpty()
        if (showDownloadSheet) {
            DownloadSelectionSheet(
                videoName = item.name,
                episodes = episodes,
                sources = sources,
                selectedSourceIndex = selectedSourceIndex,
                onDismiss = { showDownloadSheet = false },
                onDownloadOne = { episode ->
                    val playlist = collectFailoverUrls(sources, selectedSourceIndex, episode.name)
                    if (playlist.isNotEmpty()) {
                        BackgroundDownloadService.start(
                            context = context,
                            rawUrl = playlist.joinToString("\n"),
                            title = buildPlayerTitle(item.name, episode.name)
                        )
                        downloadToast = "已加入下载：${episode.name}"
                    }
                    showDownloadSheet = false
                },
                onDownloadAll = {
                    val tasks = episodes.mapNotNull { episode ->
                        val playlist = collectFailoverUrls(sources, selectedSourceIndex, episode.name)
                        playlist.takeIf { it.isNotEmpty() }?.let { episode to it }
                    }
                    tasks.forEach { (episode, playlist) ->
                        BackgroundDownloadService.start(
                            context = context,
                            rawUrl = playlist.joinToString("\n"),
                            title = buildPlayerTitle(item.name, episode.name)
                        )
                    }
                    downloadToast = "已加入批量下载：${tasks.size} 集"
                    showDownloadSheet = false
                }
            )
        }
    }

    downloadToast?.let { message ->
        LaunchedEffect(message) {
            kotlinx.coroutines.delay(1400)
            if (downloadToast == message) downloadToast = null
        }
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Surface(
                modifier = Modifier.padding(16.dp),
                color = MaterialTheme.colorScheme.inverseSurface,
                shape = MaterialTheme.shapes.small
            ) {
                Text(
                    text = message,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    color = MaterialTheme.colorScheme.inverseOnSurface
                )
            }
        }
    }
}

data class PlaySource(val name: String, val episodes: List<Episode>)

@Composable
private fun DetailHeaderText(
    item: VideoItem,
    sourceCount: Int
) {
    Column {
        Text(text = item.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(text = "类型: ${item.typeName ?: "未知"}", style = MaterialTheme.typography.bodyMedium)
        Text(text = "状态: ${item.remarks ?: "未知"}", style = MaterialTheme.typography.bodyMedium)
        Text(
            text = "聚合源数: $sourceCount",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun DetailPlayButton(
    item: VideoItem,
    sources: List<PlaySource>,
    selectedSourceIndex: Int,
    videoId: Int,
    viewModel: DetailViewModel,
    onPlayClick: (Int, String, String, List<PlayerEpisodePayload>, Int) -> Unit,
    onDownloadClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Button(
            onClick = {
                sources.getOrNull(selectedSourceIndex)?.episodes?.firstOrNull()?.let {
                    val episodePayload = buildPlayerEpisodesPayload(
                        sources = sources,
                        selectedSourceIndex = selectedSourceIndex,
                        videoName = item.name
                    )
                    val playlist = collectFailoverUrls(
                        sources = sources,
                        selectedSourceIndex = selectedSourceIndex,
                        episodeName = it.name
                    )
                    viewModel.addToHistory()
                    onPlayClick(
                        videoId,
                        buildPlayerTitle(item.name, it.name),
                        playlist.joinToString("\n"),
                        episodePayload,
                        0
                    )
                }
            },
            modifier = Modifier.weight(1f).heightIn(min = 48.dp)
        ) {
            Icon(Icons.Default.PlayArrow, contentDescription = null)
            Text("立即播放")
        }
        OutlinedButton(
            onClick = onDownloadClick,
            enabled = sources.getOrNull(selectedSourceIndex)?.episodes?.isNotEmpty() == true,
            modifier = Modifier.weight(1f).heightIn(min = 48.dp)
        ) {
            Icon(Icons.Default.Download, contentDescription = null)
            Text("下载")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DownloadSelectionSheet(
    videoName: String,
    episodes: List<Episode>,
    sources: List<PlaySource>,
    selectedSourceIndex: Int,
    onDismiss: () -> Unit,
    onDownloadOne: (Episode) -> Unit,
    onDownloadAll: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .padding(horizontal = 16.dp)
                .padding(bottom = 20.dp)
        ) {
            Text(videoName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(modifier = Modifier.height(8.dp))
            if (episodes.size > 1) {
                Button(
                    onClick = onDownloadAll,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Download, contentDescription = null)
                    Text("批量下载全部 ${episodes.size} 集")
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                lazyItems(episodes, key = { it.name }) { episode ->
                    val failoverCount = collectFailoverUrls(sources, selectedSourceIndex, episode.name).size
                    OutlinedButton(
                        onClick = { onDownloadOne(episode) },
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                            Text(episode.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "自动嗅探 $failoverCount 条线路",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(Icons.Default.Download, contentDescription = null)
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailLoadingSkeleton(modifier: Modifier = Modifier) {
    val shimmer = rememberInfiniteTransition(label = "detailLoading")
    val alpha by shimmer.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 850, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "detailLoadingAlpha"
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val compactLayout = maxWidth < 520.dp
            val posterWidth = when {
                maxWidth >= 900.dp -> 200.dp
                maxWidth >= 700.dp -> 168.dp
                maxWidth >= 520.dp -> 140.dp
                else -> (maxWidth * 0.34f).coerceIn(104.dp, 148.dp)
            }
            if (compactLayout) {
                Column {
                    Box(
                        modifier = Modifier
                            .width(posterWidth)
                            .aspectRatio(0.7f)
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha), MaterialTheme.shapes.medium)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    repeat(4) { index ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(if (index == 0) 0.9f else 0.7f)
                                .height(if (index == 0) 20.dp else 12.dp)
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha), MaterialTheme.shapes.small)
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(40.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha), MaterialTheme.shapes.medium)
                    )
                }
            } else {
                Row {
                    Box(
                        modifier = Modifier
                            .width(posterWidth)
                            .aspectRatio(0.7f)
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha), MaterialTheme.shapes.medium)
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        repeat(4) { index ->
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(if (index == 0) 0.9f else 0.6f)
                                    .height(if (index == 0) 20.dp else 12.dp)
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha), MaterialTheme.shapes.small)
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(40.dp)
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha), MaterialTheme.shapes.medium)
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(20.dp))
        repeat(2) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(36.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha), MaterialTheme.shapes.medium)
            )
            Spacer(modifier = Modifier.height(12.dp))
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 100.dp),
            contentPadding = PaddingValues(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(12) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(38.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha), MaterialTheme.shapes.small)
                )
            }
        }
    }
}
data class Episode(val name: String, val url: String)
private data class SiteVideoDetail(val site: Site, val video: VideoItem)
@Serializable
data class PlayerEpisodePayload(val name: String, val title: String, val playlist: String)

private fun buildPlayerTitle(videoName: String, episodeName: String): String {
    val cleanedEpisode = episodeName.trim()
    return if (cleanedEpisode.isBlank() || cleanedEpisode == "播放") {
        videoName
    } else {
        "$videoName · $cleanedEpisode"
    }
}

fun parseSources(playFrom: String?, playUrl: String?): List<PlaySource> {
    if (playUrl.isNullOrEmpty()) return emptyList()
    
    val fromNames = playFrom?.split("\$\$\$") ?: listOf("默认线路")
    val urlGroups = playUrl.split("\$\$\$")
    
    return urlGroups.mapIndexedNotNull { index, urlGroup ->
        val name = fromNames.getOrNull(index) ?: "线路${index + 1}"
        val episodes = urlGroup.split("#").mapNotNull { epStr ->
            val parts = epStr.split("\$", limit = 2)
            if (parts.size >= 2) {
                Episode(name = parts[0], url = parts[1])
            } else if (parts.isNotEmpty() && parts[0].isNotBlank()) {
                Episode(name = "播放", url = parts[0])
            } else null
        }
        if (episodes.isNotEmpty()) PlaySource(name, episodes) else null
    }
}

private fun collectFailoverUrls(
    sources: List<PlaySource>,
    selectedSourceIndex: Int,
    episodeName: String
): List<String> {
    val urls = mutableListOf<String>()
    fun add(url: String?) {
        val u = url?.trim().orEmpty()
        if (u.isBlank()) return
        if (urls.any { it == u }) return
        urls += u
    }

    val preferred = sources.getOrNull(selectedSourceIndex)
        ?.episodes
        ?.firstOrNull { it.name == episodeName }
        ?.url
    add(preferred)

    sources.forEachIndexed { index, source ->
        if (index == selectedSourceIndex) return@forEachIndexed
        add(source.episodes.firstOrNull { it.name == episodeName }?.url)
    }

    return urls
}

private fun buildPlayerEpisodesPayload(
    sources: List<PlaySource>,
    selectedSourceIndex: Int,
    videoName: String
): List<PlayerEpisodePayload> {
    val episodes = sources.getOrNull(selectedSourceIndex)?.episodes.orEmpty()
    return episodes.map { episode ->
        PlayerEpisodePayload(
            name = episode.name,
            title = buildPlayerTitle(videoName, episode.name),
            playlist = collectFailoverUrls(
                sources = sources,
                selectedSourceIndex = selectedSourceIndex,
                episodeName = episode.name
            ).joinToString("\n")
        )
    }
}

package com.example.myapplicationlibretv.ui.home

import android.content.Context
import com.example.myapplicationlibretv.data.api.ActorFilmographyScraper
import androidx.lifecycle.viewModelScope
import com.example.myapplicationlibretv.data.api.fetchCmsResponse
import com.example.myapplicationlibretv.data.api.RetrofitClient
import com.example.myapplicationlibretv.data.model.Site
import com.example.myapplicationlibretv.data.model.VideoItem
import com.example.myapplicationlibretv.data.repository.SourceRepository
import com.example.myapplicationlibretv.data.db.AppDatabase
import com.example.myapplicationlibretv.data.db.FavoriteVideo
import com.example.myapplicationlibretv.data.db.HistoryVideo
import java.io.IOException
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.LinkedHashMap
import kotlin.random.Random

@Serializable
data class SourcedVideo(
    val siteKey: String,
    val siteName: String,
    val siteApi: String,
    val video: VideoItem
)

class HomeViewModel(application: android.app.Application) : androidx.lifecycle.AndroidViewModel(application) {
    companion object {
        private const val MOON_TV_CONFIG_URL =
            "https://raw.githubusercontent.com/666zmy/MoonTV/refs/heads/main/config.json"
        private const val FAN_TAI_YING_CONFIG_URL =
            "http://www.饭太硬.com/tv"
        private const val SEARCH_HISTORY_LIMIT = 12
        private const val FETCH_CONCURRENCY = 8
        private const val PROBE_CONCURRENCY = 8
        private const val HOT_REQUEST_TIMEOUT_MS = 4_000L
        private const val SEARCH_REQUEST_TIMEOUT_MS = 4_500L
        private const val PROBE_TIMEOUT_MS = 2_500L
        private const val SEARCH_DEBOUNCE_MS = 350L
        private const val HOT_CACHE_KEY = "hot_cache"
        private const val SITE_SCORE_KEY = "site_scores_v2"
        private const val ADULT_FILTER_KEY = "adult_filter_enabled"
        private const val MAX_TRENDING_SITES = 18
        private const val MAX_SEARCH_SITES = 24
        private const val MAX_PERSON_SEARCH_SITES = 48
        private const val SEARCH_FALLBACK_MIN_RESULTS = 8
        private const val SEARCH_FALLBACK_SITES = 8
        private const val SEARCH_FALLBACK_PAGES = 2
        private const val SEARCH_DETAIL_ENRICH_LIMIT = 36
        private const val PERSON_SEARCH_FALLBACK_SITES = 36
        private const val PERSON_SEARCH_FALLBACK_PAGES = 10
        private const val PERSON_SEARCH_DETAIL_ENRICH_LIMIT = 160
    }

    private val videoDao = AppDatabase.getDatabase(application).videoDao()
    private val prefs = application.getSharedPreferences("subscriptions", Context.MODE_PRIVATE)
    private val cacheJson = Json { ignoreUnknownKeys = true }
    private var autoUpdateJob: Job? = null
    private var homeFetchJob: Job? = null
    private var searchFetchJob: Job? = null
    private var searchInputDebounceJob: Job? = null
    private var isAppActive = true
    private val searchCache = object : LinkedHashMap<String, List<SourcedVideo>>(24, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<SourcedVideo>>?): Boolean {
            return size > 20
        }
    }

    private val _videoList = MutableStateFlow<List<SourcedVideo>>(emptyList())
    val videoList: StateFlow<List<SourcedVideo>> = _videoList
    private val _searchResults = MutableStateFlow<List<SourcedVideo>>(emptyList())
    val searchResults: StateFlow<List<SourcedVideo>> = _searchResults

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading
    private val _searchLoading = MutableStateFlow(false)
    val searchLoading: StateFlow<Boolean> = _searchLoading

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage
    private val _searchErrorMessage = MutableStateFlow<String?>(null)
    val searchErrorMessage: StateFlow<String?> = _searchErrorMessage

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery
    private val _searchUiVisible = MutableStateFlow(false)
    val searchUiVisible: StateFlow<Boolean> = _searchUiVisible

    private val _searchHistory = MutableStateFlow(loadSearchHistory())
    val searchHistory: StateFlow<List<String>> = _searchHistory
    private val _adultContentEnabled = MutableStateFlow(loadAdultContentEnabled())
    val adultContentEnabled: StateFlow<Boolean> = _adultContentEnabled
    private val _selectedTab = MutableStateFlow(0)
    val selectedTab: StateFlow<Int> = _selectedTab

    val sites = SourceRepository.sites
    val currentSite = SourceRepository.currentSite

    val favorites: StateFlow<List<FavoriteVideo>> = videoDao.getFavorites()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val history: StateFlow<List<HistoryVideo>> = videoDao.getHistory()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        SourceRepository.setAdultContentEnabled(_adultContentEnabled.value)
        viewModelScope.launch {
            ensureDefaultSubscriptions()
            loadCachedTrending()
            fetchVideos()
            refreshSubscriptions(manual = false)
        }
        startAutoUpdate()
    }

    fun setAppActive(active: Boolean) {
        if (isAppActive == active) return
        isAppActive = active
        if (active) {
            startAutoUpdate()
        } else {
            autoUpdateJob?.cancel()
            autoUpdateJob = null
        }
    }

    fun onSearchQueryChange(query: String) {
        _searchQuery.value = query
        if (query.isBlank()) {
            searchInputDebounceJob?.cancel()
            clearSearchState()
        } else if (_searchUiVisible.value) {
            scheduleDebouncedSearch(query)
        }
    }

    fun openSearchUi() {
        _searchUiVisible.value = true
        val query = _searchQuery.value.trim()
        if (query.isNotEmpty()) {
            scheduleDebouncedSearch(query, debounceMs = 120L)
        }
    }

    fun setSelectedTab(tab: Int) {
        _selectedTab.value = tab.coerceIn(0, 3)
    }

    fun closeSearchUi(resetQuery: Boolean) {
        _searchUiVisible.value = false
        searchInputDebounceJob?.cancel()
        if (resetQuery) {
            _searchQuery.value = ""
            clearSearchState()
        }
    }

    fun onSiteSelected(site: Site) {
        SourceRepository.selectSite(site)
        if (_searchQuery.value.isBlank()) {
            fetchVideos(keyword = null)
        }
    }

    fun setAdultContentEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(ADULT_FILTER_KEY, enabled).apply()
        _adultContentEnabled.value = enabled
        SourceRepository.setAdultContentEnabled(enabled)
        if (_searchUiVisible.value && _searchQuery.value.isNotBlank()) {
            searchVideos(_searchQuery.value.trim())
        } else {
            fetchVideos(keyword = null)
        }
    }

    fun fetchVideos(keyword: String? = _searchQuery.value.ifEmpty { null }) {
        val searchKeyword = keyword?.trim()?.takeIf { it.isNotEmpty() }
        if (searchKeyword != null) {
            searchVideos(searchKeyword)
            return
        }
        homeFetchJob?.cancel()
        val refreshToken = nextTrendingRefreshToken()
        homeFetchJob = viewModelScope.launch(Dispatchers.IO) {
            _isLoading.value = true
            _errorMessage.value = null
            try {
                val allSites = SourceRepository.getSitesSnapshot()
                val sitesSnapshot = pickRuntimeSites(allSites, null, refreshToken)
                if (sitesSnapshot.isEmpty()) {
                    _videoList.value = emptyList()
                    _errorMessage.value = "No sources"
                    return@launch
                }

                val results = fetchSiteResults(
                    sites = sitesSnapshot,
                    searchKeyword = searchKeyword,
                    refreshToken = refreshToken
                )

                val healthUpdates = mutableListOf<Pair<String, Boolean>>()
                val succeeded = results.mapIndexedNotNull { index, result ->
                    val site = sitesSnapshot.getOrNull(index) ?: return@mapIndexedNotNull null
                    healthUpdates += site.api to result.isSuccess
                    result.getOrNull()
                }
                updateSiteHealthBatch(healthUpdates)
                val failedCount = results.count { it.isFailure }
                val merged = buildTrendingVideos(
                    videos = applyContentFilter(succeeded.flatten()),
                    refreshToken = refreshToken
                )

                _videoList.value = merged
                if (merged.isNotEmpty()) {
                    saveCachedTrending(merged)
                }

                if (merged.isEmpty()) {
                    _errorMessage.value = "暂无热门推荐"
                } else if (failedCount > 0) {
                    _errorMessage.value = "已为你刷新热门推荐，部分源不可用（${failedCount}/${sitesSnapshot.size}）"
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                e.printStackTrace()
                _errorMessage.value = "Error: ${e.localizedMessage ?: "Unknown error"}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    private fun searchVideos(keyword: String) {
        searchFetchJob?.cancel()
        searchFetchJob = viewModelScope.launch(Dispatchers.IO) {
            _searchLoading.value = true
            _searchErrorMessage.value = null
            try {
                searchCache[keyword]?.let { cached ->
                    _searchResults.value = cached
                }
                val personSearchMode = isLikelyPersonQuery(keyword)
                val allSites = SourceRepository.getSitesSnapshot()
                val sitesSnapshot = pickRuntimeSites(allSites, keyword)
                if (sitesSnapshot.isEmpty()) {
                    _searchResults.value = emptyList()
                    _searchErrorMessage.value = "No sources"
                    return@launch
                }

                val results = fetchSiteResults(
                    sites = sitesSnapshot,
                    searchKeyword = keyword
                )

                val healthUpdates = mutableListOf<Pair<String, Boolean>>()
                val succeeded = results.mapIndexedNotNull { index, result ->
                    val site = sitesSnapshot.getOrNull(index) ?: return@mapIndexedNotNull null
                    healthUpdates += site.api to result.isSuccess
                    result.getOrNull()
                }
                updateSiteHealthBatch(healthUpdates)
                val failedCount = results.count { it.isFailure }

                val primaryResults = if (personSearchMode) {
                    enrichSearchCandidates(
                        candidates = succeeded.flatten(),
                        enrichLimit = PERSON_SEARCH_DETAIL_ENRICH_LIMIT
                    )
                } else {
                    succeeded.flatten()
                }
                val sourceReturned = primaryResults
                    .let(::applyContentFilter)
                    .distinctBy { "${it.siteKey}:${it.video.id}" }
                var merged = filterSearchResults(
                    searchKeyword = keyword,
                    videos = sourceReturned
                ).distinctBy { "${it.siteKey}:${it.video.id}" }
                if (!personSearchMode && merged.size < sourceReturned.size) {
                    merged = (merged + sourceReturned)
                        .distinctBy { "${it.siteKey}:${it.video.id}" }
                }

                if (merged.size < SEARCH_FALLBACK_MIN_RESULTS) {
                    val fallbackCandidates = fetchFallbackSearchResults(
                        searchKeyword = keyword,
                        sites = sitesSnapshot.take(if (personSearchMode) PERSON_SEARCH_FALLBACK_SITES else SEARCH_FALLBACK_SITES),
                        pageCount = if (personSearchMode) PERSON_SEARCH_FALLBACK_PAGES else SEARCH_FALLBACK_PAGES,
                        enrichLimit = if (personSearchMode) PERSON_SEARCH_DETAIL_ENRICH_LIMIT else SEARCH_DETAIL_ENRICH_LIMIT
                    )
                    merged = (merged + fallbackCandidates)
                        .distinctBy { "${it.siteKey}:${it.video.id}" }
                }

                if (personSearchMode && merged.size < 20) {
                    val filmographyCandidates = fetchActorFilmographyResults(
                        actorName = keyword,
                        sites = sitesSnapshot
                    )
                    merged = (merged + filmographyCandidates)
                        .distinctBy { "${it.siteKey}:${it.video.id}" }
                }

                _searchResults.value = merged
                if (merged.isNotEmpty()) {
                    searchCache[keyword] = merged
                    saveSearchKeyword(keyword)
                }

                if (merged.isEmpty()) {
                    _searchErrorMessage.value = "没有找到和“$keyword”相关的结果"
                } else if (failedCount > 0) {
                    _searchErrorMessage.value = "部分源不可用（${failedCount}/${sitesSnapshot.size}）"
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                e.printStackTrace()
                _searchErrorMessage.value = "Error: ${e.localizedMessage ?: "Unknown error"}"
            } finally {
                _searchLoading.value = false
            }
        }
    }

    fun onSearchHistorySelected(keyword: String) {
        _searchQuery.value = keyword
        searchInputDebounceJob?.cancel()
        searchVideos(keyword)
    }

    fun clearSearchHistory() {
        prefs.edit().remove("search_history").apply()
        _searchHistory.value = emptyList()
    }

    fun deleteHistoryItem(videoId: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            videoDao.deleteHistory(videoId)
        }
    }

    fun clearAllHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            videoDao.clearHistory()
        }
    }

    fun importSubscriptions(input: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val urls = parseSubscriptionInput(input)
                if (urls.isEmpty()) {
                    _errorMessage.value = "请先粘贴订阅链接或 CMS API"
                    return@launch
                }
                saveSubscriptionUrls(urls)
                refreshSubscriptions(manual = true)
            } catch (e: Exception) {
                e.printStackTrace()
                _errorMessage.value = "导入失败: ${e.localizedMessage ?: "Unknown error"}"
            }
        }
    }

    private fun startAutoUpdate() {
        if (!isAppActive) return
        autoUpdateJob?.cancel()
        autoUpdateJob = viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                delay(6 * 60 * 60 * 1000L)
                if (!isAppActive) break
                refreshSubscriptions(manual = false)
            }
        }
    }

    private suspend fun refreshSubscriptions(manual: Boolean) {
        val urls = getSavedSubscriptionUrls()
        if (urls.isEmpty()) return

        if (manual) {
            _isLoading.value = true
            _errorMessage.value = null
        }

        try {
            val selectedKey = SourceRepository.currentSite.value?.key
            val existingCount = SourceRepository.getSitesSnapshot().size
            val loadResult = SourceRepository.loadSubscription(urls.joinToString("\n"))
            val sitesSnapshot = loadResult.mergedSites

            val limiter = Semaphore(PROBE_CONCURRENCY)
            val reachable = supervisorScope {
                sitesSnapshot.map { site ->
                    async {
                        limiter.withPermit {
                            site to runCatching { probeSite(site) }.getOrDefault(false)
                        }
                    }
                }.map { it.await() }
            }.filter { (_, ok) -> ok }
                .map { (site, _) -> site }

            val curatedSites = (reachable + SourceRepository.getDefaultSites())
                .distinctBy { it.api }

            if (curatedSites.isNotEmpty()) {
                SourceRepository.setSites(curatedSites, selectedKey)
            }

            if (manual) {
                val removedCount = (sitesSnapshot.size - reachable.size).coerceAtLeast(0)
                val importedCount = loadResult.parsedSites
                val importFailedCount = loadResult.failedUrls
                val addedCount = (curatedSites.size - existingCount).coerceAtLeast(0)
                if (reachable.isEmpty()) {
                    _errorMessage.value = "导入${loadResult.requestedUrls}条订阅，解析到${importedCount}个源，检测成功0个，失败${importFailedCount + importedCount}个"
                } else {
                    _errorMessage.value = "导入${loadResult.requestedUrls}条订阅，解析到${importedCount}个源，成功${reachable.size}个，失败${(importedCount - reachable.size).coerceAtLeast(0) + importFailedCount}个，当前共${curatedSites.size}个源"
                }
            }

            if (manual) {
                fetchVideos()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            if (manual) {
                _errorMessage.value = "更新失败: ${e.localizedMessage ?: "Unknown error"}"
            }
        } finally {
            if (manual) {
                _isLoading.value = false
            }
        }
    }

    private fun parseSubscriptionInput(input: String): List<String> {
        return input
            .split("\n", ",", ";")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
    }

    private fun getSavedSubscriptionUrls(): Set<String> {
        return prefs.getStringSet("urls", emptySet()).orEmpty()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toSet()
    }

    private fun ensureDefaultSubscriptions() {
        val defaults = listOf(MOON_TV_CONFIG_URL, FAN_TAI_YING_CONFIG_URL)
        val current = getSavedSubscriptionUrls()
        if (defaults.all(current::contains)) return
        saveSubscriptionUrls(defaults)
    }

    private fun saveSubscriptionUrls(urls: List<String>) {
        val current = getSavedSubscriptionUrls().toMutableSet()
        current.addAll(urls)
        prefs.edit().putStringSet("urls", current).apply()
    }

    private fun loadSearchHistory(): List<String> {
        return prefs.getString("search_history", "").orEmpty()
            .split('\n')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(SEARCH_HISTORY_LIMIT)
    }

    private fun saveSearchKeyword(keyword: String) {
        val updated = listOf(keyword) + _searchHistory.value.filterNot { it.equals(keyword, ignoreCase = true) }
        val normalized = updated
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .take(SEARCH_HISTORY_LIMIT)
        prefs.edit().putString("search_history", normalized.joinToString("\n")).apply()
        _searchHistory.value = normalized
    }

    private fun loadAdultContentEnabled(): Boolean {
        return prefs.getBoolean(ADULT_FILTER_KEY, false)
    }

    private fun loadCachedTrending() {
        val cached = prefs.getString(HOT_CACHE_KEY, null).orEmpty()
        if (cached.isBlank()) return
        val items = runCatching {
            cacheJson.decodeFromString(ListSerializer(SourcedVideo.serializer()), cached)
        }.getOrDefault(emptyList())
        val filtered = applyContentFilter(items)
        if (filtered.isNotEmpty()) {
            _videoList.value = filtered
        }
    }

    private fun saveCachedTrending(videos: List<SourcedVideo>) {
        val payload = runCatching {
            cacheJson.encodeToString(ListSerializer(SourcedVideo.serializer()), videos.take(90))
        }.getOrNull() ?: return
        prefs.edit().putString(HOT_CACHE_KEY, payload).apply()
    }

    private suspend fun fetchSiteResults(
        sites: List<Site>,
        searchKeyword: String?,
        refreshToken: Long? = null
    ): List<Result<List<SourcedVideo>>> {
        val limiter = Semaphore(FETCH_CONCURRENCY)
        val timeoutMs = if (searchKeyword == null) HOT_REQUEST_TIMEOUT_MS else SEARCH_REQUEST_TIMEOUT_MS
        return supervisorScope {
            sites.mapIndexed { index, site ->
                async {
                    limiter.withPermit {
                        withTimeoutOrNull(timeoutMs) {
                            runCatching {
                                val page = if (searchKeyword == null) {
                                    (((refreshToken ?: System.nanoTime()) + index).mod(3L) + 1L).toInt()
                                } else {
                                    1
                                }
                                val response = fetchCmsResponse(
                                    baseUrl = site.api,
                                    page = page,
                                    keyword = searchKeyword
                                )
                                response.list.map { video ->
                                    SourcedVideo(
                                        siteKey = site.key ?: site.api,
                                        siteName = site.name,
                                        siteApi = site.api,
                                        video = video
                                    )
                                }
                            }
                        } ?: Result.failure(IOException("timeout"))
                    }
                }
            }.map { it.await() }
        }
    }

    private suspend fun fetchFallbackSearchResults(
        searchKeyword: String,
        sites: List<Site>,
        pageCount: Int,
        enrichLimit: Int
    ): List<SourcedVideo> {
        if (sites.isEmpty()) return emptyList()
        val limiter = Semaphore(FETCH_CONCURRENCY)
        val keyword = searchKeyword.trim()
        if (keyword.isBlank()) return emptyList()

        val variantKeywords = buildSearchKeywordVariants(keyword).drop(1)
        val rawCandidates = supervisorScope {
            sites.flatMap { site ->
                val pageTasks = (1..pageCount).map { page ->
                    async {
                        limiter.withPermit {
                            withTimeoutOrNull(SEARCH_REQUEST_TIMEOUT_MS) {
                                runCatching {
                                    val response = fetchCmsResponse(
                                        baseUrl = site.api,
                                        page = page,
                                        keyword = null
                                    )
                                    response.list.map { video ->
                                        SourcedVideo(
                                            siteKey = site.key ?: site.api,
                                            siteName = site.name,
                                            siteApi = site.api,
                                            video = video
                                        )
                                    }
                                }.getOrDefault(emptyList())
                            }.orEmpty()
                        }
                    }
                }
                val variantTasks = variantKeywords.map { variant ->
                    async {
                        limiter.withPermit {
                            withTimeoutOrNull(SEARCH_REQUEST_TIMEOUT_MS) {
                                runCatching {
                                    fetchCmsResponse(
                                        baseUrl = site.api,
                                        keyword = variant
                                    ).list.map { video ->
                                        SourcedVideo(
                                            siteKey = site.key ?: site.api,
                                            siteName = site.name,
                                            siteApi = site.api,
                                            video = video
                                        )
                                    }
                                }.getOrDefault(emptyList())
                            }.orEmpty()
                        }
                    }
                }
                pageTasks + variantTasks
            }.map { it.await() }
                .flatten()
        }
        val enrichedCandidates = enrichSearchCandidates(rawCandidates, enrichLimit = enrichLimit)
        return filterSearchResults(keyword, enrichedCandidates)
            .distinctBy { "${it.siteKey}:${it.video.id}" }
            .take(80)
    }

    private suspend fun fetchActorFilmographyResults(
        actorName: String,
        sites: List<Site>
    ): List<SourcedVideo> {
        val rawTitles = ActorFilmographyScraper.fetchKnownTitles(actorName)
        val titles = ActorFilmographyScraper.expandTitleAliases(rawTitles)
            .take(32)
        if (titles.isEmpty() || sites.isEmpty()) return emptyList()

        val limiter = Semaphore(FETCH_CONCURRENCY)
        val targetSites = sites.take(18)
        val rawCandidates = supervisorScope {
            titles.flatMap { title ->
                targetSites.map { site ->
                    async {
                        limiter.withPermit {
                            withTimeoutOrNull(SEARCH_REQUEST_TIMEOUT_MS) {
                                runCatching {
                                    fetchCmsResponse(
                                        baseUrl = site.api,
                                        keyword = title
                                    ).list.map { video ->
                                        SourcedVideo(
                                            siteKey = site.key ?: site.api,
                                            siteName = site.name,
                                            siteApi = site.api,
                                            video = video
                                        )
                                    }
                                }.getOrDefault(emptyList())
                            }.orEmpty()
                        }
                    }
                }
            }.map { it.await() }.flatten()
        }

        val enrichedCandidates = enrichSearchCandidates(
            candidates = rawCandidates,
            enrichLimit = PERSON_SEARCH_DETAIL_ENRICH_LIMIT
        )
        return filterSearchResults(actorName, applyContentFilter(enrichedCandidates))
            .distinctBy { "${it.siteKey}:${it.video.id}" }
            .take(120)
    }

    private suspend fun enrichSearchCandidates(
        candidates: List<SourcedVideo>,
        enrichLimit: Int = SEARCH_DETAIL_ENRICH_LIMIT
    ): List<SourcedVideo> {
        if (candidates.isEmpty()) return emptyList()
        val limiter = Semaphore(FETCH_CONCURRENCY)
        val uniqueCandidates = candidates
            .distinctBy { "${it.siteApi}:${it.video.id}" }
            .take(enrichLimit)

        val enrichedMap = supervisorScope {
            uniqueCandidates.map { candidate ->
                async {
                    limiter.withPermit {
                        val video = candidate.video
                        if (
                            !video.actor.isNullOrBlank() &&
                            !video.director.isNullOrBlank() &&
                            !video.content.isNullOrBlank()
                        ) {
                            return@withPermit candidate
                        }
                        val detail = withTimeoutOrNull(SEARCH_REQUEST_TIMEOUT_MS) {
                            runCatching {
                                fetchCmsResponse(
                                    baseUrl = candidate.siteApi,
                                    ids = video.id.toString()
                                ).list.firstOrNull()
                            }.getOrNull()
                        }
                        detail?.let { candidate.copy(video = mergeVideoItem(video, it)) } ?: candidate
                    }
                }
            }.map { deferred ->
                val item = deferred.await()
                "${item.siteApi}:${item.video.id}" to item
            }.toMap()
        }

        return candidates.map { candidate ->
            enrichedMap["${candidate.siteApi}:${candidate.video.id}"] ?: candidate
        }
    }

    private suspend fun probeSite(site: Site): Boolean {
        return withContext(Dispatchers.IO) {
            val api = site.api.trim()
            if (api.isBlank()) return@withContext false
            val body = withTimeoutOrNull(PROBE_TIMEOUT_MS) {
                fetchCmsResponse(baseUrl = api, page = 1)
            } ?: return@withContext false
            body.list.isNotEmpty()
        }
    }

    private fun buildTrendingVideos(videos: List<SourcedVideo>, refreshToken: Long): List<SourcedVideo> {
        if (videos.isEmpty()) return emptyList()

        val randomized = videos
            .groupBy { normalizeVideoName(it.video.name) }
            .values
            .mapNotNull { group ->
                val valid = group.filter { it.video.name.isNotBlank() }
                if (valid.isEmpty()) return@mapNotNull null
                valid.maxWithOrNull(
                    compareBy<SourcedVideo> { it.video.time.orEmpty() }
                        .thenBy { it.video.remarks.orEmpty() }
                ) ?: valid.first()
            }
            .shuffled(Random(refreshToken xor videos.size.toLong()))

        if (randomized.isEmpty()) return emptyList()
        return randomized
            .distinctBy { "${it.siteKey}:${it.video.id}" }
            .take(90)
    }

    private fun filterSearchResults(
        searchKeyword: String,
        videos: List<SourcedVideo>
    ): List<SourcedVideo> {
        if (videos.isEmpty()) return emptyList()
        val normalizedKeyword = normalizeVideoName(searchKeyword)
        if (normalizedKeyword.isBlank()) return videos

        data class RankedSearchResult(
            val sourcedVideo: SourcedVideo,
            val score: Int
        )

        return videos
            .mapNotNull { video ->
                val score = calculateSearchScore(searchKeyword, normalizedKeyword, video)
                val normalizedKeywordTokens = searchKeyword.split(Regex("\\s+"))
                    .map { normalizeVideoName(it) }
                    .filter { it.isNotBlank() }
                val hasWeakSignal = normalizedKeywordTokens.any { token ->
                    token.length >= 2 && (
                        normalizeVideoName(video.video.name).contains(token) ||
                            normalizeVideoName(video.video.remarks.orEmpty()).contains(token) ||
                            normalizeVideoName(video.video.typeName.orEmpty()).contains(token)
                        )
                }
                val finalScore = when {
                    score > 0 -> score
                    hasWeakSignal -> 120
                    else -> 0
                }
                if (finalScore <= 0) null else RankedSearchResult(video, finalScore)
            }
            .sortedWith(
                compareByDescending<RankedSearchResult> { it.score }
                    .thenByDescending { it.sourcedVideo.video.time.orEmpty() }
                    .thenByDescending { it.sourcedVideo.video.remarks.orEmpty() }
            )
            .map { it.sourcedVideo }
    }

    private fun calculateSearchScore(
        rawKeyword: String,
        normalizedKeyword: String,
        sourcedVideo: SourcedVideo
    ): Int {
        val name = sourcedVideo.video.name.trim()
        val normalizedName = normalizeVideoName(name)
        if (normalizedName.isBlank()) return 0

        val enName = sourcedVideo.video.enName.orEmpty().trim()
        val normalizedEnName = normalizeVideoName(enName)
        val actor = sourcedVideo.video.actor.orEmpty().trim()
        val normalizedActor = normalizeVideoName(actor)
        val director = sourcedVideo.video.director.orEmpty().trim()
        val normalizedDirector = normalizeVideoName(director)
        val remarks = sourcedVideo.video.remarks.orEmpty().trim()
        val normalizedRemarks = normalizeVideoName(remarks)
        val typeName = sourcedVideo.video.typeName.orEmpty().trim()
        val normalizedTypeName = normalizeVideoName(typeName)
        val area = sourcedVideo.video.area.orEmpty().trim()
        val normalizedArea = normalizeVideoName(area)
        val year = sourcedVideo.video.year.orEmpty().trim()
        val normalizedYear = normalizeVideoName(year)
        val content = sourcedVideo.video.content.orEmpty().take(80)
        val normalizedContent = normalizeVideoName(content)
        val rawKeywordLower = rawKeyword.lowercase().trim()
        val enKeyword = rawKeywordLower.replace(Regex("[^a-z0-9]"), "")
        val keywords = rawKeyword.split(Regex("\\s+"))
            .map { normalizeVideoName(it) }
            .filter { it.isNotBlank() }
        val searchableFields = listOf(
            normalizedName,
            normalizedEnName,
            normalizedActor,
            normalizedDirector,
            normalizedRemarks,
            normalizedTypeName,
            normalizedArea,
            normalizedYear,
            normalizedContent
        )

        if (normalizedName == normalizedKeyword) return 1_000
        if (normalizedEnName.isNotBlank() && normalizedEnName == normalizedKeyword) return 980
        if (normalizedName.startsWith(normalizedKeyword)) return 920
        if (normalizedName.contains(normalizedKeyword)) return 860
        if (normalizedEnName.isNotBlank() && normalizedEnName.contains(normalizedKeyword)) return 820
        if (normalizedActor == normalizedKeyword) return 800
        if (normalizedActor.contains(normalizedKeyword) && normalizedKeyword.length >= 2) return 760
        if (normalizedDirector == normalizedKeyword) return 740
        if (normalizedDirector.contains(normalizedKeyword) && normalizedKeyword.length >= 2) return 700
        if (keywords.isNotEmpty() && keywords.all { keyword -> searchableFields.any { it.contains(keyword) } }) {
            val titleHits = keywords.count { keyword ->
                normalizedName.contains(keyword) || normalizedEnName.contains(keyword)
            }
            val actorHits = keywords.count { keyword ->
                normalizedActor.contains(keyword) || normalizedDirector.contains(keyword)
            }
            return 620 + titleHits * 60 + actorHits * 40
        }

        if (normalizedRemarks == normalizedKeyword) return 740
        if (normalizedRemarks.contains(normalizedKeyword) && normalizedKeyword.length >= 2) return 680
        if (normalizedTypeName == normalizedKeyword) return 320
        if (normalizedTypeName.contains(normalizedKeyword) && normalizedKeyword.length >= 2) return 260
        if (normalizedArea == normalizedKeyword) return 240
        if (normalizedArea.contains(normalizedKeyword) && normalizedKeyword.length >= 2) return 200
        if (normalizedYear == normalizedKeyword) return 220

        if (enKeyword.isNotBlank() && normalizedEnName.contains(enKeyword)) return 700
        if (normalizedContent.contains(normalizedKeyword) && normalizedKeyword.length >= 2) return 180

        return 0
    }

    private fun normalizeVideoName(name: String): String {
        return name.lowercase()
            .replace(Regex("\\s+"), "")
            .replace(Regex("[\\[（(【].*?[\\]）)】]"), "")
            .replace(Regex("[^\\p{L}\\p{N}]"), "")
    }

    private fun applyContentFilter(videos: List<SourcedVideo>): List<SourcedVideo> {
        if (_adultContentEnabled.value) return videos
        return videos.filterNot { isAdultContent(it) }
    }

    private fun isAdultContent(item: SourcedVideo): Boolean {
        val rawText = buildString {
            append(item.siteName).append(' ')
            append(item.video.name).append(' ')
            append(item.video.typeName.orEmpty()).append(' ')
            append(item.video.remarks.orEmpty()).append(' ')
            append(item.video.content.orEmpty()).append(' ')
            append(item.video.actor.orEmpty()).append(' ')
            append(item.video.director.orEmpty()).append(' ')
            append(item.video.area.orEmpty())
        }.lowercase()
        val compactText = rawText.replace(Regex("\\s+"), "")

        val strongKeywords = listOf(
            "伦理片", "伦理剧", "情色", "成人", "无码", "有码", "无码av", "sex", "porn", "hentai",
            "番号", "av", "sm调教", "裸聊", "乱伦", "萝莉淫", "巨乳", "淫妻", "淫乱", "欲奴",
            "约炮", "援交", "强奸", "迷奸", "母子乱伦", "父女乱伦", "禁播", "春宫", "艳情"
        )
        val mediumKeywords = listOf(
            "伦理", "lunli", "激情", "调教", "偷情", "欲望", "做爱", "性爱", "黄片", "黄漫",
            "成人版", "私拍", "偷拍", "露脸", "白浆", "后入", "口爆", "自拍偷拍", "国产自拍",
            "麻豆", "91", "国产自拍", "骚货", "嫩模", "制服诱惑", "女仆", "情欲", "欲海"
        )
        val adultTypeKeywords = listOf(
            "伦理", "情色", "成人", "福利", "私房", "写真", "av", "成人视频", "两性"
        )
        val adultSiteKeywords = listOf(
            "麻豆", "91", "swag", "h动漫", "国产自拍", "无码", "有码", "av资源"
        )

        if (strongKeywords.any { compactText.contains(it) }) return true

        var score = 0
        score += mediumKeywords.count { compactText.contains(it) } * 2
        score += adultTypeKeywords.count { compactText.contains(it) } * 3
        score += adultSiteKeywords.count { compactText.contains(it) } * 3

        if (item.video.typeName.orEmpty().contains("伦理", ignoreCase = true)) score += 4
        if (item.siteName.contains("伦理", ignoreCase = true)) score += 4
        if (Regex("""\b(av|sm|h漫|r18)\b""", RegexOption.IGNORE_CASE).containsMatchIn(rawText)) score += 3
        if (compactText.contains("预告片") || compactText.contains("电影解说")) score -= 2

        return score >= 4
    }

    private fun nextTrendingRefreshToken(): Long {
        return System.nanoTime()
    }

    private fun buildSearchKeywordVariants(keyword: String?): List<String> {
        val raw = keyword?.trim().orEmpty()
        if (raw.isBlank()) return emptyList()

        val compact = raw.replace(Regex("\\s+"), " ").trim()
        val simplified = compact
            .replace(Regex("[（(]\\d{4}[）)]"), "")
            .replace(Regex("\\b\\d{4}\\b"), "")
            .replace(Regex("第\\s*\\d+\\s*[季部集]"), "")
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        val noSpace = simplified.replace(" ", "")

        return listOf(raw, compact, simplified, noSpace)
            .map { it.trim() }
            .filter { it.length >= 2 }
            .distinct()
            .take(4)
    }

    private fun isLikelyPersonQuery(keyword: String): Boolean {
        val trimmed = keyword.trim()
        if (trimmed.length !in 2..8) return false
        if (trimmed.any { it.isDigit() }) return false
        return trimmed.matches(Regex("[\\p{L}·•]+"))
    }

    private fun scheduleDebouncedSearch(query: String, debounceMs: Long = SEARCH_DEBOUNCE_MS) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return
        searchInputDebounceJob?.cancel()
        searchInputDebounceJob = viewModelScope.launch {
            delay(debounceMs)
            if (_searchUiVisible.value && _searchQuery.value.trim() == trimmed) {
                fetchVideos(trimmed)
            }
        }
    }

    private fun pickRuntimeSites(
        allSites: List<Site>,
        searchKeyword: String?,
        refreshToken: Long? = null
    ): List<Site> {
        if (searchKeyword.isNullOrBlank()) {
            if (allSites.size <= MAX_TRENDING_SITES) return allSites.shuffled(Random(refreshToken ?: System.nanoTime()))
            return allSites
                .shuffled(Random(refreshToken ?: System.nanoTime()))
                .take(MAX_TRENDING_SITES)
        }
        if (allSites.size <= MAX_SEARCH_SITES) return allSites
        val currentApi = currentSite.value?.api
        val scoreMap = loadSiteScores()
        val personSearchMode = searchKeyword?.let(::isLikelyPersonQuery) == true
        val prioritized = allSites
            .sortedWith(
                compareByDescending<Site> { if (personSearchMode && it.name.contains("豆瓣")) 1 else 0 }
                    .thenByDescending { if (it.api == currentApi) 1 else 0 }
                    .thenByDescending { scoreMap[it.api] ?: 0 }
                    .thenBy { it.name }
            )

        val limit = if (personSearchMode) MAX_PERSON_SEARCH_SITES else MAX_SEARCH_SITES
        return prioritized.take(limit)
    }

    private fun loadSiteScores(): Map<String, Int> {
        return prefs.getString(SITE_SCORE_KEY, "").orEmpty()
            .lineSequence()
            .mapNotNull { line ->
                val index = line.indexOf('=')
                if (index <= 0) return@mapNotNull null
                val api = line.substring(0, index)
                val score = line.substring(index + 1).toIntOrNull() ?: return@mapNotNull null
                api to score
            }
            .toMap()
    }

    private fun updateSiteHealthBatch(updates: List<Pair<String, Boolean>>) {
        if (updates.isEmpty()) return
        val current = loadSiteScores().toMutableMap()
        updates.forEach { (api, success) ->
            val previous = current[api] ?: 0
            current[api] = if (success) {
                (previous + 2).coerceAtMost(50)
            } else {
                (previous - 1).coerceAtLeast(-20)
            }
        }
        val encoded = current.entries.joinToString("\n") { (key, value) -> "$key=$value" }
        prefs.edit().putString(SITE_SCORE_KEY, encoded).apply()
    }

    override fun onCleared() {
        autoUpdateJob?.cancel()
        homeFetchJob?.cancel()
        searchFetchJob?.cancel()
        searchInputDebounceJob?.cancel()
        super.onCleared()
    }

    private fun clearSearchState() {
        _searchResults.value = emptyList()
        _searchErrorMessage.value = null
        _searchLoading.value = false
    }

    private fun mergeVideoItem(primary: VideoItem, detail: VideoItem): VideoItem {
        return primary.copy(
            name = detail.name.ifBlank { primary.name },
            typeId = detail.typeId ?: primary.typeId,
            typeName = detail.typeName ?: primary.typeName,
            enName = detail.enName ?: primary.enName,
            actor = detail.actor ?: primary.actor,
            director = detail.director ?: primary.director,
            area = detail.area ?: primary.area,
            lang = detail.lang ?: primary.lang,
            year = detail.year ?: primary.year,
            time = detail.time ?: primary.time,
            remarks = detail.remarks ?: primary.remarks,
            playFrom = detail.playFrom ?: primary.playFrom,
            playUrl = detail.playUrl ?: primary.playUrl,
            pic = detail.pic ?: primary.pic,
            content = detail.content ?: primary.content
        )
    }
}

package com.example.myapplicationlibretv.ui.home

import android.content.Context
import com.example.myapplicationlibretv.data.api.ActorFilmographyScraper
import com.example.myapplicationlibretv.data.api.PlatformHotlistScraper
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

data class HomeCategory(
    val name: String,
    val siteTypeIds: Map<String, Int> = emptyMap()
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
        private const val HOT_REQUEST_TIMEOUT_MS = 2_200L
        private const val SEARCH_REQUEST_TIMEOUT_MS = 3_000L
        private const val HOME_HOT_SEARCH_TIMEOUT_MS = 2_300L
        private const val PERSON_SEARCH_REQUEST_TIMEOUT_MS = 5_500L
        private const val PROBE_TIMEOUT_MS = 1_600L
        private const val SEARCH_DEBOUNCE_MS = 220L
        private const val HOT_CACHE_KEY = "hot_cache"
        private const val SITE_SCORE_KEY = "site_scores_v2"
        private const val ADULT_FILTER_KEY = "adult_filter_enabled"
        private const val HOME_DISPLAY_BY_SOURCE_KEY = "home_display_by_source"
        private const val MAX_TRENDING_SITES = 24
        private const val MAX_SEARCH_SITES = 24
        private const val MAX_PERSON_SEARCH_SITES = 48
        private const val SEARCH_FALLBACK_MIN_RESULTS = 8
        private const val SEARCH_FALLBACK_SITES = 8
        private const val SEARCH_FALLBACK_PAGES = 2
        private const val SEARCH_DETAIL_ENRICH_LIMIT = 36
        private const val PERSON_SEARCH_FALLBACK_SITES = 24
        private const val PERSON_SEARCH_FALLBACK_PAGES = 5
        private const val PERSON_SEARCH_DETAIL_ENRICH_LIMIT = 72
        private const val PERSON_FIELD_SEARCH_SITES = 24
        private const val PERSON_FILMOGRAPHY_SITES = 8
        private const val PERSON_FILMOGRAPHY_TITLES = 12
        private const val PERSON_FILMOGRAPHY_CONCURRENCY = 24
        private const val PLATFORM_HOT_TITLE_LIMIT = 64
        private const val PLATFORM_HOT_SITE_LIMIT = 10
        private const val PLATFORM_HOT_CONCURRENCY = 24
        private const val STARTUP_PROBE_SITE_LIMIT = 28
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
    private val _homeDisplayBySourceEnabled = MutableStateFlow(loadHomeDisplayBySourceEnabled())
    val homeDisplayBySourceEnabled: StateFlow<Boolean> = _homeDisplayBySourceEnabled
    private val _homeCategories = MutableStateFlow(listOf(HomeCategory("全部")))
    val homeCategories: StateFlow<List<HomeCategory>> = _homeCategories
    private val _selectedHomeCategory = MutableStateFlow("全部")
    val selectedHomeCategory: StateFlow<String> = _selectedHomeCategory
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
            fetchVideos(keyword = null)
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
        _searchErrorMessage.value = null
        if (query.isBlank()) {
            searchInputDebounceJob?.cancel()
            clearSearchState()
        } else if (_searchUiVisible.value) {
            _searchResults.value = searchCache[query.trim()].orEmpty()
            _searchLoading.value = true
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

    fun setHomeDisplayBySourceEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(HOME_DISPLAY_BY_SOURCE_KEY, enabled).apply()
        _homeDisplayBySourceEnabled.value = enabled
        _selectedHomeCategory.value = "全部"
        if (_searchQuery.value.isBlank()) {
            fetchVideos(keyword = null)
        }
    }

    fun selectHomeCategory(category: String) {
        _selectedHomeCategory.value = category.ifBlank { "全部" }
        fetchVideos(keyword = null)
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
                val selectedCategory = _selectedHomeCategory.value
                if (selectedCategory != "全部") {
                    fetchCategoryVideos(selectedCategory, refreshToken)
                    return@launch
                }
                val allSites = SourceRepository.getSitesSnapshot()
                val sitesSnapshot = if (_homeDisplayBySourceEnabled.value) {
                    currentSite.value?.let(::listOf) ?: pickRuntimeSites(allSites, null, refreshToken)
                } else {
                    pickRuntimeSites(allSites, null, refreshToken)
                }
                if (sitesSnapshot.isEmpty()) {
                    _videoList.value = emptyList()
                    _errorMessage.value = "No sources"
                    return@launch
                }

                val merged = if (_homeDisplayBySourceEnabled.value) {
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
                    succeeded.flatten()
                        .let(::applyContentFilter)
                        .distinctBy { "${it.siteKey}:${it.video.id}" }
                } else {
                    val platformTrending = withTimeoutOrNull(12_000L) {
                        fetchPlatformHotlistVideos(sitesSnapshot)
                    }.orEmpty()
                    if (platformTrending.size >= 72) {
                        platformTrending
                    } else {
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
                        val siteTrending = buildTrendingVideos(
                            videos = applyContentFilter(succeeded.flatten()),
                            refreshToken = refreshToken
                        )
                        (platformTrending + siteTrending)
                            .distinctBy { "${it.siteKey}:${it.video.id}" }
                            .distinctBy { normalizeVideoName(it.video.name) }
                            .take(140)
                    }
                }

                _videoList.value = merged
                _homeCategories.value = buildHomeCategoriesFromVideos(merged)
                if (merged.isNotEmpty()) {
                    saveCachedTrending(merged)
                }

                if (merged.isEmpty()) {
                    _errorMessage.value = "暂无热门推荐"
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
                val cached = searchCache[keyword]
                if (cached != null) {
                    _searchResults.value = cached
                } else {
                    _searchResults.value = emptyList()
                }
                val personSearchMode = isLikelyPersonQuery(keyword)
                val allSites = SourceRepository.getSitesSnapshot()
                val sitesSnapshot = pickRuntimeSites(allSites, keyword)
                if (sitesSnapshot.isEmpty()) {
                    _searchResults.value = emptyList()
                    _searchErrorMessage.value = "No sources"
                    return@launch
                }

                var merged = emptyList<SourcedVideo>()
                var filmographyLoaded = false
                if (personSearchMode) {
                    val personFieldDeferred = async {
                        fetchPersonFieldSearchResults(
                            personName = keyword,
                            sites = sitesSnapshot.take(PERSON_FIELD_SEARCH_SITES)
                        )
                    }
                    val filmographyDeferred = async {
                        fetchActorFilmographyResults(
                            actorName = keyword,
                            sites = sitesSnapshot
                        )
                    }

                    val personFieldCandidates = personFieldDeferred.await()
                    merged = personFieldCandidates.distinctBy { "${it.siteKey}:${it.video.id}" }
                    if (merged.isNotEmpty() && _searchQuery.value.trim() == keyword) {
                        _searchResults.value = merged
                        _searchErrorMessage.value = null
                    }

                    val filmographyCandidates = filmographyDeferred.await()
                    filmographyLoaded = true
                    merged = (merged + filmographyCandidates)
                        .distinctBy { "${it.siteKey}:${it.video.id}" }
                    if (merged.isNotEmpty() && _searchQuery.value.trim() == keyword) {
                        _searchResults.value = merged
                        _searchErrorMessage.value = null
                    }
                    if (merged.size >= 24) {
                        searchCache[keyword] = merged
                        saveSearchKeyword(keyword)
                        return@launch
                    }
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
                        enrichLimit = 36
                    )
                } else {
                    succeeded.flatten()
                }
                val sourceReturned = primaryResults
                    .let(::applyContentFilter)
                    .distinctBy { "${it.siteKey}:${it.video.id}" }
                val creditMatchedResults = if (personSearchMode) {
                    sourceReturned.filter { matchesPersonInCredits(keyword, it.video) }
                } else {
                    emptyList()
                }
                val directMerged = filterSearchResults(
                    searchKeyword = keyword,
                    videos = sourceReturned
                ).distinctBy { "${it.siteKey}:${it.video.id}" }
                merged = (merged + directMerged)
                    .distinctBy { "${it.siteKey}:${it.video.id}" }
                if (creditMatchedResults.isNotEmpty()) {
                    merged = (creditMatchedResults + merged)
                        .distinctBy { "${it.siteKey}:${it.video.id}" }
                }
                if (!personSearchMode && merged.size < sourceReturned.size) {
                    merged = (merged + sourceReturned)
                        .distinctBy { "${it.siteKey}:${it.video.id}" }
                }

                if ((!personSearchMode || merged.isEmpty()) && merged.size < SEARCH_FALLBACK_MIN_RESULTS) {
                    val fallbackCandidates = fetchFallbackSearchResults(
                        searchKeyword = keyword,
                        sites = sitesSnapshot.take(if (personSearchMode) PERSON_SEARCH_FALLBACK_SITES else SEARCH_FALLBACK_SITES),
                        pageCount = if (personSearchMode) PERSON_SEARCH_FALLBACK_PAGES else SEARCH_FALLBACK_PAGES,
                        enrichLimit = if (personSearchMode) PERSON_SEARCH_DETAIL_ENRICH_LIMIT else SEARCH_DETAIL_ENRICH_LIMIT
                    )
                    merged = (merged + fallbackCandidates)
                        .distinctBy { "${it.siteKey}:${it.video.id}" }
                }

                if (personSearchMode && !filmographyLoaded && merged.size < 20) {
                    val filmographyCandidates = fetchActorFilmographyResults(
                        actorName = keyword,
                        sites = sitesSnapshot
                    )
                    merged = (merged + filmographyCandidates)
                        .distinctBy { "${it.siteKey}:${it.video.id}" }
                }

                if (_searchQuery.value.trim() != keyword) return@launch
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

    fun deleteFavoriteItem(videoId: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            videoDao.deleteFavorite(videoId)
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

    fun refreshAll() {
        viewModelScope.launch(Dispatchers.IO) {
            refreshSubscriptions(manual = true)
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
            val sitesToProbe = if (manual) {
                sitesSnapshot
            } else {
                prioritizeMainlandSites(sitesSnapshot, selectedKey).take(STARTUP_PROBE_SITE_LIMIT)
            }

            val limiter = Semaphore(PROBE_CONCURRENCY)
            val reachable = supervisorScope {
                sitesToProbe.map { site ->
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

    private fun loadHomeDisplayBySourceEnabled(): Boolean {
        return prefs.getBoolean(HOME_DISPLAY_BY_SOURCE_KEY, false)
    }

    private suspend fun fetchCategoryVideos(category: String, refreshToken: Long) {
        val categoryModel = _homeCategories.value.firstOrNull { it.name == category }
        if (categoryModel == null || categoryModel.name == "全部") {
            _selectedHomeCategory.value = "全部"
            fetchVideos(keyword = null)
            return
        }

        val allSites = SourceRepository.getSitesSnapshot()
        val candidateSites = if (_homeDisplayBySourceEnabled.value) {
            currentSite.value?.let(::listOf) ?: emptyList()
        } else {
            pickRuntimeSites(allSites, null, refreshToken)
        }
        val sitesWithType = candidateSites.filter { site -> categoryModel.siteTypeIds[site.api] != null }
        if (sitesWithType.isEmpty()) {
            _videoList.value = emptyList()
            _errorMessage.value = "当前没有可用的“$category”分类源"
            return
        }

        val limiter = Semaphore(FETCH_CONCURRENCY)
        val results = supervisorScope {
            sitesWithType.map { site ->
                async {
                    limiter.withPermit {
                        withTimeoutOrNull(HOT_REQUEST_TIMEOUT_MS) {
                            runCatching {
                                val response = fetchCmsResponse(
                                    baseUrl = site.api,
                                    typeId = categoryModel.siteTypeIds[site.api],
                                    page = (((refreshToken) + site.api.hashCode()).mod(3L) + 1L).toInt()
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

        val healthUpdates = mutableListOf<Pair<String, Boolean>>()
        val succeeded = results.mapIndexedNotNull { index, result ->
            val site = sitesWithType.getOrNull(index) ?: return@mapIndexedNotNull null
            healthUpdates += site.api to result.isSuccess
            result.getOrNull()
        }
        updateSiteHealthBatch(healthUpdates)
        val merged = if (_homeDisplayBySourceEnabled.value) {
            succeeded.flatten()
                .let(::applyContentFilter)
                .distinctBy { "${it.siteKey}:${it.video.id}" }
        } else {
            buildTrendingVideos(applyContentFilter(succeeded.flatten()), refreshToken)
        }
        _videoList.value = merged
        _homeCategories.value = buildHomeCategoriesFromVideos(merged)
        _errorMessage.value = if (merged.isEmpty()) "没有找到“$category”分类资源" else null
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
            _homeCategories.value = buildHomeCategoriesFromVideos(filtered)
        }
    }

    private fun saveCachedTrending(videos: List<SourcedVideo>) {
        val payload = runCatching {
            cacheJson.encodeToString(ListSerializer(SourcedVideo.serializer()), videos.take(140))
        }.getOrNull() ?: return
        prefs.edit().putString(HOT_CACHE_KEY, payload).apply()
    }

    private suspend fun fetchSiteResults(
        sites: List<Site>,
        searchKeyword: String?,
        refreshToken: Long? = null
    ): List<Result<List<SourcedVideo>>> {
        val limiter = Semaphore(FETCH_CONCURRENCY)
        val timeoutMs = when {
            searchKeyword == null -> HOT_REQUEST_TIMEOUT_MS
            isLikelyPersonQuery(searchKeyword) -> PERSON_SEARCH_REQUEST_TIMEOUT_MS
            else -> SEARCH_REQUEST_TIMEOUT_MS
        }
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
        val timeoutMs = if (isLikelyPersonQuery(keyword)) {
            PERSON_SEARCH_REQUEST_TIMEOUT_MS
        } else {
            SEARCH_REQUEST_TIMEOUT_MS
        }

        val variantKeywords = buildSearchKeywordVariants(keyword).drop(1)
        val rawCandidates = supervisorScope {
            sites.flatMap { site ->
                val pageTasks = (1..pageCount).map { page ->
                    async {
                        limiter.withPermit {
                            withTimeoutOrNull(timeoutMs) {
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
                            withTimeoutOrNull(timeoutMs) {
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
        val filtered = filterSearchResults(keyword, enrichedCandidates)
        val creditMatched = if (isLikelyPersonQuery(keyword)) {
            enrichedCandidates.filter { matchesPersonInCredits(keyword, it.video) }
        } else {
            emptyList()
        }
        return (creditMatched + filtered)
            .distinctBy { "${it.siteKey}:${it.video.id}" }
            .take(80)
    }

    private suspend fun fetchPlatformHotlistVideos(
        sites: List<Site>
    ): List<SourcedVideo> {
        if (sites.isEmpty()) return emptyList()
        val hotTitles = withTimeoutOrNull(2_000L) {
            PlatformHotlistScraper.fetchHotTitles()
        }.orEmpty()
            .filter { it.isNotBlank() }
            .take(PLATFORM_HOT_TITLE_LIMIT)
        if (hotTitles.isEmpty()) return emptyList()

        val targetSites = sites.take(PLATFORM_HOT_SITE_LIMIT)
        val hotTitleRank = hotTitles
            .mapIndexed { index, title -> normalizeVideoName(title) to index }
            .toMap()
        val limiter = Semaphore(PLATFORM_HOT_CONCURRENCY)
        val candidates = mutableListOf<Pair<SourcedVideo, String>>()
        val batches = hotTitles.chunked(10)
        for (batch in batches) {
            val batchCandidates = supervisorScope {
                batch.flatMap { title ->
                    targetSites.map { site ->
                        async {
                            limiter.withPermit {
                                withTimeoutOrNull(HOME_HOT_SEARCH_TIMEOUT_MS) {
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
                                            ) to title
                                        }
                                    }.getOrDefault(emptyList())
                                }.orEmpty()
                            }
                        }
                    }
                }.map { it.await() }.flatten()
            }
            candidates += batchCandidates
            if (candidates.size >= 120) break
        }

        val matched = candidates
            .filter { (item, title) -> isKnownFilmographyTitleMatch(item.video.name, title) }
        val allowedKeys = applyContentFilter(matched.map { it.first })
            .map { "${it.siteKey}:${it.video.id}" }
            .toSet()
        return matched
            .asSequence()
            .filter { (item, _) -> "${item.siteKey}:${item.video.id}" in allowedKeys }
            .distinctBy { normalizeVideoName(it.first.video.name) }
            .sortedWith(
                compareBy<Pair<SourcedVideo, String>> { (_, title) ->
                    hotTitleRank[normalizeVideoName(title)] ?: Int.MAX_VALUE
                }
                    .thenByDescending { (item, _) -> homeRecencyScore(item.video) }
                    .thenByDescending { (item, _) -> item.video.remarks.orEmpty() }
            )
            .map { it.first }
            .take(140)
            .toList()
    }

    private suspend fun fetchActorFilmographyResults(
        actorName: String,
        sites: List<Site>
    ): List<SourcedVideo> {
        val rawTitles = withTimeoutOrNull(5_000L) {
            ActorFilmographyScraper.fetchKnownTitles(
                personName = actorName,
                includeAdultWebSearch = _adultContentEnabled.value
            )
        }.orEmpty()
        val titles = ActorFilmographyScraper.expandTitleAliases(rawTitles)
            .take(PERSON_FILMOGRAPHY_TITLES)
        if (titles.isEmpty() || sites.isEmpty()) return emptyList()

        val limiter = Semaphore(PERSON_FILMOGRAPHY_CONCURRENCY)
        val targetSites = sites.take(PERSON_FILMOGRAPHY_SITES)
        val rawCandidates = supervisorScope {
            titles.flatMap { title ->
                targetSites.map { site ->
                    async {
                        limiter.withPermit {
                            withTimeoutOrNull(PERSON_SEARCH_REQUEST_TIMEOUT_MS) {
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
        val filteredCandidates = applyContentFilter(enrichedCandidates)
        val creditMatched = filteredCandidates.filter { matchesPersonInCredits(actorName, it.video) }
        val filmographyMatched = filteredCandidates.filter { candidate ->
            titles.any { title -> isKnownFilmographyTitleMatch(candidate.video.name, title) }
        }
        return (creditMatched + filmographyMatched)
            .distinctBy { "${it.siteKey}:${it.video.id}" }
            .take(120)
    }

    private suspend fun fetchPersonFieldSearchResults(
        personName: String,
        sites: List<Site>
    ): List<SourcedVideo> {
        val keyword = personName.trim()
        if (keyword.isBlank() || sites.isEmpty()) return emptyList()

        val limiter = Semaphore(PERSON_FILMOGRAPHY_CONCURRENCY)
        val searchParams = listOf("wd")
        val rawCandidates = supervisorScope {
            sites.flatMap { site ->
                searchParams.map { param ->
                    async {
                        limiter.withPermit {
                            withTimeoutOrNull(PERSON_SEARCH_REQUEST_TIMEOUT_MS) {
                                runCatching {
                                    val response = if (param == "wd") {
                                        fetchCmsResponse(
                                            baseUrl = site.api,
                                            action = "detail",
                                            keyword = keyword
                                        )
                                    } else {
                                        fetchCmsResponse(
                                            baseUrl = site.api,
                                            action = "detail",
                                            extraQueryParams = mapOf(param to keyword)
                                        )
                                    }
                                    if (response.list.size > 80 && param != "wd") {
                                        return@runCatching emptyList()
                                    }
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
            }.map { it.await() }.flatten()
        }

        val quickMatches = applyContentFilter(rawCandidates)
            .filter { matchesPersonInCredits(keyword, it.video) }
            .distinctBy { "${it.siteKey}:${it.video.id}" }
        if (quickMatches.size >= 12) {
            return quickMatches.take(120)
        }

        val trustedFieldCandidates = applyContentFilter(rawCandidates)
            .filterNot { normalizeVideoName(it.video.name) == normalizeVideoName(keyword) }
            .distinctBy { "${it.siteKey}:${it.video.id}" }
            .take(36)
        val enrichedCandidates = enrichSearchCandidates(
            candidates = rawCandidates,
            enrichLimit = PERSON_SEARCH_DETAIL_ENRICH_LIMIT
        )
        val creditMatched = applyContentFilter(enrichedCandidates)
            .filter { matchesPersonInCredits(keyword, it.video) }
        return (creditMatched + trustedFieldCandidates)
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
                        val detail = withTimeoutOrNull(PERSON_SEARCH_REQUEST_TIMEOUT_MS) {
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

    private fun matchesPersonInCredits(keyword: String, video: VideoItem): Boolean {
        val normalizedKeyword = normalizeVideoName(keyword)
        if (normalizedKeyword.length < 2) return false
        val credits = listOf(video.actor.orEmpty(), video.director.orEmpty())
        return credits.any { creditText ->
            if (creditText.isBlank()) return@any false
            val normalizedCreditText = normalizeVideoName(creditText)
            if (normalizedCreditText == normalizedKeyword) return@any true
            val names = creditText
                .split(Regex("[,，、/／|｜;；\\s]+"))
                .map { normalizeVideoName(it) }
                .filter { it.isNotBlank() }
            names.any { name ->
                name == normalizedKeyword ||
                    name.contains(normalizedKeyword) ||
                    normalizedKeyword.contains(name)
            } || normalizedCreditText.contains(normalizedKeyword)
        }
    }

    private fun isKnownFilmographyTitleMatch(videoName: String, knownTitle: String): Boolean {
        val normalizedName = normalizeKnownWorkTitle(videoName)
        val normalizedTitle = normalizeKnownWorkTitle(knownTitle)
        if (normalizedName.isBlank() || normalizedTitle.isBlank()) return false
        if (normalizedName.contains("解说")) return false
        return normalizedName == normalizedTitle
    }

    private fun homeRecencyScore(video: VideoItem): Int {
        val text = listOf(
            video.year.orEmpty(),
            video.time.orEmpty(),
            video.remarks.orEmpty(),
            video.name
        ).joinToString(" ")
        val years = Regex("""20\d{2}""")
            .findAll(text)
            .mapNotNull { it.value.toIntOrNull() }
            .toList()
        val year = years.maxOrNull() ?: 0
        val updateScore = when {
            video.remarks.orEmpty().contains("更", ignoreCase = true) -> 30
            video.remarks.orEmpty().contains("完", ignoreCase = true) -> 20
            else -> 0
        }
        return year * 100 + updateScore
    }

    private fun normalizeKnownWorkTitle(value: String): String {
        return normalizeVideoName(value)
            .replace("国语", "")
            .replace("粤语", "")
            .replace("普通话版", "")
            .replace("粤语版", "")
            .replace("原声版", "")
            .replace("高清", "")
            .replace("蓝光", "")
            .replace("hd", "")
            .replace("版", "")
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
            "伦理片", "伦理剧", "情色", "成人电影", "成人视频", "成人", "18禁", "r18", "无码", "有码",
            "无码视频", "有码视频", "sex", "porn", "porno", "hentai", "里番", "h动漫", "黄播",
            "番号", "女优", "av女优", "jav", "fc2", "一本道", "东京热", "加勒比", "sm调教",
            "裸聊", "裸舞", "乱伦", "母子乱伦", "父女乱伦", "萝莉淫", "巨乳", "爆乳",
            "淫妻", "淫乱", "淫荡", "欲奴", "约炮", "援交", "强奸", "迷奸", "春宫", "艳情"
        )
        val mediumKeywords = listOf(
            "伦理", "lunli", "激情", "调教", "偷情", "欲望", "做爱", "性爱", "黄片", "黄漫",
            "成人版", "私拍", "偷拍", "露脸", "白浆", "后入", "口爆", "自拍偷拍", "国产自拍",
            "麻豆", "91", "91porn", "swag", "jable", "国产自拍", "骚货", "嫩模", "制服诱惑",
            "女仆", "情欲", "欲海", "福利姬", "私房", "写真", "啪啪", "喷潮", "口交"
        )
        val adultTypeKeywords = listOf(
            "伦理", "情色", "成人", "福利", "私房", "写真", "av", "r18", "成人视频", "两性", "里番"
        )
        val adultSiteKeywords = listOf(
            "麻豆", "91", "91porn", "swag", "h动漫", "国产自拍", "无码", "有码", "av资源",
            "jav", "jable", "fc2", "porn", "成人资源"
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
            val prioritized = prioritizeMainlandSites(allSites, currentSite.value?.key)
            if (prioritized.size <= MAX_TRENDING_SITES) return prioritized
            return prioritized
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

    private fun prioritizeMainlandSites(
        sites: List<Site>,
        selectedSiteKey: String?
    ): List<Site> {
        val scoreMap = loadSiteScores()
        val seed = System.nanoTime()
        return sites
            .shuffled(Random(seed))
            .sortedWith(
                compareByDescending<Site> { if (it.key == selectedSiteKey || it.api == selectedSiteKey) 1 else 0 }
                    .thenByDescending { mainlandSiteScore(it) }
                    .thenByDescending { scoreMap[it.api] ?: 0 }
                    .thenBy { it.name }
            )
    }

    private fun mainlandSiteScore(site: Site): Int {
        val raw = "${site.name} ${site.key.orEmpty()} ${site.api}".lowercase()
        var score = 0
        val fastHints = listOf(
            "lzi", "1080", "uku", "wujin", "yaya", "guangsu", "wolong", "rycj", "xinlang",
            "wwzy", "ffzy", "dbzy", "subo", "jyzy", "suoni", "heimuer", "api.php", "provide/vod"
        )
        fastHints.forEachIndexed { index, hint ->
            if (raw.contains(hint)) score += 50 - index.coerceAtMost(40)
        }
        if (raw.contains(".cn") || raw.contains(".com.cn")) score += 30
        if (raw.contains(".cc") || raw.contains(".tv") || raw.contains(".me") || raw.contains(".hk")) score += 12
        if (raw.contains("github") || raw.contains("raw.githubusercontent") || raw.contains("imdb") || raw.contains("rottentomatoes")) {
            score -= 100
        }
        return score
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

    private fun buildHomeCategoriesFromVideos(videos: List<SourcedVideo>): List<HomeCategory> {
        val grouped = linkedMapOf<String, MutableMap<String, Int>>()
        videos.forEach { item ->
            val category = normalizeHomeCategory(item.video.typeName) ?: return@forEach
            val typeId = item.video.typeId ?: return@forEach
            grouped.getOrPut(category) { linkedMapOf() }.putIfAbsent(item.siteApi, typeId)
        }
        val sorted = grouped.entries
            .sortedByDescending { it.value.size }
            .map { HomeCategory(it.key, it.value) }
            .take(18)
        return listOf(HomeCategory("全部")) + sorted
    }

    private fun normalizeHomeCategory(typeName: String?): String? {
        val raw = typeName?.trim().orEmpty()
        if (raw.isBlank()) return null
        val compact = raw
            .replace("电影片", "电影")
            .replace("电视剧片", "电视剧")
            .replace("連續劇", "电视剧")
            .replace("劇集", "电视剧")
            .replace("综艺片", "综艺")
            .replace("动漫片", "动漫")
            .replace("動畫", "动漫")
            .replace("紀錄片", "纪录片")
            .replace("記錄片", "纪录片")
        return when {
            compact.contains("电影") -> "电影"
            compact.contains("电视剧") || compact.contains("连续剧") || compact.contains("短剧") -> "电视剧"
            compact.contains("动漫") || compact.contains("动画") -> "动漫"
            compact.contains("综艺") -> "综艺"
            compact.contains("纪录") -> "纪录片"
            compact.contains("动作") -> "动作"
            compact.contains("喜剧") -> "喜剧"
            compact.contains("爱情") -> "爱情"
            compact.contains("科幻") -> "科幻"
            compact.contains("悬疑") -> "悬疑"
            compact.contains("恐怖") -> "恐怖"
            compact.contains("战争") -> "战争"
            compact.contains("剧情") -> "剧情"
            compact.contains("古装") -> "古装"
            compact.contains("犯罪") -> "犯罪"
            compact.contains("家庭") -> "家庭"
            compact.contains("冒险") -> "冒险"
            compact.contains("奇幻") -> "奇幻"
            else -> compact.take(8)
        }.takeIf { it.isNotBlank() }
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

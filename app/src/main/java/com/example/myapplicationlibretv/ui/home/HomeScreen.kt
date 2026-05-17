package com.example.myapplicationlibretv.ui.home

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import coil.request.ImageRequest
import coil.compose.AsyncImage
import com.example.myapplicationlibretv.BuildConfig
import com.example.myapplicationlibretv.download.BackgroundDownloadService
import com.example.myapplicationlibretv.download.DownloadCenter
import com.example.myapplicationlibretv.download.DownloadStatus
import com.example.myapplicationlibretv.download.DownloadTaskInfo
import com.example.myapplicationlibretv.ui.detail.PlayerEpisodePayload

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel = viewModel(),
    darkModeEnabled: Boolean,
    onDarkModeChange: (Boolean) -> Unit,
    onVideoClick: (siteKey: String, videoId: Int, videoTitle: String) -> Unit,
    onDownloadedVideoClick: (title: String, fileUri: String, episodes: List<PlayerEpisodePayload>, currentEpisodeIndex: Int) -> Unit
) {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        DownloadCenter.initialize(context)
    }
    val videoList by viewModel.videoList.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val searchResults by viewModel.searchResults.collectAsState()
    val searchLoading by viewModel.searchLoading.collectAsState()
    val searchErrorMessage by viewModel.searchErrorMessage.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val searchUiVisible by viewModel.searchUiVisible.collectAsState()
    val searchHistory by viewModel.searchHistory.collectAsState()
    val adultContentEnabled by viewModel.adultContentEnabled.collectAsState()
    val homeDisplayBySourceEnabled by viewModel.homeDisplayBySourceEnabled.collectAsState()
    val homeCategories by viewModel.homeCategories.collectAsState()
    val selectedHomeCategory by viewModel.selectedHomeCategory.collectAsState()
    val sites by viewModel.sites.collectAsState()
    val currentSite by viewModel.currentSite.collectAsState()
    val favorites by viewModel.favorites.collectAsState()
    val history by viewModel.history.collectAsState()
    val downloadTasks by DownloadCenter.tasks.collectAsState()
    val clipboard = LocalClipboardManager.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = {}
    )

    var showSourceSheet by remember { mutableStateOf(false) }
    val selectedTab by viewModel.selectedTab.collectAsState()
    var subscriptionInput by remember { mutableStateOf("") }
    var hiddenSettingsUnlocked by remember { mutableStateOf(false) }
    var versionTapCount by remember { mutableIntStateOf(0) }
    var homeScrollToTopTrigger by remember { mutableIntStateOf(0) }

    // 实现“点击两下‘首页’刷新”的逻辑
    var lastHomeTabClickTime by remember { mutableStateOf(0L) }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(adultContentEnabled) {
        if (!adultContentEnabled) {
            hiddenSettingsUnlocked = false
            versionTapCount = 0
        }
    }

    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> viewModel.setAppActive(true)
                Lifecycle.Event.ON_STOP -> viewModel.setAppActive(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    if (searchUiVisible) {
        BackHandler(enabled = true) {
            viewModel.setSelectedTab(0)
            viewModel.closeSearchUi(resetQuery = true)
            viewModel.fetchVideos(keyword = null)
        }
        SearchScreen(
            searchQuery = searchQuery,
            searchHistory = searchHistory,
            videoList = searchResults,
            isLoading = searchLoading,
            errorMessage = searchErrorMessage,
            onQueryChange = viewModel::onSearchQueryChange,
            onSearch = { keyword ->
                val trimmed = keyword.trim()
                if (trimmed.isNotEmpty()) {
                    viewModel.fetchVideos(trimmed)
                }
            },
            onSearchHistoryClick = viewModel::onSearchHistorySelected,
            onClearSearchHistory = viewModel::clearSearchHistory,
            onClose = {
                viewModel.setSelectedTab(0)
                viewModel.closeSearchUi(resetQuery = true)
                viewModel.fetchVideos(keyword = null)
            },
            onVideoClick = onVideoClick
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { showSourceSheet = true }) {
                        Icon(Icons.Default.Menu, contentDescription = "Sources")
                    }
                },
                title = {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(end = 8.dp)
                            .heightIn(min = 54.dp)
                            .clickable { viewModel.openSearchUi() },
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Search,
                                contentDescription = "打开搜索",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = if (searchQuery.isBlank()) "搜索影片或资源..." else searchQuery,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = {
                        if (selectedTab == 0) {
                            val currentTime = System.currentTimeMillis()
                            if (currentTime - lastHomeTabClickTime < 500) {
                                viewModel.refreshAll()
                                homeScrollToTopTrigger += 1
                            }
                            lastHomeTabClickTime = currentTime
                        } else {
                            viewModel.setSelectedTab(0)
                        }
                    },
                    icon = { Icon(Icons.Default.Search, contentDescription = "Home") },
                    label = { Text("首页") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { viewModel.setSelectedTab(1) },
                    icon = { Icon(Icons.Default.Star, contentDescription = "Favorites") },
                    label = { Text("收藏") }
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { viewModel.setSelectedTab(2) },
                    icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = "History") },
                    label = { Text("历史") }
                )
                NavigationBarItem(
                    selected = selectedTab == 3,
                    onClick = { viewModel.setSelectedTab(3) },
                    icon = { Icon(Icons.Default.Download, contentDescription = "Downloads") },
                    label = { Text("下载") }
                )
            }
        }
    ) { paddingValues ->
        if (showSourceSheet) {
            ModalBottomSheet(
                onDismissRequest = { showSourceSheet = false }
            ) {
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                ) {
                    val compactLayout = maxWidth < 560.dp
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(0.95f)
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            item {
                                Text("订阅源", style = MaterialTheme.typography.titleLarge)
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "当前共 ${sites.size} 个源",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            item {
                                OutlinedTextField(
                                    value = subscriptionInput,
                                    onValueChange = { subscriptionInput = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    placeholder = { Text("粘贴订阅链接或 CMS API（支持多条）") },
                                    minLines = if (compactLayout) 2 else 3
                                )
                            }
                            item {
                                if (compactLayout) {
                                    Column(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        OutlinedButton(
                                            onClick = {
                                                val text = clipboard.getText()?.text.orEmpty()
                                                if (text.isNotBlank()) {
                                                    subscriptionInput = if (subscriptionInput.isBlank()) text else "$subscriptionInput\n$text"
                                                }
                                            },
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Icon(Icons.Default.ContentPaste, contentDescription = null)
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("粘贴")
                                        }
                                        Button(
                                            onClick = {
                                                viewModel.importSubscriptions(subscriptionInput)
                                                subscriptionInput = ""
                                                showSourceSheet = false
                                            },
                                            enabled = !isLoading,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Text("导入并检测")
                                        }
                                    }
                                } else {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        OutlinedButton(
                                            onClick = {
                                                val text = clipboard.getText()?.text.orEmpty()
                                                if (text.isNotBlank()) {
                                                    subscriptionInput = if (subscriptionInput.isBlank()) text else "$subscriptionInput\n$text"
                                                }
                                            },
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Icon(Icons.Default.ContentPaste, contentDescription = null)
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("粘贴")
                                        }
                                        Button(
                                            onClick = {
                                                viewModel.importSubscriptions(subscriptionInput)
                                                subscriptionInput = ""
                                                showSourceSheet = false
                                            },
                                            enabled = !isLoading,
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Text("导入并检测")
                                        }
                                    }
                                }
                            }
                            item {
                                OutlinedButton(
                                    onClick = { viewModel.fetchVideos() },
                                    enabled = !isLoading,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("刷新列表")
                                }
                            }
                            item {
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = MaterialTheme.shapes.medium,
                                    color = MaterialTheme.colorScheme.surfaceVariant
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 12.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text("夜间模式")
                                            Text(
                                                text = "开启后使用深色界面，适合夜间观看。",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                imageVector = if (darkModeEnabled) Icons.Default.DarkMode else Icons.Default.LightMode,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Switch(
                                                checked = darkModeEnabled,
                                                onCheckedChange = onDarkModeChange
                                            )
                                        }
                                    }
                                }
                            }
                            item {
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = MaterialTheme.shapes.medium,
                                    color = MaterialTheme.colorScheme.surfaceVariant
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 12.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text("首页按源显示")
                                            Text(
                                                text = "默认关闭。关闭时首页聚合推荐，开启后只显示当前所选源的资源。",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        Switch(
                                            checked = homeDisplayBySourceEnabled,
                                            onCheckedChange = viewModel::setHomeDisplayBySourceEnabled
                                        )
                                    }
                                }
                            }
                            if (hiddenSettingsUnlocked || adultContentEnabled) {
                                item {
                                    Surface(
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = MaterialTheme.shapes.medium,
                                        color = MaterialTheme.colorScheme.surfaceVariant
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 12.dp, vertical = 12.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text("18+ 内容")
                                                Text(
                                                    text = "默认关闭。关闭时严格过滤成人推荐和搜索结果。",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                            Switch(
                                                checked = adultContentEnabled,
                                                onCheckedChange = viewModel::setAdultContentEnabled
                                            )
                                        }
                                    }
                                }
                            }
                            if (isLoading) {
                                item {
                                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                }
                            }
                            if (!errorMessage.isNullOrBlank()) {
                                item {
                                    Text(text = errorMessage!!, color = MaterialTheme.colorScheme.error)
                                }
                            }
                            item {
                                Text("已加载源", style = MaterialTheme.typography.titleMedium)
                            }
                            items(sites, key = { it.api }) { site ->
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            viewModel.onSiteSelected(site)
                                            showSourceSheet = false
                                        },
                                    shape = MaterialTheme.shapes.medium,
                                    color = if (site.key == currentSite?.key) {
                                        MaterialTheme.colorScheme.secondaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant
                                    }
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 14.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        RadioButton(
                                            selected = site.key == currentSite?.key,
                                            onClick = null
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(site.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text(
                                                site.api,
                                                style = MaterialTheme.typography.bodySmall,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp, bottom = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "版本 ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.clickable {
                                    versionTapCount += 1
                                    if (versionTapCount >= 5) {
                                        hiddenSettingsUnlocked = true
                                        versionTapCount = 0
                                    }
                                }
                            )
                            TextButton(onClick = { showSourceSheet = false }) {
                                Text("关闭")
                            }
                        }
                    }
                }
            }
        }

        PullToRefreshBox(
            isRefreshing = isLoading && selectedTab == 0,
            onRefresh = { viewModel.fetchVideos() },
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when (selectedTab) {
                0 -> HomeTabContent(
                    videoList = videoList,
                    categories = homeCategories.map { it.name },
                    selectedCategory = selectedHomeCategory,
                    displayBySource = homeDisplayBySourceEnabled,
                    currentSiteName = currentSite?.name,
                    isLoading = isLoading,
                    errorMessage = errorMessage,
                    scrollToTopTrigger = homeScrollToTopTrigger,
                    onCategorySelected = viewModel::selectHomeCategory,
                    onRetry = { viewModel.fetchVideos() },
                    onVideoClick = onVideoClick
                )
                1 -> FavoritesTabContent(
                    favorites = favorites,
                    currentSiteName = currentSite?.name,
                    onDeleteFavoriteItem = viewModel::deleteFavoriteItem,
                    onVideoClick = onVideoClick
                )
                2 -> HistoryTabContent(
                    history = history,
                    currentSiteName = currentSite?.name,
                    onDeleteHistoryItem = viewModel::deleteHistoryItem,
                    onClearAllHistory = viewModel::clearAllHistory,
                    onVideoClick = onVideoClick
                )
                3 -> DownloadsTabContent(
                    downloadTasks = downloadTasks,
                    onPlayInApp = { task, groupTasks ->
                        val fileUri = task.fileUri ?: return@DownloadsTabContent
                        val playableTasks = groupTasks
                            .filter { it.status == DownloadStatus.COMPLETED && !it.fileUri.isNullOrBlank() }
                            .sortedBy { it.title }
                        val episodes = playableTasks.map { item ->
                            PlayerEpisodePayload(
                                name = inferDownloadEpisodeName(item.title),
                                title = item.title,
                                playlist = item.fileUri.orEmpty()
                            )
                        }
                        val currentIndex = playableTasks.indexOfFirst { it.id == task.id }.coerceAtLeast(0)
                        onDownloadedVideoClick(task.title, fileUri, episodes, currentIndex)
                    },
                    onPause = { task ->
                        BackgroundDownloadService.pause(context, task.id)
                    },
                    onResume = { task ->
                        BackgroundDownloadService.resume(context, task.id)
                    },
                    onDelete = { task ->
                        if (task.status == DownloadStatus.COMPLETED) {
                            DownloadCenter.delete(context, task.id, removeFile = true)
                        } else {
                            BackgroundDownloadService.delete(context, task.id)
                        }
                    },
                    onOpenFile = { task ->
                        val fileUri = task.fileUri ?: return@DownloadsTabContent
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(Uri.parse(fileUri), guessMimeType(task.fileName))
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        runCatching {
                            context.startActivity(
                                Intent.createChooser(intent, "打开下载视频").apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                            )
                        }.recoverCatching {
                            context.startActivity(intent)
                        }.onFailure {
                            val message = if (it is ActivityNotFoundException) {
                                "没有可用的播放器来打开该文件"
                            } else {
                                "打开文件失败"
                            }
                            android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun HomeTabContent(
    videoList: List<SourcedVideo>,
    categories: List<String>,
    selectedCategory: String,
    displayBySource: Boolean,
    currentSiteName: String?,
    isLoading: Boolean,
    errorMessage: String?,
    scrollToTopTrigger: Int,
    onCategorySelected: (String) -> Unit,
    onRetry: () -> Unit,
    onVideoClick: (siteKey: String, videoId: Int, videoTitle: String) -> Unit
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val horizontalPadding = remember(maxWidth) { calculateContentHorizontalPadding(maxWidth) }
        val gridState = rememberLazyGridState()
        LaunchedEffect(scrollToTopTrigger) {
            if (scrollToTopTrigger > 0) {
                gridState.animateScrollToItem(0)
            }
        }
        if (isLoading && videoList.isEmpty()) {
            LoadingMediaGridSkeleton(modifier = Modifier.fillMaxSize())
            return@BoxWithConstraints
        }

        if (videoList.isEmpty()) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = errorMessage ?: "没有找到资源",
                    color = if (errorMessage != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = onRetry) {
                    Text("重试")
                }
            }
            return@BoxWithConstraints
        }

        Column(modifier = Modifier.fillMaxSize()) {
            if (categories.size > 1) {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = horizontalPadding, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(categories, key = { it }) { category ->
                        FilterChip(
                            selected = selectedCategory == category,
                            onClick = { onCategorySelected(category) },
                            label = { Text(category, maxLines = 1) }
                        )
                    }
                }
            }
            if (!errorMessage.isNullOrBlank()) {
                Text(
                    text = errorMessage,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = horizontalPadding, vertical = 6.dp)
                )
            }
            MediaGrid(
                modifier = Modifier.fillMaxSize(),
                state = gridState,
                outerHorizontalPadding = horizontalPadding,
                itemsCount = videoList.size,
                key = { index -> "${videoList[index].siteKey}:${videoList[index].video.id}" },
                itemContent = { index ->
                    val item = videoList[index]
                    VideoItemCard(
                        title = item.video.name,
                        imageUrl = item.video.pic,
                        subtitle = item.siteName,
                        meta = buildVideoMeta(item.video),
                        onClick = { onVideoClick(item.siteKey, item.video.id, item.video.name) }
                    )
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchScreen(
    searchQuery: String,
    searchHistory: List<String>,
    videoList: List<SourcedVideo>,
    isLoading: Boolean,
    errorMessage: String?,
    onQueryChange: (String) -> Unit,
    onSearch: (String) -> Unit,
    onSearchHistoryClick: (String) -> Unit,
    onClearSearchHistory: () -> Unit,
    onClose: () -> Unit,
    onVideoClick: (siteKey: String, videoId: Int, videoTitle: String) -> Unit
) {
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    var fieldValue by remember { mutableStateOf(TextFieldValue(searchQuery)) }

    LaunchedEffect(searchQuery) {
        if (searchQuery != fieldValue.text) {
            fieldValue = fieldValue.copy(
                text = searchQuery,
                selection = androidx.compose.ui.text.TextRange(searchQuery.length)
            )
        }
    }

    val submitSearch = remember(fieldValue, onSearch, focusManager, keyboardController) {
        {
            val keyword = fieldValue.text.trim()
            focusManager.clearFocus(force = true)
            keyboardController?.hide()
            if (keyword.isNotEmpty()) {
                onSearch(keyword)
            }
        }
    }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        color = MaterialTheme.colorScheme.background
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            val horizontalPadding = remember(maxWidth) { calculateContentHorizontalPadding(maxWidth) }
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = horizontalPadding, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, contentDescription = "关闭搜索")
                }
                OutlinedTextField(
                    value = fieldValue,
                    onValueChange = {
                        fieldValue = it
                        onQueryChange(it.text)
                    },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 56.dp),
                    placeholder = { Text("搜索影片、演员、导演或关键词...") },
                    singleLine = true,
                    trailingIcon = {
                        IconButton(onClick = submitSearch) {
                            Icon(Icons.Default.Search, contentDescription = "搜索")
                        }
                    },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                        onSearch = { submitSearch() }
                    ),
                    colors = TextFieldDefaults.colors(
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                        focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent
                    ),
                    shape = RoundedCornerShape(22.dp)
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
            ) {
                if (searchQuery.isBlank() && searchHistory.isNotEmpty()) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = horizontalPadding, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("搜索历史", style = MaterialTheme.typography.titleMedium)
                            TextButton(onClick = onClearSearchHistory) {
                                Text("清空")
                            }
                        }
                        LazyColumn(
                            contentPadding = PaddingValues(horizontal = horizontalPadding, vertical = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(searchHistory, key = { it }) { keyword ->
                                AssistChip(
                                    onClick = { onSearchHistoryClick(keyword) },
                                    label = { Text(keyword, maxLines = 1) }
                                )
                            }
                        }
                    }
                } else if (searchQuery.isBlank()) {
                    SearchIdleContent()
                } else {
                    SearchResultsContent(
                        query = searchQuery,
                        videoList = videoList,
                        isLoading = isLoading,
                        errorMessage = errorMessage,
                        onRetry = { submitSearch() },
                        onVideoClick = onVideoClick,
                        horizontalPadding = horizontalPadding
                    )
                }
            }
        }
        }
    }
}

@Composable
private fun SearchIdleContent() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("输入片名、演员或关键词开始搜索")
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "搜索结果会单独显示，不会混入首页热门。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SearchResultsContent(
    query: String,
    videoList: List<SourcedVideo>,
    isLoading: Boolean,
    errorMessage: String?,
    onRetry: () -> Unit,
    onVideoClick: (siteKey: String, videoId: Int, videoTitle: String) -> Unit,
    horizontalPadding: Dp
) {
    Box(modifier = Modifier.fillMaxSize()) {
        if (isLoading && videoList.isEmpty()) {
            LoadingMediaGridSkeleton(modifier = Modifier.fillMaxSize())
            return@Box
        }

        if (videoList.isEmpty()) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = errorMessage ?: "没有找到和“$query”相关的结果",
                    color = if (errorMessage != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = onRetry) {
                    Text("重试")
                }
            }
            return@Box
        }

        Column(modifier = Modifier.fillMaxSize()) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = horizontalPadding, vertical = 8.dp),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                ) {
                    Text(
                        text = "搜索结果",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "关键词：$query",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (!errorMessage.isNullOrBlank()) {
                Text(
                    text = errorMessage,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = horizontalPadding, vertical = 6.dp)
                )
            }
            MediaGrid(
                modifier = Modifier.fillMaxSize(),
                outerHorizontalPadding = horizontalPadding,
                itemsCount = videoList.size,
                key = { index -> "${videoList[index].siteKey}:${videoList[index].video.id}" },
                itemContent = { index ->
                    val item = videoList[index]
                    VideoItemCard(
                        title = item.video.name,
                        imageUrl = item.video.pic,
                        subtitle = item.siteName,
                        meta = buildVideoMeta(item.video),
                        onClick = { onVideoClick(item.siteKey, item.video.id, item.video.name) }
                    )
                }
            )
        }
    }
}

@Composable
private fun FavoritesTabContent(
    favorites: List<com.example.myapplicationlibretv.data.db.FavoriteVideo>,
    currentSiteName: String?,
    onDeleteFavoriteItem: (Int) -> Unit,
    onVideoClick: (siteKey: String, videoId: Int, videoTitle: String) -> Unit
) {
    if (favorites.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("暂无收藏")
        }
        return
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val horizontalPadding = remember(maxWidth) { calculateContentHorizontalPadding(maxWidth) }
        MediaGrid(
            modifier = Modifier.fillMaxSize(),
            outerHorizontalPadding = horizontalPadding,
            itemsCount = favorites.size,
            key = { index -> favorites[index].id },
            itemContent = { index ->
                val favorite = favorites[index]
                VideoItemCard(
                    title = favorite.name,
                    imageUrl = favorite.pic,
                    subtitle = "收藏资源",
                    actionButton = {
                        IconButton(onClick = { onDeleteFavoriteItem(favorite.id) }) {
                            Icon(Icons.Default.Delete, contentDescription = "删除收藏")
                        }
                    },
                    onClick = {
                        onVideoClick(
                            favorite.siteKey,
                            if (favorite.sourceVideoId != 0) favorite.sourceVideoId else favorite.id,
                            favorite.name
                        )
                    }
                )
            }
        )
    }
}

@Composable
private fun HistoryTabContent(
    history: List<com.example.myapplicationlibretv.data.db.HistoryVideo>,
    currentSiteName: String?,
    onDeleteHistoryItem: (Int) -> Unit,
    onClearAllHistory: () -> Unit,
    onVideoClick: (siteKey: String, videoId: Int, videoTitle: String) -> Unit
) {
    if (history.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("暂无历史")
        }
        return
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val minCellWidth = remember(maxWidth) { calculateGridMinSize(maxWidth) }
        val horizontalPadding = remember(maxWidth) { calculateContentHorizontalPadding(maxWidth) }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = minCellWidth),
            contentPadding = PaddingValues(horizontal = horizontalPadding, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 2.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("观看历史", style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = onClearAllHistory) {
                        Text("全部清除")
                    }
                }
            }
            items(history, key = { it.id }) { record ->
                VideoItemCard(
                    title = record.name,
                    imageUrl = record.pic,
                    subtitle = "观看历史",
                    meta = buildHistoryMeta(record.progress, record.duration),
                    actionButton = {
                        IconButton(onClick = { onDeleteHistoryItem(record.id) }) {
                            Icon(Icons.Default.Delete, contentDescription = "删除历史")
                        }
                    },
                    onClick = {
                        onVideoClick(
                            record.siteKey,
                            if (record.sourceVideoId != 0) record.sourceVideoId else record.id,
                            record.name
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun DownloadsTabContent(
    downloadTasks: List<DownloadTaskInfo>,
    onPlayInApp: (DownloadTaskInfo, List<DownloadTaskInfo>) -> Unit,
    onPause: (DownloadTaskInfo) -> Unit,
    onResume: (DownloadTaskInfo) -> Unit,
    onDelete: (DownloadTaskInfo) -> Unit,
    onOpenFile: (DownloadTaskInfo) -> Unit
) {
    var selectedDownloadSeries by remember { mutableStateOf<String?>(null) }
    var selectedDownloadPage by remember { mutableIntStateOf(0) }
    if (downloadTasks.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("暂无下载任务")
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "在播放页点击“下载”后，这里会显示前台/后台下载状态。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        return
    }

    val activeTasks = downloadTasks.filter {
        it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.RUNNING || it.status == DownloadStatus.PAUSED
    }
    val completedTasks = downloadTasks.filter { it.status == DownloadStatus.COMPLETED }
    val failedTasks = downloadTasks.filter { it.status == DownloadStatus.FAILED }
    val allGroups = remember(downloadTasks) {
        downloadTasks.groupBy { inferDownloadSeriesTitle(it.title) }
    }
    val activeGroups = remember(activeTasks) {
        activeTasks.groupBy { inferDownloadSeriesTitle(it.title) }
    }
    val completedGroups = remember(completedTasks) {
        completedTasks.groupBy { inferDownloadSeriesTitle(it.title) }
    }
    val failedGroups = remember(failedTasks) {
        failedTasks.groupBy { inferDownloadSeriesTitle(it.title) }
    }
    val visibleTasks = when (selectedDownloadPage) {
        1 -> completedTasks
        2 -> failedTasks
        else -> activeTasks
    }
    val visibleGroups = when (selectedDownloadPage) {
        1 -> completedGroups
        2 -> failedGroups
        else -> activeGroups
    }
    val selectedGroupTasks = selectedDownloadSeries?.let { visibleGroups[it].orEmpty() }.orEmpty()
    val emptyPageText = when (selectedDownloadPage) {
        1 -> "暂无完成下载"
        2 -> "暂无失败任务"
        else -> "暂无正在下载"
    }
    LaunchedEffect(selectedDownloadSeries, allGroups) {
        if (selectedDownloadSeries != null && selectedGroupTasks.isEmpty()) {
            selectedDownloadSeries = null
        }
    }
    BackHandler(enabled = selectedDownloadSeries != null || selectedDownloadPage != 0) {
        if (selectedDownloadSeries != null) {
            selectedDownloadSeries = null
        } else {
            selectedDownloadPage = 0
        }
    }

    if (selectedDownloadSeries != null) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val horizontalPadding = remember(maxWidth) { calculateContentHorizontalPadding(maxWidth) }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = horizontalPadding, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { selectedDownloadSeries = null }) {
                            Text("返回")
                        }
                        Text(
                            selectedDownloadSeries.orEmpty(),
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = {
                            selectedGroupTasks.forEach(onDelete)
                            selectedDownloadSeries = null
                        }) {
                            Icon(Icons.Default.Delete, contentDescription = "删除分组")
                        }
                    }
                }
                item {
                    DownloadBulkActionsRow(
                        page = selectedDownloadPage,
                        tasks = selectedGroupTasks,
                        onPause = onPause,
                        onResume = onResume
                    )
                }
                items(selectedGroupTasks, key = { it.id }) { task ->
                    if (selectedDownloadPage == 1) {
                        DownloadCompletedRow(
                            task = task,
                            onPlayInApp = { onPlayInApp(task, selectedGroupTasks) },
                            onDelete = { onDelete(task) }
                        )
                    } else {
                        DownloadTaskCard(
                            task = task,
                            onPlayInApp = { task -> onPlayInApp(task, selectedGroupTasks) },
                            onPause = onPause,
                            onResume = onResume,
                            onDelete = onDelete,
                            onOpenFile = onOpenFile
                        )
                    }
                }
            }
        }
        return
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val horizontalPadding = remember(maxWidth) { calculateContentHorizontalPadding(maxWidth) }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = horizontalPadding, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = selectedDownloadPage == 0,
                        onClick = {
                            selectedDownloadPage = 0
                            selectedDownloadSeries = null
                        },
                        label = { Text("下载中 ${activeTasks.size}") }
                    )
                    FilterChip(
                        selected = selectedDownloadPage == 1,
                        onClick = {
                            selectedDownloadPage = 1
                            selectedDownloadSeries = null
                        },
                        label = { Text("已完成 ${completedTasks.size}") }
                    )
                    FilterChip(
                        selected = selectedDownloadPage == 2,
                        onClick = {
                            selectedDownloadPage = 2
                            selectedDownloadSeries = null
                        },
                        label = { Text("失败 ${failedTasks.size}") }
                    )
                }
            }

            item {
                DownloadBulkActionsRow(
                    page = selectedDownloadPage,
                    tasks = visibleTasks,
                    onPause = onPause,
                    onResume = onResume
                )
            }

            if (visibleTasks.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 140.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(emptyPageText, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            visibleGroups.entries.forEach { (seriesTitle, tasks) ->
                if (tasks.size > 1) {
                    item(key = "download_folder_${selectedDownloadPage}_$seriesTitle") {
                        DownloadSeriesFolderCard(
                            title = seriesTitle,
                            count = tasks.size,
                            summary = buildDownloadSeriesSummary(tasks),
                            onClick = { selectedDownloadSeries = seriesTitle },
                            onDelete = { tasks.forEach(onDelete) }
                        )
                    }
                } else {
                    items(tasks, key = { it.id }) { task ->
                        if (selectedDownloadPage == 1) {
                            DownloadCompletedRow(
                                task = task,
                                onPlayInApp = { onPlayInApp(task, visibleGroups[inferDownloadSeriesTitle(task.title)].orEmpty()) },
                                onDelete = { onDelete(task) }
                            )
                        } else {
                            DownloadTaskCard(
                                task = task,
                                onPlayInApp = { task -> onPlayInApp(task, allGroups[inferDownloadSeriesTitle(task.title)].orEmpty()) },
                                onPause = onPause,
                                onResume = onResume,
                                onDelete = onDelete,
                                onOpenFile = onOpenFile
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadBulkActionsRow(
    page: Int,
    tasks: List<DownloadTaskInfo>,
    onPause: (DownloadTaskInfo) -> Unit,
    onResume: (DownloadTaskInfo) -> Unit
) {
    val pauseTargets = remember(tasks) {
        tasks.filter { it.status == DownloadStatus.RUNNING || it.status == DownloadStatus.QUEUED }
    }
    val resumeTargets = remember(tasks) {
        tasks.filter { it.status == DownloadStatus.PAUSED }
    }
    val retryTargets = remember(tasks) {
        tasks.filter { it.status == DownloadStatus.FAILED }
    }

    when (page) {
        0 -> {
            if (pauseTargets.isEmpty() && resumeTargets.isEmpty()) return
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val compact = maxWidth < 420.dp
                if (compact) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { pauseTargets.forEach(onPause) },
                            enabled = pauseTargets.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("全部暂停")
                        }
                        Button(
                            onClick = { resumeTargets.forEach(onResume) },
                            enabled = resumeTargets.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("全部开始")
                        }
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { pauseTargets.forEach(onPause) },
                            enabled = pauseTargets.isNotEmpty(),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("全部暂停")
                        }
                        Button(
                            onClick = { resumeTargets.forEach(onResume) },
                            enabled = resumeTargets.isNotEmpty(),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("全部开始")
                        }
                    }
                }
            }
        }
        2 -> {
            if (retryTargets.isEmpty()) return
            Button(
                onClick = { retryTargets.forEach(onResume) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("全部重试")
            }
        }
    }
}

@Composable
private fun DownloadSeriesFolderCard(
    title: String,
    count: Int,
    summary: String,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val compact = maxWidth < 380.dp
            if (compact) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            title,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = onDelete) {
                            Icon(Icons.Default.Delete, contentDescription = "删除分组")
                        }
                    }
                    Text(
                        "$count 集 · $summary",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "$count 集 · $summary",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Default.Delete, contentDescription = "删除分组")
                    }
                }
            }
        }
    }
}

private fun buildDownloadSeriesSummary(tasks: List<DownloadTaskInfo>): String {
    val running = tasks.count { it.status == DownloadStatus.RUNNING || it.status == DownloadStatus.QUEUED }
    val paused = tasks.count { it.status == DownloadStatus.PAUSED }
    val completed = tasks.count { it.status == DownloadStatus.COMPLETED }
    val failed = tasks.count { it.status == DownloadStatus.FAILED }
    return listOfNotNull(
        running.takeIf { it > 0 }?.let { "下载中 $it" },
        paused.takeIf { it > 0 }?.let { "暂停 $it" },
        completed.takeIf { it > 0 }?.let { "完成 $it" },
        failed.takeIf { it > 0 }?.let { "失败 $it" }
    ).joinToString("，").ifBlank { "等待中" }
}

private fun inferDownloadSeriesTitle(title: String): String {
    val trimmed = title.trim()
    val series = listOf(" · ", " - ", "_")
        .firstNotNullOfOrNull { separator ->
            trimmed.substringBefore(separator).takeIf { it != trimmed }
        }
        ?.trim()
    return series?.takeIf { it.isNotBlank() } ?: trimmed.ifBlank { "已下载视频" }
}

private fun inferDownloadEpisodeName(title: String): String {
    val trimmed = title.trim()
    return listOf(" · ", " - ", "_")
        .firstNotNullOfOrNull { separator ->
            trimmed.substringAfter(separator).takeIf { it != trimmed }
        }
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?: trimmed.ifBlank { "播放" }
}

@Composable
private fun DownloadCompletedRow(
    task: DownloadTaskInfo,
    onPlayInApp: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onPlayInApp)
                .padding(start = 12.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = inferDownloadEpisodeName(task.title),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "删除下载")
            }
        }
    }
}

@Composable
private fun DownloadTaskCard(
    task: DownloadTaskInfo,
    onPlayInApp: (DownloadTaskInfo) -> Unit,
    onPause: (DownloadTaskInfo) -> Unit,
    onResume: (DownloadTaskInfo) -> Unit,
    onDelete: (DownloadTaskInfo) -> Unit,
    onOpenFile: (DownloadTaskInfo) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(task.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                when (task.status) {
                    DownloadStatus.QUEUED -> "状态：等待开始"
                    DownloadStatus.RUNNING -> "状态：后台下载中"
                    DownloadStatus.PAUSED -> "状态：已暂停"
                    DownloadStatus.COMPLETED -> "状态：下载完成"
                    DownloadStatus.FAILED -> "状态：下载失败"
                },
                color = when (task.status) {
                    DownloadStatus.FAILED -> MaterialTheme.colorScheme.error
                    DownloadStatus.COMPLETED -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurface
                }
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                task.progressText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            task.fileName?.let {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "文件：$it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            task.errorMessage?.let {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "错误：$it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val compactButtons = maxWidth < 420.dp
                if (compactButtons) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        when (task.status) {
                            DownloadStatus.RUNNING, DownloadStatus.QUEUED -> {
                                OutlinedButton(
                                    onClick = { onPause(task) },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("暂停")
                                }
                            }
                            DownloadStatus.PAUSED, DownloadStatus.FAILED -> {
                                Button(
                                    onClick = { onResume(task) },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("继续")
                                }
                            }
                            DownloadStatus.COMPLETED -> {
                                Button(
                                    onClick = { onPlayInApp(task) },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("播放")
                                }
                                OutlinedButton(
                                    onClick = { onOpenFile(task) },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("打开")
                                }
                            }
                        }
                        OutlinedButton(
                            onClick = { onDelete(task) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("删除")
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        when (task.status) {
                            DownloadStatus.RUNNING, DownloadStatus.QUEUED -> {
                                OutlinedButton(
                                    onClick = { onPause(task) },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("暂停")
                                }
                            }
                            DownloadStatus.PAUSED, DownloadStatus.FAILED -> {
                                Button(
                                    onClick = { onResume(task) },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("继续")
                                }
                            }
                            DownloadStatus.COMPLETED -> {
                                Button(
                                    onClick = { onPlayInApp(task) },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("播放")
                                }
                                OutlinedButton(
                                    onClick = { onOpenFile(task) },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("打开")
                                }
                            }
                        }
                        OutlinedButton(
                            onClick = { onDelete(task) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("删除")
                        }
                    }
                }
            }
        }
    }
}

private fun guessMimeType(fileName: String?): String {
    val lower = fileName.orEmpty().lowercase()
    return when {
        lower.endsWith(".mp4") -> "video/mp4"
        lower.endsWith(".mkv") -> "video/x-matroska"
        lower.endsWith(".ts") -> "video/mp2t"
        lower.endsWith(".m3u8") -> "application/vnd.apple.mpegurl"
        else -> "video/*"
    }
}

@Composable
fun VideoItemCard(
    title: String,
    imageUrl: String?,
    subtitle: String? = null,
    meta: String? = null,
    actionButton: (@Composable () -> Unit)? = null,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.68f)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(imageUrl)
                    .crossfade(false)
                    .allowHardware(true)
                    .build(),
                contentDescription = title,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)),
                contentScale = ContentScale.Crop
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.surface,
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f)
                            )
                        )
                    )
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    actionButton?.invoke()
                }
                if (!subtitle.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                if (!meta.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = meta,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun MediaGrid(
    modifier: Modifier = Modifier,
    state: LazyGridState = rememberLazyGridState(),
    outerHorizontalPadding: Dp = 10.dp,
    itemsCount: Int,
    key: (Int) -> Any,
    itemContent: @Composable (Int) -> Unit
) {
    BoxWithConstraints(modifier = modifier) {
        val minCellWidth = remember(maxWidth) { calculateGridMinSize(maxWidth) }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = minCellWidth),
            state = state,
            contentPadding = PaddingValues(horizontal = outerHorizontalPadding, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(
                count = itemsCount,
                key = { index -> key(index) }
            ) { index ->
                itemContent(index)
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

private fun calculateGridMinSize(maxWidth: Dp): Dp {
    return when {
        maxWidth >= 900.dp -> 180.dp
        maxWidth >= 720.dp -> 156.dp
        maxWidth >= 520.dp -> 138.dp
        else -> 118.dp
    }
}

private fun calculateContentHorizontalPadding(maxWidth: Dp): Dp {
    return when {
        maxWidth >= 1100.dp -> 48.dp
        maxWidth >= 900.dp -> 32.dp
        maxWidth >= 700.dp -> 20.dp
        else -> 12.dp
    }
}

@Composable
private fun LoadingMediaGridSkeleton(modifier: Modifier = Modifier) {
    val shimmer = rememberInfiniteTransition(label = "homeLoading")
    val alpha by shimmer.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 850, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "homeLoadingAlpha"
    )

    MediaGrid(
        modifier = modifier,
        itemsCount = 9,
        key = { it },
        itemContent = {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.68f),
                shape = RoundedCornerShape(18.dp)
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha))
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.8f)
                                .height(14.dp)
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha), RoundedCornerShape(6.dp))
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.5f)
                                .height(10.dp)
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha), RoundedCornerShape(6.dp))
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.9f)
                                .height(10.dp)
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha), RoundedCornerShape(6.dp))
                        )
                    }
                }
            }
        }
    )
}

private fun buildHistoryMeta(progress: Long, duration: Long): String? {
    if (progress <= 0L) return null
    val progressText = formatDuration(progress)
    return if (duration > 0L) {
        "看到 $progressText / ${formatDuration(duration)}"
    } else {
        "看到 $progressText"
    }
}

private fun buildVideoMeta(video: com.example.myapplicationlibretv.data.model.VideoItem): String? {
    val baseLine = listOfNotNull(
        video.year?.trim()?.takeIf { it.isNotBlank() },
        video.typeName?.trim()?.takeIf { it.isNotBlank() },
        video.remarks?.trim()?.takeIf { it.isNotBlank() }
    ).joinToString(" · ").takeIf { it.isNotBlank() }

    val directorLine = video.director
        ?.split("/", ",", "，")
        ?.map { it.trim() }
        ?.firstOrNull { it.isNotBlank() }
        ?.let { "导演 $it" }

    val actorLine = video.actor
        ?.split("/", ",", "，")
        ?.map { it.trim() }
        ?.filter { it.isNotBlank() }
        ?.take(2)
        ?.joinToString(" / ")
        ?.let { "主演 $it" }

    return listOfNotNull(baseLine, directorLine, actorLine)
        .joinToString("\n")
        .ifBlank { null }
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val seconds = (totalSeconds % 60).toInt()
    val minutes = ((totalSeconds / 60) % 60).toInt()
    val hours = (totalSeconds / 3600).toInt()
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%02d:%02d".format(minutes, seconds)
}

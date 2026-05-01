package com.example.myapplicationlibretv

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.myapplicationlibretv.data.repository.SourceRepository
import java.net.URLEncoder
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import com.example.myapplicationlibretv.ui.detail.VideoDetailScreen
import com.example.myapplicationlibretv.ui.detail.PlayerEpisodePayload
import com.example.myapplicationlibretv.ui.home.HomeScreen
import com.example.myapplicationlibretv.ui.player.PlayerPipController
import com.example.myapplicationlibretv.ui.player.PlayerScreen
import com.example.myapplicationlibretv.ui.player.PlayerSession
import com.example.myapplicationlibretv.ui.player.PlayerSessionStore
import com.example.myapplicationlibretv.ui.theme.MyApplicationLibreTVTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val context = LocalContext.current
            val prefs = remember {
                context.applicationContext.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            }
            var darkModeEnabled by remember {
                mutableStateOf(prefs.getBoolean("dark_mode_enabled", false))
            }
            MyApplicationLibreTVTheme(darkTheme = darkModeEnabled) {
                AppNavigation(
                    darkModeEnabled = darkModeEnabled,
                    onDarkModeChange = { enabled ->
                        darkModeEnabled = enabled
                        prefs.edit().putBoolean("dark_mode_enabled", enabled).apply()
                    }
                )
            }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        PlayerPipController.enterPictureInPictureIfPossible()
    }
}

@Composable
fun AppNavigation(
    darkModeEnabled: Boolean,
    onDarkModeChange: (Boolean) -> Unit
) {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = "home") {
        composable("home") {
            HomeScreen(
                darkModeEnabled = darkModeEnabled,
                onDarkModeChange = onDarkModeChange,
                onVideoClick = { siteKey, videoId, videoTitle ->
                    val encodedSiteKey = URLEncoder.encode(siteKey, StandardCharsets.UTF_8.toString())
                    val encodedTitle = URLEncoder.encode(videoTitle, StandardCharsets.UTF_8.toString())
                    navController.navigate("detail/$encodedSiteKey/$videoId/$encodedTitle")
                },
                onDownloadedVideoClick = { title, fileUri ->
                    val encodedTitle = URLEncoder.encode(title, StandardCharsets.UTF_8.toString())
                    val encodedUri = URLEncoder.encode(fileUri, StandardCharsets.UTF_8.toString())
                    navController.navigate("player/0/$encodedTitle/$encodedUri?session=")
                }
            )
        }
        composable("detail/{siteKey}/{videoId}/{videoTitle}") { backStackEntry ->
            val siteKey =
                backStackEntry.arguments?.getString("siteKey")
                    ?.let { URLDecoder.decode(it, StandardCharsets.UTF_8.toString()) }
                    ?: ""
            val videoId = backStackEntry.arguments?.getString("videoId")?.toIntOrNull() ?: 0
            val videoTitle =
                backStackEntry.arguments?.getString("videoTitle")
                    ?.let { URLDecoder.decode(it, StandardCharsets.UTF_8.toString()) }
                    ?: ""
            VideoDetailScreen(
                siteKey = siteKey,
                videoId = videoId,
                videoTitle = videoTitle,
                onBack = { navController.popBackStack() },
                onPlayClick = { playVideoId, playerTitle, videoUrl, episodes, currentEpisodeIndex ->
                    val encodedTitle = URLEncoder.encode(playerTitle, StandardCharsets.UTF_8.toString())
                    val encodedUrl = URLEncoder.encode(videoUrl, StandardCharsets.UTF_8.toString())
                    val sessionId = PlayerSessionStore.put(
                        PlayerSession(
                            episodes = episodes,
                            currentEpisodeIndex = currentEpisodeIndex
                        )
                    )
                    navController.navigate(
                        "player/$playVideoId/$encodedTitle/$encodedUrl?session=$sessionId"
                    )
                }
            )
        }
        composable("player/{videoId}/{playerTitle}/{videoUrl}?session={session}") { backStackEntry ->
            val videoId = backStackEntry.arguments?.getString("videoId")?.toIntOrNull() ?: 0
            val playerTitle =
                backStackEntry.arguments?.getString("playerTitle")
                    ?.let { URLDecoder.decode(it, StandardCharsets.UTF_8.toString()) }
                    ?: ""
            val videoUrl = backStackEntry.arguments?.getString("videoUrl") ?: ""
            val sessionId = backStackEntry.arguments?.getString("session")
            val session = PlayerSessionStore.get(sessionId)
            PlayerScreen(
                videoId = videoId,
                displayTitle = playerTitle,
                videoUrl = videoUrl,
                episodes = session?.episodes.orEmpty(),
                currentEpisodeIndex = session?.currentEpisodeIndex ?: 0,
                onPlayNext = { nextTitle, nextPlaylist, nextIndex ->
                    val encodedTitle = URLEncoder.encode(nextTitle, StandardCharsets.UTF_8.toString())
                    val encodedUrl = URLEncoder.encode(nextPlaylist, StandardCharsets.UTF_8.toString())
                    val nextSessionId = PlayerSessionStore.put(
                        PlayerSession(
                            episodes = session?.episodes.orEmpty(),
                            currentEpisodeIndex = nextIndex
                        )
                    )
                    navController.navigate(
                        "player/$videoId/$encodedTitle/$encodedUrl?session=$nextSessionId"
                    ) {
                        popUpTo("player/{videoId}/{playerTitle}/{videoUrl}?session={session}") {
                            inclusive = true
                        }
                    }
                },
                onBack = { navController.popBackStack() }
            )
        }
    }
}

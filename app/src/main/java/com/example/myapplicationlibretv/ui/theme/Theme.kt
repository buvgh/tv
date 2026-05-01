package com.example.myapplicationlibretv.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.runtime.SideEffect
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = Ember,
    onPrimary = Night,
    secondary = Clay,
    onSecondary = Night,
    tertiary = Redwood,
    background = Night,
    surface = Ink,
    surfaceVariant = Color(0xFF2A2320),
    onSurface = Mist,
    onSurfaceVariant = Color(0xFFD0C3B6),
    outline = Color(0xFF7D6E63)
)

private val LightColorScheme = lightColorScheme(
    primary = Redwood,
    onPrimary = Color.White,
    secondary = Clay,
    onSecondary = Ink,
    tertiary = Ember,
    background = Mist,
    surface = Color.White,
    surfaceVariant = Sand,
    onSurface = Ink,
    onSurfaceVariant = Slate,
    outline = Color(0xFFB8A598)
)

@Composable
fun MyApplicationLibreTVTheme(
    darkTheme: Boolean = false,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        DarkColorScheme
    } else {
        LightColorScheme
    }
    val view = LocalView.current

    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            window.statusBarColor = colorScheme.surface.toArgb()
            window.navigationBarColor = colorScheme.surface.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}

package com.onekey.core.presentation.theme

import android.app.Activity
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF1A56DB),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E4FF),
    onPrimaryContainer = Color(0xFF001C57),
    secondary = Color(0xFF0694A2),
    onSecondary = Color.White,
    tertiary = Color(0xFFE02424),
    background = Color(0xFFF8FAFF),
    onBackground = Color(0xFF1A1C24),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1A1C24),
    surfaceVariant = Color(0xFFEEF2FF),
    onSurfaceVariant = Color(0xFF44464F),
    outline = Color(0xFF74777F),
)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF6C9FFF),
    onPrimary = Color(0xFF00215D),
    primaryContainer = Color(0xFF003399),
    onPrimaryContainer = Color(0xFFDAE2FF),
    secondary = Color(0xFF4ECDC4),
    onSecondary = Color(0xFF003737),
    tertiary = Color(0xFFFF6B6B),
    background = Color(0xFF0E1117),
    onBackground = Color(0xFFE2E2EC),
    surface = Color(0xFF1A1F2E),
    onSurface = Color(0xFFE2E2EC),
    surfaceVariant = Color(0xFF252A3A),
    onSurfaceVariant = Color(0xFFC4C6D0),
    outline = Color(0xFF8E9099),
)

@Composable
fun OneKeyTheme(darkTheme: Boolean = false, content: @Composable () -> Unit) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        typography = Typography(),
    ) {
        Surface(
            color = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground,
            content = content,
        )
    }
}

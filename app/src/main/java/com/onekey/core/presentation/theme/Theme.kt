package com.onekey.core.presentation.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

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
    secondary = Color(0xFF4ECDC4),
    tertiary = Color(0xFFFF6B6B),
    background = Color(0xFF0E1117),
    surface = Color(0xFF1A1F2E),
    surfaceVariant = Color(0xFF252A3A),
)

@Composable
fun OneKeyTheme(darkTheme: Boolean = false, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        typography = Typography(),
        content = content,
    )
}

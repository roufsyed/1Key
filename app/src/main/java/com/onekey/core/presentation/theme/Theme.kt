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
    secondaryContainer = Color(0xFFE3E6EC),
    onSecondaryContainer = Color(0xFF1A1C24),
    tertiary = Color(0xFFE02424),
    background = Color(0xFFF8FAFF),
    onBackground = Color(0xFF1A1C24),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1A1C24),
    surfaceVariant = Color(0xFFEEF2FF),
    onSurfaceVariant = Color(0xFF44464F),
    outline = Color(0xFF74777F),
    // surfaceContainer* drives NavigationBar, ModalBottomSheet, Menu, etc.
    // Without these overrides M3 falls back to its baseline (purple-derived)
    // tokens, which read as a faint pinkish tint against the cool-neutral body.
    // Pinned to a cool-grey ladder so chrome reads as elevation, not a hue shift.
    surfaceContainerLowest = Color(0xFFF8FAFF),
    surfaceContainerLow = Color(0xFFF8F9FB),
    surfaceContainer = Color(0xFFF2F4F7),
    surfaceContainerHigh = Color(0xFFE5E7EB),
    surfaceContainerHighest = Color(0xFFE5E7EB),
)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF6C9FFF),
    onPrimary = Color(0xFF00215D),
    primaryContainer = Color(0xFF003399),
    onPrimaryContainer = Color(0xFFDAE2FF),
    secondary = Color(0xFF4ECDC4),
    onSecondary = Color(0xFF003737),
    secondaryContainer = Color(0xFF343842),
    onSecondaryContainer = Color(0xFFFFFFFF),
    tertiary = Color(0xFFFF6B6B),
    background = Color(0xFF1E1F24),
    onBackground = Color(0xFFFFFFFF),
    surface = Color(0xFF25272D),
    onSurface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFF343842),
    onSurfaceVariant = Color(0xFFC9CDD4),
    outline = Color(0xFF4A505A),
    // surfaceContainer* drives NavigationBar, ModalBottomSheet, Menu, etc.
    // Pinned to neutral greys (background -> card -> elevated) so M3's
    // default tonal overlay from `primary` does not bleed a blue tint
    // into the chrome.
    surfaceContainerLowest = Color(0xFF1E1F24),
    surfaceContainerLow = Color(0xFF25272D),
    surfaceContainer = Color(0xFF25272D),
    surfaceContainerHigh = Color(0xFF343842),
    surfaceContainerHighest = Color(0xFF343842),
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

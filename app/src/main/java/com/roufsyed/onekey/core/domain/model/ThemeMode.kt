package com.roufsyed.onekey.core.domain.model

import android.content.res.Configuration
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable

/**
 * User's chosen theme preference. The repository persists this directly;
 * surfaces resolve it to a concrete dark/light Boolean at render time so the
 * `SYSTEM` choice reacts to live system-theme changes via Compose's
 * [isSystemInDarkTheme].
 */
enum class ThemeMode(val label: String) {
    /** Follow the device's system dark/light setting. New-install default. */
    SYSTEM("Follow system"),
    LIGHT("Light"),
    DARK("Dark"),
}

/**
 * Compose-side resolver. Recomposes when [isSystemInDarkTheme] flips so a
 * device-wide theme change is reflected immediately on every screen.
 */
@Composable
fun ThemeMode.isDark(): Boolean = when (this) {
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
}

/**
 * Non-Compose resolver, used only for the one-shot `runBlocking` initial-value
 * computation in `MainActivity` and the autofill activities. The Compose
 * resolver (above) takes over for subsequent recompositions.
 */
fun ThemeMode.isDarkFromConfig(configuration: Configuration): Boolean = when (this) {
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
    ThemeMode.SYSTEM ->
        (configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
}

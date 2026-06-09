package com.onekey.core.presentation.util

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable

/**
 * `TopAppBarColors` pinned to `colorScheme.surfaceContainer`, the same token
 * M3 `NavigationBar` reads for the bottom nav chrome. Pinning the top bar to
 * this token gives a single chrome tier across both bars instead of the
 * default split (top bar on `surface`, bottom bar on `surfaceContainer`).
 *
 * Concrete colours in our palette:
 *
 *  - Light: `surfaceContainer = #F2F4F7` (vs M3 default `surface = #FFFFFF`)
 *  - Dark:  `surfaceContainer = #25272D` (same as `surface` in our dark
 *    scheme - visual no-op there)
 *
 * Both `containerColor` and `scrolledContainerColor` are pinned to remove
 * M3's automatic tint-on-scroll, which would otherwise lift the bar to a
 * different tier the moment the user scrolls and recreate the chrome split.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun oneKeyTopBarColors(): TopAppBarColors = TopAppBarDefaults.topAppBarColors(
    containerColor = MaterialTheme.colorScheme.surfaceContainer,
    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
)

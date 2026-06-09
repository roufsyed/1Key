package com.onekey.core.presentation.util

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable

/**
 * `TopAppBarColors` that paint the bar with the same colour as the Scaffold body
 * (`colorScheme.background`) so credential-list and category screens read as one
 * uninterrupted surface, with no visible boundary between the bar and the list
 * beneath it.
 *
 * Without this override, M3's default `TopAppBar` uses `colorScheme.surface`,
 * which differs from `colorScheme.background` in our palette by a few percent
 * lightness and produces a visible "bar sitting on top of the body" effect now
 * that the credential rows and `TagRow`s themselves are transparent.
 *
 * Both `containerColor` and `scrolledContainerColor` are pinned to background -
 * the bar still physically collapses on scroll (via the screen's scroll
 * behaviour), it just doesn't tint when scrolled. That's the right behaviour
 * here because there's no card-on-bg contrast to telegraph elevation against
 * anyway.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun flatTopAppBarColors(): TopAppBarColors = TopAppBarDefaults.topAppBarColors(
    containerColor = MaterialTheme.colorScheme.background,
    scrolledContainerColor = MaterialTheme.colorScheme.background,
)

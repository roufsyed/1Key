package com.roufsyed.onekey.core.presentation.lockaware

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBoxScope
import androidx.compose.material3.MenuDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties

/**
 * Drop-in replacement for [androidx.compose.material3.DropdownMenu]. Wraps the
 * menu surface in [Modifier.lockAware] so menu-item taps inside the popup
 * window keep the inactivity timer alive.
 */
@Composable
fun LockAwareDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset(0.dp, 0.dp),
    scrollState: ScrollState = rememberScrollState(),
    properties: PopupProperties = PopupProperties(focusable = true),
    content: @Composable ColumnScope.() -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier.lockAware(),
        offset = offset,
        scrollState = scrollState,
        properties = properties,
        content = content,
    )
}

/**
 * Lock-aware twin of [ExposedDropdownMenuBoxScope.ExposedDropdownMenu]. Must be
 * called from inside an `ExposedDropdownMenuBox` body, exactly like the M3
 * original - receiver type is preserved so use-site syntax doesn't change.
 *
 * Why this is a separate wrapper from [LockAwareDropdownMenu]: the M3 component
 * is scope-bound to `ExposedDropdownMenuBoxScope` (it relies on the box's
 * internal anchor / focus plumbing). Re-implementing as a non-scope-bound menu
 * would break sizing and keyboard dismissal.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExposedDropdownMenuBoxScope.LockAwareExposedDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    scrollState: ScrollState = rememberScrollState(),
    matchTextFieldWidth: Boolean = true,
    shape: Shape = MenuDefaults.shape,
    containerColor: Color = MenuDefaults.containerColor,
    tonalElevation: Dp = MenuDefaults.TonalElevation,
    shadowElevation: Dp = MenuDefaults.ShadowElevation,
    border: BorderStroke? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    ExposedDropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier.lockAware(),
        scrollState = scrollState,
        matchTextFieldWidth = matchTextFieldWidth,
        shape = shape,
        containerColor = containerColor,
        tonalElevation = tonalElevation,
        shadowElevation = shadowElevation,
        border = border,
        content = content,
    )
}

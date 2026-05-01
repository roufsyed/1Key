package com.onekey.core.presentation.lockaware

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.window.DialogProperties

/**
 * Drop-in replacement for [androidx.compose.material3.AlertDialog] that wires
 * the surface modifier through [Modifier.lockAware], pinging
 * [LocalUserActivityPing] on touches and key events that land inside the
 * dialog's window.
 *
 * Why this exists: the M3 `AlertDialog` renders in its own native Window. Touches
 * inside it never trigger `Activity.onUserInteraction()`, so the inactivity
 * timer drains while the user is reading / pressing buttons / interacting. This
 * wrapper closes that gap.
 *
 * Signature is intentionally identical to [androidx.compose.material3.AlertDialog]
 * (M3 1.3.x) so migrating an existing call site is just an import swap. Every
 * default sources from `AlertDialogDefaults` directly to track Material updates.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LockAwareDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: @Composable (() -> Unit)? = null,
    icon: @Composable (() -> Unit)? = null,
    title: @Composable (() -> Unit)? = null,
    text: @Composable (() -> Unit)? = null,
    shape: Shape = AlertDialogDefaults.shape,
    containerColor: Color = AlertDialogDefaults.containerColor,
    iconContentColor: Color = AlertDialogDefaults.iconContentColor,
    titleContentColor: Color = AlertDialogDefaults.titleContentColor,
    textContentColor: Color = AlertDialogDefaults.textContentColor,
    tonalElevation: Dp = AlertDialogDefaults.TonalElevation,
    properties: DialogProperties = DialogProperties(),
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = confirmButton,
        modifier = modifier.lockAware(),
        dismissButton = dismissButton,
        icon = icon,
        title = title,
        text = text,
        shape = shape,
        containerColor = containerColor,
        iconContentColor = iconContentColor,
        titleContentColor = titleContentColor,
        textContentColor = textContentColor,
        tonalElevation = tonalElevation,
        properties = properties,
    )
}

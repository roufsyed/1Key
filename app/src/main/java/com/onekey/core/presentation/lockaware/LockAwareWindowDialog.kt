package com.onekey.core.presentation.lockaware

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Drop-in replacement for [androidx.compose.ui.window.Dialog] that injects
 * [Modifier.lockAware] into the dialog's content root.
 *
 * Used for the cases where the M3 [androidx.compose.material3.AlertDialog] (and
 * thus [LockAwareDialog]) doesn't fit — full-bleed custom content, multi-phase
 * dialogs, anything that needs `usePlatformDefaultWidth = false` and a custom
 * Card/Surface as the body. The import-preview dialog in `BackupScreen` is the
 * canonical example.
 *
 * Layout-transparent: the wrapping `Box` adopts the size of its child, so the
 * wrapped content's existing `fillMaxWidth(0.95f)` etc. behave the same as if
 * called directly under `Dialog`. Adding the modifier at the Box level catches
 * touches anywhere inside the content.
 */
@Composable
fun LockAwareWindowDialog(
    onDismissRequest: () -> Unit,
    properties: DialogProperties = DialogProperties(),
    content: @Composable () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = properties,
    ) {
        Box(modifier = Modifier.lockAware()) {
            content()
        }
    }
}

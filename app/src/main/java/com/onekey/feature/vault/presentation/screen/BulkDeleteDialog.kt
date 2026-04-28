package com.onekey.feature.vault.presentation.screen

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/**
 * Reframed delete confirmation. When the recycle bin is enabled it offers
 * Move to bin / Delete now / Cancel. When the bin is disabled the destructive
 * choice collapses to a single Delete button so the user isn't offered a
 * "move to bin" option that doesn't apply.
 */
@Composable
fun BulkDeleteDialog(
    count: Int,
    binEnabled: Boolean,
    onMoveToBin: () -> Unit,
    onDeleteNow: () -> Unit,
    onCancel: () -> Unit,
) {
    val title = if (count == 1) "Delete this credential?" else "Delete $count credentials?"
    val subtitle = if (binEnabled) {
        if (count == 1) "It moves to the recycle bin. You can restore it within 30 days."
        else "They move to the recycle bin. You can restore them within 30 days."
    } else {
        if (count == 1) "This is permanent — there's no recycle bin to restore from."
        else "This is permanent for all $count items — there's no recycle bin to restore from."
    }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title) },
        text = { Text(subtitle) },
        confirmButton = {
            if (binEnabled) {
                TextButton(onClick = onMoveToBin) { Text("Move to bin") }
            } else {
                TextButton(onClick = onDeleteNow) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        dismissButton = {
            Row {
                if (binEnabled) {
                    TextButton(onClick = onDeleteNow) {
                        Text("Delete now", color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
        },
    )
}

package com.onekey.feature.vault.presentation.screen

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/**
 * Reframed delete confirmation. Offers Move to bin (default), Delete now (irreversible),
 * and Cancel. Used for both single deletes (from the detail screen) and multi-select
 * deletes from list screens.
 */
@Composable
fun BulkDeleteDialog(
    count: Int,
    onMoveToBin: () -> Unit,
    onDeleteNow: () -> Unit,
    onCancel: () -> Unit,
) {
    val title = if (count == 1) "Delete this credential?" else "Delete $count credentials?"
    val subtitle = if (count == 1) {
        "It moves to the recycle bin. You can restore it within 30 days."
    } else {
        "They move to the recycle bin. You can restore them within 30 days."
    }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title) },
        text = { Text(subtitle) },
        confirmButton = {
            TextButton(onClick = onMoveToBin) { Text("Move to bin") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDeleteNow) {
                    Text("Delete now", color = MaterialTheme.colorScheme.error)
                }
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
        },
    )
}

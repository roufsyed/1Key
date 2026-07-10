package com.roufsyed.onekey.feature.vault.presentation.screen

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import com.roufsyed.onekey.core.presentation.lockaware.LockAwareDialog
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Two-option conflict dialog shown when the user tries to restore a recycle-bin item
 * whose (title, username) matches an existing active credential. Mirrors the import
 * flow's merge-vs-keep-separate language so users see one consistent pattern.
 */
@Composable
fun RestoreConflictDialog(
    title: String,
    username: String,
    onMerge: () -> Unit,
    onKeepBoth: () -> Unit,
    onCancel: () -> Unit,
) {
    val displayedSubtitle = if (username.isNotBlank()) "$title - $username" else title
    LockAwareDialog(
        onDismissRequest = onCancel,
        icon = { Icon(Icons.Default.SwapHoriz, contentDescription = null) },
        title = { Text("Already in your vault") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    displayedSubtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "An active credential with the same title and username already exists. " +
                        "What should we do with the one you're restoring?",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "• Merge: keep your current values, only fill in fields the existing " +
                        "item didn't have. The bin copy is deleted.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "• Keep both: restore the bin copy as a separate item alongside the existing one.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onMerge) { Text("Merge") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onKeepBoth) { Text("Keep both") }
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
        },
    )
}

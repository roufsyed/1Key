package com.onekey.feature.settings.presentation.dialog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.onekey.core.presentation.lockaware.LockAwareDialog

/**
 * Soft-block confirmation shown when the user tries to navigate back
 * from a kit-save surface (the Settings re-download screen or the
 * inlined onboarding kit-save step) without having actually saved the
 * Emergency Kit on this device. Explains the consequence and offers
 * two paths.
 *
 *  - "Continue saving" dismisses the dialog and returns the user to the
 *    kit-save screen so they can complete the save.
 *  - "Leave anyway" calls [onLeaveAnyway]. The parent decides what
 *    that means: from the Settings entry point it pops back to the SK
 *    Settings screen (SK stays enabled, kit remains unsaved on this
 *    device, user can return via Re-download Emergency Kit). From the
 *    onboarding entry point it advances past the kit-save gate to the
 *    Vault Ready page (vault is already committed, SK is in the
 *    Keystore, the user accepts the risk of being unable to restore
 *    backups on a new device until they re-render the kit from
 *    Settings).
 *
 * The destructive option is rendered as a TextButton in error styling
 * (not a filled Button) so the visual weight steers the user toward
 * the safe "Continue saving" action by default.
 */
@Composable
fun LeaveKitSaveWarningDialog(
    onContinueSaving: () -> Unit,
    onLeaveAnyway: () -> Unit,
) {
    LockAwareDialog(
        onDismissRequest = onContinueSaving,
        icon = {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
        },
        title = {
            Text(
                "Leave without saving?",
                color = MaterialTheme.colorScheme.error,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "If you leave without saving your Emergency Kit, V5 " +
                        "backups taken on this device cannot be restored on " +
                        "any new device or after a factory reset.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "You can come back to this screen later from " +
                        "Settings > Security > Secret Key > Re-download " +
                        "Emergency Kit. Your Secret Key is already protected " +
                        "in this phone's secure hardware - the kit is the " +
                        "only OFF-device copy.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(onClick = onContinueSaving) {
                Text("Continue saving")
            }
        },
        dismissButton = {
            TextButton(onClick = onLeaveAnyway) {
                Text(
                    "Leave anyway",
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
    )
}

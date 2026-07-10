package com.roufsyed.onekey.feature.settings.presentation.dialog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NoEncryption
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.roufsyed.onekey.core.presentation.lockaware.LockAwareDialog

/**
 * Second-confirm dialog presented when a user taps "Continue without Secret Key"
 * on the onboarding Secret Key ceremony step. Locked design: opt-out path
 * requires a destructive second-confirm with an explicit acknowledgement
 * checkbox gating the destructive Skip button.
 *
 * # Why a second confirm
 *
 * The Secret Key default-on stance is the project's recommended setting. Users
 * can still skip, but the skip MUST be a deliberate gesture and not an
 * accidental "Skip" tap on the ceremony step. The acknowledgement checkbox
 * keeps the destructive Skip button disabled until the user actively ticks
 * "I understand the risks", matching the kit-save acknowledgement idiom in
 * [com.roufsyed.onekey.feature.settings.presentation.screen.EmergencyKitSavePromptScreen].
 *
 * Visual idiom borrowed from [SecretKeyDisableConfirmDialog]: error-coloured
 * title and destructive Skip button so a returning user recognises the
 * shape of "drops the Secret Key protection".
 *
 * # Why a LockAwareDialog
 *
 * Mirrors every other dialog this feature paints. Onboarding does not have
 * the inactivity-timer concern an unlocked-vault dialog has, but the
 * UnsafeUnlockableSurface lint rule still requires LockAwareDialog for any
 * dialog rendered in an authentication-adjacent flow.
 *
 * # Caller contract
 *
 * - [onConfirm] fires only when the user ticks "I understand the risks" AND
 *   presses Skip Secret Key. The parent (OnboardingScreen) then:
 *     * Drops the generated SK from the holder (no setBytes call).
 *     * Calls AuthViewModel.setupSkippingSecretKey, which writes
 *       `sk_opted_out=true` and derives the verifier without an SK.
 * - [onDismiss] returns the user to the ceremony step unchanged - the SK
 *   stays loaded and the kit-save flow can still be completed.
 *
 * Internal state: the acknowledgement checkbox is held in a local
 * `remember { mutableStateOf(false) }`. The dialog resets on every fresh
 * mount (re-opening after a dismiss starts with the box unchecked), which
 * is the intended behaviour - the deliberate-gesture contract requires the
 * user to re-acknowledge every time.
 */
@Composable
fun SecretKeySkipOnboardingDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    var acknowledged by remember { mutableStateOf(false) }

    LockAwareDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.NoEncryption,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
        },
        title = {
            Text(
                "Are you sure?",
                color = MaterialTheme.colorScheme.error,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "A strong Master Password is your first line of defense.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "A Secret Key provides an additional 128-bit layer of " +
                        "protection. If your Master Password is weak, reused, " +
                        "or discovered in a data breach, the Secret Key helps " +
                        "prevent attackers from successfully cracking your " +
                        "exported backups.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(
                    modifier = Modifier.toggleable(
                        value = acknowledged,
                        onValueChange = { acknowledged = it },
                        role = Role.Checkbox,
                    ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Checkbox(
                        checked = acknowledged,
                        // Row's toggleable owns the click; passing null disables
                        // Checkbox's own click handler so taps on the box and on
                        // the label flip the same state without double-firing.
                        onCheckedChange = null,
                    )
                    Text(
                        "I understand the risks",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = acknowledged,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) {
                Text("Skip Secret Key")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Keep Secret Key")
            }
        },
    )
}

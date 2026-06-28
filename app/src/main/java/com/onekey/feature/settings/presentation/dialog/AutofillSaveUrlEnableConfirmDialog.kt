package com.onekey.feature.settings.presentation.dialog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
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
import com.onekey.core.presentation.lockaware.LockAwareDialog

/**
 * Confirmation dialog presented when a user flips the "Save URL on cross-host
 * fills" toggle in Settings -> Autofill from OFF to ON. The toggle exposes a
 * convenience feature that, once enabled, lets the user tick a per-action
 * checkbox in the cross-host confirmation pane to permanently bind a
 * credential's URL field to the form's host. Because that bind is permanent
 * and 1Key cannot verify a site's legitimacy, enabling the feature is a
 * deliberate acceptance of personal responsibility for verifying each site
 * before saving its URL.
 *
 * Visual idiom borrowed from [SecretKeySkipOnboardingDialog]: error-tinted
 * warning icon and a checkbox-gated confirm button so the destructive enable
 * action requires an explicit "I understand" acknowledgement rather than a
 * single tap. The disclaimer paragraph lives here rather than in the Settings
 * row supporting text so the user reads it once, in a focused dialog, instead
 * of skimming past it on the Settings page.
 *
 * Caller contract:
 * - [onConfirm] fires only when the user ticks the acknowledgement AND taps
 *   Enable. The Settings screen flips the persisted pref to true in response.
 * - [onDismiss] fires on Cancel or back-press. The pref stays at its prior
 *   value (typically false, since the dialog only shows on OFF -> ON).
 *
 * The acknowledgement checkbox state lives in a local `remember`, so a
 * subsequent open of the dialog always starts unticked - the deliberate
 * gesture must be repeated every time. Turning the feature OFF does not
 * route through this dialog; that path is unrestricted.
 */
@Composable
fun AutofillSaveUrlEnableConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    var acknowledged by remember { mutableStateOf(false) }

    LockAwareDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
        },
        title = {
            Text(
                "Enable inline URL save?",
                color = MaterialTheme.colorScheme.error,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "1Key cannot verify a website's legitimacy. When this feature is on, " +
                        "the cross-host confirmation pane shows a checkbox that, if ticked, " +
                        "permanently associates the credential being used with the current " +
                        "site's URL.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "Saving a URL from a phishing site will leave that credential locked " +
                        "to the attacker's domain. The real site will no longer auto-suggest " +
                        "the credential, and future fills on the phishing host will happen " +
                        "without further confirmation.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "By enabling this, you accept sole responsibility for verifying every " +
                        "site is genuine before ticking the save-URL checkbox. 1Key holds no " +
                        "liability for credentials linked to malicious URLs through this " +
                        "feature.",
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
                        "I've read this carefully and understand what I am doing",
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
                Text("Enable")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

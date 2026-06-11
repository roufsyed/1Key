package com.onekey.feature.settings.presentation.dialog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.onekey.core.presentation.lockaware.LockAwareDialog
import com.onekey.core.presentation.lockaware.SecurePasswordTextField
import com.onekey.core.presentation.lockaware.rememberSecurePasswordFieldState

/**
 * Confirms disabling the Secret Key feature on this device. Disabling drops
 * the active SK and re-derives the verifier under MP alone, returning the
 * device to the same protection model the rest of the v3 codebase uses.
 *
 * Why the warning copy emphasises backups:
 *  - Existing V5 backup files marked FLAGS bit 0 require the SK to decrypt.
 *    After disable the SK is gone from this device's Keystore and cannot be
 *    re-derived. Restoring such a backup later requires the user to type
 *    the SK from their Emergency Kit (or scan the QR).
 *
 * The dialog mirrors [SecretKeyRotateConfirmDialog] in structure:
 *  - LockAwareDialog for IME-pinning and inactivity-timer keepalive.
 *  - Error-styled destructive confirm button.
 *  - Master password reauth field with visibility toggle.
 *  - errorAttemptsRemaining parameter for parent-driven retry rendering.
 *
 * Caller contract for [onConfirm] is identical to [SecretKeyRotateConfirmDialog]:
 * the CharArray ownership transfers to the caller, which MUST zero it.
 */
@Composable
fun SecretKeyDisableConfirmDialog(
    onConfirm: (CharArray) -> Unit,
    onDismiss: () -> Unit,
    isVerifying: Boolean = false,
    errorAttemptsRemaining: Int? = null,
) {
    val passwordState = rememberSecurePasswordFieldState()
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    val isError = errorAttemptsRemaining != null

    LockAwareDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.LockOpen,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
        },
        title = {
            Text(
                "Disable Secret Key?",
                color = MaterialTheme.colorScheme.error,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Turning Secret Key off drops the 128-bit Secret Key from " +
                        "this device and re-derives your verifier from your " +
                        "master password alone. This device returns to " +
                        "single-factor key derivation.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "Backup files exported while Secret Key was enabled stay " +
                        "tied to that Secret Key. You will need to scan or " +
                        "type your Emergency Kit before you can restore one " +
                        "of those backups.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(
                    "You can re-enable Secret Key at any time. A future enable " +
                        "generates a fresh 128-bit value - it does not restore " +
                        "the one you are disabling now.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SecurePasswordTextField(
                    state = passwordState,
                    enabled = !isVerifying,
                    label = { Text("Master password") },
                    isError = isError,
                    supportingText = if (isError) {
                        {
                            // isError is set iff errorAttemptsRemaining != null,
                            // so the !! is safe and the compiler folds the
                            // null branch out of the bytecode.
                            val remaining = errorAttemptsRemaining!!
                            Text(
                                if (remaining <= 1)
                                    "Incorrect password - 1 attempt remaining before vault locks."
                                else
                                    "Incorrect password - $remaining attempts remaining.",
                            )
                        }
                    } else null,
                    visualTransformation = if (passwordVisible) VisualTransformation.None
                                           else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                if (passwordVisible) Icons.Default.VisibilityOff
                                else Icons.Default.Visibility,
                                contentDescription = if (passwordVisible) "Hide password" else "Show password",
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(passwordState.consume()) },
                enabled = !isVerifying && !passwordState.isEmpty,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
                // Stable width across the idle->loading swap. See the
                // matching modifier on SecretKeyEnableConfirmDialog and
                // SecretKeyRotateConfirmDialog - same 200.dp floor.
                modifier = Modifier.widthIn(min = 200.dp),
            ) {
                // While the disable migration runs (Argon2id derive +
                // re-derive verifier under MP alone), swap the Icon+label
                // pair for an in-button spinner so the user has a clear
                // "your tap is being processed" signal. Spinner colour
                // mirrors contentColor (onError) so it remains visible
                // against the error-tinted container.
                if (isVerifying) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onError,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                    )
                    Text("  Disable Secret Key")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isVerifying) {
                Text("Cancel")
            }
        },
    )
}

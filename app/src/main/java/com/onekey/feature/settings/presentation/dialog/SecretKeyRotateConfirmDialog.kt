package com.onekey.feature.settings.presentation.dialog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Autorenew
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
 * Confirms a Secret Key rotation. Rotation generates a fresh SK, drops the
 * old one, and re-derives the verifier - so previously-exported V5 backup
 * files become uniquely tied to the OLD SK and unrecoverable without it.
 * The user has to print a new Emergency Kit immediately after the rotate
 * lands; the post-rotate flow navigates to [com.onekey.feature.settings.presentation.screen.EmergencyKitSavePromptScreen]
 * with a required "I saved my kit" checkbox.
 *
 * Why a [LockAwareDialog] (not [androidx.compose.material3.AlertDialog]):
 *  - The rotate flow includes a master-password field. The IME-aware
 *    LockAware variant pins the button row above the keyboard on devices
 *    where the platform AlertDialog clips it.
 *  - The lock-aware modifier keeps the inactivity timer alive while the
 *    user types their master password. Without it a long password entry
 *    could trip auto-lock and dump the user back to the Lock screen
 *    mid-dialog.
 *
 * Why a destructive-styled confirm button:
 *  - The error-coloured Button visually signals "this is consequential".
 *    Style follows the existing screenshot-enable confirmation in
 *    [com.onekey.feature.settings.presentation.screen.SettingsSecurityScreen].
 *
 * Caller contract:
 *  - [onConfirm] receives a freshly-allocated CharArray with the typed
 *    master password. The caller takes ownership and MUST zero it via
 *    `pwd.fill(' ')` in a finally block (typically by handing it straight
 *    to the ViewModel, which zeros in finally).
 *  - [errorAttemptsRemaining] non-null indicates the last attempt was
 *    rejected. The dialog surfaces the message and the field re-enables.
 *  - [isVerifying] disables the field and both buttons while the migration
 *    runs in the background.
 *
 * @param errorAttemptsRemaining null on first open and after dismiss.
 *   When the parent VM emits a wrong-password event, the parent passes
 *   the remaining count here; the field re-enables in the error styling.
 *   Pass `0` (or any non-null value) to keep the dialog open and surface
 *   the generic "Incorrect password" message; null means "no error yet".
 */
@Composable
fun SecretKeyRotateConfirmDialog(
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
                imageVector = Icons.Default.Autorenew,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
        },
        title = {
            Text(
                "Rotate Secret Key?",
                color = MaterialTheme.colorScheme.error,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Rotating your Secret Key generates a brand new 128-bit value " +
                        "and drops the old one. Anyone who only had the old Secret " +
                        "Key can no longer derive your verifier - even with your " +
                        "master password.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "After rotating you MUST save a fresh Emergency Kit. Backup " +
                        "files exported before this rotate are still tied to the " +
                        "OLD Secret Key and cannot be restored without it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(
                    "Existing backups are NOT modified by this action. Only future " +
                        "exports will use the new Secret Key.",
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
                // Stable lower bound on width so the button does not
                // shrink during isVerifying when the spinner replaces
                // the icon-plus-label content. Same convention as the
                // other SK confirm dialogs.
                modifier = Modifier.widthIn(min = 200.dp),
            ) {
                // While the rotate migration runs (Argon2id derive +
                // re-encrypt all credentials), swap the Icon+label pair
                // for an in-button spinner so the user has a clear "your
                // tap is being processed" signal. Spinner colour mirrors
                // contentColor (onError) so it remains visible against
                // the error-tinted container.
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
                    Text("  Rotate Secret Key")
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

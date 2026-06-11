package com.onekey.feature.settings.presentation.dialog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
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
 * Confirms enabling the Secret Key feature on an existing vault. Shown by
 * [com.onekey.feature.settings.presentation.screen.SettingsSecretKeyScreen]
 * after the user has read the pre-enable explainer and acknowledged the
 * trade-offs by tapping Continue.
 *
 * Mirrors [SecretKeyRotateConfirmDialog] / [SecretKeyDisableConfirmDialog]
 * shape (LockAwareDialog + IME-aware password field + verify spinner +
 * wrong-password retry surface) so the three dialogs feel like one family
 * to the user. The confirm button uses the default constructive styling
 * (no error tint) because enabling SK is not destructive - existing
 * backups continue to decrypt without the Emergency Kit, and only future
 * backups will require it.
 *
 * Caller contract:
 *  - [onConfirm] receives a freshly-allocated CharArray with the typed
 *    master password. The caller takes ownership and MUST zero it via
 *    `pwd.fill(' ')` in a finally block (typically by handing it straight
 *    to the ViewModel, which zeros in finally).
 *  - [errorAttemptsRemaining] non-null indicates the last attempt was
 *    rejected. The dialog surfaces the message and the field re-enables.
 *  - [isVerifying] disables the field and both buttons while
 *    [com.onekey.feature.secretkey.domain.SecretKeyEnableUseCase] runs in
 *    the background.
 *
 * @param errorAttemptsRemaining null on first open and after dismiss.
 *   When the parent VM emits a wrong-password event, the parent passes
 *   the remaining count here; the field re-enables in the error styling.
 */
@Composable
fun SecretKeyEnableConfirmDialog(
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
                imageVector = Icons.Default.Shield,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        title = { Text("Enable Secret Key") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Enter your master password to generate a 128-bit Secret Key " +
                        "and re-derive this device's verifier under it. The change " +
                        "is atomic - a crash mid-enable rolls back to the previous " +
                        "verifier with no data loss.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "After this dialog 1Key will render your Emergency Kit. You " +
                        "must save the kit before you can leave the next screen.",
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
                // Stable lower bound on width so the button does not
                // visually shrink when the spinner replaces the idle
                // icon-plus-label content. 200.dp fits the longest
                // label across the SK confirm-dialog family ("Enable
                // Secret Key" plus padding plus a leading icon).
                modifier = Modifier.widthIn(min = 200.dp),
            ) {
                // While the enable use case runs (Argon2id derive +
                // re-derive verifier under MP + SK), swap the Icon+label
                // pair for an in-button spinner so the user has a clear
                // "your tap is being processed" signal. Spinner colour
                // matches onPrimary because this button uses the default
                // constructive container colour (not error-tinted).
                if (isVerifying) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Shield,
                        contentDescription = null,
                    )
                    Text("  Enable Secret Key")
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

package com.roufsyed.onekey.feature.auth.presentation.dialog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.roufsyed.onekey.core.presentation.lockaware.LockAwareDialog
import com.roufsyed.onekey.core.presentation.lockaware.SecurePasswordFieldState
import com.roufsyed.onekey.core.presentation.lockaware.SecurePasswordTextField
import com.roufsyed.onekey.core.presentation.lockaware.rememberSecurePasswordFieldState

/**
 * Visual state surface for [MasterPasswordReauthDialog]. The caller owns the
 * verification call (typically through a ViewModel) so this dialog stays
 * presentation-pure: it does not know whether the password is verified
 * against a remote service, a local hash, or an Argon2id verifier blob.
 *
 *  - [Idle] - default. The user has not yet pressed "Verify" or the last
 *    attempt has been acknowledged. Verify button enabled iff the field is
 *    non-empty.
 *  - [Verifying] - the caller is running the verification asynchronously
 *    (Argon2id derivation typically takes 0.3-4 s). The field and buttons
 *    are disabled so the user cannot dispatch a second attempt mid-flight.
 *  - [Error] - the most recent attempt was rejected. The field is back to
 *    enabled, supporting text shows the remaining-attempts message in the
 *    error colour, and the dialog stays open. Submitting again clears the
 *    error state without recomposing the dialog.
 *
 * This is a sealed interface (not enum) so [Error] can carry `attemptsRemaining`
 * inline. Callers map their domain failure -> Error(remaining); the dialog
 * does no arithmetic of its own.
 */
sealed interface MasterPasswordReauthState {
    object Idle : MasterPasswordReauthState

    object Verifying : MasterPasswordReauthState

    /**
     * @param attemptsRemaining How many attempts the user has left before
     *                          the calling flow (typically the use case)
     *                          escalates - usually to a vault-lock event.
     *                          Pass `null` to skip the count line and just
     *                          show a generic "Incorrect password" message.
     */
    data class Error(val attemptsRemaining: Int?) : MasterPasswordReauthState
}

/**
 * Dialog used by any "Settings" surface that needs to reauthenticate the
 * user with their master password before performing a security-sensitive
 * action (rotating biometric, changing KDF preset, exporting raw bytes,
 * etc.).
 *
 * Contract:
 *  - The dialog renders a single password field with a visibility toggle,
 *    plus a confirm button labelled by [confirmButtonLabel] (default:
 *    "Verify") and a "Cancel" dismiss button.
 *  - Pressing confirm calls [onVerify] with a freshly-allocated CharArray
 *    holding the typed password. **The caller becomes the owner of that
 *    array and MUST zero it after use** (typically via `password.fill(' ')`
 *    in a `finally` block). The internal [SecurePasswordFieldState] is
 *    cleared as part of `consume()` so the JVM heap copy held by Compose
 *    is dropped at the same moment the caller takes ownership.
 *  - The dialog does not call any repository or use case directly. It
 *    surfaces user intent and lets the ViewModel decide what to do. This
 *    keeps it reusable across Settings surfaces that share the reauth UX
 *    but differ in what they do with the verified password.
 *
 *  - The caller drives lifecycle via [state]:
 *    - `Idle` -> field enabled, button enabled-when-non-empty, no error.
 *    - `Verifying` -> all interactive elements disabled while the
 *      verification call (and any downstream action) is in flight.
 *    - `Error(remaining)` -> field re-enabled, error styling on, supporting
 *      text shows "Incorrect password - N attempts remaining" (or the
 *      one-attempt variant). The dialog stays open so the user can retry
 *      without re-typing the action that triggered the dialog.
 *
 * The dialog does NOT dismiss itself on success: that's the caller's job,
 * because some flows want to keep the dialog open and swap content (e.g.
 * "verifying -> applying" inline progress). The caller hides the dialog by
 * removing it from the composition tree.
 *
 * # Why we expose a [SecurePasswordFieldState] vs. a String
 *
 * The field never returns its content as a `String` to the caller. The
 * surrounding [SecurePasswordTextField] holds the text only in a
 * composable-local `TextFieldValue`, and [SecurePasswordFieldState.consume]
 * empties that state at the moment of extraction. Routing a String through
 * the dialog API would defeat that: an interned String survives until the
 * JVM decides it's done with it (sometimes forever), and Compose's
 * snapshot system can hold extra defensive copies. Handing the caller a
 * CharArray reduces the heap residency of the password to one explicit
 * array the caller controls.
 *
 * # When NOT to use this dialog
 *
 * - Setup / unlock flows: those need to **set** the vault key, which is
 *   AuthViewModel's job, not a reusable dialog.
 * - PIN flows: PINs are 6 digits and use a different UX (per-digit boxes,
 *   no visibility toggle). Use the PIN-specific composables instead.
 */
@Composable
fun MasterPasswordReauthDialog(
    state: MasterPasswordReauthState,
    onVerify: (CharArray) -> Unit,
    onDismiss: () -> Unit,
    title: String,
    bodyTitleLine: String,
    bodyDetailLine: String,
    confirmButtonLabel: String = "Verify",
    cancelButtonLabel: String = "Cancel",
    modifier: Modifier = Modifier,
) {
    val passwordState: SecurePasswordFieldState = rememberSecurePasswordFieldState()
    var passwordVisible by rememberSaveable { mutableStateOf(false) }

    // `state` drives the visible error styling. We also re-render the
    // "Verifying" state as a disabled field; Compose recomposes anytime
    // the caller swaps state, so we don't need to thread the state into
    // the field's `enabled` parameter via remember-derived state.
    val isError = state is MasterPasswordReauthState.Error
    val isVerifying = state is MasterPasswordReauthState.Verifying

    // A stable lambda for the dismiss button so the dismissal logic is the
    // same whether triggered by Cancel, IME back, or click-outside. The
    // caller may decide to ignore dismiss while verifying (we don't enforce
    // that here - it's a UX policy, not a security one).
    val dismissAction = remember(onDismiss) { { onDismiss() } }

    LockAwareDialog(
        modifier = modifier,
        // Click-outside / back-gesture only dismisses when the dialog is
        // NOT actively verifying. Without this gate the Argon2id derive
        // coroutine survives the dismiss and races with the parent's
        // state machine - on a wrong-password retry the caller's
        // pendingChoice could be cleared before the WrongPassword event
        // lands, swallowing the error toast.
        onDismissRequest = { if (!isVerifying) dismissAction() },
        icon = { Icon(Icons.Default.Key, contentDescription = null) },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    bodyTitleLine,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    bodyDetailLine,
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
                            val remaining = (state as MasterPasswordReauthState.Error).attemptsRemaining
                            Text(
                                when {
                                    remaining == null -> "Incorrect password. Please try again."
                                    remaining <= 1 ->
                                        "Incorrect password - 1 attempt remaining before vault locks."
                                    else ->
                                        "Incorrect password - $remaining attempts remaining."
                                },
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
                onClick = {
                    // consume() empties the composable's internal
                    // TextFieldValue at the same moment we hand the caller
                    // the CharArray. The caller owns the array and MUST
                    // zero it after use (documented on this function).
                    onVerify(passwordState.consume())
                },
                enabled = !isVerifying && !passwordState.isEmpty,
                // Stable lower bound so the button does not visibly shrink
                // when the idle label is replaced by an 18.dp spinner.
                modifier = Modifier.widthIn(min = 160.dp),
            ) {
                // While the caller's verification call is in flight, swap
                // the button label for an in-button spinner so the user has
                // a clear "your tap is being processed" signal. Argon2id
                // derivation can take 0.3-4 s; without the spinner the
                // dialog looks frozen. Spinner color matches onPrimary
                // because the default Button contentColor is onPrimary.
                if (isVerifying) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text(confirmButtonLabel)
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = dismissAction,
                enabled = !isVerifying,
            ) {
                Text(cancelButtonLabel)
            }
        },
    )
}

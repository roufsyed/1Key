package com.onekey.core.presentation.lockaware

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.onekey.feature.auth.presentation.viewmodel.AuthUiState

private val PinSuccessColor = Color(0xFF4CAF50)

/**
 * Six-dot PIN entry section. Identical UX to the production `LockScreen`'s
 * `PinUnlockSection` — the only behavioural difference vs. the original is
 * the lockout-seconds source: this file delegates to [rememberLockoutSecondsRemaining]
 * so a single ticker implementation drives both the lock and autofill surfaces.
 *
 * Decoupled from `AuthViewModel` and `AppViewModel` — consumes only the
 * abstract [state], the lockout timestamp, and two callbacks. Same pattern
 * for `PasswordUnlockSection` below.
 *
 * @param state the unified `AuthUiState` driving error/loading/unlocked flips
 * @param lockoutUntilMs epoch-ms when the current PIN cooldown expires, or
 *   null when no cooldown is active. Must come from a tracker that emits the
 *   persisted value (PinAttemptTracker) — a frozen `now` will not work here.
 * @param onPinSubmit fires when the user has typed a 6-digit PIN (auto-submit
 *   pattern), or when they press the IME Done action with a non-empty PIN.
 * @param onFallbackToPassword routes the user to the master-password fallback,
 *   used by the "Forgot PIN?" button and (via callers) the automatic switch
 *   after 3 wrong PINs.
 */
@Composable
fun PinUnlockSection(
    state: AuthUiState,
    lockoutUntilMs: Long?,
    onPinSubmit: (String) -> Unit,
    onFallbackToPassword: () -> Unit,
    fallbackLabel: String = "Forgot PIN? Use master password",
) {
    var pin by remember { mutableStateOf("") }
    val isLoading = state is AuthUiState.Loading
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    val lockoutSecondsRemaining = rememberLockoutSecondsRemaining(lockoutUntilMs)
    val isLockedOut = lockoutSecondsRemaining > 0

    val density = LocalDensity.current
    val shakePx = remember(density) { with(density) { 16.dp.toPx() } }

    val shakeOffset = remember { Animatable(0f) }
    val dotScale = remember { Animatable(1f) }

    val dotFilledColor by animateColorAsState(
        targetValue = when (state) {
            is AuthUiState.Unlocked -> PinSuccessColor
            is AuthUiState.Error -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.primary
        },
        animationSpec = tween(durationMillis = 150),
        label = "dotFilledColor",
    )

    LaunchedEffect(state) {
        when (state) {
            is AuthUiState.Error -> {
                shakeOffset.animateTo(
                    targetValue = 0f,
                    animationSpec = keyframes {
                        durationMillis = 460
                        0f at 0
                        -shakePx at 65
                        shakePx at 130
                        (-shakePx * .70f) at 195
                        (shakePx * .70f) at 260
                        (-shakePx * .40f) at 325
                        (shakePx * .40f) at 390
                        0f at 460
                    }
                )
                pin = "" // Clear AFTER the shake so the red dots stay visible during it
            }
            is AuthUiState.Unlocked -> {
                keyboardController?.hide()
                focusManager.clearFocus(force = true)
                dotScale.animateTo(1.18f, tween(130))
                dotScale.animateTo(1.00f, tween(130))
            }
            else -> Unit
        }
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (isLockedOut) {
            LockoutBanner(
                title = "Too many wrong PINs",
                body = "Try again in ${lockoutSecondsRemaining}s, or use your master password.",
            )
            Spacer(Modifier.height(16.dp))
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.graphicsLayer {
                translationX = shakeOffset.value
                scaleX = dotScale.value
                scaleY = dotScale.value
            },
        ) {
            repeat(6) { index ->
                Surface(
                    shape = MaterialTheme.shapes.extraSmall,
                    color = if (index < pin.length) dotFilledColor
                    else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.size(14.dp),
                ) {}
            }
        }
        Spacer(Modifier.height(20.dp))
        LockAwareOutlinedTextField(
            value = pin,
            onValueChange = { new ->
                // Refuse new input while locked out OR while a submission is in flight.
                // We deliberately do NOT disable the field during Loading: a disabled
                // field loses focus and the IME dismisses, so on a wrong PIN the user
                // would have to tap the field again to bring the keypad back.
                if (isLockedOut || isLoading) return@LockAwareOutlinedTextField
                if (new.length <= 6 && new.all { it.isDigit() }) {
                    pin = new
                    if (new.length == 6) onPinSubmit(new)
                }
            },
            label = { Text("PIN") },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.NumberPassword,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = {
                if (pin.isNotEmpty() && !isLockedOut && !isLoading) onPinSubmit(pin)
            }),
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
            enabled = !isLockedOut,
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color.Transparent,
                unfocusedBorderColor = Color.Transparent,
                disabledBorderColor = Color.Transparent,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.70f),
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.56f),
                disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f),
            ),
        )
        if (isLoading) {
            Spacer(Modifier.height(16.dp))
            CircularProgressIndicator(Modifier.size(24.dp))
        }
        Spacer(Modifier.height(10.dp))
        TextButton(
            onClick = onFallbackToPassword,
            enabled = !isLoading,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        ) {
            Text(fallbackLabel)
        }
    }
}

/**
 * Master-password unlock section. Same decoupling rationale as [PinUnlockSection].
 *
 * @param state the unified `AuthUiState` driving error/loading/unlocked flips
 * @param lockoutUntilMs epoch-ms when the current password cooldown expires
 * @param submitButtonLabel default "Unlock vault"; autofill surfaces override
 *   to "Unlock and fill" / "Unlock and continue"
 * @param onPasswordSubmit consumed CharArray (caller fills with ' ' once done)
 */
@Composable
fun PasswordUnlockSection(
    state: AuthUiState,
    lockoutUntilMs: Long?,
    onPasswordSubmit: (CharArray) -> Unit,
    submitButtonLabel: String = "Unlock vault",
) {
    val passwordState = rememberSecurePasswordFieldState()
    var showPassword by remember { mutableStateOf(false) }
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    val density = LocalDensity.current
    val shakePx = remember(density) { with(density) { 16.dp.toPx() } }
    val shakeOffset = remember { Animatable(0f) }

    val lockoutSecondsRemaining = rememberLockoutSecondsRemaining(lockoutUntilMs)
    val isLockedOut = lockoutSecondsRemaining > 0

    LaunchedEffect(state) {
        when (state) {
            is AuthUiState.Error -> {
                passwordState.clear()
                shakeOffset.animateTo(
                    targetValue = 0f,
                    animationSpec = keyframes {
                        durationMillis = 460
                        0f at 0
                        -shakePx at 65
                        shakePx at 130
                        (-shakePx * .70f) at 195
                        (shakePx * .70f) at 260
                        (-shakePx * .40f) at 325
                        (shakePx * .40f) at 390
                        0f at 460
                    }
                )
            }
            is AuthUiState.Unlocked -> {
                keyboardController?.hide()
                focusManager.clearFocus(force = true)
            }
            else -> Unit
        }
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.graphicsLayer { translationX = shakeOffset.value },
    ) {
        if (isLockedOut) {
            LockoutBanner(
                title = "Too many failed attempts",
                body = "Try again in ${lockoutSecondsRemaining}s",
            )
            Spacer(Modifier.height(12.dp))
        }

        SecurePasswordTextField(
            state = passwordState,
            label = { Text("Master Password") },
            enabled = !isLockedOut,
            visualTransformation = if (showPassword) VisualTransformation.None
            else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = {
                if (!passwordState.isEmpty && !isLockedOut) onPasswordSubmit(passwordState.consume())
            }),
            trailingIcon = {
                IconButton(onClick = { showPassword = !showPassword }) {
                    Icon(
                        if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = null,
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
            isError = state is AuthUiState.Error && !isLockedOut,
            shape = RoundedCornerShape(16.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color.Transparent,
                unfocusedBorderColor = Color.Transparent,
                disabledBorderColor = Color.Transparent,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.70f),
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.56f),
                disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f),
            ),
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                if (!passwordState.isEmpty && !isLockedOut) onPasswordSubmit(passwordState.consume())
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            enabled = !passwordState.isEmpty && !isLockedOut && state !is AuthUiState.Loading,
            shape = RoundedCornerShape(18.dp),
        ) {
            if (state is AuthUiState.Loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp,
                )
            } else {
                Text(submitButtonLabel)
            }
        }
    }
}

/**
 * Compact error banner used by both unlock sections during a cooldown window.
 * Keep visually consistent — same shape, padding, and copy structure across
 * surfaces. The countdown re-renders via [rememberLockoutSecondsRemaining]
 * upstream; this composable just paints the current frame.
 */
@Composable
private fun LockoutBanner(title: String, body: String) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Timer,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(20.dp),
            )
            Column {
                Text(
                    title,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(
                    body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f),
                )
            }
        }
    }
}

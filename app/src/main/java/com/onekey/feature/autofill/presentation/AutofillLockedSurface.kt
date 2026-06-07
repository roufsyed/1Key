package com.onekey.feature.autofill.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.onekey.core.presentation.lockaware.PasswordUnlockSection
import com.onekey.core.presentation.lockaware.PinUnlockSection
import com.onekey.core.presentation.util.BiometricPromptController
import com.onekey.core.presentation.util.rememberCanUseBiometric
import com.onekey.core.security.LockReason
import com.onekey.feature.auth.presentation.viewmodel.AuthEvent
import com.onekey.feature.auth.presentation.viewmodel.AuthUiState
import com.onekey.feature.auth.presentation.viewmodel.AuthViewModel

/**
 * Shared lock surface for the autofill activities, replacing the per-activity
 * master-password-only panes. Mirrors the main [com.onekey.feature.auth.presentation.screen.LockScreen]
 * unlock decision tree exactly — biometric (auto-triggered if enabled), PIN
 * (if set up), master password (always available as fallback) — minus the
 * animated logo, morph transition, and full LockReason modal. The autofill
 * activity is a thin one-shot surface; the modal explanation is shown once
 * in the main app when the lockout fires, and re-showing it on every
 * autofill picker would train the user to dismiss security warnings.
 *
 * Mode selection follows the same priority order as `LockScreen`:
 *
 *  1. `mustUsePassword` (= `requiresMasterPasswordRecheck || lockReason != null`)
 *     → render password section, suppress biometric and PIN affordances.
 *  2. `isPinSetup && !forcePasswordFallback` → render PIN section + biometric
 *     trigger.
 *  3. Otherwise → render password section + biometric trigger.
 *
 * The auto-biometric trigger fires once per surface entry, gated on
 * `state is AuthUiState.Idle` so it won't fire during a recompose triggered
 * by `_state` flipping after a successful unlock. `rememberSaveable`
 * persistence prevents a rotation mid-prompt from re-firing it.
 *
 * `LockReason` and `requiresMasterPasswordRecheck` flips during the user's
 * stay on the screen are honoured live — the [BiometricPromptController]
 * cancels any in-flight prompt and the section flips to password-only.
 *
 * @param target friendly destination string ("github.com" / "com.acme.app")
 * @param headlineText e.g. "Unlock 1Key to fill" / "Unlock 1Key to save"
 * @param submitButtonLabel passed through to [PasswordUnlockSection]'s button
 * @param biometricController activity-scoped lifecycle holder for the
 *   BiometricPrompt (survives configuration change, cancels in `onDestroy`)
 * @param onAbort cancel-and-finish from the activity
 */
@Composable
fun AutofillLockedSurface(
    target: String,
    headlineText: String,
    submitButtonLabel: String,
    authViewModel: AuthViewModel,
    biometricController: BiometricPromptController,
    onAbort: () -> Unit,
) {
    val state by authViewModel.state.collectAsStateWithLifecycle()
    val isPinSetup by authViewModel.isPinSetup.collectAsStateWithLifecycle()
    val isBiometricEnabled by authViewModel.isBiometricEnabled.collectAsStateWithLifecycle()
    val requiresMasterPasswordRecheck by authViewModel.requiresMasterPasswordRecheck.collectAsStateWithLifecycle()
    val lockReason by authViewModel.lockReason.collectAsStateWithLifecycle()
    val passwordLockoutUntilMs by authViewModel.passwordLockoutUntilMs.collectAsStateWithLifecycle()
    val pinLockoutUntilMs by authViewModel.pinLockoutUntilMs.collectAsStateWithLifecycle()
    val biometricUnlockGate by authViewModel.biometricUnlockGate.collectAsStateWithLifecycle()
    val biometricAttemptsRemaining by authViewModel.biometricAttemptsRemaining.collectAsStateWithLifecycle()
    val canUseBiometric = rememberCanUseBiometric()

    // Local fallback flag: flipped by "Forgot PIN?" or PinAttemptsExhausted event.
    // rememberSaveable so a configuration change mid-fallback doesn't bounce the
    // user back to the PIN screen while they're typing their password.
    var forcePasswordFallback by rememberSaveable { mutableStateOf(false) }

    // Single-shot biometric auto-trigger flag. rememberSaveable so a rotation
    // does not re-fire the prompt under a user who already dismissed it.
    var autoTriggeredBiometric by rememberSaveable { mutableStateOf(false) }

    // Listen for the PinAttemptsExhausted event so the autofill surface flips
    // to the master-password fallback identically to the main LockScreen. The
    // VM emits this event from `unlockWithPin` after the 3rd wrong PIN.
    LaunchedEffect(Unit) {
        authViewModel.events.collect { event ->
            if (event is AuthEvent.PinAttemptsExhausted) {
                forcePasswordFallback = true
            }
        }
    }

    // Cancel any in-flight biometric prompt the moment a lock reason lands.
    // This handles the recordBiometricFailure → lock() → lockReason flips
    // path while the system prompt is still on screen.
    LaunchedEffect(lockReason) {
        if (lockReason != null) {
            biometricController.cancel()
        }
    }

    val biometricBlockedByLockReason = lockReason != null
    val mustUsePassword = requiresMasterPasswordRecheck || biometricBlockedByLockReason

    val context = LocalContext.current

    // Auto-trigger biometric exactly once per surface entry, when:
    //  - we haven't fired yet (rememberSaveable),
    //  - the unified atomic gate from AuthViewModel says biometric is OK
    //    (biometric pref on AND no lock reason — read together to avoid the
    //    cold-start race where one half flipped first),
    //  - the OS reports biometric is currently available, AND
    //  - the master-password recheck interval has not elapsed,
    //  - the VM is sitting in Idle (avoids re-fire during a Loading→Unlocked
    //    transition where the gate momentarily re-emits the same value).
    LaunchedEffect(biometricUnlockGate, requiresMasterPasswordRecheck, state) {
        if (!autoTriggeredBiometric &&
            state is AuthUiState.Idle &&
            biometricUnlockGate.biometricEnabled &&
            !biometricUnlockGate.lockReasonSet &&
            canUseBiometric &&
            !requiresMasterPasswordRecheck
        ) {
            autoTriggeredBiometric = true
            biometricController.show(
                onSuccess = { authViewModel.unlockWithBiometric() },
                onError = { msg -> authViewModel.setBiometricError(msg) },
                onAuthFailed = { authViewModel.recordBiometricFailure() },
            )
        }
    }

    val errorMessage = (state as? AuthUiState.Error)?.message

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = headlineText,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = "for $target",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Inline banners — they explain why the user is being forced to type
            // their master password. Two reasons may compose; show the more-
            // specific lock-reason copy when both are true.
            when {
                biometricBlockedByLockReason -> LockReasonBanner(lockReason!!)
                requiresMasterPasswordRecheck -> RecheckBanner()
            }

            Spacer(Modifier.height(4.dp))

            when {
                mustUsePassword -> {
                    PasswordUnlockSection(
                        state = state,
                        lockoutUntilMs = passwordLockoutUntilMs,
                        onPasswordSubmit = { authViewModel.unlockWithPassword(it) },
                        submitButtonLabel = submitButtonLabel,
                    )
                }
                isPinSetup && !forcePasswordFallback -> {
                    PinUnlockSection(
                        state = state,
                        lockoutUntilMs = pinLockoutUntilMs,
                        onPinSubmit = { pin -> authViewModel.unlockWithPin(pin.toCharArray()) },
                        onFallbackToPassword = {
                            forcePasswordFallback = true
                            authViewModel.clearError()
                        },
                    )
                }
                else -> {
                    PasswordUnlockSection(
                        state = state,
                        lockoutUntilMs = passwordLockoutUntilMs,
                        onPasswordSubmit = { authViewModel.unlockWithPassword(it) },
                        submitButtonLabel = submitButtonLabel,
                    )
                    if (isPinSetup && forcePasswordFallback && !mustUsePassword) {
                        TextButton(
                            modifier = Modifier.align(Alignment.CenterHorizontally),
                            onClick = {
                                forcePasswordFallback = false
                                authViewModel.clearError()
                            },
                        ) { Text("Use PIN instead") }
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 20.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (errorMessage != null) {
                    Text(
                        errorMessage,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            // Biometric chip: visible when biometric is configured and not blocked.
            // Tapping always re-opens the system prompt; the controller cancels any
            // existing prompt first to avoid stacking.
            if (isBiometricEnabled && canUseBiometric && !mustUsePassword) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = {
                            biometricController.show(
                                onSuccess = { authViewModel.unlockWithBiometric() },
                                onError = { msg -> authViewModel.setBiometricError(msg) },
                                onAuthFailed = { authViewModel.recordBiometricFailure() },
                            )
                        },
                        enabled = state !is AuthUiState.Loading,
                    ) {
                        Icon(
                            Icons.Default.Fingerprint,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Use biometric", style = MaterialTheme.typography.titleSmall)
                    }
                }
                if (biometricAttemptsRemaining in 1 until 3) {
                    Text(
                        text = "$biometricAttemptsRemaining biometric ${if (biometricAttemptsRemaining == 1) "attempt" else "attempts"} remaining",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                }
            }

            Spacer(Modifier.height(4.dp))
            TextButton(
                onClick = onAbort,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) { Text("Cancel autofill") }
        }
    }
}

@Composable
private fun LockReasonBanner(reason: LockReason) {
    val (title, body) = when (reason) {
        LockReason.TooManyFailedBiometricAttempts ->
            "Biometric paused" to "Too many failed biometric attempts. Enter your master password to re-enable."
        LockReason.TooManyFailedPinAttempts ->
            "PIN paused" to "Too many wrong PINs. Enter your master password to re-enable PIN unlock."
        is LockReason.TooManyFailedAttempts ->
            "Vault locked" to "Too many wrong attempts during ${reason.context}. Enter your master password to continue."
    }
    BannerSurface(icon = Icons.Default.Lock, title = title, body = body, isError = true)
}

@Composable
private fun RecheckBanner() {
    BannerSurface(
        icon = Icons.Default.Info,
        title = "Master password required",
        body = "It's been a while since you last entered your master password. " +
            "Confirm it to continue using biometric and PIN.",
        isError = false,
    )
}

@Composable
private fun BannerSurface(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    body: String,
    isError: Boolean,
) {
    val container = if (isError) MaterialTheme.colorScheme.errorContainer
    else MaterialTheme.colorScheme.primaryContainer
    val onContainer = if (isError) MaterialTheme.colorScheme.onErrorContainer
    else MaterialTheme.colorScheme.onPrimaryContainer
    Surface(
        color = container,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = onContainer,
                modifier = Modifier.size(20.dp),
            )
            Column {
                Text(
                    title,
                    style = MaterialTheme.typography.labelLarge,
                    color = onContainer,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    body,
                    style = MaterialTheme.typography.bodySmall,
                    color = onContainer.copy(alpha = 0.85f),
                )
            }
        }
    }
}

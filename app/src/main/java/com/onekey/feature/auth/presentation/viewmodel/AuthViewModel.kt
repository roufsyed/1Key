package com.onekey.feature.auth.presentation.viewmodel

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.onekey.core.domain.model.AppResult
import com.onekey.core.domain.repository.AppPreferencesRepository
import com.onekey.core.domain.repository.AuthRepository
import com.onekey.core.domain.repository.BiometricUnlockGate
import com.onekey.core.domain.usecase.SetupFromBackupUseCase
import com.onekey.core.domain.usecase.SetupMasterPasswordUseCase
import com.onekey.core.domain.usecase.UnlockVaultUseCase
import com.onekey.core.security.AuthAttemptsStore
import com.onekey.core.security.AutoLockManager
import com.onekey.core.security.BiometricAttemptTracker
import com.onekey.core.security.LockReason
import com.onekey.core.security.LockReasonStore
import com.onekey.core.security.PasswordAttemptTracker
import com.onekey.core.security.PinAttemptTracker
import com.onekey.core.security.PinAttemptTracker.Companion.lockoutDurationMs
import com.onekey.core.security.SecretKeyHolder
import com.onekey.feature.importexport.domain.EncryptedParseResult
import com.onekey.feature.importexport.domain.VaultImporter
import com.onekey.feature.secretkey.scan.canonicalSkToBytes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.SecureRandom
import javax.inject.Inject

@Immutable
sealed class AuthUiState {
    data object Idle : AuthUiState()
    data object Loading : AuthUiState()
    data object Unlocked : AuthUiState()
    data class Error(val message: String) : AuthUiState()
    data object SetupComplete : AuthUiState()

    /**
     * Restore-from-backup detected a V5 envelope whose FLAGS bit 0 is set
     * (the backup was produced with the Secret Key feature enabled) and the
     * caller did not supply an SK on this attempt. The Onboarding screen
     * pivots to the SK scanner so the user can scan their Emergency Kit;
     * the [pendingUri] / [pendingPassword] are preserved so the retry
     * after scan does not require re-picking the file or retyping the
     * password.
     *
     * Memory hygiene: [pendingPassword] is a defensive copy that the VM
     * owns. The restore-with-SK retry consumes (zeros) the array; if the
     * user cancels the SK scanner pivot, [clearPendingSecretKeyRestore]
     * zeros the array and drops the state.
     *
     * [error] carries the human-readable message from the most recent
     * failed retry attempt (wrong Secret Key, malformed input, corrupted
     * backup), so the SK input UI can surface it in supportingText on
     * the same pivot state without dropping the user back to a generic
     * Error state and losing the preserved file / password context. Null
     * on the initial pivot.
     */
    data class SecretKeyRequiredForRestore(
        val pendingUri: Uri,
        val pendingPassword: CharArray,
        val backupCreatedAtMs: Long,
        val backupVaultVersion: Int,
        val error: String? = null,
    ) : AuthUiState() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is SecretKeyRequiredForRestore) return false
            // Reference equality on pendingPassword; deep equality would
            // require iterating the zeroed CharArray and the only sensible
            // identity check here is "the same in-flight retry context".
            return pendingUri == other.pendingUri &&
                pendingPassword === other.pendingPassword &&
                backupCreatedAtMs == other.backupCreatedAtMs &&
                backupVaultVersion == other.backupVaultVersion &&
                error == other.error
        }
        override fun hashCode(): Int {
            // Reference-based hash to match equals - matches the "same
            // in-flight retry context" identity semantics above.
            var r = pendingUri.hashCode()
            r = 31 * r + System.identityHashCode(pendingPassword)
            r = 31 * r + backupCreatedAtMs.hashCode()
            r = 31 * r + backupVaultVersion
            r = 31 * r + (error?.hashCode() ?: 0)
            return r
        }
    }
}

sealed class AuthEvent {
    /** Three wrong PINs in a row - LockScreen forces the master-password fallback. */
    data object PinAttemptsExhausted : AuthEvent()
    /** Settings → Change PIN: current PIN matched. Advance to "enter new PIN". */
    data object CurrentPinVerified : AuthEvent()
    /** Settings → Change PIN: wrong current PIN, [remaining] attempts left this session. */
    data class CurrentPinFailed(val remaining: Int) : AuthEvent()
    /** Settings → Change PIN: 3 wrong current PINs. Soft cap - vault stays unlocked, but
     * the PIN field disables and the user is pointed at the Forgot-PIN escape hatch. */
    data object CurrentPinExhausted : AuthEvent()
    /** Settings → Change PIN → Forgot PIN: master password matched. Skip current-PIN
     * verification and go straight to "enter new PIN". */
    data object MasterPasswordVerifiedForPinChange : AuthEvent()
    /** Settings → Change PIN → Forgot PIN: wrong master password. */
    data class PinChangeMasterPasswordFailed(val remaining: Int) : AuthEvent()
    /** Settings → Change PIN → Forgot PIN: 3 wrong master passwords (across all sensitive
     * verify flows in this session). Vault is locked, user is bounced to LockScreen. */
    data object PinChangeVaultLocked : AuthEvent()
}

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val appPrefs: AppPreferencesRepository,
    private val setupMasterPassword: SetupMasterPasswordUseCase,
    private val unlockVault: UnlockVaultUseCase,
    private val setupFromBackup: SetupFromBackupUseCase,
    private val lockReasonStore: LockReasonStore,
    private val autoLockManager: AutoLockManager,
    private val authAttemptsStore: AuthAttemptsStore,
    private val passwordAttemptTracker: PasswordAttemptTracker,
    private val pinAttemptTracker: PinAttemptTracker,
    private val biometricAttemptTracker: BiometricAttemptTracker,
    // SK collaborators for the onboarding ceremony + restore-from-backup-with-SK
    // flows. The holder is populated after a successful SK-enabled setup so
    // EmergencyKitSavePromptScreen can render the canonical printed form
    // straight from the in-memory bytes.
    private val secretKeyHolder: SecretKeyHolder,
    private val importer: VaultImporter,
    private val credentialRepository: com.onekey.core.domain.repository.CredentialRepository,
) : ViewModel() {

    fun notifyPickerLaunched() { autoLockManager.suppressForPicker() }
    fun notifyPickerDone() { autoLockManager.clearPickerSuppression() }

    val lockReason: StateFlow<LockReason?> = lockReasonStore.reason
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * Single atomic source for the auto-biometric decision so the two underlying prefs
     * (`biometric_enabled`, `lock_reason_context`) can never be observed in a transient
     * mismatched state during cold-start DataStore hydration.
     */
    val biometricUnlockGate: StateFlow<BiometricUnlockGate> = appPrefs.getBiometricUnlockGate()
        .stateIn(viewModelScope, SharingStarted.Eagerly, BiometricUnlockGate(false, false))

    /**
     * Epoch-ms when the current master-password lockout expires, or null if no lockout
     * is in effect. May be in the past once the window has elapsed - the UI compares
     * against [System.currentTimeMillis] to decide whether to show the countdown.
     */
    val passwordLockoutUntilMs: StateFlow<Long?> = passwordAttemptTracker.lockoutUntilMs
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * Same shape as [passwordLockoutUntilMs] but for the PIN. Persisted across process
     * death via [PinAttemptTracker], so a swipe-from-recents between attempts cannot
     * reset the counter. The LockScreen consumes this to disable the PIN field and
     * surface a countdown while the lockout window is active.
     */
    val pinLockoutUntilMs: StateFlow<Long?> = pinAttemptTracker.lockoutUntilMs
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _state = MutableStateFlow<AuthUiState>(AuthUiState.Idle)
    val state: StateFlow<AuthUiState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<AuthEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<AuthEvent> = _events.asSharedFlow()

    /**
     * "X biometric attempts remaining" surface for the UI. Reads through
     * [BiometricAttemptTracker] (DataStore-backed) so the count is shared
     * across LockScreen and the autofill activities - preventing the
     * "rotate between surfaces to triple the budget" attack that an
     * in-memory ViewModel field would allow.
     */
    val biometricAttemptsRemaining: StateFlow<Int> = biometricAttemptTracker.failureCount
        .map { (BiometricAttemptTracker.MAX_FAILURES - it).coerceAtLeast(0) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, BiometricAttemptTracker.MAX_FAILURES)

    // Local counter for the in-vault Settings→Change PIN current-PIN verification.
    // The vault is already unlocked here so the threat shape is different from
    // LockScreen PIN entry - a session-scoped counter is sufficient. (LockScreen
    // PIN attempts are tracked persistently via PinAttemptTracker.)
    private var currentPinAttemptsRemaining = MAX_CURRENT_PIN_ATTEMPTS

    val isSetupComplete: StateFlow<Boolean> = authRepository.isSetupComplete()
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val isUnlocked: StateFlow<Boolean> = authRepository.isUnlocked()
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val isPinSetup: StateFlow<Boolean> = authRepository.isPinSetup()
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val isBiometricEnabled: StateFlow<Boolean> = appPrefs.isBiometricEnabled()
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // True when the user must re-enter their master password regardless of PIN/biometric state.
    val requiresMasterPasswordRecheck: StateFlow<Boolean> = combine(
        appPrefs.isMasterPasswordRecheckEnabled(),
        appPrefs.getMasterPasswordRecheckInterval(),
        appPrefs.getLastMasterPasswordTimestamp(),
    ) { enabled, interval, lastTimestamp ->
        enabled && (System.currentTimeMillis() - lastTimestamp) >= interval.millis
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setup(password: CharArray) {
        viewModelScope.launch {
            _state.value = AuthUiState.Loading
            // Two PBKDF2 derivations (~600-1600ms total) - keep them off Main so the
            // Loading spinner actually paints and the button visibly transitions.
            val result = withContext(Dispatchers.Default) { setupMasterPassword(password) }
            if (result is AppResult.Success) {
                appPrefs.setLastMasterPasswordTimestamp(System.currentTimeMillis())
            }
            _state.value = when (result) {
                is AppResult.Success -> AuthUiState.SetupComplete
                is AppResult.Error -> AuthUiState.Error(result.message ?: "Setup failed")
            }
        }
    }

    // ── Secret Key onboarding ceremony ───────────────────────────────────────

    /**
     * Fresh 16 raw bytes generated on first reach of the SK ceremony step. The
     * value is retained across recompositions / page transitions inside the
     * onboarding flow so the canonical preview the user sees on the ceremony
     * step is the same value that gets committed when they tap Save & Continue.
     *
     * Stored as a [ByteArray] so we can zero it deterministically on commit
     * (the array is consumed by [setupWithSecretKey] or
     * [setupSkippingSecretKey]). Process death drops this field; the
     * onboarding screen handles that case by regenerating on next reach of
     * the SK step.
     */
    private var pendingSecretKey: ByteArray? = null

    private val _pendingSecretKeyCanonical = MutableStateFlow<String?>(null)
    /**
     * Canonical printed form of the [pendingSecretKey] for display on the
     * SK ceremony step. Mirrors the format the Emergency Kit PDF uses
     * (`A3-XXXXX-XXXXX-XXXXX-XXXXX-XXXXXX`). Null until
     * [ensurePendingSecretKey] runs.
     *
     * The canonical String is a non-secret transit form (it carries the
     * same entropy as the raw bytes, but only this VM holds the raw bytes
     * themselves). Recomposition-safe to store in a StateFlow.
     */
    val pendingSecretKeyCanonical: StateFlow<String?> = _pendingSecretKeyCanonical.asStateFlow()

    /**
     * Idempotent: on first call generates a fresh 16-byte SK via SecureRandom
     * and publishes its canonical printed form on [pendingSecretKeyCanonical].
     * Subsequent calls are no-ops so a recomposition that re-runs the
     * LaunchedEffect cannot rotate the SK out from under a user mid-ceremony.
     *
     * Called from the SK ceremony step's first composition. The actual setup
     * commit happens later via [setupWithSecretKey] or [setupSkippingSecretKey].
     */
    fun ensurePendingSecretKey() {
        if (pendingSecretKey != null) return
        val raw = ByteArray(SecretKeyHolder.SECRET_KEY_RAW_LENGTH)
        SecureRandom().nextBytes(raw)
        pendingSecretKey = raw
        // Canonical form is needed for display; the raw bytes themselves
        // stay in pendingSecretKey for the eventual setup commit.
        _pendingSecretKeyCanonical.value =
            com.onekey.feature.secretkey.scan.formatCanonicalSkForPrint(
                com.onekey.feature.secretkey.scan.bytesToCanonicalSk(raw),
            )
    }

    /**
     * Drops the pending SK without setting up the vault. Used by the
     * onboarding back-stack when the user navigates back from the SK step
     * (e.g. to revise their master password) - the SK is regenerated on
     * the next reach of the ceremony step. Idempotent.
     */
    fun clearPendingSecretKey() {
        pendingSecretKey?.fill(0)
        pendingSecretKey = null
        _pendingSecretKeyCanonical.value = null
    }

    /**
     * Completes onboarding with the SK feature enabled. The [password] and
     * the in-memory [pendingSecretKey] are both consumed - this method
     * succeeds at most once per [ensurePendingSecretKey] cycle.
     *
     * On success:
     *  - The vault is set up under the SK-aware verifier
     *    (`K = Argon2id(MP || SK, salt, params)`).
     *  - The SK is wrapped under a fresh Keystore alias and persisted.
     *  - SP_SECRET_KEY_ENABLED is flipped to true.
     *  - The in-memory SK is installed into [SecretKeyHolder] so the
     *    Emergency Kit save prompt can render the printed form.
     *  - [AuthUiState.SetupComplete] is emitted; the onboarding screen
     *    navigates to the kit save flow.
     *
     * Failure modes:
     *  - No pending SK (programmer error: caller forgot
     *    [ensurePendingSecretKey]) - surfaces as
     *    [AuthUiState.Error] with a generic message.
     *  - Argon2id / Keystore / SP write failure - same as
     *    [setupMasterPasswordWithSecretKey]; the SK bytes are zeroed in
     *    the finally block regardless.
     */
    fun setupWithSecretKey(password: CharArray) {
        viewModelScope.launch {
            val sk = pendingSecretKey
            if (sk == null) {
                password.fill(' ')
                _state.value = AuthUiState.Error(
                    "Internal error: no Secret Key was generated. Please go back and try again.",
                )
                return@launch
            }
            _state.value = AuthUiState.Loading
            try {
                // Argon2id verifier + vault key wrap (~600-1600ms) - off Main
                // so the Loading spinner paints. The repo zeros the password
                // array in its own finally; we belt-and-suspenders here too.
                val result = withContext(Dispatchers.Default) {
                    if (password.size < 8) {
                        AppResult.Error(IllegalArgumentException("Master password must be at least 8 characters"))
                    } else {
                        authRepository.setupMasterPasswordWithSecretKey(password, sk)
                    }
                }
                _state.value = when (result) {
                    is AppResult.Success -> {
                        appPrefs.setLastMasterPasswordTimestamp(System.currentTimeMillis())
                        // The repo installs the SK into the holder; we keep
                        // our local pendingSecretKey ref alive so the kit
                        // save prompt can re-read via holder.withBytes
                        // without round-tripping through the VM.
                        AuthUiState.SetupComplete
                    }
                    is AppResult.Error -> AuthUiState.Error(result.message ?: "Setup failed")
                }
                if (result is AppResult.Success) {
                    // Drop our local SK reference now the holder owns the
                    // canonical copy. The bytes are already zeroed inside
                    // setupMasterPasswordWithSecretKey's defensive copies,
                    // but a second fill costs nothing.
                    sk.fill(0)
                    pendingSecretKey = null
                    _pendingSecretKeyCanonical.value = null
                }
            } finally {
                password.fill(' ')
            }
        }
    }

    /**
     * Completes onboarding WITHOUT the SK feature. Records the user's explicit
     * opt-out (`sk_opted_out=true`) so the Settings screen surfaces the
     * "you opted out" tile.
     *
     * Called from the OnboardingScreen after the user confirms the
     * [SecretKeySkipOnboardingDialog]. The pending SK is zeroed and
     * dropped before the underlying MP-only setup runs.
     */
    fun setupSkippingSecretKey(password: CharArray) {
        viewModelScope.launch {
            // Drop the generated SK BEFORE running setup. The opt-out flag
            // is the only persistent state; the bytes themselves never
            // reach the Keystore.
            pendingSecretKey?.fill(0)
            pendingSecretKey = null
            _pendingSecretKeyCanonical.value = null

            _state.value = AuthUiState.Loading
            try {
                val result = withContext(Dispatchers.Default) {
                    if (password.size < 8) {
                        AppResult.Error(IllegalArgumentException("Master password must be at least 8 characters"))
                    } else {
                        authRepository.setupMasterPasswordOptingOutOfSecretKey(password)
                    }
                }
                if (result is AppResult.Success) {
                    appPrefs.setLastMasterPasswordTimestamp(System.currentTimeMillis())
                }
                _state.value = when (result) {
                    is AppResult.Success -> AuthUiState.SetupComplete
                    is AppResult.Error -> AuthUiState.Error(result.message ?: "Setup failed")
                }
            } finally {
                password.fill(' ')
            }
        }
    }

    fun unlockWithPassword(password: CharArray) {
        viewModelScope.launch {
            // Defense-in-depth: honor the lockout even if called programmatically while
            // the UI button is disabled. This stops automated callers from bypassing
            // the per-attempt Argon2id cost by retrying before the window expires.
            //
            // Read DataStore directly - the [passwordLockoutUntilMs] StateFlow lags
            // a concurrent [recordFailure] DataStore commit (the StateFlow collector
            // runs on `viewModelScope`, which is one dispatcher hop behind the write).
            // A fresh `.first()` on the underlying Flow guarantees a current value
            // with no race window.
            val lockoutUntil = passwordAttemptTracker.lockoutUntilMs.first()
            if (lockoutUntil != null && System.currentTimeMillis() < lockoutUntil) {
                password.fill(' ')
                val remainingSecs = ((lockoutUntil - System.currentTimeMillis()) / 1000).coerceAtLeast(1L)
                _state.value = AuthUiState.Error("Too many failed attempts. Try again in ${remainingSecs}s.")
                return@launch
            }

            _state.value = AuthUiState.Loading
            // Argon2id/PBKDF2 verifier check (~300-800ms) - keep off Main so the button
            // properly shows its spinner instead of freezing the UI on first tap.
            val result = withContext(Dispatchers.Default) { unlockVault.withPassword(password) }
            when (result) {
                is AppResult.Success -> {
                    passwordAttemptTracker.reset()
                    // Master password is the canonical "user has proved identity" signal.
                    // Reset the PIN AND biometric trackers too so the user isn't carrying
                    // lockout state forward into the next session.
                    pinAttemptTracker.reset()
                    biometricAttemptTracker.reset()
                    appPrefs.setLastMasterPasswordTimestamp(System.currentTimeMillis())
                    // Successful master-password proof: release the biometric block set
                    // by a prior too-many-failures auto-lock.
                    lockReasonStore.clear()
                }
                is AppResult.Error -> {
                    passwordAttemptTracker.recordFailure()
                }
            }
            _state.value = when (result) {
                is AppResult.Success -> AuthUiState.Unlocked
                is AppResult.Error -> AuthUiState.Error(result.message ?: "Invalid password")
            }
        }
    }

    fun unlockWithPin(pin: CharArray) {
        viewModelScope.launch {
            // Defense-in-depth lockout check. The LockScreen UI also disables the PIN
            // field while the lockout window is active, but the repository must refuse
            // independently - otherwise an automated caller (or a future code path that
            // bypasses the UI) could brute-force at full Argon2id throughput.
            //
            // Read DataStore directly via `.first()` - the [pinLockoutUntilMs]
            // StateFlow lags a concurrent [recordFailure] commit, which would let a
            // fast retry slip through the gap on a fresh-per-Activity VM whose
            // StateFlow seed is `null` until the first upstream emission lands.
            val lockoutUntil = pinAttemptTracker.lockoutUntilMs.first()
            if (lockoutUntil != null && System.currentTimeMillis() < lockoutUntil) {
                pin.fill(' ')
                val remainingSecs = ((lockoutUntil - System.currentTimeMillis()) / 1000).coerceAtLeast(1L)
                _state.value = AuthUiState.Error("Too many wrong PINs. Try again in ${remainingSecs}s.")
                return@launch
            }

            _state.value = AuthUiState.Loading
            val result = withContext(Dispatchers.Default) { unlockVault.withPin(pin) }
            when (result) {
                is AppResult.Success -> {
                    pinAttemptTracker.reset()
                    _state.value = AuthUiState.Unlocked
                }
                is AppResult.Error -> {
                    val cumulative = pinAttemptTracker.recordFailure()
                    if (cumulative >= MAX_PIN_ATTEMPTS) {
                        // Persistent across process death (via PinAttemptTracker) AND
                        // forces the master-password fallback for the rest of the session
                        // (via LockReason). The two layers compose: the tracker ensures
                        // brute-force can't bypass the limit by killing the app; the lock
                        // reason ensures the user is bounced to master password until they
                        // prove identity that way (which clears both).
                        lockReasonStore.set(LockReason.TooManyFailedPinAttempts)
                        authRepository.lock()
                        _events.emit(AuthEvent.PinAttemptsExhausted)
                        // Compute the lockout duration directly from the just-returned
                        // cumulative count rather than reading pinLockoutUntilMs.value -
                        // the StateFlow may briefly lag the DataStore commit, which would
                        // make the message say "please use your master password" without
                        // a countdown even when one is in effect. Using `cumulative`
                        // gives a deterministic, race-free message.
                        val lockoutMs = lockoutDurationMs(cumulative)
                        val remainingSecs = if (lockoutMs != null) (lockoutMs / 1000L).coerceAtLeast(1L) else 0L
                        _state.value = AuthUiState.Error(
                            if (remainingSecs > 0) "Too many wrong PINs. Try again in ${remainingSecs}s, or use your master password."
                            else "Too many wrong PINs - please use your master password."
                        )
                    } else {
                        val remaining = MAX_PIN_ATTEMPTS - cumulative
                        _state.value = AuthUiState.Error(
                            if (remaining == 1) "Wrong PIN - 1 attempt remaining."
                            else "Wrong PIN - $remaining attempts remaining."
                        )
                    }
                }
            }
        }
    }

    /**
     * Unlocks the vault using a successful biometric authentication that the caller
     * has already obtained via [androidx.biometric.BiometricPrompt].
     *
     * Threat-model note - **biometric is a SOFT GATE**, not a cryptographic one.
     * The wrap key in `AuthRepositoryImpl.unlockWithBiometric` is wrapped with
     * `setUserAuthenticationRequired(false)` (`CryptoManager.kt`), which means the
     * Keystore will unwrap it any time the calling process can load it - no
     * `CryptoObject` is bound to the BiometricPrompt. We rely on the prompt's UX
     * confirmation, the lock-reason gate, and the [BiometricAttemptTracker] to
     * approximate the security a hardware-bound key would provide.
     *
     * Future upgrade: if this ever moves to `setUserAuthenticationRequired(true)`
     * plus a `BiometricPrompt.CryptoObject`, callers MUST also handle
     * [java.security.KeyPermanentlyInvalidatedException], which the OS throws when
     * the user enrols a new fingerprint/face after vault setup. Until then, that
     * exception cannot fire on the current path and no catch is needed.
     */
    fun unlockWithBiometric() {
        viewModelScope.launch {
            // Defensive - if a stale BiometricPrompt completes after we've already locked
            // out for too-many-failures, refuse the unlock. The button is hidden on
            // LockScreen when lockReason is set, but the in-flight prompt can still fire.
            //
            // Use [LockReasonStore.latest] (DataStore-direct read), NOT `reason.value`
            // (the StateFlow). The store's `set` is suspend and commits to DataStore;
            // the StateFlow collector on `appScope` propagates the new value
            // *asynchronously*. A concurrent [recordBiometricFailure] that just hit
            // the 3-strike threshold could land in DataStore before this method runs
            // but not yet be visible in `reason.value` - leaving a race window where
            // a fast biometric success unlocks the vault despite the just-set reason.
            // `.latest()` closes that window.
            if (lockReasonStore.latest() != null) {
                _state.value = AuthUiState.Error(
                    "Use your master password - biometric is paused after recent failures."
                )
                return@launch
            }
            _state.value = AuthUiState.Loading
            _state.value = when (val result = authRepository.unlockWithBiometric()) {
                is AppResult.Success -> {
                    biometricAttemptTracker.reset()
                    AuthUiState.Unlocked
                }
                is AppResult.Error -> AuthUiState.Error(result.message ?: "Biometric unlock failed")
            }
        }
    }

    /**
     * Called by LockScreen on each `onAuthenticationFailed` from the BiometricPrompt
     * (wrong finger / wrong face). Mirrors the PIN exhaustion shape: count down, surface
     * an "X remaining" message that the user sees when they dismiss the prompt, on zero
     * persist a lock reason, force the master-password fallback, and reset the counter.
     */
    fun recordBiometricFailure() {
        viewModelScope.launch {
            // Once we've already escalated to a lock reason, additional failures from a
            // still-visible BiometricPrompt are noise - the user is in master-password-only
            // mode and the count is meaningless. Don't decrement past the threshold.
            // Read DataStore directly for the same reason `unlockWithBiometric` does.
            if (lockReasonStore.latest() != null) return@launch

            val cumulative = biometricAttemptTracker.recordFailure()
            if (cumulative >= BiometricAttemptTracker.MAX_FAILURES) {
                // Set the lock reason BEFORE locking so any concurrent reader of
                // [LockReasonStore.latest] post-lock sees the new value. Both
                // primitives are DataStore-backed and `set` is suspend, so this
                // sequence is race-free for downstream readers.
                //
                // Do NOT reset the tracker here. Matches PinAttemptTracker semantics:
                // the persistent record of "user crossed the threshold" must survive
                // until a successful master-password proof clears it. Resetting at
                // threshold would let a forced restart show "3 attempts remaining"
                // again and (with the lock-reason gating) silently waste real budget
                // on every retry burst.
                lockReasonStore.set(LockReason.TooManyFailedBiometricAttempts)
                authRepository.lock()
                _state.value = AuthUiState.Error(
                    "Too many wrong biometric attempts - please use your master password."
                )
            } else {
                val remaining = BiometricAttemptTracker.MAX_FAILURES - cumulative
                _state.value = AuthUiState.Error(
                    if (remaining == 1)
                        "Wrong biometric - 1 attempt remaining."
                    else
                        "Wrong biometric - $remaining attempts remaining."
                )
            }
        }
    }

    /**
     * Settings → Change PIN, step 0: confirms the user knows the current PIN before
     * we let them change it. Pure verification - no vault key touched. Local-counter
     * lockout (3 attempts) that disables the PIN field for this session but keeps the
     * vault unlocked, since the user is already authenticated to the vault.
     */
    fun verifyCurrentPin(pin: CharArray) {
        viewModelScope.launch {
            _state.value = AuthUiState.Loading
            val result = withContext(Dispatchers.Default) { authRepository.verifyPin(pin) }
            _state.value = AuthUiState.Idle
            when (result) {
                is AppResult.Success -> {
                    currentPinAttemptsRemaining = MAX_CURRENT_PIN_ATTEMPTS
                    _events.emit(AuthEvent.CurrentPinVerified)
                }
                is AppResult.Error -> {
                    currentPinAttemptsRemaining--
                    if (currentPinAttemptsRemaining <= 0) {
                        currentPinAttemptsRemaining = MAX_CURRENT_PIN_ATTEMPTS
                        _events.emit(AuthEvent.CurrentPinExhausted)
                    } else {
                        _events.emit(AuthEvent.CurrentPinFailed(currentPinAttemptsRemaining))
                    }
                }
            }
        }
    }

    /**
     * Settings → Change PIN → Forgot PIN: verify the master password to bypass the
     * current-PIN check. Mirrors removePinWithVerification's lockout shape - uses the
     * shared AuthAttemptsStore so navigating Security ↔ top-level Settings can't reset
     * the count, three wrong attempts persist a lock reason and lock the vault.
     */
    fun verifyMasterPasswordForPinChange(password: CharArray) {
        viewModelScope.launch {
            _state.value = AuthUiState.Loading
            try {
                val result = withContext(Dispatchers.Default) {
                    authRepository.unlockWithPassword(password)
                }
                // unlockWithPassword sets the vault key on success - but we're already
                // inside an unlocked vault, so it's a no-op replacement. We don't want
                // _state to cascade to Unlocked here, so flip back to Idle explicitly.
                _state.value = AuthUiState.Idle
                when (result) {
                    is AppResult.Success -> {
                        authAttemptsStore.resetBiometricEnable()
                        _events.emit(AuthEvent.MasterPasswordVerifiedForPinChange)
                    }
                    is AppResult.Error -> {
                        val attempts = authAttemptsStore.incrementBiometricEnable()
                        if (attempts >= MAX_BIOMETRIC_ATTEMPTS) {
                            authAttemptsStore.resetBiometricEnable()
                            lockReasonStore.set(LockReason.TooManyFailedAttempts("PIN change"))
                            authRepository.lock()
                            _events.emit(AuthEvent.PinChangeVaultLocked)
                        } else {
                            _events.emit(
                                AuthEvent.PinChangeMasterPasswordFailed(MAX_BIOMETRIC_ATTEMPTS - attempts)
                            )
                        }
                    }
                }
            } finally {
                password.fill(' ')
            }
        }
    }

    fun setupPin(pin: CharArray) {
        viewModelScope.launch {
            _state.value = AuthUiState.Loading
            val result = withContext(Dispatchers.Default) { authRepository.setupPin(pin) }
            _state.value = when (result) {
                is AppResult.Success -> AuthUiState.SetupComplete
                is AppResult.Error -> AuthUiState.Error(result.message ?: "Failed to set PIN")
            }
        }
    }

    fun lock() {
        viewModelScope.launch { authRepository.lock() }
    }

    fun setBiometricError(message: String) { _state.value = AuthUiState.Error(message) }

    fun clearError() { if (_state.value is AuthUiState.Error) _state.value = AuthUiState.Idle }

    private companion object {
        const val MAX_PIN_ATTEMPTS = 3
        const val MAX_BIOMETRIC_ATTEMPTS = 3
        const val MAX_CURRENT_PIN_ATTEMPTS = 3
    }

    /**
     * Decrypts an encrypted 1Key backup, creates the vault using the backup password as the
     * master password, and imports the credentials - all in one step for onboarding.
     *
     * The [password] CharArray is zeroed inside [setupFromBackup] (by AuthRepository) and again
     * in the finally block as a safety net.
     */
    fun restoreFromEncryptedBackup(uri: Uri, password: CharArray, context: Context) {
        viewModelScope.launch {
            _state.value = AuthUiState.Loading
            val tmpFile = File(context.cacheDir, "restore.1key")
            // Defensive copy of the password so we can preserve it for the
            // SK-required pivot path WITHOUT relying on the caller's array
            // surviving the zeroing inside setupFromBackup.
            val passwordSnapshot = password.copyOf()
            var pivotedToSkRequired = false
            try {
                // Detect a missing or revoked URI explicitly so the user sees a clean message
                // instead of a cryptic decryption failure further down the pipeline.
                val copied = withContext(Dispatchers.IO) {
                    runCatching {
                        val input = context.contentResolver.openInputStream(uri) ?: return@runCatching false
                        input.use { inp -> tmpFile.outputStream().use { os -> inp.copyTo(os) } }
                        true
                    }.getOrDefault(false)
                }
                if (!copied) {
                    _state.value = AuthUiState.Error(
                        "Couldn't read the backup file. It may have been moved, deleted, or the app no longer has permission."
                    )
                    return@launch
                }

                // Peek the encrypted-parse result FIRST so we can pivot to
                // SK-needed before any vault state is written. parseEncrypted
                // distinguishes SecretKeyRequired from a plain Failure; the
                // SK-required branch is a control-flow pivot, not an error.
                val parseResult = withContext(Dispatchers.Default) {
                    importer.parseEncrypted(tmpFile.absolutePath, password.copyOf(), secretKey = null)
                }
                when (parseResult) {
                    is EncryptedParseResult.SecretKeyRequired -> {
                        // Pivot to the SK scanner UI. Preserve the password and
                        // uri so the retry after scan does not require the user
                        // to re-type or re-pick. The tmpFile is deleted here -
                        // the retry path re-reads the original URI which is
                        // still valid for the SAF session.
                        pivotedToSkRequired = true
                        _state.value = AuthUiState.SecretKeyRequiredForRestore(
                            pendingUri = uri,
                            pendingPassword = passwordSnapshot,
                            backupCreatedAtMs = parseResult.createdAtMs,
                            backupVaultVersion = parseResult.vaultVersion,
                        )
                    }
                    is EncryptedParseResult.Failure -> {
                        _state.value = AuthUiState.Error(parseResult.message)
                    }
                    is EncryptedParseResult.Success -> {
                        // Decryption succeeded with no SK requirement (V4 or
                        // V5-FLAGS=0 envelope). Set up the MP-only vault and
                        // import the credentials. We fall back to the
                        // SetupFromBackupUseCase path so the existing
                        // password-snapshot-then-setup logic stays canonical;
                        // the use case re-parses but the second parse is
                        // cheap relative to the Argon2id cost of setup.
                        when (val result = withContext(Dispatchers.Default) {
                            setupFromBackup(password, tmpFile.absolutePath)
                        }) {
                            is AppResult.Success -> {
                                appPrefs.setLastMasterPasswordTimestamp(System.currentTimeMillis())
                                _state.value = AuthUiState.SetupComplete
                            }
                            is AppResult.Error ->
                                _state.value = AuthUiState.Error(result.message ?: "Restore failed")
                        }
                    }
                }
            } finally {
                withContext(Dispatchers.IO) { tmpFile.delete() }
                if (!pivotedToSkRequired) {
                    // Zero both copies. On the SK-required pivot the snapshot
                    // is the one preserved in the UI state for retry; zeroing
                    // it here would break that flow. The retry path
                    // (restoreFromEncryptedBackupWithSecretKey) is responsible
                    // for zeroing the snapshot when it finishes.
                    passwordSnapshot.fill(' ')
                }
                password.fill(' ')
            }
        }
    }

    /**
     * Restore-from-backup-with-SK retry. Called from the OnboardingScreen
     * after the user scans their Emergency Kit QR (or types the SK by hand).
     *
     * Contract: [canonicalSk] is the bare 26-character Crockford base32
     * canonical SK string (no dashes, no prefix). The VM decodes it into
     * raw bytes, parses the backup file with the SK, and on success calls
     * [AuthRepository.setupWithSecretKeyFromBackup] to commit the vault key,
     * verifier, and wrapped-SK blob in a single edit().commit().
     *
     * The [pendingPassword] / [pendingUri] are pulled from the current
     * [AuthUiState.SecretKeyRequiredForRestore]; this method refuses to run
     * when the state is anything else.
     *
     * Memory hygiene:
     *  - The decoded raw SK bytes are zeroed in finally.
     *  - The pendingPassword from the state is zeroed in finally.
     *  - On Malformed / WrongVersion / wrong-password we surface an error
     *    and KEEP the SecretKeyRequiredForRestore state so the user can
     *    retry the scan without re-uploading the file.
     */
    fun restoreFromEncryptedBackupWithSecretKey(
        canonicalSk: String,
        context: Context,
    ) {
        val current = _state.value
        if (current !is AuthUiState.SecretKeyRequiredForRestore) {
            return
        }
        val uri = current.pendingUri
        // Work on a defensive copy so the finally's password.fill(' ') below
        // does NOT poison the snapshot we re-emit on retry-failure. The
        // prior snapshot (current.pendingPassword) is zeroed immediately so
        // only ONE live copy of the master password exists at a time, in our
        // local `password` variable. On retry-failure we publish a fresh
        // copy via current.copy(pendingPassword = password.copyOf(), ...);
        // on Success the local copy is the last to die in the finally.
        val password = current.pendingPassword.copyOf()
        current.pendingPassword.fill(' ')
        viewModelScope.launch {
            _state.value = AuthUiState.Loading
            val tmpFile = File(context.cacheDir, "restore_sk.1key")
            var skBytes: ByteArray? = null
            try {
                // Decode the canonical SK string into raw bytes. Failure here
                // (bad QR scan, hand-typed typo) is a recoverable error - the
                // file and password stay alive for the next retry.
                val decoded = try {
                    canonicalSkToBytes(canonicalSk)
                } catch (e: IllegalArgumentException) {
                    // Restore the pivot state with the malformed-SK message
                    // attached so the SK input UI can render it in
                    // supportingText. Allocate a fresh password copy so the
                    // re-emitted state does not alias our local working
                    // buffer (which the finally below zeros).
                    _state.value = current.copy(
                        pendingPassword = password.copyOf(),
                        error = e.message ?: "The Secret Key value couldn't be read. Please scan again.",
                    )
                    return@launch
                }
                skBytes = decoded

                val copied = withContext(Dispatchers.IO) {
                    runCatching {
                        val input = context.contentResolver.openInputStream(uri) ?: return@runCatching false
                        input.use { inp -> tmpFile.outputStream().use { os -> inp.copyTo(os) } }
                        true
                    }.getOrDefault(false)
                }
                if (!copied) {
                    // The file went away between the original pick and this
                    // retry. This is unrecoverable from the pivot state, so
                    // drop to a plain Error - the user has to re-pick.
                    _state.value = AuthUiState.Error(
                        "Couldn't read the backup file. It may have been moved, deleted, or the app no longer has permission."
                    )
                    return@launch
                }
                // Parse the file with the SK. parseEncrypted's contract
                // zeros its password argument; pass a copy so the original
                // remains alive for setupWithSecretKeyFromBackup.
                val parseResult = withContext(Dispatchers.Default) {
                    importer.parseEncrypted(
                        path = tmpFile.absolutePath,
                        password = password.copyOf(),
                        secretKey = decoded,
                    )
                }
                when (parseResult) {
                    is EncryptedParseResult.SecretKeyRequired -> {
                        // SK didn't match the FLAGS bit - should not happen
                        // here because we passed an SK; treat as a corrupt
                        // file but keep the pivot alive so the user can
                        // retry with a different Secret Key. Re-emit the
                        // pivot with a fresh password copy so a subsequent
                        // retry holds clean bytes.
                        _state.value = current.copy(
                            pendingPassword = password.copyOf(),
                            error = "The backup is corrupted or the Secret Key was rejected.",
                        )
                    }
                    is EncryptedParseResult.Failure -> {
                        _state.value = current.copy(
                            pendingPassword = password.copyOf(),
                            error = parseResult.message,
                        )
                    }
                    is EncryptedParseResult.Success -> {
                        // Commit the SK-enabled vault state. Use a fresh
                        // password copy because setupWithSecretKeyFromBackup
                        // zeros its argument internally.
                        val setupResult = withContext(Dispatchers.Default) {
                            authRepository.setupWithSecretKeyFromBackup(
                                password = password.copyOf(),
                                secretKey = decoded,
                            )
                        }
                        when (setupResult) {
                            is AppResult.Success -> {
                                // Import the parsed credentials into the
                                // freshly-set-up vault.
                                val credentials = parseResult.parsed.credentials
                                if (credentials.isNotEmpty()) {
                                    val importResult = withContext(Dispatchers.Default) {
                                        credentialRepository.importCredentials(credentials)
                                    }
                                    if (importResult is AppResult.Error) {
                                        _state.value = AuthUiState.Error(
                                            importResult.message ?: "Restore failed",
                                        )
                                        return@launch
                                    }
                                }
                                appPrefs.setLastMasterPasswordTimestamp(System.currentTimeMillis())
                                _state.value = AuthUiState.SetupComplete
                            }
                            is AppResult.Error -> {
                                _state.value = current.copy(
                                    pendingPassword = password.copyOf(),
                                    error = setupResult.message ?: "Restore failed",
                                )
                            }
                        }
                    }
                }
            } finally {
                withContext(Dispatchers.IO) { tmpFile.delete() }
                skBytes?.fill(0)
                // Zero this method's local working copy. The pivot snapshot
                // held by the UI state is either (a) a fresh copy emitted on
                // retry-failure, or (b) zeroed at function entry on Success.
                // Either way, this fill does NOT touch any other live copy.
                password.fill(' ')
            }
        }
    }

    /**
     * Cancels the SK-required-restore pivot and returns the UI to Idle. Zeros
     * the preserved password CharArray so the next attempt requires a fresh
     * type-in. Called when the user dismisses the SK scanner without scanning.
     */
    fun clearPendingSecretKeyRestore() {
        val current = _state.value
        if (current is AuthUiState.SecretKeyRequiredForRestore) {
            current.pendingPassword.fill(' ')
        }
        _state.value = AuthUiState.Idle
    }

    /**
     * Clears the `error` field on the current SK-required pivot state while
     * keeping the file / password snapshot alive. Called when the user starts
     * typing a fresh Secret Key value after a failed retry so the supportingText
     * disappears as soon as they edit the input. No-op if the state is anything
     * other than [AuthUiState.SecretKeyRequiredForRestore].
     */
    fun clearSkRestoreError() {
        val current = _state.value as? AuthUiState.SecretKeyRequiredForRestore ?: return
        if (current.error != null) {
            _state.value = current.copy(error = null)
        }
    }

    override fun onCleared() {
        super.onCleared()
        pendingSecretKey?.fill(0)
        pendingSecretKey = null
        val current = _state.value
        if (current is AuthUiState.SecretKeyRequiredForRestore) {
            current.pendingPassword.fill(' ')
        }
    }
}

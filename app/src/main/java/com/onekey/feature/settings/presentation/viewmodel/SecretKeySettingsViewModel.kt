package com.onekey.feature.settings.presentation.viewmodel

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.onekey.core.domain.model.AppResult
import com.onekey.core.security.AutoLockManager
import com.onekey.core.domain.repository.AuthRepository
import com.onekey.core.security.AuthAttemptsStore
import com.onekey.core.security.HardwareKeyIsolationProbe
import com.onekey.core.security.HardwareKeyIsolationStatus
import com.onekey.core.security.HardwareKeyIsolationTier
import com.onekey.core.security.KEYSTORE_ALIAS_SECRET_KEY_PREFIX
import com.onekey.core.security.LockReason
import com.onekey.core.security.LockReasonStore
import com.onekey.core.security.SecretKeyHolder
import com.onekey.core.security.SecretKeyKeystoreWrapper
import com.onekey.feature.secretkey.domain.SecretKeyDisableUseCase
import com.onekey.feature.secretkey.domain.SecretKeyEnableUseCase
import com.onekey.feature.secretkey.domain.SecretKeyRotateUseCase
import com.onekey.feature.secretkey.pdf.EmergencyKitPdfGenerator
import com.onekey.feature.secretkey.scan.bytesToCanonicalSk
import com.onekey.feature.secretkey.scan.formatCanonicalSkForPrint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.KeyStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Named

private const val TAG = "SecretKeySettingsVM"

/**
 * SharedPreferences key for the wall-clock timestamp (ms epoch) of the
 * fresh-SK generation event. Lets the Secret Key screen render "Generated
 * <date>" so a user knows roughly when this SK was minted.
 *
 * The flag is wall-clock, not monotonic. A device-time backjump would
 * surface as a "Generated in the future" subtitle; that is cosmetic only -
 * the SK itself is unaffected by clock skew.
 */
private const val SP_SK_GENERATED_AT = "sk_generated_at"

/**
 * SharedPreferences key for the wall-clock timestamp of the most recent
 * successful Emergency Kit save (PDF written to a user-chosen URI). The
 * post-rotate banner reads this against [SP_SK_GENERATED_AT] to decide
 * whether the user has saved a fresh kit since the last rotate.
 */
private const val SP_SK_LAST_KIT_DOWNLOAD_AT = "sk_last_kit_download_at"

/**
 * Backup envelope version stamped into the QR `ver=` parameter. Locked at 5
 * to match the V5 envelope FLAGS encoding from Stage 4.
 */
private const val SK_QR_ENVELOPE_VERSION = 5

/**
 * Maximum number of wrong-password attempts the SK transition flows allow
 * before locking the vault and dumping the user back to the Lock screen.
 * Mirrors the policy used by the biometric-enable and KDF-migration flows
 * but uses a dedicated counter (challenger Issue 6) so a wrong attempt on
 * one SK action does not consume the budget for an unrelated security flow.
 */
private const val MAX_SK_ATTEMPTS = 3

/**
 * Storage-backing tier the SK Keystore alias resolved to. Mirrors the
 * shape used by [HardwareKeyIsolationStatus] but is sourced from the SK
 * alias' own KeyInfo so the UI distinguishes "SK in StrongBox" from
 * "vault key in StrongBox, SK fell back to TEE on this device". Defaults
 * to UNKNOWN until the SK Keystore alias has been minted.
 */
enum class SecretKeyStorageTier { UNKNOWN, STRONGBOX, TEE, SOFTWARE }

/**
 * Top-level state surface emitted to [com.onekey.feature.settings.presentation.screen.SettingsSecretKeyScreen].
 * Carries everything the screen needs to render its three states (off,
 * pending-kit, fully-enabled) without re-reading the wrapper or auth prefs.
 *
 *  - [isPresent] - true when SP_SECRET_KEY_ENABLED is set. Cold-start
 *    reflects what the wrapper sees after [SecretKeyKeystoreWrapper.activeBlob].
 *  - [generationDateMs] - wall-clock ms epoch when this SK was last
 *    generated (enable or rotate). Null when the feature has never been
 *    enabled on this install.
 *  - [lastKitDownloadAtMs] - wall-clock ms epoch of the most recent
 *    Emergency Kit save. Null when no save has occurred yet.
 *  - [storageBacking] - resolved tier for the SK Keystore alias.
 *  - [kitNotYetSavedAfterRotation] - true when [generationDateMs] is more
 *    recent than [lastKitDownloadAtMs]. Drives the post-rotate "save a new
 *    kit" banner; cleared by a subsequent save.
 *  - [corruptionDetected] - true when the wrapper reports the active blob
 *    is present but unwrapping fails (Keystore wiped under us, etc.).
 *    Cosmetic flag; the unlock path also surfaces a separate error.
 */
data class SecretKeyUiState(
    val isPresent: Boolean = false,
    val generationDateMs: Long? = null,
    val lastKitDownloadAtMs: Long? = null,
    val storageBacking: SecretKeyStorageTier = SecretKeyStorageTier.UNKNOWN,
    val kitNotYetSavedAfterRotation: Boolean = false,
    val corruptionDetected: Boolean = false,
    val optedOut: Boolean = false,
)

/**
 * One-shot events the screen consumes (toast / snackbar / navigation). The
 * VM emits via [MutableSharedFlow] with extraBufferCapacity=1 so a single
 * pending event survives configuration changes. Patterns mirror
 * [SettingsViewModel.event] / [SettingsEvent].
 */
sealed class SecretKeySettingsEvent {
    object EnableSucceeded : SecretKeySettingsEvent()
    object DisableSucceeded : SecretKeySettingsEvent()
    object RotateSucceeded : SecretKeySettingsEvent()
    data class WrongPassword(val attemptsRemaining: Int) : SecretKeySettingsEvent()
    object VaultLocked : SecretKeySettingsEvent()
    data class Error(val message: String) : SecretKeySettingsEvent()

    object KitSaved : SecretKeySettingsEvent()
    data class KitSaveFailed(val message: String) : SecretKeySettingsEvent()
    object PrintNotYetSupported : SecretKeySettingsEvent()
}

/**
 * Drives the Secret Key Settings surface. Composes three use cases (Enable
 * / Disable / Rotate), the in-memory holder, the on-disk wrapper, the PDF
 * generator, and the auth repository's MP-recheck helpers behind a
 * StateFlow-shaped UI contract.
 *
 * # Scope
 *
 * Stage 5 owns the use cases, the screens, and this VM. The wiring into
 * NavGraph is done here (Stage 5) but the related onboarding branch
 * (Issue 9) is Stage 7 work. This VM does NOT touch the onboarding flow.
 *
 * # Why a dedicated VM (not folded into SettingsViewModel)
 *
 * SettingsViewModel is already 600 lines and orchestrates a dozen unrelated
 * security flags. Folding the SK lifecycle in would push it past 1000
 * lines and entangle the SK lockout counter with the existing biometric
 * counter. A dedicated VM keeps the SK state machine inspectable in
 * isolation and lets the use-case orchestration share an instance scope
 * with the screen.
 *
 * # Lockout counter
 *
 * Per challenger Issue 6 this flow uses the dedicated SK counter on
 * [AuthAttemptsStore.incrementSecretKey] / [AuthAttemptsStore.resetSecretKey].
 * A user who fails three biometric-enable attempts can still attempt an
 * SK enable; conversely, three wrong SK attempts do not consume the
 * biometric budget. The two counters share the lockout policy (vault
 * locks on the third strike) but the budgets are independent.
 *
 * # Memory hygiene
 *
 * All three use cases zero the master-password CharArray in their finally
 * blocks. The VM does an additional `password.fill(' ')` after the use case
 * returns so any path through the VM (success, error, lockout) zeros the
 * array exactly once more. Idempotent re-zero is cheap and shields
 * against a future use-case refactor that drops its own finally.
 *
 * The fresh SK ByteArray returned by Enable / Rotate is consumed inline by
 * [bytesToCanonicalSk] and zeroed before the function returns. The
 * canonical printed-form String emitted to the screen does NOT contain
 * the raw bytes; it carries the Crockford base32 encoding which is what
 * the user sees on the PDF.
 */
@HiltViewModel
class SecretKeySettingsViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val enableUseCase: SecretKeyEnableUseCase,
    private val disableUseCase: SecretKeyDisableUseCase,
    private val rotateUseCase: SecretKeyRotateUseCase,
    private val wrapper: SecretKeyKeystoreWrapper,
    private val holder: SecretKeyHolder,
    private val pdfGenerator: EmergencyKitPdfGenerator,
    private val authAttemptsStore: AuthAttemptsStore,
    private val lockReasonStore: LockReasonStore,
    private val hardwareKeyIsolationProbe: HardwareKeyIsolationProbe,
    private val autoLockManager: AutoLockManager,
    @Named("auth") private val authPrefs: SharedPreferences,
) : ViewModel() {

    /**
     * Tells the inactivity/background timer that a system file picker
     * (SAF for the kit save) is about to open. The SAF picker
     * foregrounds the system Files app, which sends 1Key to the
     * background long enough for the standard background-lock timer
     * to fire. That would zero the in-memory Secret Key bytes BEFORE
     * the SAF callback returns, so the PDF generation in
     * [saveKitToUri] would have no SK plaintext to render. Mirrors
     * [com.onekey.feature.auth.presentation.viewmodel.AuthViewModel.notifyPickerLaunched]
     * and the same pattern in the Backup screen.
     *
     * Call IMMEDIATELY before launching the SAF picker. Match with
     * [notifyPickerDone] on the result callback (both null and
     * non-null URI branches).
     */
    fun notifyPickerLaunched() {
        autoLockManager.suppressForPicker()
    }

    /**
     * Companion to [notifyPickerLaunched]. Call from the SAF
     * `ActivityResultLauncher` callback regardless of whether the user
     * picked a URI or cancelled. Idempotent: multiple calls are safe.
     */
    fun notifyPickerDone() {
        autoLockManager.clearPickerSuppression()
    }

    private val _state = MutableStateFlow(SecretKeyUiState())
    /** Top-level UI state. Composed by the Secret Key Settings screen. */
    val state: StateFlow<SecretKeyUiState> = _state.asStateFlow()

    private val _isBusy = MutableStateFlow(false)
    /**
     * True while an enable / disable / rotate transition is in flight.
     * Disables the screen's action buttons so a double-tap or sleep-wake
     * cannot kick off a second migration mid-flight.
     */
    val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

    private val _isSavingKit = MutableStateFlow(false)
    /** True while a kit-save coroutine is writing to the user-chosen URI. */
    val isSavingKit: StateFlow<Boolean> = _isSavingKit.asStateFlow()

    private val _canonicalKitPrintedForm = MutableStateFlow<String?>(null)
    /**
     * Printed-form canonical SK string for the Emergency Kit save prompt.
     * Populated when [EmergencyKitSavePromptScreen] composes (the screen
     * calls [refreshCanonicalKitPrintedForm] in a LaunchedEffect).
     */
    val canonicalKitPrintedForm: StateFlow<String?> = _canonicalKitPrintedForm.asStateFlow()

    private val _lastKitDownloadAt = MutableStateFlow<Long?>(null)
    /**
     * Last-kit-download timestamp as a separate StateFlow so the save
     * prompt's Done-button gate observes it directly without re-deriving
     * from [state]. Mirrors [SecretKeyUiState.lastKitDownloadAtMs].
     */
    val lastKitDownloadAt: StateFlow<Long?> = _lastKitDownloadAt.asStateFlow()

    private val _event = MutableSharedFlow<SecretKeySettingsEvent>(extraBufferCapacity = 1)
    val event: SharedFlow<SecretKeySettingsEvent> = _event.asSharedFlow()

    private val _enableRequested = MutableStateFlow(false)
    /**
     * Set to true when the user dismisses the pre-enable explainer with
     * "Continue", signalling that [SettingsSecretKeyScreen] should mount
     * the master-password reauth dialog. The dialog calls
     * [clearEnableRequested] once it lands, so a back-out from the dialog
     * does not re-trigger when the user revisits the screen.
     *
     * Lives on the VM (not in screen-local `remember`) because the
     * explainer is a separate composable and the navigation pop returns
     * focus to a fresh SettingsSecretKeyScreen instance whose local
     * remember state has been recreated. The VM survives across the pop.
     */
    val enableRequested: StateFlow<Boolean> = _enableRequested.asStateFlow()

    /**
     * Called by the explainer screen's Continue handler (via NavGraph)
     * to signal the user has acknowledged the explainer and now wants
     * to enter their master password. Idempotent: a repeated call
     * before [clearEnableRequested] is a no-op.
     */
    fun markEnableRequested() {
        _enableRequested.value = true
    }

    /**
     * Called by [SettingsSecretKeyScreen] once the reauth dialog has
     * landed (or been dismissed) so the flag does not re-fire on a
     * subsequent recomposition.
     */
    fun clearEnableRequested() {
        _enableRequested.value = false
    }

    init {
        // Cold-start sync: pull the current isPresent / generation date /
        // last-kit-download bookkeeping out of auth prefs.
        refreshState()
        // The HW isolation probe is also kicked from SettingsViewModel.init;
        // calling start() here is idempotent so a user who entered the SK
        // screen without first opening top-level Settings still gets a
        // probe result.
        hardwareKeyIsolationProbe.start()
    }

    /**
     * Re-reads the wrapper / authPrefs and republishes [state]. Called from
     * [init] and after each successful transition. Reads are cheap enough
     * (one EncryptedSharedPreferences fetch per field) that we do not
     * memoise.
     */
    private fun refreshState() {
        viewModelScope.launch {
            val isPresent = authRepository.isSecretKeyEnabled()
            val generationDateMs = readLongOrNull(SP_SK_GENERATED_AT)
            val lastKitDownloadAtMs = readLongOrNull(SP_SK_LAST_KIT_DOWNLOAD_AT)
            val kitNotYetSavedAfterRotation = isPresent &&
                generationDateMs != null &&
                (lastKitDownloadAtMs == null || lastKitDownloadAtMs < generationDateMs)
            val corruptionDetected = isPresent && runCatching { wrapper.activeBlob() }
                .getOrNull() == null
            val storageBacking = if (isPresent) resolveStorageTier() else SecretKeyStorageTier.UNKNOWN
            _state.value = SecretKeyUiState(
                isPresent = isPresent,
                generationDateMs = generationDateMs,
                lastKitDownloadAtMs = lastKitDownloadAtMs,
                storageBacking = storageBacking,
                kitNotYetSavedAfterRotation = kitNotYetSavedAfterRotation,
                corruptionDetected = corruptionDetected,
                optedOut = !isPresent && authPrefs.getBoolean(SP_SK_OPTED_OUT, false),
            )
            _lastKitDownloadAt.value = lastKitDownloadAtMs
        }
    }

    /**
     * Resolves the SK Keystore alias' storage tier. Uses [KeyInfo] +
     * SecurityLevel on API 31+ and falls back to a "TEE or STRONGBOX based
     * on the HW isolation probe" heuristic on older versions where the SK
     * alias may not advertise its tier independently.
     *
     * On any error (alias missing despite SP flag, exception reading
     * KeyInfo), returns [SecretKeyStorageTier.UNKNOWN] so the screen's
     * subtitle text shows "Checking..." rather than a misleading tier.
     */
    @Suppress("DEPRECATION") // isInsideSecureHardware is the right fallback signal here.
    private fun resolveStorageTier(): SecretKeyStorageTier {
        return try {
            // The active SK alias is named "$KEYSTORE_ALIAS_SECRET_KEY_PREFIX$N"
            // where N is the current generation counter (B1 counter-alias
            // scheme). The first-enable case lands on v1, rotations land
            // on v2, v3, etc. Using the wrapper's active version keeps
            // this resolver correct across rotations - the previous
            // hardcoded v1 constant returned UNKNOWN as soon as the user
            // rotated even once.
            val activeVersion = wrapper.activeVersion()
            if (activeVersion < 1) return SecretKeyStorageTier.UNKNOWN
            val aliasName = "$KEYSTORE_ALIAS_SECRET_KEY_PREFIX$activeVersion"

            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val secretKey = keyStore.getKey(aliasName, null) as? javax.crypto.SecretKey
                ?: return SecretKeyStorageTier.UNKNOWN
            val factory = javax.crypto.SecretKeyFactory.getInstance(secretKey.algorithm, "AndroidKeyStore")
            val keyInfo = factory.getKeySpec(secretKey, android.security.keystore.KeyInfo::class.java)
                as android.security.keystore.KeyInfo

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Mirrors HardwareKeyIsolationProbe.classifyApi31Plus so
                // the two probes agree on the same device. Critically,
                // handle SECURITY_LEVEL_UNKNOWN_SECURE (treat as TEE -
                // we know it is secure hardware, just not which kind)
                // and SECURITY_LEVEL_UNKNOWN (fall back to the
                // isInsideSecureHardware Boolean). On many devices the
                // OS reports UNKNOWN_SECURE even when the key is in
                // TEE; the previous else->UNKNOWN branch left the UI
                // stuck on "Checking..." indefinitely.
                when (keyInfo.securityLevel) {
                    android.security.keystore.KeyProperties.SECURITY_LEVEL_STRONGBOX ->
                        SecretKeyStorageTier.STRONGBOX
                    android.security.keystore.KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT ->
                        SecretKeyStorageTier.TEE
                    android.security.keystore.KeyProperties.SECURITY_LEVEL_SOFTWARE ->
                        SecretKeyStorageTier.SOFTWARE
                    android.security.keystore.KeyProperties.SECURITY_LEVEL_UNKNOWN_SECURE ->
                        SecretKeyStorageTier.TEE
                    android.security.keystore.KeyProperties.SECURITY_LEVEL_UNKNOWN ->
                        if (keyInfo.isInsideSecureHardware) SecretKeyStorageTier.TEE
                        else SecretKeyStorageTier.SOFTWARE
                    else ->
                        // Future-proofing: any new SECURITY_LEVEL_* constant
                        // (e.g. a future StrongBox-plus tier) lands here. Map
                        // conservatively via the legacy boolean.
                        if (keyInfo.isInsideSecureHardware) SecretKeyStorageTier.TEE
                        else SecretKeyStorageTier.SOFTWARE
                }
            } else {
                // Pre-API 31 KeyInfo lacks securityLevel. The
                // isInsideSecureHardware boolean is true for BOTH
                // StrongBox and TEE - it cannot distinguish them by
                // itself. HardwareKeyIsolationProbe has a richer
                // heuristic that combines isInsideSecureHardware with a
                // same-process StrongBox-creation flag, so we defer to
                // it. On StrongBox-capable Pixels running Android 9 or
                // 10 this preserves the STRONGBOX label that the
                // simplified isInsideSecureHardware path would have
                // collapsed to TEE.
                //
                // The probe is started in [init], so its status flow is
                // typically already populated by the time the SK
                // settings screen mounts. If the probe has not finished
                // yet (status == null), fall back to the legacy boolean
                // - better to claim TEE than show "Checking..." on a
                // tier the probe could not classify in time.
                when (hardwareKeyIsolationProbe.status.value?.tier) {
                    HardwareKeyIsolationTier.STRONGBOX -> SecretKeyStorageTier.STRONGBOX
                    HardwareKeyIsolationTier.TEE -> SecretKeyStorageTier.TEE
                    HardwareKeyIsolationTier.SOFTWARE -> SecretKeyStorageTier.SOFTWARE
                    null ->
                        if (keyInfo.isInsideSecureHardware) SecretKeyStorageTier.TEE
                        else SecretKeyStorageTier.SOFTWARE
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Could not resolve SK storage tier", t)
            SecretKeyStorageTier.UNKNOWN
        }
    }

    // ── Enable ─────────────────────────────────────────────────────────────

    /**
     * Runs [SecretKeyEnableUseCase] with the user-supplied master password.
     * On success records the generation timestamp, refreshes [state], and
     * emits [SecretKeySettingsEvent.EnableSucceeded] so the screen can
     * navigate to [com.onekey.feature.settings.presentation.screen.EmergencyKitSavePromptScreen].
     *
     * @param password caller-owned CharArray. The VM zeros it in finally.
     */
    fun enable(password: CharArray) {
        if (_isBusy.value) return
        viewModelScope.launch {
            try {
                _isBusy.value = true
                when (val result = enableUseCase(password)) {
                    is AppResult.Success -> {
                        authAttemptsStore.resetSecretKey()
                        // Stamp the generation timestamp. The post-success
                        // SP commit in the migrator does not write this -
                        // it is metadata for the Settings UI, not for the
                        // crypto path - so we own it here.
                        val now = System.currentTimeMillis()
                        authPrefs.edit()
                            .putLong(SP_SK_GENERATED_AT, now)
                            .remove(SP_SK_LAST_KIT_DOWNLOAD_AT)
                            .remove(SP_SK_OPTED_OUT)
                            .commit()
                        _canonicalKitPrintedForm.value =
                            formatCanonicalSkForPrint(bytesToCanonicalSk(result.data))
                        result.data.fill(0)
                        refreshState()
                        _event.emit(SecretKeySettingsEvent.EnableSucceeded)
                    }
                    is AppResult.Error -> handleTransitionError(result)
                }
            } finally {
                password.fill(' ')
                _isBusy.value = false
            }
        }
    }

    // ── Disable ────────────────────────────────────────────────────────────

    /**
     * Runs [SecretKeyDisableUseCase] with the user-supplied master password.
     * On success clears the generation / kit-download timestamps, marks
     * the opted-out flag, and refreshes [state].
     */
    fun disable(password: CharArray) {
        if (_isBusy.value) return
        viewModelScope.launch {
            try {
                _isBusy.value = true
                when (val result = disableUseCase(password)) {
                    is AppResult.Success -> {
                        authAttemptsStore.resetSecretKey()
                        authPrefs.edit()
                            .remove(SP_SK_GENERATED_AT)
                            .remove(SP_SK_LAST_KIT_DOWNLOAD_AT)
                            .putBoolean(SP_SK_OPTED_OUT, true)
                            .commit()
                        _canonicalKitPrintedForm.value = null
                        refreshState()
                        _event.emit(SecretKeySettingsEvent.DisableSucceeded)
                    }
                    is AppResult.Error -> handleTransitionError(result)
                }
            } finally {
                password.fill(' ')
                _isBusy.value = false
            }
        }
    }

    // ── Rotate ─────────────────────────────────────────────────────────────

    /**
     * Runs [SecretKeyRotateUseCase] with the user-supplied master password.
     * On success the new generation timestamp is written and the
     * kit-download timestamp is cleared, so [SecretKeyUiState.kitNotYetSavedAfterRotation]
     * flips true until the user saves a fresh kit.
     */
    fun rotate(password: CharArray) {
        if (_isBusy.value) return
        viewModelScope.launch {
            try {
                _isBusy.value = true
                when (val result = rotateUseCase(password)) {
                    is AppResult.Success -> {
                        authAttemptsStore.resetSecretKey()
                        val now = System.currentTimeMillis()
                        authPrefs.edit()
                            .putLong(SP_SK_GENERATED_AT, now)
                            .remove(SP_SK_LAST_KIT_DOWNLOAD_AT)
                            .commit()
                        _canonicalKitPrintedForm.value =
                            formatCanonicalSkForPrint(bytesToCanonicalSk(result.data))
                        result.data.fill(0)
                        refreshState()
                        _event.emit(SecretKeySettingsEvent.RotateSucceeded)
                    }
                    is AppResult.Error -> handleTransitionError(result)
                }
            } finally {
                password.fill(' ')
                _isBusy.value = false
            }
        }
    }

    /**
     * Common error path for enable / disable / rotate. Looks at the error
     * message to decide whether the failure was a wrong-password attempt
     * (the migrator's [com.onekey.core.security.KdfMigrator.runSecretKeyTransition]
     * surfaces wrong-password as "Incorrect master password. Please try
     * again.") and routes the user through the lockout policy.
     *
     * Any other error message is treated as a generic transition failure
     * and emitted via [SecretKeySettingsEvent.Error] so the screen can
     * snackbar it without consuming the lockout budget.
     */
    private suspend fun handleTransitionError(error: AppResult.Error) {
        val message = error.message.orEmpty()
        val isWrongPassword = message.contains("Incorrect master password", ignoreCase = true)
        if (!isWrongPassword) {
            _event.emit(SecretKeySettingsEvent.Error(message.ifBlank { "Could not update Secret Key." }))
            return
        }
        val attempts = authAttemptsStore.incrementSecretKey()
        if (attempts >= MAX_SK_ATTEMPTS) {
            authAttemptsStore.resetSecretKey()
            lockReasonStore.set(LockReason.TooManyFailedAttempts("Secret Key change"))
            authRepository.lock()
            _event.emit(SecretKeySettingsEvent.VaultLocked)
        } else {
            _event.emit(SecretKeySettingsEvent.WrongPassword(MAX_SK_ATTEMPTS - attempts))
        }
    }

    // ── Emergency Kit save / print ────────────────────────────────────────

    /**
     * Refreshes the canonical printed-form SK string. Reads from the
     * in-memory holder via [SecretKeyHolder.withBytes], formats inside the
     * lambda so the bytes are zeroed before this method returns, and
     * publishes the result on [canonicalKitPrintedForm].
     *
     * Returns silently when the holder has no SK (vault locked, SK not
     * enabled). The save prompt screen handles a null preview gracefully
     * (renders "Loading...") so the absence of an SK in the holder is not
     * a fatal UI state.
     */
    fun refreshCanonicalKitPrintedForm() {
        if (!holder.isPresent()) {
            _canonicalKitPrintedForm.value = null
            return
        }
        _canonicalKitPrintedForm.value = runCatching {
            holder.withBytes { sk -> formatCanonicalSkForPrint(bytesToCanonicalSk(sk)) }
        }.getOrNull()
    }

    /**
     * Writes the Emergency Kit PDF to [uri]. The holder's `withBytes`
     * lambda is the only place the raw SK is exposed; the PDF generator
     * receives the bare canonical form (already Crockford-encoded), not
     * the raw bytes, so the renderer never sees an unmasked SK.
     *
     * On success the kit-download timestamp is committed and
     * [SecretKeySettingsEvent.KitSaved] is emitted.
     */
    fun saveKitToUri(uri: Uri, context: Context) {
        if (_isSavingKit.value) return
        viewModelScope.launch {
            try {
                _isSavingKit.value = true
                val bytes = withContext(Dispatchers.IO) { generateKitBytes(context) }
                    ?: run {
                        _event.emit(SecretKeySettingsEvent.KitSaveFailed("Secret Key is not loaded."))
                        return@launch
                    }
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { os ->
                        os.write(bytes)
                        os.flush()
                    } ?: error("Could not open output stream for selected location")
                }
                val now = System.currentTimeMillis()
                authPrefs.edit().putLong(SP_SK_LAST_KIT_DOWNLOAD_AT, now).commit()
                _lastKitDownloadAt.value = now
                refreshState()
                _event.emit(SecretKeySettingsEvent.KitSaved)
            } catch (t: Throwable) {
                Log.w(TAG, "Save Emergency Kit failed", t)
                _event.emit(
                    SecretKeySettingsEvent.KitSaveFailed(
                        t.message ?: "Could not save Emergency Kit.",
                    )
                )
            } finally {
                _isSavingKit.value = false
            }
        }
    }

    /**
     * Returns a defensive copy of the PDF bytes for an external caller that
     * needs the file content (e.g. a Print path that does not use SAF).
     * Returns null when the holder has no SK loaded.
     *
     * Currently used by [requestPrintKit] which fires a "not yet
     * supported" event - the actual platform-print wiring lands in a
     * future stage. The function is exposed now so the Stage 5 surface
     * area is stable; future stages can call [exportKitPdfBytes] without
     * a new entry point.
     */
    fun exportKitPdfBytes(context: Context): ByteArray? {
        if (!holder.isPresent()) return null
        return runCatching { generateKitBytes(context) }.getOrNull()
    }

    /**
     * Stage-5 placeholder for the print path. The platform-print integration
     * is deferred to a future stage; this method emits a
     * [SecretKeySettingsEvent.PrintNotYetSupported] event so the screen can
     * surface a "Coming soon" snackbar without crashing.
     */
    fun requestPrintKit() {
        viewModelScope.launch {
            _event.emit(SecretKeySettingsEvent.PrintNotYetSupported)
        }
    }

    /**
     * Re-download path the screen calls when a user wants to save the kit
     * again (e.g. they realised the first save was to the wrong location).
     * Identical to [saveKitToUri] - the post-success event is the same,
     * the screen surfaces the same toast. Kept as a separate entry point
     * so a future change can layer (e.g.) a "confirm overwriting" dialog
     * on top of the redownload path without affecting the first-save path.
     */
    fun redownloadKit(uri: Uri, context: Context) {
        saveKitToUri(uri, context)
    }

    /**
     * Default filename for the SAF "create document" picker. Uses the
     * generation date when one is available so a user with multiple kits
     * saved on disk can tell them apart. Returns "1key_emergency_kit.pdf"
     * when no generation timestamp is known.
     */
    fun defaultKitFilename(): String {
        val ts = _state.value.generationDateMs ?: return "1key_emergency_kit.pdf"
        val fmt = SimpleDateFormat("yyyyMMdd", Locale.US)
        return "1key_emergency_kit_${fmt.format(Date(ts))}.pdf"
    }

    /**
     * Human-readable wall-clock format for the "Last saved" line on the
     * save prompt and the Settings screen. Uses the device's default locale
     * for the date, the system 24h preference for the time. Matches the
     * existing app-wide convention of ISO-style year-month-day with HH:mm
     * (no seconds - the precision is not useful here).
     */
    fun formatKitDownloadAt(ms: Long): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        return fmt.format(Date(ms))
    }

    /**
     * Renders the kit PDF bytes inside the holder's `withBytes` lambda.
     * The Crockford-encoded canonical string is a non-secret transit form
     * (it carries the same entropy as the raw bytes, but only the lambda
     * has the raw bytes themselves). Returns null when the holder is not
     * present, so the caller can surface a "Secret Key not loaded" error.
     */
    private fun generateKitBytes(context: Context): ByteArray? {
        if (!holder.isPresent()) return null
        return holder.withBytes { sk ->
            val canonical = bytesToCanonicalSk(sk)
            pdfGenerator.generate(
                secretKeyDisplay = canonical,
                envelopeVersion = SK_QR_ENVELOPE_VERSION,
                appVersionName = appVersionName(context),
                generatedAt = Date(),
                deviceName = "${Build.MANUFACTURER} ${Build.MODEL}",
            )
        }
    }

    private fun appVersionName(context: Context): String {
        return try {
            val pkg = context.packageManager.getPackageInfo(context.packageName, 0)
            pkg.versionName ?: "unknown"
        } catch (t: Throwable) {
            "unknown"
        }
    }

    private fun readLongOrNull(key: String): Long? {
        return if (authPrefs.contains(key)) authPrefs.getLong(key, 0L) else null
    }

    /**
     * Resolved HW isolation status used by the screen's storage-backing
     * tile. Re-exposed here (instead of injecting both VMs) so the SK
     * screen does not need a parallel SettingsViewModel instance just to
     * read this single StateFlow.
     */
    val hardwareKeyIsolationStatus: StateFlow<HardwareKeyIsolationStatus?>
        get() = hardwareKeyIsolationProbe.status

    companion object {
        /**
         * SharedPreferences flag set when a user explicitly opts out of
         * Secret Key (via the Settings disable path or the onboarding
         * skip dialog). Drives the optional "you have opted out" tile
         * shown in state A of the Settings screen.
         */
        internal const val SP_SK_OPTED_OUT = "sk_opted_out"
    }
}

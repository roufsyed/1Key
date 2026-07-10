package com.roufsyed.onekey.core.domain.repository

import com.roufsyed.onekey.core.domain.model.BackgroundLockTimeout
import com.roufsyed.onekey.core.domain.model.CredentialSortOrder
import com.roufsyed.onekey.core.domain.model.InactivityLockTimeout
import com.roufsyed.onekey.core.domain.model.MasterPasswordInterval
import com.roufsyed.onekey.core.domain.model.RecycleBinRetention
import com.roufsyed.onekey.core.domain.model.ThemeMode
import kotlinx.coroutines.flow.Flow

interface AppPreferencesRepository {
    /**
     * Persisted theme preference. `SYSTEM` is the default for anyone who has
     * not explicitly picked a theme yet; the app then follows the device-wide
     * dark/light setting. `LIGHT` / `DARK` force a specific mode.
     */
    fun getThemeMode(): Flow<ThemeMode>
    suspend fun setThemeMode(mode: ThemeMode)
    fun isBiometricEnabled(): Flow<Boolean>
    suspend fun setBiometricEnabled(enabled: Boolean)
    fun isScreenshotsEnabled(): Flow<Boolean>
    suspend fun setScreenshotsEnabled(enabled: Boolean)
    fun getBackgroundLockTimeout(): Flow<BackgroundLockTimeout>
    suspend fun setBackgroundLockTimeout(timeout: BackgroundLockTimeout)
    fun getInactivityLockTimeout(): Flow<InactivityLockTimeout>
    suspend fun setInactivityLockTimeout(timeout: InactivityLockTimeout)
    fun isMasterPasswordRecheckEnabled(): Flow<Boolean>
    suspend fun setMasterPasswordRecheckEnabled(enabled: Boolean)
    fun getMasterPasswordRecheckInterval(): Flow<MasterPasswordInterval>
    suspend fun setMasterPasswordRecheckInterval(interval: MasterPasswordInterval)
    fun getLastMasterPasswordTimestamp(): Flow<Long>
    suspend fun setLastMasterPasswordTimestamp(timestamp: Long)
    fun isShowFavourites(): Flow<Boolean>
    suspend fun setShowFavourites(show: Boolean)
    fun getCredentialSortOrder(): Flow<CredentialSortOrder>
    suspend fun setCredentialSortOrder(order: CredentialSortOrder)
    fun isHideTopBarOnScroll(): Flow<Boolean>
    suspend fun setHideTopBarOnScroll(enabled: Boolean)
    /**
     * Whether the notes field renders inline as formatted markdown when viewing a
     * credential. Default `true` because markdown is the intended reading mode for
     * the notes field; users who prefer the raw, plain-text view (or who use the
     * notes field purely for ad-hoc text and find markdown rendering distracting)
     * can opt out from Settings without losing any stored content.
     */
    fun isNotesRenderMarkdownEnabled(): Flow<Boolean>
    suspend fun setNotesRenderMarkdownEnabled(enabled: Boolean)
    fun isVaultFooterVisible(): Flow<Boolean>
    suspend fun setVaultFooterVisible(visible: Boolean)
    fun getRecycleBinRetention(): Flow<RecycleBinRetention>
    suspend fun setRecycleBinRetention(retention: RecycleBinRetention)
    fun isRecycleBinEnabled(): Flow<Boolean>
    suspend fun setRecycleBinEnabled(enabled: Boolean)
    fun isRestoreLastScreenOnUnlock(): Flow<Boolean>
    suspend fun setRestoreLastScreenOnUnlock(enabled: Boolean)
    /**
     * Soft kill-switch for the autofill service. The OS-level enablement
     * (`AutofillManager.hasEnabledAutofillServices`) is the source of truth for whether
     * the system routes fill requests here; this preference lets a user disable our
     * response without first revoking us in system Settings. Defaults to `true` so
     * users who enable the service in Settings get fills immediately.
     */
    fun isAutofillEnabled(): Flow<Boolean>
    suspend fun setAutofillEnabled(enabled: Boolean)
    /**
     * Opt-in toggle for showing category (tag) chips inside the autofill search
     * screen. Defaults to `false` because the autofill picker is a high-frequency,
     * low-real-estate surface - adding a chip row taxes every fill for the benefit
     * of the subset of users who organise vault entries by tag. Bitwarden, Proton
     * Pass, and 1Password v8 deliberately omit category filters from their pickers
     * for the same reason. Power users opt in via Settings → Autofill.
     */
    fun isAutofillCategoryFilterEnabled(): Flow<Boolean>
    suspend fun setAutofillCategoryFilterEnabled(enabled: Boolean)
    /**
     * Double-gated opt-in for the "save URL to credential" checkbox that appears
     * on the cross-host confirmation pane during autofill. Default `false`.
     *
     * Off: cross-host pane is informational only - the user must edit the
     * credential manually in 1Key to add the URL for future auto-suggestions.
     *
     * On: cross-host pane renders a disclaimer + liability paragraph and a
     * per-action checkbox (OFF every time the pane opens). Ticking the checkbox
     * AND tapping Use writes `credential.url = formHost`. This is gated behind
     * a Settings opt-in so a user who has not deliberately accepted the
     * personal-responsibility tradeoff never sees the checkbox at all - their
     * cross-host pane stays at the informational-only flavour.
     *
     * The two-gate design (Settings opt-in + per-action checkbox) is the
     * locked invariant; never collapse to a single gate. See the
     * `feedback-autofill-matching` memory for the rationale.
     */
    fun isAutofillSaveUrlOnCrossHostEnabled(): Flow<Boolean>
    suspend fun setAutofillSaveUrlOnCrossHostEnabled(enabled: Boolean)
    /** Persistent lock-reason context - survives process restart so biometric stays paused. */
    fun getLockReasonContext(): Flow<String?>
    /**
     * Race-free read of the persisted lock-reason context. Reads `dataStore.data.first()`
     * directly, bypassing the cached `prefs` StateFlow that the regular getters compose
     * on. Required by [com.roufsyed.onekey.core.security.LockReasonStore.latest] so a concurrent
     * read sees writes from a just-completed [setLockReasonContext] commit immediately,
     * with no StateFlow-collector propagation lag.
     */
    suspend fun getLockReasonContextDirect(): String?
    suspend fun setLockReasonContext(context: String?)
    /**
     * Reads `biometric_enabled` and `lock_reason_context` from the same Preferences
     * snapshot so the two values can never be observed in an inconsistent intermediate
     * state. Use this for auto-biometric gating; the per-key flows are fine elsewhere.
     */
    fun getBiometricUnlockGate(): Flow<BiometricUnlockGate>

    // ── Sync on Master Password Unlock ────────────────────────────────────────
    //
    // Single toggle plus three companion settings. When `syncEnabled` is true AND
    // `syncLocationUri` is non-null, a successful master-password unlock triggers
    // a one-shot encrypted backup write to the SAF tree URI. Biometric and PIN
    // unlocks deliberately skip sync because they do not give the app the master
    // password - persisting any MP-derived material would break PRIVACY.md:49.

    fun isSyncEnabled(): Flow<Boolean>
    suspend fun setSyncEnabled(enabled: Boolean)

    /**
     * Persisted SAF tree URI for the sync destination. Stored as a String because
     * `android.net.Uri` is not directly serialisable via DataStore; the consumer
     * (SafLocationManager) parses back via `Uri.parse`. `null` when the user has
     * not yet picked a location, or after the user disables sync.
     */
    fun getSyncLocationUri(): Flow<String?>
    suspend fun setSyncLocationUri(uri: String?)

    /** Opt-in for the post-sync completion notification. Default `false` (privacy-by-default). */
    fun isSyncCompletionNotificationEnabled(): Flow<Boolean>
    suspend fun setSyncCompletionNotificationEnabled(enabled: Boolean)

    /** Epoch-ms of the most recent successful sync; 0L means never. Set by the SyncEngine. */
    fun getSyncLastSuccessAt(): Flow<Long>
    suspend fun setSyncLastSuccessAt(timestamp: Long)

    /**
     * Race-free read of `syncEnabled` and `syncLocationUri` from the same snapshot.
     * Used at unlock-fork time so a half-toggle (enabled flipped without location set,
     * or vice versa) cannot leak through and trigger a useless sync.
     */
    suspend fun getSyncGateDirect(): SyncGate
}

data class SyncGate(
    val enabled: Boolean,
    val locationUri: String?,
)

data class BiometricUnlockGate(
    val biometricEnabled: Boolean,
    val lockReasonSet: Boolean,
)

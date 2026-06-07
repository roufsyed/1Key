package com.onekey.core.domain.repository

import com.onekey.core.domain.model.BackgroundLockTimeout
import com.onekey.core.domain.model.CredentialSortOrder
import com.onekey.core.domain.model.InactivityLockTimeout
import com.onekey.core.domain.model.MasterPasswordInterval
import com.onekey.core.domain.model.RecycleBinRetention
import kotlinx.coroutines.flow.Flow

interface AppPreferencesRepository {
    fun isDarkTheme(): Flow<Boolean>
    suspend fun setDarkTheme(dark: Boolean)
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
     * low-real-estate surface — adding a chip row taxes every fill for the benefit
     * of the subset of users who organise vault entries by tag. Bitwarden, Proton
     * Pass, and 1Password v8 deliberately omit category filters from their pickers
     * for the same reason. Power users opt in via Settings → Autofill.
     */
    fun isAutofillCategoryFilterEnabled(): Flow<Boolean>
    suspend fun setAutofillCategoryFilterEnabled(enabled: Boolean)
    /** Persistent lock-reason context — survives process restart so biometric stays paused. */
    fun getLockReasonContext(): Flow<String?>
    /**
     * Race-free read of the persisted lock-reason context. Reads `dataStore.data.first()`
     * directly, bypassing the cached `prefs` StateFlow that the regular getters compose
     * on. Required by [com.onekey.core.security.LockReasonStore.latest] so a concurrent
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
}

data class BiometricUnlockGate(
    val biometricEnabled: Boolean,
    val lockReasonSet: Boolean,
)

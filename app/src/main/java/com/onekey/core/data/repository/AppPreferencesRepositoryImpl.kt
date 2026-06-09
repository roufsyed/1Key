package com.onekey.core.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.onekey.core.di.ApplicationScope
import com.onekey.core.domain.model.BackgroundLockTimeout
import com.onekey.core.domain.model.CredentialSortOrder
import com.onekey.core.domain.model.InactivityLockTimeout
import com.onekey.core.domain.model.MasterPasswordInterval
import com.onekey.core.domain.model.RecycleBinRetention
import com.onekey.core.domain.model.ThemeMode
import com.onekey.core.domain.repository.AppPreferencesRepository
import com.onekey.core.domain.repository.BiometricUnlockGate
import com.onekey.core.domain.repository.SyncGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

private val KEY_CREDENTIAL_SORT_ORDER = stringPreferencesKey("credential_sort_order")
private val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
private val KEY_SHOW_FAVOURITES = booleanPreferencesKey("show_favourites")
private val KEY_BIOMETRIC_ENABLED = booleanPreferencesKey("biometric_enabled")
private val KEY_SCREENSHOTS_ENABLED = booleanPreferencesKey("screenshots_enabled")
private val KEY_LOCK_TIMEOUT = stringPreferencesKey("lock_timeout") // legacy (read-only for migration)
private val KEY_BACKGROUND_LOCK_TIMEOUT = stringPreferencesKey("background_lock_timeout")
private val KEY_INACTIVITY_LOCK_TIMEOUT = stringPreferencesKey("inactivity_lock_timeout")

private val DEFAULT_INACTIVITY_LOCK = InactivityLockTimeout.THIRTY_SECONDS
private val KEY_MP_RECHECK_ENABLED = booleanPreferencesKey("mp_recheck_enabled")
private val KEY_MP_RECHECK_INTERVAL = stringPreferencesKey("mp_recheck_interval")
private val KEY_LAST_MP_TIMESTAMP = longPreferencesKey("last_mp_timestamp")
private val KEY_HIDE_TOP_BAR_ON_SCROLL = booleanPreferencesKey("hide_top_bar_on_scroll")
private val KEY_RECYCLE_BIN_RETENTION = stringPreferencesKey("recycle_bin_retention")
private val KEY_RECYCLE_BIN_ENABLED = booleanPreferencesKey("recycle_bin_enabled")
private val KEY_LOCK_REASON_CONTEXT = stringPreferencesKey("lock_reason_context")
private val KEY_RESTORE_LAST_SCREEN_ON_UNLOCK = booleanPreferencesKey("restore_last_screen_on_unlock")
private val KEY_VAULT_FOOTER_VISIBLE = booleanPreferencesKey("vault_footer_visible")
private val KEY_AUTOFILL_ENABLED = booleanPreferencesKey("autofill_enabled")
private val KEY_AUTOFILL_CATEGORY_FILTER = booleanPreferencesKey("autofill_category_filter")
private val KEY_SYNC_ENABLED = booleanPreferencesKey("sync_enabled")
private val KEY_SYNC_LOCATION_URI = stringPreferencesKey("sync_location_uri")
private val KEY_SYNC_COMPLETION_NOTIFICATION_ENABLED = booleanPreferencesKey("sync_completion_notification_enabled")
private val KEY_SYNC_LAST_SUCCESS_AT = longPreferencesKey("sync_last_success_at")

@Singleton
class AppPreferencesRepositoryImpl @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    @ApplicationScope appScope: CoroutineScope,
) : AppPreferencesRepository {

    // Single hot snapshot of the entire Preferences file, started at singleton
    // creation time (app startup). All individual flows derive from this so that
    // SettingsViewModel always receives real values on first composition.
    private val prefs: StateFlow<Preferences> = dataStore.data
        .stateIn(appScope, SharingStarted.Eagerly, emptyPreferences())

    override fun getThemeMode(): Flow<ThemeMode> =
        prefs.map { p ->
            val stored = p[KEY_THEME_MODE]
            ThemeMode.entries.find { it.name == stored } ?: ThemeMode.SYSTEM
        }.distinctUntilChanged()

    override suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { p -> p[KEY_THEME_MODE] = mode.name }
    }

    override fun isBiometricEnabled(): Flow<Boolean> =
        prefs.map { it[KEY_BIOMETRIC_ENABLED] ?: false }.distinctUntilChanged()

    override suspend fun setBiometricEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_BIOMETRIC_ENABLED] = enabled }
    }

    override fun isScreenshotsEnabled(): Flow<Boolean> =
        prefs.map { it[KEY_SCREENSHOTS_ENABLED] ?: false }.distinctUntilChanged()

    override suspend fun setScreenshotsEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_SCREENSHOTS_ENABLED] = enabled }
    }

    override fun getBackgroundLockTimeout(): Flow<BackgroundLockTimeout> =
        prefs.map { p ->
            val stored = p[KEY_BACKGROUND_LOCK_TIMEOUT]
            if (stored != null) {
                BackgroundLockTimeout.entries.find { it.name == stored }
                    ?: BackgroundLockTimeout.IMMEDIATE
            } else {
                // Mid-upgrade: derive from the legacy single LockTimeout. Round down for
                // security (favour shorter lock window) when an exact match isn't present.
                when (p[KEY_LOCK_TIMEOUT]) {
                    "ONE_MINUTE", "TWO_MINUTES" -> BackgroundLockTimeout.ONE_MINUTE
                    "FIVE_MINUTES" -> BackgroundLockTimeout.FIVE_MINUTES
                    else -> BackgroundLockTimeout.IMMEDIATE
                }
            }
        }.distinctUntilChanged()

    override suspend fun setBackgroundLockTimeout(timeout: BackgroundLockTimeout) {
        dataStore.edit { it[KEY_BACKGROUND_LOCK_TIMEOUT] = timeout.name }
    }

    override fun getInactivityLockTimeout(): Flow<InactivityLockTimeout> =
        prefs.map { p ->
            val stored = p[KEY_INACTIVITY_LOCK_TIMEOUT]
            if (stored != null) {
                InactivityLockTimeout.entries.find { it.name == stored }
                    ?: DEFAULT_INACTIVITY_LOCK
            } else {
                // Default for everyone (fresh installs and legacy users mid-upgrade) is
                // 30 seconds. ONE_MINUTE / FIVE_MINUTES are honoured because users who
                // explicitly picked them clearly wanted a longer idle window.
                when (p[KEY_LOCK_TIMEOUT]) {
                    "ONE_MINUTE", "TWO_MINUTES" -> InactivityLockTimeout.ONE_MINUTE
                    "FIVE_MINUTES" -> InactivityLockTimeout.FIVE_MINUTES
                    else -> DEFAULT_INACTIVITY_LOCK
                }
            }
        }.distinctUntilChanged()

    override suspend fun setInactivityLockTimeout(timeout: InactivityLockTimeout) {
        dataStore.edit { it[KEY_INACTIVITY_LOCK_TIMEOUT] = timeout.name }
    }

    override fun isMasterPasswordRecheckEnabled(): Flow<Boolean> =
        prefs.map { it[KEY_MP_RECHECK_ENABLED] ?: true }.distinctUntilChanged()

    override suspend fun setMasterPasswordRecheckEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_MP_RECHECK_ENABLED] = enabled }
    }

    override fun getMasterPasswordRecheckInterval(): Flow<MasterPasswordInterval> =
        prefs.map { p ->
            val name = p[KEY_MP_RECHECK_INTERVAL] ?: MasterPasswordInterval.HOURS_48.name
            MasterPasswordInterval.entries.find { it.name == name } ?: MasterPasswordInterval.HOURS_48
        }.distinctUntilChanged()

    override suspend fun setMasterPasswordRecheckInterval(interval: MasterPasswordInterval) {
        dataStore.edit { it[KEY_MP_RECHECK_INTERVAL] = interval.name }
    }

    override fun getLastMasterPasswordTimestamp(): Flow<Long> =
        prefs.map { it[KEY_LAST_MP_TIMESTAMP] ?: 0L }.distinctUntilChanged()

    override suspend fun setLastMasterPasswordTimestamp(timestamp: Long) {
        dataStore.edit { it[KEY_LAST_MP_TIMESTAMP] = timestamp }
    }

    override fun isShowFavourites(): Flow<Boolean> =
        prefs.map { it[KEY_SHOW_FAVOURITES] ?: false }.distinctUntilChanged()

    override suspend fun setShowFavourites(show: Boolean) {
        dataStore.edit { it[KEY_SHOW_FAVOURITES] = show }
    }

    override fun getCredentialSortOrder(): Flow<CredentialSortOrder> =
        prefs.map { p ->
            val name = p[KEY_CREDENTIAL_SORT_ORDER] ?: CredentialSortOrder.NEWEST_FIRST.name
            CredentialSortOrder.entries.find { it.name == name } ?: CredentialSortOrder.NEWEST_FIRST
        }.distinctUntilChanged()

    override suspend fun setCredentialSortOrder(order: CredentialSortOrder) {
        dataStore.edit { it[KEY_CREDENTIAL_SORT_ORDER] = order.name }
    }

    override fun isHideTopBarOnScroll(): Flow<Boolean> =
        prefs.map { it[KEY_HIDE_TOP_BAR_ON_SCROLL] ?: true }.distinctUntilChanged()

    override suspend fun setHideTopBarOnScroll(enabled: Boolean) {
        dataStore.edit { it[KEY_HIDE_TOP_BAR_ON_SCROLL] = enabled }
    }

    override fun getRecycleBinRetention(): Flow<RecycleBinRetention> =
        prefs.map { p ->
            val name = p[KEY_RECYCLE_BIN_RETENTION] ?: RecycleBinRetention.DAYS_30.name
            RecycleBinRetention.entries.find { it.name == name } ?: RecycleBinRetention.DAYS_30
        }.distinctUntilChanged()

    override suspend fun setRecycleBinRetention(retention: RecycleBinRetention) {
        dataStore.edit { it[KEY_RECYCLE_BIN_RETENTION] = retention.name }
    }

    override fun isRecycleBinEnabled(): Flow<Boolean> =
        prefs.map { it[KEY_RECYCLE_BIN_ENABLED] ?: true }.distinctUntilChanged()

    override suspend fun setRecycleBinEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_RECYCLE_BIN_ENABLED] = enabled }
    }

    override fun isRestoreLastScreenOnUnlock(): Flow<Boolean> =
        prefs.map { it[KEY_RESTORE_LAST_SCREEN_ON_UNLOCK] ?: false }.distinctUntilChanged()

    override suspend fun setRestoreLastScreenOnUnlock(enabled: Boolean) {
        dataStore.edit { it[KEY_RESTORE_LAST_SCREEN_ON_UNLOCK] = enabled }
    }

    override suspend fun getLockReasonContextDirect(): String? =
        dataStore.data.first()[KEY_LOCK_REASON_CONTEXT]

    override fun isAutofillEnabled(): Flow<Boolean> =
        prefs.map { it[KEY_AUTOFILL_ENABLED] ?: true }.distinctUntilChanged()

    override suspend fun setAutofillEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_AUTOFILL_ENABLED] = enabled }
    }

    override fun isAutofillCategoryFilterEnabled(): Flow<Boolean> =
        prefs.map { it[KEY_AUTOFILL_CATEGORY_FILTER] ?: false }.distinctUntilChanged()

    override suspend fun setAutofillCategoryFilterEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_AUTOFILL_CATEGORY_FILTER] = enabled }
    }

    override fun isVaultFooterVisible(): Flow<Boolean> =
        prefs.map { it[KEY_VAULT_FOOTER_VISIBLE] ?: true }.distinctUntilChanged()

    override suspend fun setVaultFooterVisible(visible: Boolean) {
        dataStore.edit { it[KEY_VAULT_FOOTER_VISIBLE] = visible }
    }

    override fun getLockReasonContext(): Flow<String?> =
        prefs.map { it[KEY_LOCK_REASON_CONTEXT] }.distinctUntilChanged()

    override suspend fun setLockReasonContext(context: String?) {
        dataStore.edit { p ->
            if (context == null) p.remove(KEY_LOCK_REASON_CONTEXT) else p[KEY_LOCK_REASON_CONTEXT] = context
        }
    }

    override fun getBiometricUnlockGate(): Flow<BiometricUnlockGate> =
        prefs.map { p ->
            BiometricUnlockGate(
                biometricEnabled = p[KEY_BIOMETRIC_ENABLED] ?: false,
                lockReasonSet = p[KEY_LOCK_REASON_CONTEXT] != null,
            )
        }.distinctUntilChanged()

    // ── Sync on Master Password Unlock ────────────────────────────────────────

    override fun isSyncEnabled(): Flow<Boolean> =
        prefs.map { it[KEY_SYNC_ENABLED] ?: false }.distinctUntilChanged()

    override suspend fun setSyncEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_SYNC_ENABLED] = enabled }
    }

    override fun getSyncLocationUri(): Flow<String?> =
        prefs.map { it[KEY_SYNC_LOCATION_URI] }.distinctUntilChanged()

    override suspend fun setSyncLocationUri(uri: String?) {
        dataStore.edit { p ->
            if (uri == null) p.remove(KEY_SYNC_LOCATION_URI) else p[KEY_SYNC_LOCATION_URI] = uri
        }
    }

    override fun isSyncCompletionNotificationEnabled(): Flow<Boolean> =
        prefs.map { it[KEY_SYNC_COMPLETION_NOTIFICATION_ENABLED] ?: false }.distinctUntilChanged()

    override suspend fun setSyncCompletionNotificationEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_SYNC_COMPLETION_NOTIFICATION_ENABLED] = enabled }
    }

    override fun getSyncLastSuccessAt(): Flow<Long> =
        prefs.map { it[KEY_SYNC_LAST_SUCCESS_AT] ?: 0L }.distinctUntilChanged()

    override suspend fun setSyncLastSuccessAt(timestamp: Long) {
        dataStore.edit { it[KEY_SYNC_LAST_SUCCESS_AT] = timestamp }
    }

    override suspend fun getSyncGateDirect(): SyncGate {
        // Race-free read direct from DataStore so the unlock-fork sees writes from a
        // just-completed setSyncEnabled / setSyncLocationUri commit without waiting for
        // the cached prefs StateFlow to propagate. Same rationale as getLockReasonContextDirect.
        val snapshot = dataStore.data.first()
        return SyncGate(
            enabled = snapshot[KEY_SYNC_ENABLED] ?: false,
            locationUri = snapshot[KEY_SYNC_LOCATION_URI],
        )
    }
}

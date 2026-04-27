package com.onekey.core.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.onekey.core.di.ApplicationScope
import com.onekey.core.domain.model.CredentialSortOrder
import com.onekey.core.domain.model.LockTimeout
import com.onekey.core.domain.model.MasterPasswordInterval
import com.onekey.core.domain.repository.AppPreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

private val KEY_CREDENTIAL_SORT_ORDER = stringPreferencesKey("credential_sort_order")
private val KEY_DARK_THEME = booleanPreferencesKey("dark_theme")
private val KEY_SHOW_FAVOURITES = booleanPreferencesKey("show_favourites")
private val KEY_BIOMETRIC_ENABLED = booleanPreferencesKey("biometric_enabled")
private val KEY_SCREENSHOTS_ENABLED = booleanPreferencesKey("screenshots_enabled")
private val KEY_LOCK_TIMEOUT = stringPreferencesKey("lock_timeout")
private val KEY_MP_RECHECK_ENABLED = booleanPreferencesKey("mp_recheck_enabled")
private val KEY_MP_RECHECK_INTERVAL = stringPreferencesKey("mp_recheck_interval")
private val KEY_LAST_MP_TIMESTAMP = longPreferencesKey("last_mp_timestamp")
private val KEY_HIDE_TOP_BAR_ON_SCROLL = booleanPreferencesKey("hide_top_bar_on_scroll")

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

    override fun isDarkTheme(): Flow<Boolean> =
        prefs.map { it[KEY_DARK_THEME] ?: false }.distinctUntilChanged()

    override suspend fun setDarkTheme(dark: Boolean) {
        dataStore.edit { it[KEY_DARK_THEME] = dark }
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

    override fun getLockTimeout(): Flow<LockTimeout> =
        prefs.map { p ->
            val name = p[KEY_LOCK_TIMEOUT] ?: LockTimeout.IMMEDIATE.name
            LockTimeout.entries.find { it.name == name } ?: LockTimeout.IMMEDIATE
        }.distinctUntilChanged()

    override suspend fun setLockTimeout(timeout: LockTimeout) {
        dataStore.edit { it[KEY_LOCK_TIMEOUT] = timeout.name }
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
}

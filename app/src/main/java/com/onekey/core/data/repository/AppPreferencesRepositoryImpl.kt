package com.onekey.core.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.onekey.core.domain.model.LockTimeout
import com.onekey.core.domain.repository.AppPreferencesRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val KEY_DARK_THEME = booleanPreferencesKey("dark_theme")
private val KEY_BIOMETRIC_ENABLED = booleanPreferencesKey("biometric_enabled")
private val KEY_SCREENSHOTS_ENABLED = booleanPreferencesKey("screenshots_enabled")
private val KEY_LOCK_TIMEOUT = stringPreferencesKey("lock_timeout")

@Singleton
class AppPreferencesRepositoryImpl @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : AppPreferencesRepository {

    override fun isDarkTheme(): Flow<Boolean> =
        dataStore.data.map { it[KEY_DARK_THEME] ?: false }.distinctUntilChanged()

    override suspend fun setDarkTheme(dark: Boolean) {
        dataStore.edit { it[KEY_DARK_THEME] = dark }
    }

    override fun isBiometricEnabled(): Flow<Boolean> =
        dataStore.data.map { it[KEY_BIOMETRIC_ENABLED] ?: false }.distinctUntilChanged()

    override suspend fun setBiometricEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_BIOMETRIC_ENABLED] = enabled }
    }

    override fun isScreenshotsEnabled(): Flow<Boolean> =
        dataStore.data.map { it[KEY_SCREENSHOTS_ENABLED] ?: true }.distinctUntilChanged()

    override suspend fun setScreenshotsEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_SCREENSHOTS_ENABLED] = enabled }
    }

    override fun getLockTimeout(): Flow<LockTimeout> =
        dataStore.data.map { prefs ->
            val name = prefs[KEY_LOCK_TIMEOUT] ?: LockTimeout.IMMEDIATE.name
            LockTimeout.entries.find { it.name == name } ?: LockTimeout.IMMEDIATE
        }.distinctUntilChanged()

    override suspend fun setLockTimeout(timeout: LockTimeout) {
        dataStore.edit { it[KEY_LOCK_TIMEOUT] = timeout.name }
    }
}

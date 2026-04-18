package com.onekey.core.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.onekey.core.domain.repository.AppPreferencesRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val KEY_DARK_THEME = booleanPreferencesKey("dark_theme")
private val KEY_BIOMETRIC_ENABLED = booleanPreferencesKey("biometric_enabled")

@Singleton
class AppPreferencesRepositoryImpl @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : AppPreferencesRepository {

    override fun isDarkTheme(): Flow<Boolean> =
        dataStore.data.map { it[KEY_DARK_THEME] ?: false }

    override suspend fun setDarkTheme(dark: Boolean) {
        dataStore.edit { it[KEY_DARK_THEME] = dark }
    }

    override fun isBiometricEnabled(): Flow<Boolean> =
        dataStore.data.map { it[KEY_BIOMETRIC_ENABLED] ?: false }

    override suspend fun setBiometricEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_BIOMETRIC_ENABLED] = enabled }
    }
}

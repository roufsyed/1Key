package com.roufsyed.onekey.feature.passkey.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Passkey-provider preferences, kept apart from [com.roufsyed.onekey.core.domain.repository.AppPreferencesRepository]
 * so the feature's settings do not widen an interface seven test fakes implement.
 */
interface PasskeyPreferences {
    /**
     * Soft kill switch mirroring the autofill one: Android decides whether 1Key
     * is a selected provider, this decides whether 1Key answers. Default on so
     * enabling 1Key in Android settings works immediately.
     */
    fun isProviderEnabled(): Flow<Boolean>
    suspend fun setProviderEnabled(enabled: Boolean)

    /**
     * Browsers the user trusted by hand, as an allowlist document in Google's
     * schema (see `PrivilegedAppList`). Null until the user adds one.
     */
    fun trustedBrowsersJson(): Flow<String?>
    suspend fun setTrustedBrowsersJson(json: String?)
}

private val KEY_PASSKEY_PROVIDER_ENABLED = booleanPreferencesKey("passkey_provider_enabled")
private val KEY_PASSKEY_TRUSTED_BROWSERS = stringPreferencesKey("passkey_trusted_browsers_json")

@Singleton
class PasskeyPreferencesImpl @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : PasskeyPreferences {

    override fun isProviderEnabled(): Flow<Boolean> =
        dataStore.data.map { it[KEY_PASSKEY_PROVIDER_ENABLED] ?: true }.distinctUntilChanged()

    override suspend fun setProviderEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_PASSKEY_PROVIDER_ENABLED] = enabled }
    }

    override fun trustedBrowsersJson(): Flow<String?> =
        dataStore.data.map { it[KEY_PASSKEY_TRUSTED_BROWSERS] }.distinctUntilChanged()

    override suspend fun setTrustedBrowsersJson(json: String?) {
        dataStore.edit { prefs ->
            if (json.isNullOrBlank()) prefs.remove(KEY_PASSKEY_TRUSTED_BROWSERS) else prefs[KEY_PASSKEY_TRUSTED_BROWSERS] = json
        }
    }
}

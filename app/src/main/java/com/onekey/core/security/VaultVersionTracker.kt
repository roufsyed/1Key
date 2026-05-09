package com.onekey.core.security

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

private val KEY_VAULT_VERSION = intPreferencesKey("vault_version")

/**
 * Monotonically increasing counter that advances whenever the vault's master password
 * changes. Embedded in the V4 backup AAD so a backup file is cryptographically bound
 * to the vault state at the time of export: swapping the ciphertext body or the header
 * fields across files with different version counters will fail GCM authentication.
 *
 * Counter starts at 0 for new vaults and existing vaults that predate this field.
 * It is intentionally not decremented or reset — only ever incremented.
 */
@Singleton
class VaultVersionTracker @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    suspend fun getVersion(): Int = dataStore.data.first()[KEY_VAULT_VERSION] ?: 0

    suspend fun increment() {
        dataStore.edit { prefs ->
            prefs[KEY_VAULT_VERSION] = (prefs[KEY_VAULT_VERSION] ?: 0) + 1
        }
    }
}

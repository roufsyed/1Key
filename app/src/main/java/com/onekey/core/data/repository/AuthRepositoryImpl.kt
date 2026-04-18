package com.onekey.core.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import com.onekey.core.domain.model.AppResult
import com.onekey.core.domain.model.runCatchingResult
import com.onekey.core.domain.repository.AuthRepository
import com.onekey.core.security.CryptoManager
import com.onekey.core.security.EncryptedData
import com.onekey.core.security.VaultKeyHolder
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import javax.inject.Singleton

private val KEY_SETUP_COMPLETE = booleanPreferencesKey("setup_complete")
private val KEY_SALT = stringPreferencesKey("vault_salt")
private val KEY_WRAPPED_KEY_CT = stringPreferencesKey("wrapped_key_ct")
private val KEY_WRAPPED_KEY_IV = stringPreferencesKey("wrapped_key_iv")
private val KEY_PASSWORD_VERIFIER = stringPreferencesKey("password_verifier")
private val KEY_PIN_HASH = stringPreferencesKey("pin_hash")
private val KEY_PIN_SALT = stringPreferencesKey("pin_salt")

@Singleton
class AuthRepositoryImpl @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val crypto: CryptoManager,
    private val keyHolder: VaultKeyHolder,
) : AuthRepository {

    override fun isSetupComplete(): Flow<Boolean> =
        dataStore.data.map { it[KEY_SETUP_COMPLETE] ?: false }.distinctUntilChanged()

    override suspend fun setupMasterPassword(password: CharArray): AppResult<Unit> =
        runCatchingResult {
            val salt = crypto.generateSalt()
            val vaultKey = crypto.deriveKeyFromPassword(password, salt)

            val wrapped = crypto.wrapKey(vaultKey)

            val verifierSalt = crypto.generateSalt()
            val verifierKey = crypto.deriveKeyFromPassword(password, verifierSalt)
            val verifier = crypto.encryptString("VALID", verifierKey)

            dataStore.edit { prefs ->
                prefs[KEY_SETUP_COMPLETE] = true
                prefs[KEY_SALT] = android.util.Base64.encodeToString(salt, android.util.Base64.NO_WRAP)
                prefs[KEY_WRAPPED_KEY_CT] = android.util.Base64.encodeToString(wrapped.ciphertext, android.util.Base64.NO_WRAP)
                prefs[KEY_WRAPPED_KEY_IV] = android.util.Base64.encodeToString(wrapped.iv, android.util.Base64.NO_WRAP)
                prefs[KEY_PASSWORD_VERIFIER] = listOf(
                    android.util.Base64.encodeToString(verifierSalt, android.util.Base64.NO_WRAP),
                    android.util.Base64.encodeToString(verifier.ciphertext, android.util.Base64.NO_WRAP),
                    android.util.Base64.encodeToString(verifier.iv, android.util.Base64.NO_WRAP),
                ).joinToString(":")
            }
            password.fill('\u0000')
            keyHolder.setKey(vaultKey)
        }

    override suspend fun unlockWithPassword(password: CharArray): AppResult<Unit> =
        runCatchingResult {
            val prefs = dataStore.data.first()

            val verifierStr = prefs[KEY_PASSWORD_VERIFIER] ?: error("No verifier stored")
            val verifierParts = verifierStr.split(":")
            require(verifierParts.size == 3) { "Corrupted verifier data" }
            val verSalt = android.util.Base64.decode(verifierParts[0], android.util.Base64.NO_WRAP)
            val verCt = android.util.Base64.decode(verifierParts[1], android.util.Base64.NO_WRAP)
            val verIv = android.util.Base64.decode(verifierParts[2], android.util.Base64.NO_WRAP)
            val verKey = crypto.deriveKeyFromPassword(password, verSalt)
            val plainVerifier = crypto.decryptString(EncryptedData(verCt, verIv), verKey)
            check(plainVerifier == "VALID") { "Invalid master password" }
            password.fill('\u0000')

            // Load the vault key from the Keystore-wrapped blob (decoupled from password)
            val vaultKey = unwrapStoredKey(prefs)
            keyHolder.setKey(vaultKey)
        }

    override suspend fun unlockWithBiometric(): AppResult<Unit> = runCatchingResult {
        val prefs = dataStore.data.first()
        val vaultKey = unwrapStoredKey(prefs)
        keyHolder.setKey(vaultKey)
    }

    override suspend fun setupPin(pin: String): AppResult<Unit> = runCatchingResult {
        require(pin.length in 4..8) { "PIN must be 4–8 digits" }
        val pinChars = pin.toCharArray()
        val salt = crypto.generateSalt()
        val pinKey = crypto.deriveKeyFromPassword(pinChars, salt)
        pinChars.fill('\u0000')
        val pinHash = crypto.encryptString("PIN_VALID", pinKey)
        dataStore.edit { prefs ->
            prefs[KEY_PIN_SALT] = android.util.Base64.encodeToString(salt, android.util.Base64.NO_WRAP)
            prefs[KEY_PIN_HASH] = listOf(
                android.util.Base64.encodeToString(pinHash.ciphertext, android.util.Base64.NO_WRAP),
                android.util.Base64.encodeToString(pinHash.iv, android.util.Base64.NO_WRAP),
            ).joinToString(":")
        }
    }

    override suspend fun unlockWithPin(pin: String): AppResult<Unit> = runCatchingResult {
        val prefs = dataStore.data.first()
        val saltB64 = prefs[KEY_PIN_SALT] ?: error("PIN not set up")
        val salt = android.util.Base64.decode(saltB64, android.util.Base64.NO_WRAP)

        val hashStr = prefs[KEY_PIN_HASH] ?: error("PIN hash missing")
        val parts = hashStr.split(":")
        require(parts.size == 2) { "Corrupted PIN hash data" }
        val ct = android.util.Base64.decode(parts[0], android.util.Base64.NO_WRAP)
        val iv = android.util.Base64.decode(parts[1], android.util.Base64.NO_WRAP)

        val pinChars = pin.toCharArray()
        val pinKey = crypto.deriveKeyFromPassword(pinChars, salt)
        pinChars.fill('\u0000')

        val result = crypto.decryptString(EncryptedData(ct, iv), pinKey)
        check(result == "PIN_VALID") { "Invalid PIN" }

        val vaultKey = unwrapStoredKey(prefs)
        keyHolder.setKey(vaultKey)
    }

    override suspend fun changePassword(oldPassword: CharArray, newPassword: CharArray): AppResult<Unit> =
        runCatchingResult {
            val prefs = dataStore.data.first()

            // Verify old password
            val verifierStr = prefs[KEY_PASSWORD_VERIFIER] ?: error("No verifier stored")
            val verifierParts = verifierStr.split(":")
            require(verifierParts.size == 3) { "Corrupted verifier data" }
            val verSalt = android.util.Base64.decode(verifierParts[0], android.util.Base64.NO_WRAP)
            val verCt = android.util.Base64.decode(verifierParts[1], android.util.Base64.NO_WRAP)
            val verIv = android.util.Base64.decode(verifierParts[2], android.util.Base64.NO_WRAP)
            val verKey = crypto.deriveKeyFromPassword(oldPassword, verSalt)
            val plainVerifier = crypto.decryptString(EncryptedData(verCt, verIv), verKey)
            check(plainVerifier == "VALID") { "Current password is incorrect" }
            oldPassword.fill('\u0000')

            // Build new verifier for new password (vault key itself doesn't change)
            val newVerSalt = crypto.generateSalt()
            val newVerKey = crypto.deriveKeyFromPassword(newPassword, newVerSalt)
            val newVerifier = crypto.encryptString("VALID", newVerKey)
            newPassword.fill('\u0000')

            dataStore.edit { p ->
                p[KEY_PASSWORD_VERIFIER] = listOf(
                    android.util.Base64.encodeToString(newVerSalt, android.util.Base64.NO_WRAP),
                    android.util.Base64.encodeToString(newVerifier.ciphertext, android.util.Base64.NO_WRAP),
                    android.util.Base64.encodeToString(newVerifier.iv, android.util.Base64.NO_WRAP),
                ).joinToString(":")
            }
            // VaultKeyHolder key remains the same — no credential re-encryption needed
        }

    override suspend fun resetPin(): AppResult<Unit> = runCatchingResult {
        dataStore.edit { prefs ->
            prefs.remove(KEY_PIN_HASH)
            prefs.remove(KEY_PIN_SALT)
        }
    }

    override suspend fun resetVault(): AppResult<Unit> = runCatchingResult {
        crypto.deleteVaultKey()
        dataStore.edit { it.clear() }
        keyHolder.lock()
    }

    override suspend fun lock(): AppResult<Unit> = runCatchingResult { keyHolder.lock() }

    override fun isUnlocked(): Flow<Boolean> = keyHolder.isUnlocked

    override fun isPinSetup(): Flow<Boolean> =
        dataStore.data.map { it[KEY_PIN_HASH] != null }.distinctUntilChanged()

    override suspend fun clearAll(): AppResult<Unit> = runCatchingResult {
        dataStore.edit { it.clear() }
        keyHolder.lock()
    }

    private fun unwrapStoredKey(prefs: Preferences): javax.crypto.SecretKey {
        val ct = prefs[KEY_WRAPPED_KEY_CT]?.let {
            android.util.Base64.decode(it, android.util.Base64.NO_WRAP)
        } ?: error("Vault key not found")
        val iv = prefs[KEY_WRAPPED_KEY_IV]?.let {
            android.util.Base64.decode(it, android.util.Base64.NO_WRAP)
        } ?: error("Vault key IV not found")
        return crypto.unwrapKey(EncryptedData(ct, iv))
    }
}

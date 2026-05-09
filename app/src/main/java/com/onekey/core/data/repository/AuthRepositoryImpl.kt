package com.onekey.core.data.repository

import android.content.SharedPreferences
import android.os.Build
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import com.onekey.core.di.ApplicationScope
import com.onekey.core.domain.model.AppResult
import com.onekey.core.domain.model.runCatchingResult
import com.onekey.core.domain.repository.AuthRepository
import com.onekey.core.security.CryptoManager
import com.onekey.core.security.EncryptedData
import com.onekey.core.security.KEYSTORE_ALIAS_V1
import com.onekey.core.security.KEYSTORE_ALIAS_V2
import com.onekey.core.security.VaultKeyHolder
import com.onekey.core.security.VaultVersionTracker
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.crypto.BadPaddingException
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

// ── EncryptedSharedPreferences key names (must match legacy DataStore names for migration) ──

private const val SP_SETUP_COMPLETE    = "setup_complete"
private const val SP_SALT              = "vault_salt"
private const val SP_WRAPPED_KEY_CT    = "wrapped_key_ct"
private const val SP_WRAPPED_KEY_IV    = "wrapped_key_iv"
private const val SP_PASSWORD_VERIFIER = "password_verifier"
private const val SP_KDF_VERSION       = "kdf_version"
private const val SP_PIN_HASH          = "pin_hash"
private const val SP_PIN_SALT          = "pin_salt"
private const val SP_PIN_KDF_VERSION   = "pin_kdf_version"
private const val SP_KEYSTORE_UPGRADED = "ks_upgraded"
private const val SP_WRAPPED_KEY_CT_V2 = "wrapped_key_ct_v2"
private const val SP_WRAPPED_KEY_IV_V2 = "wrapped_key_iv_v2"

// ── Legacy DataStore keys — read-only, used only during the one-time migration ──

private val DS_SETUP_COMPLETE    = booleanPreferencesKey(SP_SETUP_COMPLETE)
private val DS_SALT              = stringPreferencesKey(SP_SALT)
private val DS_WRAPPED_KEY_CT    = stringPreferencesKey(SP_WRAPPED_KEY_CT)
private val DS_WRAPPED_KEY_IV    = stringPreferencesKey(SP_WRAPPED_KEY_IV)
private val DS_PASSWORD_VERIFIER = stringPreferencesKey(SP_PASSWORD_VERIFIER)
private val DS_KDF_VERSION       = intPreferencesKey(SP_KDF_VERSION)
private val DS_PIN_HASH          = stringPreferencesKey(SP_PIN_HASH)
private val DS_PIN_SALT          = stringPreferencesKey(SP_PIN_SALT)
private val DS_PIN_KDF_VERSION   = intPreferencesKey(SP_PIN_KDF_VERSION)
private val DS_KEYSTORE_UPGRADED = booleanPreferencesKey(SP_KEYSTORE_UPGRADED)
private val DS_WRAPPED_KEY_CT_V2 = stringPreferencesKey(SP_WRAPPED_KEY_CT_V2)
private val DS_WRAPPED_KEY_IV_V2 = stringPreferencesKey(SP_WRAPPED_KEY_IV_V2)

private const val KDF_PBKDF2   = 0
private const val KDF_ARGON2ID = 1

@Singleton
class AuthRepositoryImpl @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    @Named("auth") private val authPrefs: SharedPreferences,
    private val crypto: CryptoManager,
    private val keyHolder: VaultKeyHolder,
    private val vaultVersionTracker: VaultVersionTracker,
    @ApplicationScope appScope: CoroutineScope,
) : AuthRepository {

    /**
     * Completed once the one-time DataStore→EncryptedSharedPreferences migration has
     * run (or was skipped because no legacy data existed). All auth reads await this
     * before accessing [authPrefs] so they never see a partially-migrated state.
     */
    private val migrationComplete = CompletableDeferred<Unit>()

    init {
        appScope.launch(Dispatchers.IO) {
            try {
                migrateFromDataStoreIfNeeded()
            } finally {
                migrationComplete.complete(Unit)
            }
        }
    }

    /**
     * One-time migration: copies auth keys from the legacy plaintext DataStore into
     * [authPrefs] (EncryptedSharedPreferences), then removes them from DataStore.
     * Safe to retry: if [authPrefs] already contains [SP_SETUP_COMPLETE] the
     * migration is considered done and skipped.
     */
    private suspend fun migrateFromDataStoreIfNeeded() {
        if (authPrefs.contains(SP_SETUP_COMPLETE)) return

        val old = dataStore.data.first()
        val setupComplete = old[DS_SETUP_COMPLETE] ?: return  // new install, nothing to migrate

        authPrefs.edit().apply {
            putBoolean(SP_SETUP_COMPLETE, setupComplete)
            old[DS_SALT]?.let              { putString(SP_SALT, it) }
            old[DS_WRAPPED_KEY_CT]?.let    { putString(SP_WRAPPED_KEY_CT, it) }
            old[DS_WRAPPED_KEY_IV]?.let    { putString(SP_WRAPPED_KEY_IV, it) }
            old[DS_PASSWORD_VERIFIER]?.let { putString(SP_PASSWORD_VERIFIER, it) }
            old[DS_KDF_VERSION]?.let       { putInt(SP_KDF_VERSION, it) }
            old[DS_PIN_HASH]?.let          { putString(SP_PIN_HASH, it) }
            old[DS_PIN_SALT]?.let          { putString(SP_PIN_SALT, it) }
            old[DS_PIN_KDF_VERSION]?.let   { putInt(SP_PIN_KDF_VERSION, it) }
            old[DS_KEYSTORE_UPGRADED]?.let { putBoolean(SP_KEYSTORE_UPGRADED, it) }
            old[DS_WRAPPED_KEY_CT_V2]?.let { putString(SP_WRAPPED_KEY_CT_V2, it) }
            old[DS_WRAPPED_KEY_IV_V2]?.let { putString(SP_WRAPPED_KEY_IV_V2, it) }
        }.commit()  // commit() = synchronous write; essential before clearing source

        // Remove auth keys from DataStore now that they live in EncryptedSharedPreferences.
        // Failure here is tolerable — the keys are orphaned in DataStore but the app
        // always reads from authPrefs going forward.
        runCatching {
            dataStore.edit { p ->
                p.remove(DS_SETUP_COMPLETE)
                p.remove(DS_SALT)
                p.remove(DS_WRAPPED_KEY_CT)
                p.remove(DS_WRAPPED_KEY_IV)
                p.remove(DS_PASSWORD_VERIFIER)
                p.remove(DS_KDF_VERSION)
                p.remove(DS_PIN_HASH)
                p.remove(DS_PIN_SALT)
                p.remove(DS_PIN_KDF_VERSION)
                p.remove(DS_KEYSTORE_UPGRADED)
                p.remove(DS_WRAPPED_KEY_CT_V2)
                p.remove(DS_WRAPPED_KEY_IV_V2)
            }
        }
    }

    override fun isSetupComplete(): Flow<Boolean> = flow {
        migrationComplete.await()
        emitAll(authPrefs.watchBoolean(SP_SETUP_COMPLETE, false))
    }.distinctUntilChanged()

    // ── Setup ─────────────────────────────────────────────────────────────────

    override suspend fun setupMasterPassword(password: CharArray): AppResult<Unit> =
        runCatchingResult {
            migrationComplete.await()

            val vaultSalt = crypto.generateSalt()
            val vaultKey = crypto.deriveKeyFromPassword(password, vaultSalt)
            val keystoreKey = crypto.getOrCreateLegacyKeystoreKey()
            val wrapped = crypto.wrapKey(vaultKey, keystoreKey)

            val verifierSalt = crypto.generateSalt()
            val verifierKey = crypto.deriveKeyFromPasswordArgon2id(password, verifierSalt)
            val verifier = crypto.encryptString("VALID", verifierKey)

            authPrefs.edit().apply {
                putBoolean(SP_SETUP_COMPLETE, true)
                putString(SP_SALT, vaultSalt.encodeBase64())
                putString(SP_WRAPPED_KEY_CT, wrapped.ciphertext.encodeBase64())
                putString(SP_WRAPPED_KEY_IV, wrapped.iv.encodeBase64())
                putString(SP_PASSWORD_VERIFIER, encodeVerifier(verifierSalt, verifier))
                putInt(SP_KDF_VERSION, KDF_ARGON2ID)
            }.commit()

            password.fill(' ')
            keyHolder.setKey(vaultKey)
        }

    // ── Unlock ────────────────────────────────────────────────────────────────

    override suspend fun unlockWithPassword(password: CharArray): AppResult<Unit> =
        runCatchingResult {
            migrationComplete.await()

            val kdfVersion = authPrefs.getInt(SP_KDF_VERSION, KDF_PBKDF2)
            val passwordForMigration = if (kdfVersion == KDF_PBKDF2) password.copyOf() else null

            verifyMasterPassword(password, kdfVersion)

            val vaultKey = unwrapStoredKey()
            keyHolder.setKey(vaultKey)

            if (kdfVersion == KDF_PBKDF2 && passwordForMigration != null) {
                try {
                    migrateVerifierToArgon2id(passwordForMigration)
                } catch (_: Exception) {
                } finally {
                    passwordForMigration.fill(' ')
                }
            }

            val keystoreUpgraded = authPrefs.getBoolean(SP_KEYSTORE_UPGRADED, false)
            if (!keystoreUpgraded && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                try {
                    migrateKeystoreKey(vaultKey)
                } catch (_: Exception) {
                }
            }
        }

    override suspend fun unlockWithBiometric(): AppResult<Unit> = runCatchingResult {
        migrationComplete.await()
        keyHolder.setKey(unwrapStoredKey())
    }

    // ── Change password ───────────────────────────────────────────────────────

    override suspend fun changePassword(oldPassword: CharArray, newPassword: CharArray): AppResult<Unit> =
        runCatchingResult {
            migrationComplete.await()

            val kdfVersion = authPrefs.getInt(SP_KDF_VERSION, KDF_PBKDF2)
            verifyMasterPassword(oldPassword, kdfVersion)

            val newVerifierSalt = crypto.generateSalt()
            val newVerifierKey = crypto.deriveKeyFromPasswordArgon2id(newPassword, newVerifierSalt)
            val newVerifier = crypto.encryptString("VALID", newVerifierKey)
            newPassword.fill(' ')

            authPrefs.edit().apply {
                putString(SP_PASSWORD_VERIFIER, encodeVerifier(newVerifierSalt, newVerifier))
                putInt(SP_KDF_VERSION, KDF_ARGON2ID)
            }.commit()

            vaultVersionTracker.increment()
        }

    // ── PIN ───────────────────────────────────────────────────────────────────

    override suspend fun setupPin(pin: CharArray): AppResult<Unit> = runCatchingResult {
        migrationComplete.await()

        require(pin.size == 6) { "PIN must be 6 digits" }
        val salt = crypto.generateSalt()
        val pinKey = crypto.deriveKeyFromPasswordArgon2id(pin, salt)
        pin.fill(' ')
        val pinHash = crypto.encryptString("PIN_VALID", pinKey)

        authPrefs.edit().apply {
            putString(SP_PIN_SALT, salt.encodeBase64())
            putString(SP_PIN_HASH, buildString {
                append(pinHash.ciphertext.encodeBase64())
                append(':')
                append(pinHash.iv.encodeBase64())
            })
            putInt(SP_PIN_KDF_VERSION, KDF_ARGON2ID)
        }.commit()
    }

    override suspend fun unlockWithPin(pin: CharArray): AppResult<Unit> = runCatchingResult {
        migrationComplete.await()

        val pinCopy = pin.copyOf()
        verifyPin(pin)
        // pin is zeroed inside verifyPin.

        keyHolder.setKey(unwrapStoredKey())

        val pinKdfVersion = authPrefs.getInt(SP_PIN_KDF_VERSION, KDF_PBKDF2)
        if (pinKdfVersion == KDF_PBKDF2) {
            try {
                migratePinVerifierToArgon2id(pinCopy)
            } catch (_: Exception) {
            } finally {
                pinCopy.fill(' ')
            }
        } else {
            pinCopy.fill(' ')
        }
    }

    override suspend fun verifyPin(pin: CharArray): AppResult<Unit> = runCatchingResult {
        migrationComplete.await()
        verifyPinInternal(pin)
    }

    // ── Core verification helpers ─────────────────────────────────────────────

    private fun verifyMasterPassword(password: CharArray, kdfVersion: Int) {
        val verifierStr = authPrefs.getString(SP_PASSWORD_VERIFIER, null)
            ?: error("No verifier stored")
        val parts = verifierStr.split(":")
        require(parts.size == 3) { "Corrupted verifier data" }
        val verSalt = parts[0].decodeBase64()
        val verCt   = parts[1].decodeBase64()
        val verIv   = parts[2].decodeBase64()

        val verifierKey = when (kdfVersion) {
            KDF_ARGON2ID -> crypto.deriveKeyFromPasswordArgon2id(password, verSalt)
            else         -> crypto.deriveKeyFromPassword(password, verSalt)
        }
        password.fill(' ')

        val plainVerifier = try {
            crypto.decryptString(EncryptedData(verCt, verIv), verifierKey)
        } catch (e: BadPaddingException) {
            throw IllegalStateException("Incorrect master password. Please try again.", e)
        }
        check(plainVerifier == "VALID") { "Incorrect master password. Please try again." }
    }

    private fun verifyPinInternal(pin: CharArray) {
        val saltB64  = authPrefs.getString(SP_PIN_SALT, null)  ?: error("PIN not set up")
        val hashStr  = authPrefs.getString(SP_PIN_HASH, null)  ?: error("PIN hash missing")
        val salt     = saltB64.decodeBase64()

        val parts = hashStr.split(":")
        require(parts.size == 2) { "Corrupted PIN hash data" }
        val ct = parts[0].decodeBase64()
        val iv = parts[1].decodeBase64()

        val pinKdfVersion = authPrefs.getInt(SP_PIN_KDF_VERSION, KDF_PBKDF2)
        val pinKey = when (pinKdfVersion) {
            KDF_ARGON2ID -> crypto.deriveKeyFromPasswordArgon2id(pin, salt)
            else         -> crypto.deriveKeyFromPassword(pin, salt)
        }
        pin.fill(' ')

        val result = try {
            crypto.decryptString(EncryptedData(ct, iv), pinKey)
        } catch (e: BadPaddingException) {
            throw IllegalStateException("Incorrect PIN. Please try again.", e)
        }
        check(result == "PIN_VALID") { "Incorrect PIN. Please try again." }
    }

    // ── Silent KDF migrations ─────────────────────────────────────────────────

    private suspend fun migrateVerifierToArgon2id(password: CharArray) {
        val newVerifierSalt = crypto.generateSalt()
        val newVerifierKey  = crypto.deriveKeyFromPasswordArgon2id(password, newVerifierSalt)
        val newVerifier     = crypto.encryptString("VALID", newVerifierKey)
        authPrefs.edit().apply {
            putString(SP_PASSWORD_VERIFIER, encodeVerifier(newVerifierSalt, newVerifier))
            putInt(SP_KDF_VERSION, KDF_ARGON2ID)
        }.commit()
    }

    private suspend fun migratePinVerifierToArgon2id(pin: CharArray) {
        val newSalt   = crypto.generateSalt()
        val newPinKey = crypto.deriveKeyFromPasswordArgon2id(pin, newSalt)
        val newHash   = crypto.encryptString("PIN_VALID", newPinKey)
        authPrefs.edit().apply {
            putString(SP_PIN_SALT, newSalt.encodeBase64())
            putString(SP_PIN_HASH, buildString {
                append(newHash.ciphertext.encodeBase64())
                append(':')
                append(newHash.iv.encodeBase64())
            })
            putInt(SP_PIN_KDF_VERSION, KDF_ARGON2ID)
        }.commit()
    }

    // ── Silent Keystore migration (API >= 28) ─────────────────────────────────

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.P)
    private suspend fun migrateKeystoreKey(vaultKey: javax.crypto.SecretKey) {
        val v2Key      = crypto.createUpgradedKeystoreKey()
        val newWrapped = crypto.wrapKey(vaultKey, v2Key)

        authPrefs.edit().apply {
            putString(SP_WRAPPED_KEY_CT_V2, newWrapped.ciphertext.encodeBase64())
            putString(SP_WRAPPED_KEY_IV_V2, newWrapped.iv.encodeBase64())
            putBoolean(SP_KEYSTORE_UPGRADED, true)
        }.commit()

        runCatching { crypto.deleteKeystoreKey(KEYSTORE_ALIAS_V1) }
    }

    // ── Wrapped key loading ───────────────────────────────────────────────────

    private fun unwrapStoredKey(): javax.crypto.SecretKey {
        val keystoreUpgraded = authPrefs.getBoolean(SP_KEYSTORE_UPGRADED, false)
        return if (keystoreUpgraded) {
            val ct   = authPrefs.getString(SP_WRAPPED_KEY_CT_V2, null)?.decodeBase64()
                ?: error("V2 vault key not found")
            val iv   = authPrefs.getString(SP_WRAPPED_KEY_IV_V2, null)?.decodeBase64()
                ?: error("V2 vault key IV not found")
            val v2Key = crypto.loadKeystoreKey(KEYSTORE_ALIAS_V2)
                ?: error("V2 Keystore key not found — vault may need repair")
            crypto.unwrapKey(EncryptedData(ct, iv), v2Key)
        } else {
            val ct   = authPrefs.getString(SP_WRAPPED_KEY_CT, null)?.decodeBase64()
                ?: error("Vault key not found")
            val iv   = authPrefs.getString(SP_WRAPPED_KEY_IV, null)?.decodeBase64()
                ?: error("Vault key IV not found")
            val v1Key = crypto.getOrCreateLegacyKeystoreKey()
            crypto.unwrapKey(EncryptedData(ct, iv), v1Key)
        }
    }

    // ── Vault management ──────────────────────────────────────────────────────

    override suspend fun resetPin(): AppResult<Unit> = runCatchingResult {
        migrationComplete.await()
        authPrefs.edit().apply {
            remove(SP_PIN_HASH)
            remove(SP_PIN_SALT)
            remove(SP_PIN_KDF_VERSION)
        }.commit()
    }

    override suspend fun resetVault(): AppResult<Unit> = runCatchingResult {
        crypto.deleteAllVaultKeys()
        authPrefs.edit().clear().commit()
        dataStore.edit { it.clear() }
        keyHolder.lock()
    }

    override suspend fun lock(): AppResult<Unit> = runCatchingResult { keyHolder.lock() }

    override fun isUnlocked(): Flow<Boolean> = keyHolder.isUnlocked

    override fun isPinSetup(): Flow<Boolean> = flow {
        migrationComplete.await()
        emitAll(authPrefs.watchString(SP_PIN_HASH).map { it != null })
    }.distinctUntilChanged()

    override suspend fun clearAll(): AppResult<Unit> = runCatchingResult {
        authPrefs.edit().clear().commit()
        dataStore.edit { it.clear() }
        keyHolder.lock()
    }

    // ── Flow helpers ──────────────────────────────────────────────────────────

    private fun SharedPreferences.watchBoolean(key: String, default: Boolean): Flow<Boolean> =
        callbackFlow {
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, k ->
                if (k == key) trySend(getBoolean(key, default))
            }
            registerOnSharedPreferenceChangeListener(listener)
            trySend(getBoolean(key, default))
            awaitClose { unregisterOnSharedPreferenceChangeListener(listener) }
        }

    private fun SharedPreferences.watchString(key: String): Flow<String?> =
        callbackFlow {
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, k ->
                if (k == key) trySend(getString(key, null))
            }
            registerOnSharedPreferenceChangeListener(listener)
            trySend(getString(key, null))
            awaitClose { unregisterOnSharedPreferenceChangeListener(listener) }
        }

    // ── Encoding helpers ──────────────────────────────────────────────────────

    private fun ByteArray.encodeBase64(): String =
        android.util.Base64.encodeToString(this, android.util.Base64.NO_WRAP)

    private fun String.decodeBase64(): ByteArray =
        android.util.Base64.decode(this, android.util.Base64.NO_WRAP)

    private fun encodeVerifier(salt: ByteArray, verifier: EncryptedData): String =
        "${salt.encodeBase64()}:${verifier.ciphertext.encodeBase64()}:${verifier.iv.encodeBase64()}"
}

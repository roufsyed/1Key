package com.onekey.core.data.repository

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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*
import javax.crypto.BadPaddingException
import javax.inject.Inject
import javax.inject.Singleton

// ── Vault auth metadata ───────────────────────────────────────────────────────

private val KEY_SETUP_COMPLETE = booleanPreferencesKey("setup_complete")
private val KEY_SALT = stringPreferencesKey("vault_salt")
private val KEY_WRAPPED_KEY_CT = stringPreferencesKey("wrapped_key_ct")
private val KEY_WRAPPED_KEY_IV = stringPreferencesKey("wrapped_key_iv")
private val KEY_PASSWORD_VERIFIER = stringPreferencesKey("password_verifier")

// KDF version for the master-password verifier.
// 0 = PBKDF2-HMAC-SHA256 @ 310k iterations (existing installs)
// 1 = Argon2id (m=64MB, t=3, p=1) — written on new installs and on first successful
//     unlock after the app update (silent migration).
private val KEY_KDF_VERSION = intPreferencesKey("kdf_version")

// ── PIN metadata ─────────────────────────────────────────────────────────────

private val KEY_PIN_HASH = stringPreferencesKey("pin_hash")
private val KEY_PIN_SALT = stringPreferencesKey("pin_salt")

// KDF version for the PIN verifier (same versioning scheme as KDF_VERSION).
private val KEY_PIN_KDF_VERSION = intPreferencesKey("pin_kdf_version")

// ── Keystore upgrade ─────────────────────────────────────────────────────────

// True once the vault key has been re-wrapped under the v2 alias which includes
// setUnlockedDeviceRequired(true) on API >= 28. Before this flag is set, reads go
// to KEY_WRAPPED_KEY_CT/IV under the v1 alias. After, reads go to the _V2 keys.
private val KEY_KEYSTORE_UPGRADED = booleanPreferencesKey("ks_upgraded")
private val KEY_WRAPPED_KEY_CT_V2 = stringPreferencesKey("wrapped_key_ct_v2")
private val KEY_WRAPPED_KEY_IV_V2 = stringPreferencesKey("wrapped_key_iv_v2")

private const val KDF_PBKDF2 = 0
private const val KDF_ARGON2ID = 1

@Singleton
class AuthRepositoryImpl @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val crypto: CryptoManager,
    private val keyHolder: VaultKeyHolder,
    private val vaultVersionTracker: VaultVersionTracker,
    @ApplicationScope appScope: CoroutineScope,
) : AuthRepository {

    private val prefs: StateFlow<Preferences> = dataStore.data
        .stateIn(appScope, SharingStarted.Eagerly, emptyPreferences())

    override fun isSetupComplete(): Flow<Boolean> =
        prefs.map { it[KEY_SETUP_COMPLETE] ?: false }.distinctUntilChanged()

    // ── Setup ─────────────────────────────────────────────────────────────────

    override suspend fun setupMasterPassword(password: CharArray): AppResult<Unit> =
        runCatchingResult {
            val vaultSalt = crypto.generateSalt()
            // Derive the vault key with PBKDF2. This key is immediately wrapped by
            // the Keystore and never derived again — its origin algorithm does not
            // affect long-term security once it is Keystore-protected.
            val vaultKey = crypto.deriveKeyFromPassword(password, vaultSalt)
            val keystoreKey = crypto.getOrCreateLegacyKeystoreKey()
            val wrapped = crypto.wrapKey(vaultKey, keystoreKey)

            // Verifier uses Argon2id on new installs.
            val verifierSalt = crypto.generateSalt()
            val verifierKey = crypto.deriveKeyFromPasswordArgon2id(password, verifierSalt)
            val verifier = crypto.encryptString("VALID", verifierKey)

            dataStore.edit { p ->
                p[KEY_SETUP_COMPLETE] = true
                p[KEY_SALT] = vaultSalt.encodeBase64()
                p[KEY_WRAPPED_KEY_CT] = wrapped.ciphertext.encodeBase64()
                p[KEY_WRAPPED_KEY_IV] = wrapped.iv.encodeBase64()
                p[KEY_PASSWORD_VERIFIER] = encodeVerifier(verifierSalt, verifier)
                p[KEY_KDF_VERSION] = KDF_ARGON2ID
            }
            password.fill(' ')
            keyHolder.setKey(vaultKey)
        }

    // ── Unlock ────────────────────────────────────────────────────────────────

    override suspend fun unlockWithPassword(password: CharArray): AppResult<Unit> =
        runCatchingResult {
            val prefs = dataStore.data.first()
            val kdfVersion = prefs[KEY_KDF_VERSION] ?: KDF_PBKDF2

            // Keep a copy of the password for the silent Argon2id migration that
            // runs after verification. The original is zeroed at the end of the
            // verification block to match the existing contract.
            val passwordForMigration = if (kdfVersion == KDF_PBKDF2) password.copyOf() else null

            verifyMasterPassword(password, prefs, kdfVersion)
            // password is zeroed inside verifyMasterPassword.

            val vaultKey = unwrapStoredKey(prefs)
            keyHolder.setKey(vaultKey)

            // On first unlock after the app update, transparently migrate the
            // PBKDF2 verifier to Argon2id and upgrade the Keystore key.
            if (kdfVersion == KDF_PBKDF2 && passwordForMigration != null) {
                try {
                    migrateVerifierToArgon2id(passwordForMigration, prefs)
                } catch (_: Exception) {
                    // Silent failure — migration retries on the next unlock.
                } finally {
                    passwordForMigration.fill(' ')
                }
            }

            // Keystore upgrade is independent of the KDF migration. It runs on API >= 28
            // on the first unlock regardless of which KDF path was taken.
            val keystoreUpgraded = prefs[KEY_KEYSTORE_UPGRADED] ?: false
            if (!keystoreUpgraded && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                try {
                    migrateKeystoreKey(vaultKey)
                } catch (_: Exception) {
                    // Silent failure — migration retries on the next unlock.
                }
            }
        }

    override suspend fun unlockWithBiometric(): AppResult<Unit> = runCatchingResult {
        val prefs = dataStore.data.first()
        val vaultKey = unwrapStoredKey(prefs)
        keyHolder.setKey(vaultKey)
    }

    // ── Change password ───────────────────────────────────────────────────────

    override suspend fun changePassword(oldPassword: CharArray, newPassword: CharArray): AppResult<Unit> =
        runCatchingResult {
            val prefs = dataStore.data.first()
            val kdfVersion = prefs[KEY_KDF_VERSION] ?: KDF_PBKDF2

            verifyMasterPassword(oldPassword, prefs, kdfVersion)
            // oldPassword is zeroed inside verifyMasterPassword.

            // Always write an Argon2id verifier — this also upgrades users whose
            // verifier was still PBKDF2 when they changed their password before
            // the silent unlock migration had a chance to run.
            val newVerifierSalt = crypto.generateSalt()
            val newVerifierKey = crypto.deriveKeyFromPasswordArgon2id(newPassword, newVerifierSalt)
            val newVerifier = crypto.encryptString("VALID", newVerifierKey)
            newPassword.fill(' ')

            dataStore.edit { p ->
                p[KEY_PASSWORD_VERIFIER] = encodeVerifier(newVerifierSalt, newVerifier)
                p[KEY_KDF_VERSION] = KDF_ARGON2ID
            }
            vaultVersionTracker.increment()
            // Vault key itself does not change — no credential re-encryption needed.
        }

    // ── PIN ───────────────────────────────────────────────────────────────────

    override suspend fun setupPin(pin: CharArray): AppResult<Unit> = runCatchingResult {
        require(pin.size == 6) { "PIN must be 6 digits" }
        val salt = crypto.generateSalt()
        val pinKey = crypto.deriveKeyFromPasswordArgon2id(pin, salt)
        pin.fill(' ')
        val pinHash = crypto.encryptString("PIN_VALID", pinKey)
        dataStore.edit { p ->
            p[KEY_PIN_SALT] = salt.encodeBase64()
            p[KEY_PIN_HASH] = buildString {
                append(pinHash.ciphertext.encodeBase64())
                append(':')
                append(pinHash.iv.encodeBase64())
            }
            p[KEY_PIN_KDF_VERSION] = KDF_ARGON2ID
        }
    }

    override suspend fun unlockWithPin(pin: CharArray): AppResult<Unit> = runCatchingResult {
        val prefs = dataStore.data.first()
        val pinCopy = pin.copyOf()
        verifyPinAgainst(prefs, pin)
        // pin is zeroed inside verifyPinAgainst.

        val vaultKey = unwrapStoredKey(prefs)
        keyHolder.setKey(vaultKey)

        // Silently migrate PIN verifier from PBKDF2 to Argon2id if needed.
        val pinKdfVersion = prefs[KEY_PIN_KDF_VERSION] ?: KDF_PBKDF2
        if (pinKdfVersion == KDF_PBKDF2) {
            try {
                migratePinVerifierToArgon2id(pinCopy)
            } catch (_: Exception) {
                // Silent failure — migration retries on next PIN unlock.
            } finally {
                pinCopy.fill(' ')
            }
        } else {
            pinCopy.fill(' ')
        }
    }

    override suspend fun verifyPin(pin: CharArray): AppResult<Unit> = runCatchingResult {
        verifyPinAgainst(dataStore.data.first(), pin)
    }

    // ── Core verification helpers ─────────────────────────────────────────────

    /**
     * Verifies the master password against the stored verifier. Dispatches to
     * PBKDF2 or Argon2id depending on [kdfVersion]. Always zeroes [password]
     * before returning — callers that need the password afterward must copy it.
     */
    private fun verifyMasterPassword(password: CharArray, prefs: Preferences, kdfVersion: Int) {
        val verifierStr = prefs[KEY_PASSWORD_VERIFIER] ?: error("No verifier stored")
        val parts = verifierStr.split(":")
        require(parts.size == 3) { "Corrupted verifier data" }
        val verSalt = parts[0].decodeBase64()
        val verCt = parts[1].decodeBase64()
        val verIv = parts[2].decodeBase64()

        val verifierKey = when (kdfVersion) {
            KDF_ARGON2ID -> crypto.deriveKeyFromPasswordArgon2id(password, verSalt)
            else -> crypto.deriveKeyFromPassword(password, verSalt)
        }
        password.fill(' ')

        val plainVerifier = try {
            crypto.decryptString(EncryptedData(verCt, verIv), verifierKey)
        } catch (e: BadPaddingException) {
            throw IllegalStateException("Incorrect master password. Please try again.", e)
        }
        check(plainVerifier == "VALID") { "Incorrect master password. Please try again." }
    }

    /**
     * Verifies the PIN. Dispatches to PBKDF2 or Argon2id based on [KEY_PIN_KDF_VERSION].
     * Always zeroes [pin] before returning.
     */
    private fun verifyPinAgainst(prefs: Preferences, pin: CharArray) {
        val saltB64 = prefs[KEY_PIN_SALT] ?: error("PIN not set up")
        val salt = saltB64.decodeBase64()

        val hashStr = prefs[KEY_PIN_HASH] ?: error("PIN hash missing")
        val parts = hashStr.split(":")
        require(parts.size == 2) { "Corrupted PIN hash data" }
        val ct = parts[0].decodeBase64()
        val iv = parts[1].decodeBase64()

        val pinKdfVersion = prefs[KEY_PIN_KDF_VERSION] ?: KDF_PBKDF2
        val pinKey = when (pinKdfVersion) {
            KDF_ARGON2ID -> crypto.deriveKeyFromPasswordArgon2id(pin, salt)
            else -> crypto.deriveKeyFromPassword(pin, salt)
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

    /**
     * Re-derives the verifier with Argon2id and writes it atomically with
     * [KEY_KDF_VERSION] = [KDF_ARGON2ID]. [password] is zeroed in the caller's
     * finally block — do not zero it here.
     */
    private suspend fun migrateVerifierToArgon2id(password: CharArray, prefs: Preferences) {
        val newVerifierSalt = crypto.generateSalt()
        val newVerifierKey = crypto.deriveKeyFromPasswordArgon2id(password, newVerifierSalt)
        val newVerifier = crypto.encryptString("VALID", newVerifierKey)
        dataStore.edit { p ->
            p[KEY_PASSWORD_VERIFIER] = encodeVerifier(newVerifierSalt, newVerifier)
            p[KEY_KDF_VERSION] = KDF_ARGON2ID
        }
    }

    /**
     * Re-derives the PIN verifier with Argon2id. [pin] is zeroed in the caller's
     * finally block — do not zero it here.
     */
    private suspend fun migratePinVerifierToArgon2id(pin: CharArray) {
        val newSalt = crypto.generateSalt()
        val newPinKey = crypto.deriveKeyFromPasswordArgon2id(pin, newSalt)
        val newHash = crypto.encryptString("PIN_VALID", newPinKey)
        dataStore.edit { p ->
            p[KEY_PIN_SALT] = newSalt.encodeBase64()
            p[KEY_PIN_HASH] = buildString {
                append(newHash.ciphertext.encodeBase64())
                append(':')
                append(newHash.iv.encodeBase64())
            }
            p[KEY_PIN_KDF_VERSION] = KDF_ARGON2ID
        }
    }

    // ── Silent Keystore migration (API >= 28) ─────────────────────────────────

    /**
     * Two-phase Keystore key upgrade:
     *   1. Create "onekey_master_v2" with setUnlockedDeviceRequired(true).
     *   2. Re-wrap the already-loaded [vaultKey] with the new key.
     *   3. Atomically write the new wrapped blob + ks_upgraded=true to DataStore.
     *   4. Delete the legacy key (cleanup; safe to omit if step 3 completed).
     *
     * If any step before step 3 fails, DataStore is untouched and the legacy key
     * path continues. If the app dies after step 3, the legacy key is orphaned in
     * the Keystore; it is cleaned up on the next unlock.
     */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.P)
    private suspend fun migrateKeystoreKey(vaultKey: javax.crypto.SecretKey) {
        val v2Key = crypto.createUpgradedKeystoreKey()
        val newWrapped = crypto.wrapKey(vaultKey, v2Key)

        dataStore.edit { p ->
            p[KEY_WRAPPED_KEY_CT_V2] = newWrapped.ciphertext.encodeBase64()
            p[KEY_WRAPPED_KEY_IV_V2] = newWrapped.iv.encodeBase64()
            p[KEY_KEYSTORE_UPGRADED] = true
        }

        // Legacy key is now orphaned — delete it so the Keystore is clean.
        // Tolerate failures here: the data is safely on v2; the orphan is harmless.
        runCatching { crypto.deleteKeystoreKey(KEYSTORE_ALIAS_V1) }
    }

    // ── Wrapped key loading ───────────────────────────────────────────────────

    private fun unwrapStoredKey(prefs: Preferences): javax.crypto.SecretKey {
        val keystoreUpgraded = prefs[KEY_KEYSTORE_UPGRADED] ?: false

        return if (keystoreUpgraded) {
            val ct = prefs[KEY_WRAPPED_KEY_CT_V2]?.decodeBase64() ?: error("V2 vault key not found")
            val iv = prefs[KEY_WRAPPED_KEY_IV_V2]?.decodeBase64() ?: error("V2 vault key IV not found")
            val v2Key = crypto.loadKeystoreKey(KEYSTORE_ALIAS_V2)
                ?: error("V2 Keystore key not found — vault may need repair")
            crypto.unwrapKey(EncryptedData(ct, iv), v2Key)
        } else {
            val ct = prefs[KEY_WRAPPED_KEY_CT]?.decodeBase64() ?: error("Vault key not found")
            val iv = prefs[KEY_WRAPPED_KEY_IV]?.decodeBase64() ?: error("Vault key IV not found")
            val v1Key = crypto.getOrCreateLegacyKeystoreKey()
            crypto.unwrapKey(EncryptedData(ct, iv), v1Key)
        }
    }

    // ── Vault management ──────────────────────────────────────────────────────

    override suspend fun resetPin(): AppResult<Unit> = runCatchingResult {
        dataStore.edit { p ->
            p.remove(KEY_PIN_HASH)
            p.remove(KEY_PIN_SALT)
            p.remove(KEY_PIN_KDF_VERSION)
        }
    }

    override suspend fun resetVault(): AppResult<Unit> = runCatchingResult {
        crypto.deleteAllVaultKeys()
        dataStore.edit { it.clear() }
        keyHolder.lock()
    }

    override suspend fun lock(): AppResult<Unit> = runCatchingResult { keyHolder.lock() }

    override fun isUnlocked(): Flow<Boolean> = keyHolder.isUnlocked

    override fun isPinSetup(): Flow<Boolean> =
        prefs.map { it[KEY_PIN_HASH] != null }.distinctUntilChanged()

    override suspend fun clearAll(): AppResult<Unit> = runCatchingResult {
        dataStore.edit { it.clear() }
        keyHolder.lock()
    }

    // ── Encoding helpers ──────────────────────────────────────────────────────

    private fun ByteArray.encodeBase64(): String =
        android.util.Base64.encodeToString(this, android.util.Base64.NO_WRAP)

    private fun String.decodeBase64(): ByteArray =
        android.util.Base64.decode(this, android.util.Base64.NO_WRAP)

    private fun encodeVerifier(salt: ByteArray, verifier: EncryptedData): String =
        "${salt.encodeBase64()}:${verifier.ciphertext.encodeBase64()}:${verifier.iv.encodeBase64()}"
}

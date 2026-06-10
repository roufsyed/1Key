package com.onekey.core.security

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import androidx.annotation.RequiresApi
import com.lambdapioneer.argon2kt.Argon2Kt
import com.lambdapioneer.argon2kt.Argon2Mode
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

private const val ANDROID_KEYSTORE = "AndroidKeyStore"

// Legacy alias - no setUnlockedDeviceRequired. Kept for reading wrapped blobs on
// existing installs and on API < 28 devices where the upgrade cannot be applied.
internal const val KEYSTORE_ALIAS_V1 = "onekey_master"

// Upgraded alias - setUnlockedDeviceRequired(true) on API >= 28. Written during
// the one-time silent Keystore migration that runs after the first successful unlock.
internal const val KEYSTORE_ALIAS_V2 = "onekey_master_v2"

private const val TAG_CRYPTO = "CryptoManager"

private const val AES_GCM = "AES/GCM/NoPadding"
private const val GCM_TAG_LENGTH = 128

// PBKDF2 - kept for reading existing verifiers (kdf_version=0 rows).
// New verifiers always use Argon2id.
private const val PBKDF2_ITERATIONS = 310_000
private const val PBKDF2_KEY_LENGTH = 256
private const val PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256"

// Argon2id parameters (OWASP 2023 interactive-auth recommendation).
private const val ARGON2_T_COST = 3
private const val ARGON2_M_COST = 65_536  // 64 MB
private const val ARGON2_PARALLELISM = 1
private const val ARGON2_HASH_LENGTH = 32  // 256-bit AES key

// Salt length for the Sync backup-key derivation. Matches BackupEncryption's SALT_LEN
// so the bytes round-trip through the V4 envelope header without truncation.
private const val SALT_LEN_BACKUP = 32

data class EncryptedData(val ciphertext: ByteArray, val iv: ByteArray) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EncryptedData) return false
        return ciphertext.contentEquals(other.ciphertext) && iv.contentEquals(other.iv)
    }
    override fun hashCode(): Int = 31 * ciphertext.contentHashCode() + iv.contentHashCode()
}

@Singleton
open class CryptoManager @Inject constructor() {

    // ── Android Keystore ─────────────────────────────────────────────────────

    /**
     * Process-lifetime flag tracking whether the most recent successful
     * [getOrCreateKeystoreKey] generation placed the key in StrongBox.
     *
     * This is the signal the hardware-isolation detector consumes on API 28-30
     * where the per-key `KeyInfo.securityLevel` API does not yet exist. On API
     * 31+ the detector reads `KeyInfo.securityLevel` directly and ignores this
     * flag. We do NOT persist this to disk: on next launch the detector
     * re-derives the tier from KeyInfo metadata, which is the source of truth.
     * The flag is only the lookup hint when KeyInfo cannot tell us.
     */
    @Volatile
    internal var lastKeyCreatedWithStrongBox: Boolean = false
        private set

    /**
     * Returns the AndroidKeyStore-backed AES-256/GCM wrapping key for [alias],
     * creating it on first use.
     *
     * StrongBox handling (API 28+):
     *  - The first creation attempt sets `setIsStrongBoxBacked(true)`. On
     *    devices with a dedicated tamper-resistant element (recent Pixels,
     *    many flagships) this places the key in StrongBox, the strongest
     *    isolation Android exposes to apps.
     *  - If StrongBox is unavailable, the platform throws
     *    `StrongBoxUnavailableException`. We catch it, log at INFO, and
     *    retry without the StrongBox flag. The resulting TEE-backed key is
     *    still hardware-isolated and full-strength.
     *  - If the StrongBox attempt throws `ProviderException` (the Keystore
     *    provider's bucket for chip/firmware misbehaviour, observed in field
     *    reports across some Pixel firmware updates that bring StrongBox up
     *    lazily), we treat it the same way: log at WARN and fall back to TEE.
     *  - Any other exception (a true KeyStore failure, an invalid spec, etc.)
     *    propagates to the caller. [AuthRepositoryImpl] wraps the call sites
     *    so the user lands on a setup-failed flow rather than a crash.
     *  - If BOTH attempts fail, the second exception bubbles up. The migration
     *    call site in [AuthRepositoryImpl.migrateKeystoreKey] catches it and
     *    keeps the V1 key for that boot, retrying on the next unlock.
     *
     * On API < 28 the StrongBox block is skipped entirely (the
     * `setIsStrongBoxBacked` builder method and `StrongBoxUnavailableException`
     * are both API 28+).
     *
     * Idempotency: the early-return when the alias already exists is preserved.
     * A key created under StrongBox=true is returned to callers without
     * regeneration even if the StrongBox path now misbehaves on this boot,
     * because re-generating the wrapping key would destroy the user's vault.
     */
    private fun getOrCreateKeystoreKey(alias: String, unlockedDeviceRequired: Boolean): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        keyStore.getKey(alias, null)?.let { return it as SecretKey }

        // Spec factory so the StrongBox and non-StrongBox builds share one chain.
        fun buildSpec(useStrongBox: Boolean): KeyGenParameterSpec =
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(false)
                .apply {
                    if (unlockedDeviceRequired && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        setUnlockedDeviceRequired(true)
                    }
                    if (useStrongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        setIsStrongBoxBacked(true)
                    }
                }
                .build()

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)

        // StrongBox attempt only on API 28+. setIsStrongBoxBacked,
        // StrongBoxUnavailableException, and KeyInfo.getSecurityLevel are all API 28+.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                keyGenerator.init(buildSpec(useStrongBox = true))
                val key = keyGenerator.generateKey()
                lastKeyCreatedWithStrongBox = true
                Log.i(TAG_CRYPTO, "Keystore key '" + alias + "' generated in StrongBox")
                return key
            } catch (e: android.security.keystore.StrongBoxUnavailableException) {
                // Device declared no StrongBox at this moment. Fall through to TEE.
                Log.i(TAG_CRYPTO, "StrongBox unavailable for '" + alias + "'; falling back to TEE", e)
            } catch (e: java.security.ProviderException) {
                // ProviderException is the Keystore provider's bucket for chip/firmware
                // misbehaviour. Fall back to TEE rather than crashing the unlock flow.
                Log.w(
                    TAG_CRYPTO,
                    "StrongBox generateKey threw ProviderException for '" + alias +
                        "'; falling back to TEE",
                    e,
                )
            }
        }

        // TEE / non-StrongBox attempt. Also the path taken on API < 28.
        lastKeyCreatedWithStrongBox = false
        keyGenerator.init(buildSpec(useStrongBox = false))
        return keyGenerator.generateKey()
    }

    // Returns the legacy (v1) key, creating it if absent. Used for existing installs
    // on API < 28 and as the read key before the one-time upgrade migration runs.
    fun getOrCreateLegacyKeystoreKey(): SecretKey =
        getOrCreateKeystoreKey(KEYSTORE_ALIAS_V1, unlockedDeviceRequired = false)

    // Creates the upgraded (v2) key with setUnlockedDeviceRequired=true on API >= 28.
    // Only called once, during the silent Keystore migration after successful unlock.
    @RequiresApi(Build.VERSION_CODES.P)
    fun createUpgradedKeystoreKey(): SecretKey =
        getOrCreateKeystoreKey(KEYSTORE_ALIAS_V2, unlockedDeviceRequired = true)

    fun deleteKeystoreKey(alias: String) {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
    }

    // Deletes both aliases. Called from resetVault() to ensure a full wipe.
    fun deleteAllVaultKeys() {
        deleteKeystoreKey(KEYSTORE_ALIAS_V1)
        deleteKeystoreKey(KEYSTORE_ALIAS_V2)
    }

    // Returns an existing Keystore key by alias, or null if it has not been created yet.
    open fun loadKeystoreKey(alias: String): SecretKey? {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        return keyStore.getKey(alias, null) as? SecretKey
    }

    // ── PBKDF2 key derivation (backward-compat read path only) ───────────────

    fun deriveKeyFromPassword(password: CharArray, salt: ByteArray): SecretKey {
        val factory = SecretKeyFactory.getInstance(PBKDF2_ALGORITHM)
        val spec = PBEKeySpec(password, salt, PBKDF2_ITERATIONS, PBKDF2_KEY_LENGTH)
        val raw = factory.generateSecret(spec).encoded
        spec.clearPassword()
        val key = SecretKeySpec(raw, "AES")
        raw.fill(0)
        return key
    }

    // ── Argon2id key derivation (new installs + migrated verifiers) ──────────
    //
    // password is NOT zeroed here - the caller owns the CharArray and must zero it.
    // The UTF-8 byte conversion is done without creating an intermediate String
    // (CharBuffer.encode) to avoid interning key material in immutable heap objects.

    fun deriveKeyFromPasswordArgon2id(password: CharArray, salt: ByteArray): SecretKey {
        val passwordBytes = password.toUtf8ByteArray()
        return try {
            val raw = Argon2Kt().hash(
                mode = Argon2Mode.ARGON2_ID,
                password = passwordBytes,
                salt = salt,
                tCostInIterations = ARGON2_T_COST,
                mCostInKibibyte = ARGON2_M_COST,
                parallelism = ARGON2_PARALLELISM,
                hashLengthInBytes = ARGON2_HASH_LENGTH,
            ).rawHashAsByteArray()
            val key = SecretKeySpec(raw, "AES")
            raw.fill(0)
            key
        } finally {
            passwordBytes.fill(0)
        }
    }

    // Converts a CharArray to its UTF-8 byte representation without passing through
    // a String, so the key material is never interned in the JVM string pool.
    private fun CharArray.toUtf8ByteArray(): ByteArray {
        val charBuffer = java.nio.CharBuffer.wrap(this)
        val byteBuffer = Charsets.UTF_8.encode(charBuffer)
        val bytes = ByteArray(byteBuffer.remaining())
        byteBuffer.get(bytes)
        if (byteBuffer.hasArray()) byteBuffer.array().fill(0)
        return bytes
    }

    // ── AES/GCM encrypt ──────────────────────────────────────────────────────
    //
    // Optional `aad` ties additional bytes to the GCM auth tag so tampering with
    // them invalidates decryption. Pass null when not needed.

    fun encrypt(plaintext: ByteArray, key: SecretKey, aad: ByteArray? = null): EncryptedData {
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        if (aad != null) cipher.updateAAD(aad)
        return EncryptedData(cipher.doFinal(plaintext), cipher.iv)
    }

    fun decrypt(data: EncryptedData, key: SecretKey, aad: ByteArray? = null): ByteArray {
        val cipher = Cipher.getInstance(AES_GCM)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH, data.iv)
        cipher.init(Cipher.DECRYPT_MODE, key, spec)
        if (aad != null) cipher.updateAAD(aad)
        return cipher.doFinal(data.ciphertext)
    }

    // ── Wrap/unwrap vault key (key-encryption-key pattern) ───────────────────

    fun wrapKey(vaultKey: SecretKey, keystoreKey: SecretKey): EncryptedData =
        encrypt(vaultKey.encoded, keystoreKey)

    fun unwrapKey(wrappedKey: EncryptedData, keystoreKey: SecretKey): SecretKey {
        val raw = decrypt(wrappedKey, keystoreKey)
        val key = SecretKeySpec(raw, "AES")
        raw.fill(0)
        return key
    }

    fun generateSalt(length: Int = 32): ByteArray {
        val salt = ByteArray(length)
        java.security.SecureRandom().nextBytes(salt)
        return salt
    }

    fun encryptString(value: String, key: SecretKey): EncryptedData =
        encrypt(value.toByteArray(Charsets.UTF_8), key)

    fun decryptString(data: EncryptedData, key: SecretKey): String =
        decrypt(data, key).toString(Charsets.UTF_8)

    // ── One-shot backup-key derivation (Sync feature) ────────────────────────
    //
    // The Sync-on-Master-Password-Unlock feature needs a backup encryption key
    // derived from the master password, but it must not let the password material
    // cross the auth-layer boundary into the sync coroutine. The contract:
    //
    //  - Caller (AuthRepositoryImpl) passes a freshly-copied CharArray.
    //  - `deriveBackupKey` runs Argon2id on a per-call random salt, returns the
    //    raw 32-byte key + the salt.
    //  - The caller's CharArray is zeroed in place before this function returns.
    //  - The returned `key` is caller-owned: the SyncEngine MUST `.fill(0)` it
    //    after the backup write completes (success OR failure).
    //  - `BackupEncryption.encryptWithKey(... key, salt ...)` is then called by
    //    the sync coroutine with the same `salt` so the V4 envelope header records
    //    the value that would re-derive the same key from the master password on
    //    restore. The restore path is unchanged - it still does Argon2id over the
    //    salt-from-file + user-typed password.
    //
    // Compared to passing the password to `BackupEncryption.encrypt`, this
    // refactor keeps the master password material in exactly one layer
    // (AuthRepositoryImpl.unlockWithPassword) and crosses no boundaries. See
    // memory note `project_auto_backup.md` (2026-06-09 reopening) for rationale.

    fun deriveBackupKey(password: CharArray): BackupKeyMaterial {
        val salt = generateSalt(SALT_LEN_BACKUP)
        val passwordBytes = password.toUtf8ByteArray()
        return try {
            val rawKey = Argon2Kt().hash(
                mode = Argon2Mode.ARGON2_ID,
                password = passwordBytes,
                salt = salt,
                tCostInIterations = ARGON2_T_COST,
                mCostInKibibyte = ARGON2_M_COST,
                parallelism = ARGON2_PARALLELISM,
                hashLengthInBytes = ARGON2_HASH_LENGTH,
            ).rawHashAsByteArray()
            BackupKeyMaterial(salt = salt, key = rawKey)
        } finally {
            passwordBytes.fill(0)
            password.fill(' ')
        }
    }

    // ── HKDF-SHA256 subkey derivation ────────────────────────────────────────
    //
    // Domain-separated subkeys derived from the vault master key. Using one
    // master key for multiple purposes (field encryption, title encryption,
    // ...) is a known anti-pattern; HKDF gives each purpose its own key.
    //
    // The vault key is already a uniformly random 256-bit AES key, so the
    // HKDF-Extract step (HMAC(salt, IKM)) is unnecessary - RFC 5869 §3.3
    // explicitly allows skipping it when the input is already uniform.
    // We emit a single 32-byte block via T(1) = HMAC(masterKey, info || 0x01).

    fun deriveSubkey(masterKey: SecretKey, info: String): SecretKey {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(masterKey.encoded, "HmacSHA256"))
        val infoBytes = info.toByteArray(Charsets.UTF_8)
        val data = ByteArray(infoBytes.size + 1)
        System.arraycopy(infoBytes, 0, data, 0, infoBytes.size)
        data[infoBytes.size] = 0x01
        val raw = mac.doFinal(data)
        val key = SecretKeySpec(raw, "AES")
        raw.fill(0)
        return key
    }
}

// Domain-separation labels for HKDF-derived subkeys. Append a version suffix
// when the encryption scheme changes so old and new subkeys don't collide.
internal const val HKDF_FIELD_KEY_INFO = "1key-field-enc-v1"
internal const val HKDF_TITLE_KEY_INFO = "1key-title-enc-v1"

/**
 * Output of [CryptoManager.deriveBackupKey]. The [key] is raw 32-byte AES key material;
 * caller MUST `key.fill(0)` after use (typically in a `finally` block of the sync
 * coroutine). The [salt] is the per-derivation random salt the V4 envelope must record
 * so the master-password-from-the-user path on restore re-derives the same key. The
 * salt is not secret on its own; we still zero it on dispose as a defensive habit.
 */
data class BackupKeyMaterial(val salt: ByteArray, val key: ByteArray) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BackupKeyMaterial) return false
        return salt.contentEquals(other.salt) && key.contentEquals(other.key)
    }
    override fun hashCode(): Int = 31 * salt.contentHashCode() + key.contentHashCode()
}

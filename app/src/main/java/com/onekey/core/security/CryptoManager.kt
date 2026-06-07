package com.onekey.core.security

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
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

data class EncryptedData(val ciphertext: ByteArray, val iv: ByteArray) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EncryptedData) return false
        return ciphertext.contentEquals(other.ciphertext) && iv.contentEquals(other.iv)
    }
    override fun hashCode(): Int = 31 * ciphertext.contentHashCode() + iv.contentHashCode()
}

@Singleton
class CryptoManager @Inject constructor() {

    // ── Android Keystore ─────────────────────────────────────────────────────

    private fun getOrCreateKeystoreKey(alias: String, unlockedDeviceRequired: Boolean): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        keyStore.getKey(alias, null)?.let { return it as SecretKey }

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
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
            }
            .build()
        keyGenerator.init(spec)
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
    fun loadKeystoreKey(alias: String): SecretKey? {
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

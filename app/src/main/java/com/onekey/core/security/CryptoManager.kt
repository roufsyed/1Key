package com.onekey.core.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

private const val ANDROID_KEYSTORE = "AndroidKeyStore"
private const val KEYSTORE_ALIAS = "onekey_master"
private const val AES_GCM = "AES/GCM/NoPadding"
private const val GCM_TAG_LENGTH = 128
private const val PBKDF2_ITERATIONS = 310_000  // OWASP 2023 recommendation
private const val PBKDF2_KEY_LENGTH = 256
private const val PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256"

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

    // ── Keystore-backed key (wraps the session key) ──────────────────────────

    private fun getOrCreateKeystoreKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        keyStore.getKey(KEYSTORE_ALIAS, null)?.let { return it as SecretKey }

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        keyGenerator.init(
            KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(false)
                .build()
        )
        return keyGenerator.generateKey()
    }

    // ── PBKDF2 key derivation (master password → vault key) ──────────────────

    fun deriveKeyFromPassword(password: CharArray, salt: ByteArray): SecretKey {
        val factory = SecretKeyFactory.getInstance(PBKDF2_ALGORITHM)
        val spec = PBEKeySpec(password, salt, PBKDF2_ITERATIONS, PBKDF2_KEY_LENGTH)
        val raw = factory.generateSecret(spec).encoded
        spec.clearPassword()
        return SecretKeySpec(raw, "AES")
    }

    // ── AES/GCM encrypt with derived vault key ────────────────────────────────

    fun encrypt(plaintext: ByteArray, key: SecretKey): EncryptedData {
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return EncryptedData(cipher.doFinal(plaintext), cipher.iv)
    }

    fun decrypt(data: EncryptedData, key: SecretKey): ByteArray {
        val cipher = Cipher.getInstance(AES_GCM)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH, data.iv)
        cipher.init(Cipher.DECRYPT_MODE, key, spec)
        return cipher.doFinal(data.ciphertext)
    }

    // ── Wrap/unwrap vault key with Keystore key (key-encryption-key pattern) ──

    fun wrapKey(vaultKey: SecretKey): EncryptedData {
        val kek = getOrCreateKeystoreKey()
        return encrypt(vaultKey.encoded, kek)
    }

    fun unwrapKey(wrappedKey: EncryptedData): SecretKey {
        val kek = getOrCreateKeystoreKey()
        val raw = decrypt(wrappedKey, kek)
        return SecretKeySpec(raw, "AES")
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
}

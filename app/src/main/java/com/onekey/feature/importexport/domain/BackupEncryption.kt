package com.onekey.feature.importexport.domain

import com.onekey.core.domain.usecase.ExportFormat
import com.onekey.core.security.CryptoManager
import com.onekey.core.security.EncryptedData
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import javax.crypto.spec.SecretKeySpec

/**
 * Binary format for encrypted backups:
 *   MAGIC      8 B   "1KEYBKP\n"
 *   VERSION    1 B   0x01 (legacy, no AAD) | 0x02 (header AAD, PBKDF2)
 *                  | 0x03 (header AAD, Argon2id) | 0x04 (timestamp + vault version in AAD)
 *   FORMAT     1 B   0x00=JSON  0x01=CSV
 *   TIMESTAMP  8 B   export epoch-ms, big-endian [V4+ only]
 *   VAULT_VER  4 B   vault version counter, big-endian [V4+ only]
 *   SALT      32 B   KDF salt (random per export)
 *   IV        12 B   AES-GCM nonce
 *   BODY       N B   AES-256-GCM ciphertext + 16-byte auth tag
 *
 * V2 binds (MAGIC || VERSION || FORMAT) into the GCM auth tag via AAD.
 * V3 is identical in layout to V2 but derives the key with Argon2id (m=64 MB, t=3, p=1).
 * V4 extends the AAD to (MAGIC || VERSION || FORMAT || TIMESTAMP || VAULT_VER), binding
 * the ciphertext to the exact export time and vault version so neither can be swapped
 * without invalidating the auth tag.
 * New exports always use V4. V1/V2/V3 backups still decrypt for backward compatibility.
 */
internal object BackupEncryption {

    private val MAGIC = byteArrayOf(0x31, 0x4B, 0x45, 0x59, 0x42, 0x4B, 0x50, 0x0A)
    private const val VERSION_LEGACY: Byte = 0x01
    private const val VERSION_AAD: Byte = 0x02
    private const val VERSION_ARGON2ID: Byte = 0x03
    private const val VERSION_V4: Byte = 0x04
    private const val CURRENT_VERSION: Byte = VERSION_V4
    private const val FORMAT_JSON: Byte = 0x00
    private const val FORMAT_CSV: Byte = 0x01
    private const val SALT_LEN = 32
    private const val IV_LEN = 12  // AES-GCM nonce is always 96 bits
    private const val TIMESTAMP_LEN = 8
    private const val VAULT_VER_LEN = 4

    fun isEncrypted(path: String): Boolean = try {
        val header = ByteArray(MAGIC.size)
        val filledFully = File(path).inputStream().use { stream ->
            // InputStream.read(byte[]) is allowed to return fewer bytes than requested even
            // when more are available - looping until full avoids spuriously misclassifying
            // a real encrypted backup as plaintext on slow / DocumentFile-backed streams.
            var read = 0
            while (read < header.size) {
                val n = stream.read(header, read, header.size - read)
                if (n < 0) break
                read += n
            }
            read == header.size
        }
        filledFully && header.contentEquals(MAGIC)
    } catch (_: Exception) { false }

    /**
     * Encrypts [plaintext] under a key derived from [password] and writes a V4 backup
     * envelope. **Consumes [password]:** the caller's [CharArray] is zeroed in place
     * after key derivation. Callers that need the password again must pass a copy.
     */
    fun encrypt(
        plaintext: ByteArray,
        password: CharArray,
        format: ExportFormat,
        crypto: CryptoManager,
        createdAtMs: Long = System.currentTimeMillis(),
        vaultVersion: Int = 0,
    ): ByteArray {
        val salt = crypto.generateSalt(SALT_LEN)
        // V4: Argon2id (m=64 MB, t=3, p=1) - ~100× harder to brute-force than PBKDF2.
        val key = crypto.deriveKeyFromPasswordArgon2id(password, salt)
        password.fill(' ')
        val formatByte = if (format == ExportFormat.JSON) FORMAT_JSON else FORMAT_CSV
        val aad = buildHeaderAad(CURRENT_VERSION, formatByte, createdAtMs, vaultVersion)
        val enc = crypto.encrypt(plaintext, key, aad)
        check(enc.iv.size == IV_LEN) { "Unexpected IV length: ${enc.iv.size}" }

        return ByteArrayOutputStream(
            MAGIC.size + 2 + TIMESTAMP_LEN + VAULT_VER_LEN + SALT_LEN + IV_LEN + enc.ciphertext.size
        ).apply {
            write(MAGIC)
            write(CURRENT_VERSION.toInt())
            write(formatByte.toInt())
            write(ByteBuffer.allocate(TIMESTAMP_LEN).putLong(createdAtMs).array())
            write(ByteBuffer.allocate(VAULT_VER_LEN).putInt(vaultVersion).array())
            write(salt)
            write(enc.iv)
            write(enc.ciphertext)
        }.toByteArray()
    }

    /**
     * Variant of [encrypt] that takes a pre-derived 32-byte AES key and its salt
     * instead of running Argon2id internally. Used by the Sync feature: the
     * auth layer derives the key once (via `CryptoManager.deriveBackupKey`) at
     * the moment of master-password unlock and hands the bytes to the sync
     * coroutine. The master password never crosses the auth/sync boundary;
     * only this caller-owned derived key does.
     *
     * **Does NOT zero [key]** - ownership stays with the caller (typically the
     * sync coroutine's `finally` block). The salt is written verbatim into the
     * V4 envelope so the restore path (which still types the master password)
     * re-derives the same key via Argon2id over `salt + password`. Restore is
     * unchanged.
     */
    fun encryptWithKey(
        plaintext: ByteArray,
        key: ByteArray,
        salt: ByteArray,
        format: ExportFormat,
        crypto: CryptoManager,
        createdAtMs: Long = System.currentTimeMillis(),
        vaultVersion: Int = 0,
    ): ByteArray {
        require(key.size == 32) { "Backup key must be 32 bytes (AES-256), got ${key.size}" }
        require(salt.size == SALT_LEN) { "Backup salt must be $SALT_LEN bytes, got ${salt.size}" }
        val secretKey = SecretKeySpec(key, "AES")
        val formatByte = if (format == ExportFormat.JSON) FORMAT_JSON else FORMAT_CSV
        val aad = buildHeaderAad(CURRENT_VERSION, formatByte, createdAtMs, vaultVersion)
        val enc = crypto.encrypt(plaintext, secretKey, aad)
        check(enc.iv.size == IV_LEN) { "Unexpected IV length: ${enc.iv.size}" }

        return ByteArrayOutputStream(
            MAGIC.size + 2 + TIMESTAMP_LEN + VAULT_VER_LEN + SALT_LEN + IV_LEN + enc.ciphertext.size
        ).apply {
            write(MAGIC)
            write(CURRENT_VERSION.toInt())
            write(formatByte.toInt())
            write(ByteBuffer.allocate(TIMESTAMP_LEN).putLong(createdAtMs).array())
            write(ByteBuffer.allocate(VAULT_VER_LEN).putInt(vaultVersion).array())
            write(salt)
            write(enc.iv)
            write(enc.ciphertext)
        }.toByteArray()
    }

    data class Decrypted(val plaintext: ByteArray, val format: ExportFormat)

    /**
     * Decrypts an encrypted-backup file body and returns the inner plaintext + format.
     *
     * **Consumes [password]:** this function zeros the caller's [CharArray] in place
     * after deriving the KDF key, regardless of whether the GCM tag verifies.
     * Callers that need the password again afterwards must pass [password.copyOf()].
     * This contract is shared with [encrypt] and intentionally enforced here so the
     * password material has the shortest possible lifetime in memory.
     */
    fun decrypt(fileBytes: ByteArray, password: CharArray, crypto: CryptoManager): Decrypted {
        var off = 0

        val magic = fileBytes.sliceArray(off until off + MAGIC.size); off += MAGIC.size
        require(magic.contentEquals(MAGIC)) { "Not a 1Key encrypted backup" }

        val version = fileBytes[off++]
        require(
            version == VERSION_LEGACY || version == VERSION_AAD ||
            version == VERSION_ARGON2ID || version == VERSION_V4
        ) {
            "Unsupported backup version: 0x${version.toInt().and(0xFF).toString(16)}"
        }

        val fmtByte = fileBytes[off++]
        val format = if (fmtByte == FORMAT_JSON) ExportFormat.JSON else ExportFormat.CSV

        // V4 embeds timestamp and vault version between FORMAT and SALT.
        val createdAtMs: Long
        val vaultVersion: Int
        if (version == VERSION_V4) {
            createdAtMs = ByteBuffer.wrap(fileBytes, off, TIMESTAMP_LEN).long; off += TIMESTAMP_LEN
            vaultVersion = ByteBuffer.wrap(fileBytes, off, VAULT_VER_LEN).int; off += VAULT_VER_LEN
        } else {
            createdAtMs = 0L
            vaultVersion = 0
        }

        val salt = fileBytes.sliceArray(off until off + SALT_LEN); off += SALT_LEN
        val iv = fileBytes.sliceArray(off until off + IV_LEN); off += IV_LEN
        val ciphertext = fileBytes.sliceArray(off until fileBytes.size)

        // Dispatch KDF: V3/V4 = Argon2id; V1/V2 = PBKDF2 (backward compat).
        val key = when (version) {
            VERSION_ARGON2ID, VERSION_V4 -> crypto.deriveKeyFromPasswordArgon2id(password, salt)
            else -> crypto.deriveKeyFromPassword(password, salt)
        }
        password.fill(' ')

        // V2/V3: AAD = (MAGIC || VERSION || FORMAT)
        // V4:    AAD = (MAGIC || VERSION || FORMAT || TIMESTAMP || VAULT_VER)
        // V1: no AAD - legacy backups decrypt without header authentication.
        val aad = when (version) {
            VERSION_V4 -> buildHeaderAad(version, fmtByte, createdAtMs, vaultVersion)
            VERSION_AAD, VERSION_ARGON2ID -> buildHeaderAad(version, fmtByte)
            else -> null
        }
        val plaintext = crypto.decrypt(EncryptedData(ciphertext, iv), key, aad)
        return Decrypted(plaintext, format)
    }

    private fun buildHeaderAad(version: Byte, format: Byte): ByteArray {
        val aad = ByteArray(MAGIC.size + 2)
        System.arraycopy(MAGIC, 0, aad, 0, MAGIC.size)
        aad[MAGIC.size] = version
        aad[MAGIC.size + 1] = format
        return aad
    }

    private fun buildHeaderAad(version: Byte, format: Byte, createdAtMs: Long, vaultVersion: Int): ByteArray {
        val aad = ByteArray(MAGIC.size + 2 + TIMESTAMP_LEN + VAULT_VER_LEN)
        System.arraycopy(MAGIC, 0, aad, 0, MAGIC.size)
        aad[MAGIC.size] = version
        aad[MAGIC.size + 1] = format
        ByteBuffer.wrap(aad, MAGIC.size + 2, TIMESTAMP_LEN).putLong(createdAtMs)
        ByteBuffer.wrap(aad, MAGIC.size + 2 + TIMESTAMP_LEN, VAULT_VER_LEN).putInt(vaultVersion)
        return aad
    }
}

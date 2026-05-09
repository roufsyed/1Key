package com.onekey.feature.importexport.domain

import com.onekey.core.domain.usecase.ExportFormat
import com.onekey.core.security.CryptoManager
import com.onekey.core.security.EncryptedData
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Binary format for encrypted backups:
 *   MAGIC   8 B   "1KEYBKP\n"
 *   VERSION 1 B   0x01 (legacy, no AAD) | 0x02 (header AAD, PBKDF2) | 0x03 (header AAD, Argon2id)
 *   FORMAT  1 B   0x00=JSON  0x01=CSV
 *   SALT   32 B   KDF salt (random per export)
 *   IV     12 B   AES-GCM nonce
 *   BODY    N B   AES-256-GCM ciphertext + 16-byte auth tag
 *
 * V2 binds (MAGIC || VERSION || FORMAT) into the GCM auth tag via AAD.
 * V3 is identical in layout to V2 but derives the key with Argon2id (m=64 MB, t=3, p=1)
 * instead of PBKDF2-HMAC-SHA256. New exports always use V3.
 * V1/V2 backups still decrypt for backward compatibility.
 */
internal object BackupEncryption {

    private val MAGIC = byteArrayOf(0x31, 0x4B, 0x45, 0x59, 0x42, 0x4B, 0x50, 0x0A)
    private const val VERSION_LEGACY: Byte = 0x01
    private const val VERSION_AAD: Byte = 0x02
    private const val VERSION_ARGON2ID: Byte = 0x03
    private const val CURRENT_VERSION: Byte = VERSION_ARGON2ID
    private const val FORMAT_JSON: Byte = 0x00
    private const val FORMAT_CSV: Byte = 0x01
    private const val SALT_LEN = 32
    private const val IV_LEN = 12  // AES-GCM nonce is always 96 bits

    fun isEncrypted(path: String): Boolean = try {
        val header = ByteArray(MAGIC.size)
        val filledFully = File(path).inputStream().use { stream ->
            // InputStream.read(byte[]) is allowed to return fewer bytes than requested even
            // when more are available — looping until full avoids spuriously misclassifying
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

    fun encrypt(
        plaintext: ByteArray,
        password: CharArray,
        format: ExportFormat,
        crypto: CryptoManager,
    ): ByteArray {
        val salt = crypto.generateSalt(SALT_LEN)
        // V3: Argon2id (m=64 MB, t=3, p=1) — ~100× harder to brute-force than PBKDF2.
        val key = crypto.deriveKeyFromPasswordArgon2id(password, salt)
        password.fill(' ')
        val formatByte = if (format == ExportFormat.JSON) FORMAT_JSON else FORMAT_CSV
        val aad = buildHeaderAad(CURRENT_VERSION, formatByte)
        val enc = crypto.encrypt(plaintext, key, aad)
        check(enc.iv.size == IV_LEN) { "Unexpected IV length: ${enc.iv.size}" }

        return ByteArrayOutputStream(MAGIC.size + 2 + SALT_LEN + IV_LEN + enc.ciphertext.size).apply {
            write(MAGIC)
            write(CURRENT_VERSION.toInt())
            write(formatByte.toInt())
            write(salt)
            write(enc.iv)
            write(enc.ciphertext)
        }.toByteArray()
    }

    data class Decrypted(val plaintext: ByteArray, val format: ExportFormat)

    fun decrypt(fileBytes: ByteArray, password: CharArray, crypto: CryptoManager): Decrypted {
        var off = 0

        val magic = fileBytes.sliceArray(off until off + MAGIC.size); off += MAGIC.size
        require(magic.contentEquals(MAGIC)) { "Not a 1Key encrypted backup" }

        val version = fileBytes[off++]
        require(version == VERSION_LEGACY || version == VERSION_AAD || version == VERSION_ARGON2ID) {
            "Unsupported backup version: 0x${version.toInt().and(0xFF).toString(16)}"
        }

        val fmtByte = fileBytes[off++]
        val format = if (fmtByte == FORMAT_JSON) ExportFormat.JSON else ExportFormat.CSV

        val salt = fileBytes.sliceArray(off until off + SALT_LEN); off += SALT_LEN
        val iv = fileBytes.sliceArray(off until off + IV_LEN); off += IV_LEN
        val ciphertext = fileBytes.sliceArray(off until fileBytes.size)

        // Dispatch KDF by version: V3 = Argon2id; V1/V2 = PBKDF2 (backward compat).
        val key = when (version) {
            VERSION_ARGON2ID -> crypto.deriveKeyFromPasswordArgon2id(password, salt)
            else -> crypto.deriveKeyFromPassword(password, salt)
        }
        password.fill(' ')

        // V2 and V3 authenticate (MAGIC || VERSION || FORMAT) in the GCM tag via AAD.
        // V1 has no AAD — legacy backups decrypt without header authentication.
        val aad = if (version == VERSION_AAD || version == VERSION_ARGON2ID) {
            buildHeaderAad(version, fmtByte)
        } else null
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
}

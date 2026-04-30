package com.onekey.feature.importexport.domain

import com.onekey.core.domain.usecase.ExportFormat
import com.onekey.core.security.CryptoManager
import com.onekey.core.security.EncryptedData
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Binary format for encrypted backups:
 *   MAGIC   8 B   "1KEYBKP\n"
 *   VERSION 1 B   0x01
 *   FORMAT  1 B   0x00=JSON  0x01=CSV
 *   SALT   32 B   PBKDF2 salt (random per export)
 *   IV     12 B   AES-GCM nonce
 *   BODY    N B   AES-256-GCM ciphertext + 16-byte auth tag
 */
internal object BackupEncryption {

    private val MAGIC = byteArrayOf(0x31, 0x4B, 0x45, 0x59, 0x42, 0x4B, 0x50, 0x0A)
    private const val VERSION: Byte = 0x01
    private const val FORMAT_JSON: Byte = 0x00
    private const val FORMAT_CSV: Byte = 0x01
    private const val SALT_LEN = 32
    private const val IV_LEN = 12  // AES-GCM nonce is always 96 bits

    fun isEncrypted(path: String): Boolean = try {
        val header = ByteArray(MAGIC.size)
        val filledFully = File(path).inputStream().use { stream ->
            // InputStream.read(byte[]) is allowed to return fewer bytes than requested even
            // when more are available — looping until full avoids spuriously misclassifying
            // a real encrypted backup as plaintext on slow / DocumentFile-wrapped streams.
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
        val key = crypto.deriveKeyFromPassword(password, salt)
        val enc = crypto.encrypt(plaintext, key)
        check(enc.iv.size == IV_LEN) { "Unexpected IV length: ${enc.iv.size}" }

        return ByteArrayOutputStream(MAGIC.size + 2 + SALT_LEN + IV_LEN + enc.ciphertext.size).apply {
            write(MAGIC)
            write(VERSION.toInt())
            write(if (format == ExportFormat.JSON) FORMAT_JSON.toInt() else FORMAT_CSV.toInt())
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
        require(version == VERSION) { "Unsupported backup version: $version" }

        val fmtByte = fileBytes[off++]
        val format = if (fmtByte == FORMAT_JSON) ExportFormat.JSON else ExportFormat.CSV

        val salt = fileBytes.sliceArray(off until off + SALT_LEN); off += SALT_LEN
        val iv = fileBytes.sliceArray(off until off + IV_LEN); off += IV_LEN
        val ciphertext = fileBytes.sliceArray(off until fileBytes.size)

        val key = crypto.deriveKeyFromPassword(password, salt)
        val plaintext = crypto.decrypt(EncryptedData(ciphertext, iv), key)
        return Decrypted(plaintext, format)
    }
}

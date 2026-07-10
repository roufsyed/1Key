package com.roufsyed.onekey.feature.importexport.domain

import com.roufsyed.onekey.core.domain.usecase.ExportFormat
import com.roufsyed.onekey.core.security.CryptoManager
import com.roufsyed.onekey.core.security.EncryptedData
import com.roufsyed.onekey.core.security.KdfParams
import com.roufsyed.onekey.core.security.KdfPreset
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import javax.crypto.spec.SecretKeySpec

/**
 * Binary format for encrypted backups:
 *   MAGIC      8 B   "1KEYBKP\n"
 *   VERSION    1 B   0x01 (legacy, no AAD) | 0x02 (header AAD, PBKDF2)
 *                  | 0x03 (header AAD, Argon2id) | 0x04 (timestamp + vault version in AAD)
 *                  | 0x05 (FLAGS + embedded KDF params; optional Secret Key in KDF input)
 *   FORMAT     1 B   0x00=JSON  0x01=CSV
 *   FLAGS      1 B   [V5+ only] bit 0 = requires_secret_key; bits 1-7 RESERVED, MUST be 0
 *   KDF_M_KIB  4 B   [V5+ only] uint32 big-endian (Argon2id memory in KiB)
 *   KDF_T      4 B   [V5+ only] uint32 big-endian (Argon2id iterations)
 *   KDF_P      1 B   [V5+ only] uint8 (parallelism, always 1 today)
 *   TIMESTAMP  8 B   [V4+] export epoch-ms, big-endian
 *   VAULT_VER  4 B   [V4+] vault version counter, big-endian
 *   SALT      32 B   KDF salt (random per export)
 *   IV        12 B   AES-GCM nonce
 *   BODY       N B   AES-256-GCM ciphertext + 16-byte auth tag
 *
 * V2 binds (MAGIC || VERSION || FORMAT) into the GCM auth tag via AAD.
 * V3 is identical in layout to V2 but derives the key with Argon2id (m=64 MB, t=3, p=1).
 * V4 extends the AAD to (MAGIC || VERSION || FORMAT || TIMESTAMP || VAULT_VER), binding
 * the ciphertext to the exact export time and vault version so neither can be swapped
 * without invalidating the auth tag.
 * V5 adds three fields between FORMAT and TIMESTAMP: FLAGS (1 byte), KDF_M_KIB (4 bytes
 * big-endian), KDF_T (4 bytes big-endian), KDF_P (1 byte). The Secret Key feature flips
 * FLAGS bit 0 and concatenates SK into the Argon2id input. Embedded KDF parameters let
 * a backup be decrypted on a device whose current preset differs from the export's.
 *
 * Manual user-initiated exports always write V5 (see [MANUAL_EXPORT_VERSION]). The
 * Sync feature continues to write V4 (see [SYNC_EXPORT_VERSION]) because V4 cannot
 * represent the Secret Key flag - the Sync engine refuses to run when SK is enabled
 * (gated upstream in `AuthRepositoryImpl.unlockWithPassword`). V1-V4 backups
 * continue to decrypt forever; V5 is purely additive in [decrypt].
 */
internal object BackupEncryption {

    private val MAGIC = byteArrayOf(0x31, 0x4B, 0x45, 0x59, 0x42, 0x4B, 0x50, 0x0A)
    private const val VERSION_LEGACY: Byte = 0x01
    private const val VERSION_AAD: Byte = 0x02
    private const val VERSION_ARGON2ID: Byte = 0x03
    private const val VERSION_V4: Byte = 0x04
    private const val VERSION_V5: Byte = 0x05

    /**
     * Version byte written by [encrypt] for user-initiated encrypted exports.
     * V5 carries FLAGS + embedded KDF parameters so Secret Key-protected
     * backups can self-describe their derivation. Bumping this to V6 in the
     * future requires extending the V5 read branch in [decrypt] - existing
     * V1-V5 envelopes MUST keep decrypting forever.
     */
    private const val MANUAL_EXPORT_VERSION: Byte = VERSION_V5

    /**
     * Version byte written by [encryptWithKey] for the Sync engine. The Sync
     * path uses a pre-derived key (Argon2id is paid once at unlock) and
     * therefore cannot embed user-tunable KDF parameters - the receiver must
     * re-derive from the typed master password on restore. V4's AAD binds
     * timestamp + vault version into the tag which is the strongest guarantee
     * the unkeyed payload can carry. The Secret Key feature does not extend
     * sync today (Issue 1 in the locked design): sync refuses to run while
     * SK is enabled, which keeps the V4 envelope honest.
     */
    private const val SYNC_EXPORT_VERSION: Byte = VERSION_V4

    private const val FORMAT_JSON: Byte = 0x00
    private const val FORMAT_CSV: Byte = 0x01
    private const val SALT_LEN = 32
    private const val IV_LEN = 12  // AES-GCM nonce is always 96 bits
    private const val TIMESTAMP_LEN = 8
    private const val VAULT_VER_LEN = 4

    // V5 extension fields between FORMAT and TIMESTAMP.
    private const val FLAGS_LEN = 1
    private const val KDF_M_LEN = 4
    private const val KDF_T_LEN = 4
    private const val KDF_P_LEN = 1

    /** Bit 0 in FLAGS: when set, the KDF input was MP || SK (Secret Key feature on). */
    private const val FLAG_REQUIRES_SECRET_KEY: Byte = 0x01

    /** Mask of bits the V5 reader rejects when set - reserved for forward compatibility. */
    private const val FLAGS_RESERVED_MASK: Int = 0xFE

    // V5 header length up to (but excluding) BODY: 8 + 1 + 1 + 1 + 4 + 4 + 1 + 8 + 4 + 32 + 12 = 76.
    private const val V5_HEADER_LEN =
        8 + 1 + 1 + FLAGS_LEN + KDF_M_LEN + KDF_T_LEN + KDF_P_LEN +
        TIMESTAMP_LEN + VAULT_VER_LEN + SALT_LEN + IV_LEN

    // V5 AAD length: MAGIC || VERSION || FORMAT || FLAGS || KDF_M_KIB || KDF_T || KDF_P
    //              || TIMESTAMP || VAULT_VER. Exactly 32 bytes per the locked design.
    private const val V5_AAD_LEN =
        8 + 1 + 1 + FLAGS_LEN + KDF_M_LEN + KDF_T_LEN + KDF_P_LEN + TIMESTAMP_LEN + VAULT_VER_LEN

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
     * Raised by [decrypt] when a V5 envelope's FLAGS byte has the
     * `requires_secret_key` bit set but the caller passed no [secretKey].
     * Carries the parsed header metadata (timestamp + vault version) so the
     * UI layer can show a useful "this backup needs your Secret Key, scanned
     * from ... (date)" message without re-parsing the file.
     *
     * Thrown BEFORE any Argon2id work so a wrong-SK / missing-SK condition
     * is cheap. The full memory cost of Argon2id (64-256 MiB depending on
     * preset) is only paid once we know we have an SK to feed into it.
     */
    class SecretKeyRequiredException(
        val createdAtMs: Long,
        val vaultVersion: Int,
    ) : Exception("Secret Key required to decrypt this backup")

    /**
     * Encrypts [plaintext] under a key derived from [password] (and optionally
     * [secretKey]) and writes a V5 backup envelope. **Consumes [password]:**
     * the caller's [CharArray] is zeroed in place after key derivation.
     * Callers that need the password again must pass a copy.
     *
     * @param plaintext serialised JSON/CSV bytes to encrypt.
     * @param password the user-typed master password. Zeroed in place after
     *                 derivation; caller-owned [secretKey] is NOT zeroed
     *                 (typically handed in via `SecretKeyHolder.withBytes`).
     * @param format JSON or CSV, written into the FORMAT byte.
     * @param crypto the singleton [CryptoManager] that runs Argon2id and GCM.
     * @param createdAtMs export epoch-ms, written into the V5 header and
     *                    bound into the AAD.
     * @param vaultVersion vault-version counter, written into the V5 header
     *                     and bound into the AAD.
     * @param secretKey optional 16-byte raw Secret Key. When non-null, FLAGS
     *                  bit 0 is set and the KDF input becomes (password ||
     *                  SK). When null, the envelope is SK-free (FLAGS=0).
     * @param kdfParams Argon2id parameters used for this export's derivation.
     *                  Embedded verbatim in the V5 header so restore on a
     *                  device with a different active preset still works.
     *                  Defaults to [KdfPreset.STANDARD] params to keep the
     *                  legacy unparameterised call sites compiling without
     *                  behaviour change.
     */
    fun encrypt(
        plaintext: ByteArray,
        password: CharArray,
        format: ExportFormat,
        crypto: CryptoManager,
        createdAtMs: Long = System.currentTimeMillis(),
        vaultVersion: Int = 0,
        secretKey: ByteArray? = null,
        kdfParams: KdfParams = KdfPreset.STANDARD.toKdfParams(),
    ): ByteArray {
        require(kdfParams.parallelism == 1) {
            "V5 envelope writes KDF_P as a single byte; only parallelism=1 is supported, " +
                "was ${kdfParams.parallelism}"
        }
        require(kdfParams.mCostKiB >= 0) { "KDF mCostKiB must be non-negative" }
        require(kdfParams.tCost >= 0) { "KDF tCost must be non-negative" }

        val salt = crypto.generateSalt(SALT_LEN)
        val key = if (secretKey != null) {
            crypto.deriveKeyFromPasswordWithSecretKeyArgon2id(
                password = password,
                secretKey = secretKey,
                salt = salt,
                params = kdfParams,
            )
        } else {
            crypto.deriveKeyFromPasswordArgon2id(password, salt, kdfParams)
        }
        password.fill(' ')

        val formatByte = if (format == ExportFormat.JSON) FORMAT_JSON else FORMAT_CSV
        val flagsByte: Byte = if (secretKey != null) FLAG_REQUIRES_SECRET_KEY else 0x00
        val aad = buildHeaderAadV5(
            version = MANUAL_EXPORT_VERSION,
            format = formatByte,
            flags = flagsByte,
            kdfMCostKiB = kdfParams.mCostKiB,
            kdfTCost = kdfParams.tCost,
            kdfParallelism = kdfParams.parallelism.toByte(),
            createdAtMs = createdAtMs,
            vaultVersion = vaultVersion,
        )
        val enc = crypto.encrypt(plaintext, key, aad)
        check(enc.iv.size == IV_LEN) { "Unexpected IV length: ${enc.iv.size}" }

        return ByteArrayOutputStream(V5_HEADER_LEN + enc.ciphertext.size).apply {
            write(MAGIC)
            write(MANUAL_EXPORT_VERSION.toInt())
            write(formatByte.toInt())
            write(flagsByte.toInt())
            write(ByteBuffer.allocate(KDF_M_LEN).putInt(kdfParams.mCostKiB).array())
            write(ByteBuffer.allocate(KDF_T_LEN).putInt(kdfParams.tCost).array())
            write(kdfParams.parallelism)
            write(ByteBuffer.allocate(TIMESTAMP_LEN).putLong(createdAtMs).array())
            write(ByteBuffer.allocate(VAULT_VER_LEN).putInt(vaultVersion).array())
            write(salt)
            write(enc.iv)
            write(enc.ciphertext)
        }.toByteArray()
    }

    /**
     * Variant of [encrypt] that takes a pre-derived 32-byte AES key and its
     * salt instead of running Argon2id internally. Used by the Sync feature:
     * the auth layer derives the key once (via `CryptoManager.deriveBackupKey`)
     * at the moment of master-password unlock and hands the bytes to the
     * sync coroutine. The master password never crosses the auth/sync
     * boundary; only this caller-owned derived key does.
     *
     * **Does NOT zero [key]** - ownership stays with the caller (typically the
     * sync coroutine's `finally` block). The salt is written verbatim into the
     * V4 envelope so the restore path (which still types the master password)
     * re-derives the same key via Argon2id over `salt + password`. Restore is
     * unchanged.
     *
     * Envelope version selection:
     *  - [requiresSecretKey] = false (default): writes V4. Identical bytes
     *    to the pre-SK sync output. Pre-derived key was derived from MP
     *    alone via [CryptoManager.deriveBackupKey] with `secretKey=null`.
     *  - [requiresSecretKey] = true: writes V5 with `FLAGS = 0x01`. The
     *    pre-derived key MUST have been derived from MP || SK via
     *    [CryptoManager.deriveBackupKey] with the same `secretKey` bytes.
     *    The V5 envelope also embeds the KDF parameters; sync always
     *    derives at the STANDARD preset (see `deriveBackupKey`) so we
     *    record those parameters in the header. Decrypt re-runs Argon2id
     *    with the embedded parameters and the user-supplied SK at restore
     *    time on a new device.
     */
    fun encryptWithKey(
        plaintext: ByteArray,
        key: ByteArray,
        salt: ByteArray,
        format: ExportFormat,
        crypto: CryptoManager,
        createdAtMs: Long = System.currentTimeMillis(),
        vaultVersion: Int = 0,
        requiresSecretKey: Boolean = false,
    ): ByteArray {
        require(key.size == 32) { "Backup key must be 32 bytes (AES-256), got ${key.size}" }
        require(salt.size == SALT_LEN) { "Backup salt must be $SALT_LEN bytes, got ${salt.size}" }
        val secretKeySpec = SecretKeySpec(key, "AES")
        val formatByte = if (format == ExportFormat.JSON) FORMAT_JSON else FORMAT_CSV

        return if (requiresSecretKey) {
            // V5 envelope path. Sync hardcodes the STANDARD preset KDF
            // parameters; embedded so the decrypt path re-derives with
            // the same values regardless of the restoring device's
            // current default preset.
            val kdfParams = KdfPreset.STANDARD.toKdfParams()
            val flagsByte: Byte = FLAG_REQUIRES_SECRET_KEY
            val aad = buildHeaderAadV5(
                version = VERSION_V5,
                format = formatByte,
                flags = flagsByte,
                kdfMCostKiB = kdfParams.mCostKiB,
                kdfTCost = kdfParams.tCost,
                kdfParallelism = kdfParams.parallelism.toByte(),
                createdAtMs = createdAtMs,
                vaultVersion = vaultVersion,
            )
            val enc = crypto.encrypt(plaintext, secretKeySpec, aad)
            check(enc.iv.size == IV_LEN) { "Unexpected IV length: ${enc.iv.size}" }
            ByteArrayOutputStream(V5_HEADER_LEN + enc.ciphertext.size).apply {
                write(MAGIC)
                write(VERSION_V5.toInt())
                write(formatByte.toInt())
                write(flagsByte.toInt())
                write(ByteBuffer.allocate(KDF_M_LEN).putInt(kdfParams.mCostKiB).array())
                write(ByteBuffer.allocate(KDF_T_LEN).putInt(kdfParams.tCost).array())
                write(kdfParams.parallelism)
                write(ByteBuffer.allocate(TIMESTAMP_LEN).putLong(createdAtMs).array())
                write(ByteBuffer.allocate(VAULT_VER_LEN).putInt(vaultVersion).array())
                write(salt)
                write(enc.iv)
                write(enc.ciphertext)
            }.toByteArray()
        } else {
            // V4 envelope path (legacy sync output). Bytes are identical
            // to pre-SK sync, so existing restores on V4 readers keep
            // working.
            val aad = buildHeaderAad(SYNC_EXPORT_VERSION, formatByte, createdAtMs, vaultVersion)
            val enc = crypto.encrypt(plaintext, secretKeySpec, aad)
            check(enc.iv.size == IV_LEN) { "Unexpected IV length: ${enc.iv.size}" }
            ByteArrayOutputStream(
                MAGIC.size + 2 + TIMESTAMP_LEN + VAULT_VER_LEN + SALT_LEN + IV_LEN + enc.ciphertext.size,
            ).apply {
                write(MAGIC)
                write(SYNC_EXPORT_VERSION.toInt())
                write(formatByte.toInt())
                write(ByteBuffer.allocate(TIMESTAMP_LEN).putLong(createdAtMs).array())
                write(ByteBuffer.allocate(VAULT_VER_LEN).putInt(vaultVersion).array())
                write(salt)
                write(enc.iv)
                write(enc.ciphertext)
            }.toByteArray()
        }
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
     *
     * @param secretKey optional 16-byte raw Secret Key. Required when the file is
     *                  a V5 envelope with FLAGS bit 0 set; ignored on V1-V4. When
     *                  the envelope demands SK but [secretKey] is null,
     *                  [SecretKeyRequiredException] is thrown BEFORE any Argon2id
     *                  work runs so the caller can pivot to the SK scanner UI
     *                  without paying the full Argon2id cost.
     */
    fun decrypt(
        fileBytes: ByteArray,
        password: CharArray,
        secretKey: ByteArray? = null,
        crypto: CryptoManager,
    ): Decrypted {
        var off = 0

        val magic = fileBytes.sliceArray(off until off + MAGIC.size); off += MAGIC.size
        require(magic.contentEquals(MAGIC)) { "Not a 1Key encrypted backup" }

        val version = fileBytes[off++]
        require(
            version == VERSION_LEGACY || version == VERSION_AAD ||
            version == VERSION_ARGON2ID || version == VERSION_V4 || version == VERSION_V5
        ) {
            "Unsupported backup version: 0x${version.toInt().and(0xFF).toString(16)}"
        }

        val fmtByte = fileBytes[off++]
        val format = if (fmtByte == FORMAT_JSON) ExportFormat.JSON else ExportFormat.CSV

        // V5 extension fields between FORMAT and TIMESTAMP. Parsed BEFORE the
        // SK-required check so we can surface a useful exception including
        // header metadata to the UI without re-reading the file.
        val flagsByte: Byte
        val kdfMCostKiB: Int
        val kdfTCost: Int
        val kdfParallelism: Byte
        if (version == VERSION_V5) {
            flagsByte = fileBytes[off++]
            // Reject reserved-bit usage so a future format extension that wants
            // to use bits 1-7 can rely on V5 readers refusing the envelope
            // rather than silently ignoring the new bits. Run BEFORE the
            // SK-required check so a malformed FLAGS byte never leaks header
            // metadata via SecretKeyRequiredException.
            require((flagsByte.toInt() and FLAGS_RESERVED_MASK) == 0) {
                "Unsupported FLAGS bits set: 0x${flagsByte.toInt().and(0xFF).toString(16)}"
            }
            kdfMCostKiB = ByteBuffer.wrap(fileBytes, off, KDF_M_LEN).int; off += KDF_M_LEN
            kdfTCost = ByteBuffer.wrap(fileBytes, off, KDF_T_LEN).int; off += KDF_T_LEN
            kdfParallelism = fileBytes[off++]
        } else {
            flagsByte = 0x00
            kdfMCostKiB = 0
            kdfTCost = 0
            kdfParallelism = 0
        }

        // V4 / V5 embed timestamp and vault version between (V5 KDF block | V4 FORMAT) and SALT.
        val createdAtMs: Long
        val vaultVersion: Int
        if (version == VERSION_V4 || version == VERSION_V5) {
            createdAtMs = ByteBuffer.wrap(fileBytes, off, TIMESTAMP_LEN).long; off += TIMESTAMP_LEN
            vaultVersion = ByteBuffer.wrap(fileBytes, off, VAULT_VER_LEN).int; off += VAULT_VER_LEN
        } else {
            createdAtMs = 0L
            vaultVersion = 0
        }

        // V5 SK-required guard. MUST fire BEFORE any Argon2id work - the
        // password has not been touched yet, and missing SK is a fast user
        // pivot to the scanner UI. CryptoManager's derive* methods are
        // overridable (open fun) so tests can spy on whether derivation was
        // ever called - this ordering is locked by the BackupEncryptionV5Test
        // suite.
        if (version == VERSION_V5 &&
            (flagsByte.toInt() and FLAG_REQUIRES_SECRET_KEY.toInt()) != 0 &&
            secretKey == null
        ) {
            throw SecretKeyRequiredException(
                createdAtMs = createdAtMs,
                vaultVersion = vaultVersion,
            )
        }

        val salt = fileBytes.sliceArray(off until off + SALT_LEN); off += SALT_LEN
        val iv = fileBytes.sliceArray(off until off + IV_LEN); off += IV_LEN
        val ciphertext = fileBytes.sliceArray(off until fileBytes.size)

        // Dispatch KDF:
        //  - V5 with SK flag: deriveKeyFromPasswordWithSecretKeyArgon2id under embedded params.
        //  - V5 without SK:   deriveKeyFromPasswordArgon2id under embedded params.
        //  - V3 / V4:         deriveKeyFromPasswordArgon2id under Standard params (historical).
        //  - V1 / V2:         PBKDF2 (backward compat).
        val key = when (version) {
            VERSION_V5 -> {
                val params = KdfParams(
                    mCostKiB = kdfMCostKiB,
                    tCost = kdfTCost,
                    parallelism = kdfParallelism.toInt().and(0xFF),
                )
                if ((flagsByte.toInt() and FLAG_REQUIRES_SECRET_KEY.toInt()) != 0) {
                    // SK presence has been checked above; non-null guaranteed here.
                    crypto.deriveKeyFromPasswordWithSecretKeyArgon2id(
                        password = password,
                        secretKey = secretKey!!,
                        salt = salt,
                        params = params,
                    )
                } else {
                    crypto.deriveKeyFromPasswordArgon2id(password, salt, params)
                }
            }
            VERSION_ARGON2ID, VERSION_V4 -> crypto.deriveKeyFromPasswordArgon2id(password, salt)
            else -> crypto.deriveKeyFromPassword(password, salt)
        }
        password.fill(' ')

        // V2/V3: AAD = (MAGIC || VERSION || FORMAT)
        // V4:    AAD = (MAGIC || VERSION || FORMAT || TIMESTAMP || VAULT_VER)
        // V5:    AAD = (MAGIC || VERSION || FORMAT || FLAGS || KDF_M_KIB || KDF_T || KDF_P
        //              || TIMESTAMP || VAULT_VER)
        // V1: no AAD - legacy backups decrypt without header authentication.
        val aad = when (version) {
            VERSION_V5 -> buildHeaderAadV5(
                version = version,
                format = fmtByte,
                flags = flagsByte,
                kdfMCostKiB = kdfMCostKiB,
                kdfTCost = kdfTCost,
                kdfParallelism = kdfParallelism,
                createdAtMs = createdAtMs,
                vaultVersion = vaultVersion,
            )
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

    /**
     * Builds the V5 AAD blob bound into the GCM auth tag. Layout matches the
     * locked design exactly:
     *   MAGIC || VERSION || FORMAT || FLAGS || KDF_M_KIB(BE) || KDF_T(BE)
     *        || KDF_P || TIMESTAMP(BE) || VAULT_VER(BE)
     * Total length is [V5_AAD_LEN] (32 bytes). Tampering with any of the
     * fields - including FLAGS or the KDF parameter triple - invalidates the
     * tag, so an attacker cannot swap an SK-protected envelope for a non-SK
     * one or push a victim onto cheaper Argon2id params.
     */
    private fun buildHeaderAadV5(
        version: Byte,
        format: Byte,
        flags: Byte,
        kdfMCostKiB: Int,
        kdfTCost: Int,
        kdfParallelism: Byte,
        createdAtMs: Long,
        vaultVersion: Int,
    ): ByteArray {
        val aad = ByteArray(V5_AAD_LEN)
        var pos = 0
        System.arraycopy(MAGIC, 0, aad, pos, MAGIC.size); pos += MAGIC.size
        aad[pos++] = version
        aad[pos++] = format
        aad[pos++] = flags
        ByteBuffer.wrap(aad, pos, KDF_M_LEN).putInt(kdfMCostKiB); pos += KDF_M_LEN
        ByteBuffer.wrap(aad, pos, KDF_T_LEN).putInt(kdfTCost); pos += KDF_T_LEN
        aad[pos++] = kdfParallelism
        ByteBuffer.wrap(aad, pos, TIMESTAMP_LEN).putLong(createdAtMs); pos += TIMESTAMP_LEN
        ByteBuffer.wrap(aad, pos, VAULT_VER_LEN).putInt(vaultVersion); pos += VAULT_VER_LEN
        check(pos == V5_AAD_LEN) { "V5 AAD builder filled $pos bytes, expected $V5_AAD_LEN" }
        return aad
    }
}

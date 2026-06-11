package com.onekey.feature.importexport.domain

import com.onekey.core.domain.usecase.ExportFormat
import com.onekey.core.security.CryptoManager
import com.onekey.core.security.KdfParams
import com.onekey.core.security.KdfPreset
import com.onekey.core.security.SecretKeyHolder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.security.MessageDigest
import javax.crypto.AEADBadTagException
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * Behavioural locks for the V5 backup envelope.
 *
 * # Why this file uses a stub CryptoManager rather than running real Argon2id
 *
 * The Argon2id library (`com.lambdapioneer.argon2kt`) ships native libraries
 * for Android ABIs only - there is no host-JVM `.so`, so calling
 * `deriveKeyFromPasswordArgon2id` from a plain unit test crashes with
 * UnsatisfiedLinkError. The StrongBox-fallback test file documents the same
 * limitation for the AndroidKeyStore provider; we are not the first piece of
 * crypto code to hit it.
 *
 * The fix is a [StubCryptoManager] subclass that overrides every `derive*`
 * method to produce a deterministic 32-byte SHA-256 hash of (password ||
 * secretKey || salt || params). This is NOT a cryptographic claim about the
 * derivation - the test target is the V5 envelope structure (header layout,
 * AAD binding, FLAGS handling, SK-required ordering) which is fully testable
 * against any key-derivation function that is honest about its inputs.
 *
 * The stub also overrides [CryptoManager.deriveKeyFromPassword] (PBKDF2) so
 * V1/V2 envelopes can round-trip without the real PBKDF2 implementation.
 *
 * # SK-required ordering test
 *
 * The "no Argon2id work before the SK guard fires" assertion (challenger
 * Issue 13) is locked by a recording spy: a flag on the stub that flips to
 * true the moment any `derive*` method is called. Asserting the flag stays
 * false after [BackupEncryption.SecretKeyRequiredException] is thrown
 * proves the guard fires upstream.
 */
class BackupEncryptionV5Test {

    private val crypto = StubCryptoManager()
    // Fixed-bytes plaintext keeps every round-trip assertion to one
    // comparison; the JSON-vs-CSV branch is exercised by separate tests
    // that flip the [format] parameter.
    private val plaintext = "credential-blob-v5".toByteArray(Charsets.UTF_8)
    private val skBytes = ByteArray(SecretKeyHolder.SECRET_KEY_RAW_LENGTH) { (it + 1).toByte() }
    private val createdAtMs = 1_700_000_000_000L
    private val vaultVersion = 42
    private val kdfParams = KdfParams(mCostKiB = 65_536, tCost = 3, parallelism = 1)

    // ── Round trips ──────────────────────────────────────────────────────────

    @Test
    fun `v5 round-trip FLAGS=0 JSON returns plaintext and format`() {
        val pwd = "Pa55!".toCharArray()
        val bytes = BackupEncryption.encrypt(
            plaintext = plaintext,
            password = pwd,
            format = ExportFormat.JSON,
            crypto = crypto,
            createdAtMs = createdAtMs,
            vaultVersion = vaultVersion,
            secretKey = null,
            kdfParams = kdfParams,
        )
        val decoded = BackupEncryption.decrypt(
            fileBytes = bytes,
            password = "Pa55!".toCharArray(),
            secretKey = null,
            crypto = crypto,
        )
        assertArrayEquals(plaintext, decoded.plaintext)
        assertEquals(ExportFormat.JSON, decoded.format)
        assertEquals(0x05, versionByte(bytes).toInt() and 0xFF)
        assertEquals(0x00, flagsByte(bytes).toInt() and 0xFF)
    }

    @Test
    fun `v5 round-trip FLAGS=0 CSV returns plaintext and CSV format`() {
        val pwd = "Pa55!".toCharArray()
        val bytes = BackupEncryption.encrypt(
            plaintext = plaintext,
            password = pwd,
            format = ExportFormat.CSV,
            crypto = crypto,
            createdAtMs = createdAtMs,
            vaultVersion = vaultVersion,
            secretKey = null,
            kdfParams = kdfParams,
        )
        val decoded = BackupEncryption.decrypt(
            fileBytes = bytes,
            password = "Pa55!".toCharArray(),
            secretKey = null,
            crypto = crypto,
        )
        assertEquals(ExportFormat.CSV, decoded.format)
        assertArrayEquals(plaintext, decoded.plaintext)
    }

    @Test
    fun `v5 round-trip FLAGS=1 with secret key returns plaintext`() {
        val pwd = "Pa55!".toCharArray()
        val bytes = BackupEncryption.encrypt(
            plaintext = plaintext,
            password = pwd,
            format = ExportFormat.JSON,
            crypto = crypto,
            createdAtMs = createdAtMs,
            vaultVersion = vaultVersion,
            secretKey = skBytes,
            kdfParams = kdfParams,
        )
        assertEquals(
            "FLAGS bit 0 must be set when SK is mixed into derivation",
            0x01, flagsByte(bytes).toInt() and 0xFF,
        )
        val decoded = BackupEncryption.decrypt(
            fileBytes = bytes,
            password = "Pa55!".toCharArray(),
            secretKey = skBytes,
            crypto = crypto,
        )
        assertArrayEquals(plaintext, decoded.plaintext)
    }

    @Test
    fun `v5 decrypt with wrong secret key throws AEADBadTagException`() {
        // The SK is mixed into the Argon2id input so a wrong SK derives a
        // different key, which fails the GCM auth tag. Pinning that the
        // failure mode is AEADBadTagException (not IllegalStateException,
        // not a silent garbage-plaintext return) is what lets the importer
        // present "Wrong password or corrupted backup" to the user.
        val pwd = "Pa55!".toCharArray()
        val bytes = BackupEncryption.encrypt(
            plaintext = plaintext,
            password = pwd,
            format = ExportFormat.JSON,
            crypto = crypto,
            createdAtMs = createdAtMs,
            vaultVersion = vaultVersion,
            secretKey = skBytes,
            kdfParams = kdfParams,
        )
        val wrongSk = ByteArray(SecretKeyHolder.SECRET_KEY_RAW_LENGTH) { (it + 100).toByte() }
        assertThrows(AEADBadTagException::class.java) {
            BackupEncryption.decrypt(
                fileBytes = bytes,
                password = "Pa55!".toCharArray(),
                secretKey = wrongSk,
                crypto = crypto,
            )
        }
    }

    // ── SK-required ordering ────────────────────────────────────────────────

    @Test
    fun `v5 decrypt throws SecretKeyRequiredException BEFORE Argon2id when FLAGS bit 0 set and sk null`() {
        // Build the V5 envelope with FLAGS=1 using a different spy so the
        // "before Argon2id" assertion below is over a fresh recording.
        val producer = StubCryptoManager()
        val pwd = "Pa55!".toCharArray()
        val bytes = BackupEncryption.encrypt(
            plaintext = plaintext,
            password = pwd,
            format = ExportFormat.JSON,
            crypto = producer,
            createdAtMs = createdAtMs,
            vaultVersion = vaultVersion,
            secretKey = skBytes,
            kdfParams = kdfParams,
        )

        val recording = StubCryptoManager()
        val ex = assertThrows(BackupEncryption.SecretKeyRequiredException::class.java) {
            BackupEncryption.decrypt(
                fileBytes = bytes,
                password = "Pa55!".toCharArray(),
                secretKey = null,
                crypto = recording,
            )
        }
        assertFalse(
            "no derive* method may be called before the SK-required guard fires",
            recording.deriveCalled,
        )
        assertEquals(createdAtMs, ex.createdAtMs)
        assertEquals(vaultVersion, ex.vaultVersion)
    }

    @Test
    fun `v5 decrypt rejects reserved FLAGS bits`() {
        // Construct a V5 envelope and flip a reserved bit (bit 1, value 0x02).
        val pwd = "Pa55!".toCharArray()
        val bytes = BackupEncryption.encrypt(
            plaintext = plaintext,
            password = pwd,
            format = ExportFormat.JSON,
            crypto = crypto,
            createdAtMs = createdAtMs,
            vaultVersion = vaultVersion,
            secretKey = null,
            kdfParams = kdfParams,
        ).copyOf()
        val flagsOffset = 8 + 1 + 1  // MAGIC + VERSION + FORMAT
        bytes[flagsOffset] = (bytes[flagsOffset].toInt() or 0x02).toByte()

        val ex = assertThrows(IllegalArgumentException::class.java) {
            BackupEncryption.decrypt(
                fileBytes = bytes,
                password = "Pa55!".toCharArray(),
                secretKey = null,
                crypto = StubCryptoManager(),
            )
        }
        assertTrue(
            "reserved-bit message should mention FLAGS: ${ex.message}",
            ex.message?.contains("FLAGS") == true,
        )
    }

    // ── AAD tamper tests ────────────────────────────────────────────────────

    @Test
    fun `v5 decrypt rejects tampered FLAGS bit beyond reserved mask`() {
        // Tamper FLAGS to 0x01 (requires SK) without changing anything else.
        // Even when we pass an SK, the AAD mismatch must fail the auth tag.
        val pwd = "Pa55!".toCharArray()
        val bytes = BackupEncryption.encrypt(
            plaintext = plaintext,
            password = pwd,
            format = ExportFormat.JSON,
            crypto = crypto,
            createdAtMs = createdAtMs,
            vaultVersion = vaultVersion,
            secretKey = null,
            kdfParams = kdfParams,
        ).copyOf()
        val flagsOffset = 8 + 1 + 1
        bytes[flagsOffset] = 0x01.toByte()

        // The header says FLAGS=1; pass SK so the SK-required guard doesn't
        // trip and we actually reach the GCM tag verification. The verifier
        // will derive a key with SK mixed in but the original envelope's
        // ciphertext was bound to AAD with FLAGS=0, so the tag mismatches.
        assertThrows(AEADBadTagException::class.java) {
            BackupEncryption.decrypt(
                fileBytes = bytes,
                password = "Pa55!".toCharArray(),
                secretKey = skBytes,
                crypto = StubCryptoManager(),
            )
        }
    }

    @Test
    fun `v5 decrypt rejects tampered KDF_M_KIB`() {
        val pwd = "Pa55!".toCharArray()
        val bytes = BackupEncryption.encrypt(
            plaintext = plaintext,
            password = pwd,
            format = ExportFormat.JSON,
            crypto = crypto,
            createdAtMs = createdAtMs,
            vaultVersion = vaultVersion,
            secretKey = null,
            kdfParams = kdfParams,
        ).copyOf()
        // KDF_M_KIB lives at offset MAGIC(8) + VERSION(1) + FORMAT(1) + FLAGS(1) = 11.
        // Flip a high byte of the int.
        bytes[11] = (bytes[11].toInt() xor 0x01).toByte()
        assertThrows(AEADBadTagException::class.java) {
            BackupEncryption.decrypt(
                fileBytes = bytes,
                password = "Pa55!".toCharArray(),
                secretKey = null,
                crypto = StubCryptoManager(),
            )
        }
    }

    @Test
    fun `v5 decrypt rejects tampered KDF_T`() {
        val pwd = "Pa55!".toCharArray()
        val bytes = BackupEncryption.encrypt(
            plaintext = plaintext,
            password = pwd,
            format = ExportFormat.JSON,
            crypto = crypto,
            createdAtMs = createdAtMs,
            vaultVersion = vaultVersion,
            secretKey = null,
            kdfParams = kdfParams,
        ).copyOf()
        // KDF_T at offset MAGIC(8) + VERSION(1) + FORMAT(1) + FLAGS(1) + KDF_M_KIB(4) = 15.
        bytes[15] = (bytes[15].toInt() xor 0x01).toByte()
        assertThrows(AEADBadTagException::class.java) {
            BackupEncryption.decrypt(
                fileBytes = bytes,
                password = "Pa55!".toCharArray(),
                secretKey = null,
                crypto = StubCryptoManager(),
            )
        }
    }

    @Test
    fun `v5 decrypt rejects tampered KDF_P`() {
        val pwd = "Pa55!".toCharArray()
        val bytes = BackupEncryption.encrypt(
            plaintext = plaintext,
            password = pwd,
            format = ExportFormat.JSON,
            crypto = crypto,
            createdAtMs = createdAtMs,
            vaultVersion = vaultVersion,
            secretKey = null,
            kdfParams = kdfParams,
        ).copyOf()
        // KDF_P at offset 8 + 1 + 1 + 1 + 4 + 4 = 19.
        // Original parallelism is 1; XOR with 0x02 flips bit 1 to make it 3
        // (still a valid KdfParams.init value, so we reach the GCM tag
        // comparison rather than tripping the parallelism>0 require).
        bytes[19] = (bytes[19].toInt() xor 0x02).toByte()
        assertThrows(AEADBadTagException::class.java) {
            BackupEncryption.decrypt(
                fileBytes = bytes,
                password = "Pa55!".toCharArray(),
                secretKey = null,
                crypto = StubCryptoManager(),
            )
        }
    }

    @Test
    fun `v5 decrypt rejects tampered TIMESTAMP`() {
        val pwd = "Pa55!".toCharArray()
        val bytes = BackupEncryption.encrypt(
            plaintext = plaintext,
            password = pwd,
            format = ExportFormat.JSON,
            crypto = crypto,
            createdAtMs = createdAtMs,
            vaultVersion = vaultVersion,
            secretKey = null,
            kdfParams = kdfParams,
        ).copyOf()
        // TIMESTAMP at offset 8 + 1 + 1 + 1 + 4 + 4 + 1 = 20.
        bytes[20] = (bytes[20].toInt() xor 0x01).toByte()
        assertThrows(AEADBadTagException::class.java) {
            BackupEncryption.decrypt(
                fileBytes = bytes,
                password = "Pa55!".toCharArray(),
                secretKey = null,
                crypto = StubCryptoManager(),
            )
        }
    }

    @Test
    fun `v5 decrypt rejects tampered VAULT_VER`() {
        val pwd = "Pa55!".toCharArray()
        val bytes = BackupEncryption.encrypt(
            plaintext = plaintext,
            password = pwd,
            format = ExportFormat.JSON,
            crypto = crypto,
            createdAtMs = createdAtMs,
            vaultVersion = vaultVersion,
            secretKey = null,
            kdfParams = kdfParams,
        ).copyOf()
        // VAULT_VER at offset 8 + 1 + 1 + 1 + 4 + 4 + 1 + 8 = 28.
        bytes[28] = (bytes[28].toInt() xor 0x01).toByte()
        assertThrows(AEADBadTagException::class.java) {
            BackupEncryption.decrypt(
                fileBytes = bytes,
                password = "Pa55!".toCharArray(),
                secretKey = null,
                crypto = StubCryptoManager(),
            )
        }
    }

    // ── Embedded KDF params ─────────────────────────────────────────────────

    @Test
    fun `v5 decrypt re-derives with envelope embedded KDF params not crypto defaults`() {
        // Encrypt with (m=128 MiB, t=8) - explicit non-default tuple.
        val customParams = KdfParams(mCostKiB = 128, tCost = 8, parallelism = 1)
        val pwd = "Pa55!".toCharArray()
        val bytes = BackupEncryption.encrypt(
            plaintext = plaintext,
            password = pwd,
            format = ExportFormat.JSON,
            crypto = crypto,
            createdAtMs = createdAtMs,
            vaultVersion = vaultVersion,
            secretKey = null,
            kdfParams = customParams,
        )
        // Decrypt records which params it was handed. The stub stores the
        // last seen KdfParams via the [StubCryptoManager.lastDeriveParams]
        // field; the test asserts on that value.
        val recording = StubCryptoManager()
        val decoded = BackupEncryption.decrypt(
            fileBytes = bytes,
            password = "Pa55!".toCharArray(),
            secretKey = null,
            crypto = recording,
        )
        assertArrayEquals(plaintext, decoded.plaintext)
        assertEquals(
            "Decrypt MUST re-derive under the envelope's embedded params, not the crypto manager's defaults",
            customParams,
            recording.lastDeriveParams,
        )
    }

    // ── Cross-version decode ────────────────────────────────────────────────

    @Test
    fun `v5 decrypt accepts a v4 envelope produced by encryptWithKey`() {
        // V4 is what the Sync feature continues to write. The new V5
        // reader MUST still accept it untouched (purely additive switch).
        //
        // The encryptWithKey path uses an externally-derived key (the Sync
        // engine pays Argon2id once at unlock and hands the bytes here).
        // To exercise a real round-trip through the stub, we encrypt with
        // a key the stub will reproduce on decrypt: the stub hashes
        // (password || salt) under the Argon2id branch, so we hand
        // encryptWithKey the SAME hash bytes for the SAME salt.
        val salt = ByteArray(32) { 0x44 }
        val pwd = "Pa55!".toCharArray()
        val key = sha256OfPasswordSalt("Pa55!", salt)
        val bytes = BackupEncryption.encryptWithKey(
            plaintext = plaintext,
            key = key,
            salt = salt,
            format = ExportFormat.JSON,
            crypto = crypto,
            createdAtMs = createdAtMs,
            vaultVersion = vaultVersion,
            requiresSecretKey = false,
        )
        assertEquals("V4 envelope must carry VERSION=0x04", 0x04, versionByte(bytes).toInt() and 0xFF)
        // V4 decrypt path runs deriveKeyFromPasswordArgon2id(password, salt)
        // which the stub implements as SHA-256(password || salt). Since the
        // encrypt path used the same hash, the keys match and the GCM tag
        // verifies - confirming the V4 envelope is fully readable by the
        // V5-aware decrypt(...) function.
        val decoded = BackupEncryption.decrypt(
            fileBytes = bytes,
            password = pwd,
            secretKey = null,
            crypto = StubCryptoManager(),
        )
        assertArrayEquals(plaintext, decoded.plaintext)
        assertEquals(ExportFormat.JSON, decoded.format)
    }

    private fun sha256OfPasswordSalt(password: String, salt: ByteArray): ByteArray {
        // Mirror StubCryptoManager.deriveKeyFromPasswordArgon2id when invoked
        // without a SecretKey: digest = SHA-256(password_utf8 || salt || params_bytes).
        // The V4 read path passes [KdfPreset.STANDARD.toKdfParams()] to the
        // Argon2id derive call, so we must include those exact param bytes here.
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(password.toByteArray(Charsets.UTF_8))
        digest.update(salt)
        val params = KdfPreset.STANDARD.toKdfParams()
        val buf = ByteBuffer.allocate(9)
        buf.putInt(params.mCostKiB)
        buf.putInt(params.tCost)
        buf.put(params.parallelism.toByte())
        digest.update(buf.array())
        return digest.digest()
    }

    @Test
    fun `v1 v2 v3 v4 envelopes are still recognised by the version byte switch`() {
        // Synthesise minimal V1/V2/V3 envelopes that only need to reach the
        // version-switch in decrypt(). The actual GCM body is irrelevant -
        // we expect each to fail somewhere AFTER the version recognition
        // (either AEADBadTagException on bad password, or a header read
        // exception on truncation). We assert the failure is NOT the
        // "Unsupported backup version" require() at the top.
        for (version in listOf(0x01, 0x02, 0x03, 0x04)) {
            val envelope = synthesiseHeaderForVersion(version.toByte())
            val caught = runCatching {
                BackupEncryption.decrypt(
                    fileBytes = envelope,
                    password = "x".toCharArray(),
                    secretKey = null,
                    crypto = StubCryptoManager(),
                )
            }.exceptionOrNull()
            val msg = caught?.message ?: ""
            assertFalse(
                "V$version envelope must NOT be rejected as unsupported: caught=$caught",
                msg.startsWith("Unsupported backup version"),
            )
        }
    }

    @Test
    fun `decrypt rejects unknown version byte`() {
        val bytes = synthesiseHeaderForVersion(0x07.toByte())
        val ex = assertThrows(IllegalArgumentException::class.java) {
            BackupEncryption.decrypt(
                fileBytes = bytes,
                password = "x".toCharArray(),
                secretKey = null,
                crypto = StubCryptoManager(),
            )
        }
        assertTrue(
            "Unsupported version error must mention 0x07: ${ex.message}",
            ex.message?.contains("0x7") == true || ex.message?.contains("0x07") == true,
        )
    }

    @Test
    fun `encryptWithKey writes a v4 envelope not v5`() {
        // Regression guard for Issue 1: Sync (which calls encryptWithKey)
        // MUST keep writing V4 envelopes so V5 / SK semantics remain a
        // user-initiated manual export concept. If a future refactor
        // routes encryptWithKey through V5 by accident, this test goes
        // red and forces a re-read of the Issue 1 plan.
        val bytes = BackupEncryption.encryptWithKey(
            plaintext = plaintext,
            key = ByteArray(32),
            salt = ByteArray(32),
            format = ExportFormat.JSON,
            crypto = crypto,
            createdAtMs = createdAtMs,
            vaultVersion = vaultVersion,
            requiresSecretKey = false,
        )
        assertEquals(0x04, versionByte(bytes).toInt() and 0xFF)
    }

    @Test
    fun `encryptWithKey writes V5 envelope with FLAGS=1 when requiresSecretKey is true`() {
        // SK-aware sync path (blocker B3 fix): the sync layer derives the
        // backup key from MP || SK and hands it here pre-derived. The
        // envelope MUST set FLAGS bit 0 so a new-device restore knows it
        // needs the Emergency Kit. The KDF parameters embedded in the
        // header are STANDARD (sync's hardcoded preset).
        val bytes = BackupEncryption.encryptWithKey(
            plaintext = plaintext,
            key = ByteArray(32),
            salt = ByteArray(32),
            format = ExportFormat.JSON,
            crypto = crypto,
            createdAtMs = createdAtMs,
            vaultVersion = vaultVersion,
            requiresSecretKey = true,
        )

        // Version byte at offset 8 is V5 (0x05).
        assertEquals(0x05, versionByte(bytes).toInt() and 0xFF)
        // FLAGS byte at offset 10 has bit 0 set.
        val flagsByte = bytes[10].toInt() and 0xFF
        assertEquals(
            "FLAGS bit 0 must be set when requiresSecretKey=true; got 0x${flagsByte.toString(16)}",
            1,
            flagsByte and 0x01,
        )
    }

    @Test
    fun `encryptWithKey V5_FLAGS1 output is structurally a valid V5 envelope`() {
        // Header total is exactly 76 bytes before BODY; embedded KDF
        // params match the STANDARD preset that sync hardcodes. Bytes
        // produced here must be decodable by decrypt() given the same
        // key (decrypt re-derives via Argon2id over MP || SK + salt;
        // testing that round trip directly requires Argon2id which is
        // covered in the on-device test).
        val bytes = BackupEncryption.encryptWithKey(
            plaintext = plaintext,
            key = ByteArray(32),
            salt = ByteArray(32),
            format = ExportFormat.JSON,
            crypto = crypto,
            createdAtMs = createdAtMs,
            vaultVersion = vaultVersion,
            requiresSecretKey = true,
        )
        // Total length is at least the V5 header.
        assertTrue("V5 envelope too short: ${bytes.size}", bytes.size >= 76)
        // Magic prefix unchanged.
        val magicBytes = byteArrayOf(0x31, 0x4B, 0x45, 0x59, 0x42, 0x4B, 0x50, 0x0A)
        for (i in magicBytes.indices) {
            assertEquals("magic byte $i mismatch", magicBytes[i], bytes[i])
        }
    }

    // ── encrypt() argument validation ───────────────────────────────────────

    @Test
    fun `encrypt rejects parallelism greater than 1`() {
        val pwd = "x".toCharArray()
        assertThrows(IllegalArgumentException::class.java) {
            BackupEncryption.encrypt(
                plaintext = plaintext,
                password = pwd,
                format = ExportFormat.JSON,
                crypto = crypto,
                createdAtMs = createdAtMs,
                vaultVersion = vaultVersion,
                secretKey = null,
                kdfParams = KdfParams(mCostKiB = 65_536, tCost = 3, parallelism = 2),
            )
        }
    }

    @Test
    fun `encrypt FLAGS byte is 0x00 when secretKey is null and 0x01 when non-null`() {
        val pwd = "x".toCharArray()
        val bytesOff = BackupEncryption.encrypt(
            plaintext = plaintext,
            password = pwd,
            format = ExportFormat.JSON,
            crypto = crypto,
            createdAtMs = createdAtMs,
            vaultVersion = vaultVersion,
            secretKey = null,
            kdfParams = kdfParams,
        )
        assertEquals(0x00, flagsByte(bytesOff).toInt() and 0xFF)

        val pwd2 = "x".toCharArray()
        val bytesOn = BackupEncryption.encrypt(
            plaintext = plaintext,
            password = pwd2,
            format = ExportFormat.JSON,
            crypto = crypto,
            createdAtMs = createdAtMs,
            vaultVersion = vaultVersion,
            secretKey = skBytes,
            kdfParams = kdfParams,
        )
        assertEquals(0x01, flagsByte(bytesOn).toInt() and 0xFF)
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    /** Pulls the VERSION byte from an envelope (offset 8). */
    private fun versionByte(bytes: ByteArray): Byte = bytes[8]

    /** Pulls the FLAGS byte from a V5 envelope (offset 10). */
    private fun flagsByte(bytes: ByteArray): Byte = bytes[10]

    /**
     * Returns a barely-well-formed envelope of [version] long enough to
     * pass the version-switch but short enough to fail on the GCM step.
     * For V1-V4 we follow the historical layout; for V5+ we use the V5
     * layout. Only the version byte itself drives the test - the rest is
     * deliberate filler.
     */
    private fun synthesiseHeaderForVersion(version: Byte): ByteArray {
        val magic = byteArrayOf(0x31, 0x4B, 0x45, 0x59, 0x42, 0x4B, 0x50, 0x0A)
        return when (version.toInt() and 0xFF) {
            0x01, 0x02, 0x03 -> {
                // 8 magic + 1 ver + 1 fmt + 32 salt + 12 iv + 32 minimal ct
                ByteArray(8 + 2 + 32 + 12 + 32).also { buf ->
                    System.arraycopy(magic, 0, buf, 0, 8)
                    buf[8] = version
                    buf[9] = 0x00 // FORMAT_JSON
                }
            }
            0x04 -> {
                // 8 + 2 + 8 timestamp + 4 vault_ver + 32 salt + 12 iv + 32 ct
                ByteArray(8 + 2 + 8 + 4 + 32 + 12 + 32).also { buf ->
                    System.arraycopy(magic, 0, buf, 0, 8)
                    buf[8] = version
                    buf[9] = 0x00
                }
            }
            else -> {
                // V5 / unknown: write the V5 layout so the version byte is
                // observable and the later layout reads don't crash. The
                // "unknown" path fails the version-switch require() at the
                // top before reaching any field beyond byte 8.
                ByteArray(76 + 32).also { buf ->
                    System.arraycopy(magic, 0, buf, 0, 8)
                    buf[8] = version
                    buf[9] = 0x00
                    // FLAGS = 0; KDF block, timestamp, vault_ver all zeroed.
                }
            }
        }
    }

    /**
     * Stub CryptoManager that produces deterministic 32-byte keys from
     * SHA-256(password || sk || salt || params). Used so the V5 envelope
     * tests run in a plain JVM unit test without requiring the Argon2id
     * native library or a real AES KDF.
     *
     * Records:
     *  - [deriveCalled] flips to true on first invocation of any derive*.
     *    Used by the SK-required ordering test to prove the guard fires
     *    BEFORE any derivation work.
     *  - [lastDeriveParams] captures the KdfParams handed into the last
     *    derive call (null for PBKDF2). Used by the embedded-KDF-params
     *    test to confirm decrypt uses envelope values not crypto defaults.
     */
    private class StubCryptoManager : CryptoManager() {
        @Volatile var deriveCalled: Boolean = false
        @Volatile var lastDeriveParams: KdfParams? = null

        override fun deriveKeyFromPassword(password: CharArray, salt: ByteArray): SecretKey {
            deriveCalled = true
            lastDeriveParams = null
            return hashToKey(passwordBytes = password.toBytes(), sk = null, salt = salt, params = null)
        }

        override fun deriveKeyFromPasswordArgon2id(password: CharArray, salt: ByteArray): SecretKey =
            deriveKeyFromPasswordArgon2id(password, salt, KdfPreset.STANDARD.toKdfParams())

        override fun deriveKeyFromPasswordArgon2id(
            password: CharArray,
            salt: ByteArray,
            params: KdfParams,
        ): SecretKey {
            deriveCalled = true
            lastDeriveParams = params
            return hashToKey(passwordBytes = password.toBytes(), sk = null, salt = salt, params = params)
        }

        override fun deriveKeyFromPasswordWithSecretKeyArgon2id(
            password: CharArray,
            secretKey: ByteArray,
            salt: ByteArray,
            params: KdfParams,
        ): SecretKey {
            require(secretKey.size == SecretKeyHolder.SECRET_KEY_RAW_LENGTH)
            deriveCalled = true
            lastDeriveParams = params
            return hashToKey(passwordBytes = password.toBytes(), sk = secretKey, salt = salt, params = params)
        }

        private fun hashToKey(
            passwordBytes: ByteArray,
            sk: ByteArray?,
            salt: ByteArray,
            params: KdfParams?,
        ): SecretKey {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(passwordBytes)
            if (sk != null) digest.update(sk)
            digest.update(salt)
            if (params != null) {
                val buf = ByteBuffer.allocate(4 + 4 + 1)
                buf.putInt(params.mCostKiB)
                buf.putInt(params.tCost)
                buf.put(params.parallelism.toByte())
                digest.update(buf.array())
            }
            return SecretKeySpec(digest.digest(), "AES")
        }

        private fun CharArray.toBytes(): ByteArray = String(this).toByteArray(Charsets.UTF_8)
    }
}

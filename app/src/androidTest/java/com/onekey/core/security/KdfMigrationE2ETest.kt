package com.onekey.core.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.onekey.core.domain.model.AppResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device end-to-end coverage for [KdfMigrator]. The companion
 * [KdfMigratorTest] in `app/src/test/` pins everything that can be exercised
 * without running Argon2id; this file pins the parts that REQUIRE the native
 * `argon2kt` binding to load (real Android device or emulator).
 *
 * What this test proves end-to-end:
 *
 *  - A STANDARD-config verifier, planted directly into [SharedPreferences],
 *    survives a successful [KdfMigrator.migrateTo] upgrade to STANDARD_PLUS.
 *    The post-migration state matches what production code will read on the
 *    next unlock: the active `password_verifier` is replaced, `kdf_version`
 *    advances to [KDF_V3_STANDARD_PLUS], and every Phase 1 staging key is
 *    swept from the SP namespace.
 *  - The new verifier round-trips: re-deriving an Argon2id key with the same
 *    password under STANDARD_PLUS parameters decrypts the post-migration
 *    ciphertext back to the sentinel string `"VALID"`. This is the existence
 *    proof that Phase 2 commit produced a usable verifier (not just an
 *    on-disk byte sequence that LOOKS encrypted but cannot be decrypted).
 *
 * The host-JVM Robolectric suite cannot exercise this path because
 * `argon2kt` ships its native `.so` only for Android ABIs and silently
 * throws `UnsatisfiedLinkError` on macOS/Linux. Hence this file lives in
 * `androidTest` and runs via `./gradlew :app:connectedDebugAndroidTest`.
 *
 * The migrator's other behaviours (resume-from-pending rollback, stale
 * timestamp handling, defence-in-depth preset gating, password
 * zeroing, KDF version code table) are unit-tested in [KdfMigratorTest]
 * because they short-circuit before any Argon2id derivation.
 */
@RunWith(AndroidJUnit4::class)
class KdfMigrationE2ETest {

    private lateinit var authPrefs: SharedPreferences
    private lateinit var crypto: CryptoManager
    private lateinit var detector: DeviceCapacityDetector
    private lateinit var skWrapper: SecretKeyKeystoreWrapper
    private lateinit var migrator: KdfMigrator

    @Before fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        // Test-scoped SP namespace: a unique nano-time suffix isolates each
        // run from any other auth state on the device. We use the plain
        // (unencrypted) SharedPreferences interface since KdfMigrator only
        // exercises the `getInt`/`getString`/`edit().commit()` surface and
        // the encryption wrapper is transparent at this level.
        authPrefs = context.getSharedPreferences(
            "kdf_e2e_${System.nanoTime()}",
            Context.MODE_PRIVATE,
        )
        authPrefs.edit().clear().commit()

        crypto = CryptoManager()
        // Fake detector with every fixed preset enabled, so the defence-in-
        // depth check inside migrateTo does not refuse STANDARD_PLUS on the
        // grounds that the test "device" cannot support it.
        detector = object : DeviceCapacityDetector(context) {
            override fun snapshot(): CapacitySnapshot = CapacitySnapshot(
                totalRamMb = 8_192L,
                isLowRamDevice = false,
                availableCores = 4,
                socModel = "test",
                maxArgon2MemoryMb = 256,
                recommendedPreset = KdfPreset.HARDENED,
                enabledPresets = KdfPreset.entries
                    .filter { it != KdfPreset.CUSTOM }
                    .toSet(),
            )
        }
        // SK wrapper is the 4th constructor arg added when the Secret Key
        // feature shipped. Required for the migrator's runSecretKeyTransition
        // path even though this test only exercises the KDF-preset migrateTo
        // path; the constructor will reject a null.
        skWrapper = SecretKeyKeystoreWrapper(authPrefs)
        migrator = KdfMigrator(authPrefs, crypto, detector, skWrapper)
    }

    @After fun tearDown() {
        authPrefs.edit().clear().commit()
    }

    /**
     * Phase 1 derive + Phase 1c integrity round-trip + Phase 2 atomic swap.
     *
     * Plant a STANDARD verifier with a known password, migrate to
     * STANDARD_PLUS, then prove the new verifier is decryptable under
     * STANDARD_PLUS parameters with the same password.
     */
    @Test fun migrateStandardToStandardPlus_roundTrip() = runBlocking {
        val password = "correct horse battery staple".toCharArray()
        plantInitialVerifier(KdfPreset.STANDARD, password)

        val result = migrator.migrateTo(
            newParams = KdfPreset.STANDARD_PLUS.toKdfParams(),
            newPreset = KdfPreset.STANDARD_PLUS,
            masterPassword = password.copyOf(),
        )

        assertTrue("migration must succeed; got $result", result is AppResult.Success)

        // Active config reflects STANDARD_PLUS.
        assertEquals(
            "kdf_version should advance to STANDARD_PLUS",
            KDF_V3_STANDARD_PLUS,
            authPrefs.getInt(SP_KDF_VERSION, -1),
        )

        // Every Phase 1 staging key must be swept on commit.
        assertNull(authPrefs.getString(SP_KDF_PENDING_VERIFIER, null))
        assertEquals(-1, authPrefs.getInt(SP_KDF_PENDING_VERSION, -1))
        assertEquals(0L, authPrefs.getLong(SP_KDF_PENDING_STARTED_AT, 0L))
        assertNull(authPrefs.getString(SP_KDF_PENDING_DIGEST, null))

        // The new verifier decrypts to "VALID" with a key derived from the
        // same password under STANDARD_PLUS params + the new salt. This is
        // the existence proof that Phase 2 produced a usable verifier.
        val newVerifier = authPrefs.getString(SP_PASSWORD_VERIFIER, null)
        assertNotNull("post-migration verifier missing", newVerifier)
        val (salt, ct, iv) = decodeVerifier(newVerifier!!)
        val key = crypto.deriveKeyFromPasswordArgon2id(
            password.copyOf(),
            salt,
            KdfPreset.STANDARD_PLUS.toKdfParams(),
        )
        assertEquals(
            "VALID",
            crypto.decryptString(EncryptedData(ct, iv), key),
        )
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    /**
     * Plant a STANDARD-config verifier directly in [authPrefs] by replicating
     * KdfMigrator's verifier encoding: derive an Argon2id key with the given
     * preset's parameters, encrypt "VALID", and store the base64 `salt:ct:iv`
     * triple in [SP_PASSWORD_VERIFIER] alongside the [presetToKdfVersionInt]
     * code in [SP_KDF_VERSION].
     */
    private fun plantInitialVerifier(preset: KdfPreset, password: CharArray) {
        val params = preset.toKdfParams()
        val salt = crypto.generateSalt(SALT_LEN)
        val key = crypto.deriveKeyFromPasswordArgon2id(password.copyOf(), salt, params)
        val encrypted = crypto.encryptString("VALID", key)
        val encoded = "${b64(salt)}:${b64(encrypted.ciphertext)}:${b64(encrypted.iv)}"
        authPrefs.edit().apply {
            putString(SP_PASSWORD_VERIFIER, encoded)
            putInt(SP_KDF_VERSION, KdfMigrator.presetToKdfVersionInt(preset))
        }.commit()
    }

    private fun decodeVerifier(encoded: String): Triple<ByteArray, ByteArray, ByteArray> {
        val parts = encoded.split(':')
        require(parts.size == 3) { "verifier must be 'salt:ct:iv', got: $encoded" }
        return Triple(decodeB64(parts[0]), decodeB64(parts[1]), decodeB64(parts[2]))
    }

    private fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun decodeB64(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)

    // SharedPreferences keys mirrored from KdfMigrator's private constants.
    // These strings are persistence-stable - a silent change on either side
    // (migrator OR test) would deserialise wrong on existing installs, so
    // pinning them here is the cheapest tripwire.
    private companion object {
        private const val SP_PASSWORD_VERIFIER       = "password_verifier"
        private const val SP_KDF_VERSION             = "kdf_version"
        private const val SP_KDF_PENDING_VERIFIER    = "kdf_pending_verifier"
        private const val SP_KDF_PENDING_VERSION     = "kdf_pending_version"
        private const val SP_KDF_PENDING_STARTED_AT  = "kdf_pending_started_at"
        private const val SP_KDF_PENDING_DIGEST      = "kdf_pending_digest"
        private const val SALT_LEN = 32
    }
}

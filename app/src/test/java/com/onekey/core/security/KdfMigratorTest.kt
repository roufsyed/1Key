package com.onekey.core.security

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import com.onekey.core.domain.model.AppResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Behavioural locks for [KdfMigrator]'s structural surfaces that can be
 * exercised without running Argon2id.
 *
 * # Test-scope rationale
 *
 * `argon2kt` ships its native `.so` only for Android ABIs (arm64, armv7,
 * x86, x86_64). It does NOT load under a host-side Robolectric JVM on
 * macOS/Linux, so any test that actually drives `KdfMigrator.migrateTo`
 * through a successful Phase 1 derivation hits an `UnsatisfiedLinkError`.
 * The corresponding end-to-end coverage lives in `androidTest` (per the
 * design's test plan: `KdfMigrationE2ETest`). What we DO pin here is:
 *
 *  - [resumeIfPending] is a no-op when no pending state is on disk.
 *  - [resumeIfPending] unconditionally rolls back any pending state on
 *    process start (the rollback policy is "always backwards", never roll
 *    forward, because we can't get the master password during cold boot).
 *  - Recovery preserves the ACTIVE verifier + KDF version exactly. The
 *    user's vault still unlocks under the OLD config after recovery.
 *  - Recovery handles stale (24h+) pending state without crashing - only
 *    log severity changes, the data movement is identical.
 *  - The defence-in-depth check refuses migrations to a preset disabled
 *    on this device. This branch returns Error BEFORE any Argon2id work,
 *    so it's safely unit-testable on host JVM. CUSTOM is always allowed.
 *  - [KdfMigrator.presetToKdfVersionInt] is a stable lookup table - the
 *    integer codes 30..34 are persisted in `SP_KDF_VERSION` on disk, so a
 *    silent reshuffle would brick every existing install.
 *
 * `application = Application::class` follows the other Robolectric tests
 * in the suite - bypasses HiltAndroidApp's eager EncryptedSharedPreferences
 * provisioning, which Robolectric's shadow KeyStore cannot resolve.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class KdfMigratorTest {

    private lateinit var authPrefs: SharedPreferences
    private lateinit var crypto: CryptoManager
    private lateinit var detector: FakeDeviceCapacityDetector

    @Before fun setUp() {
        val context: Context = RuntimeEnvironment.getApplication()
        // A plain in-memory SharedPreferences stands in for the production
        // EncryptedSharedPreferences. The migrator operates strictly on the
        // SharedPreferences interface (`getInt`, `getString`, `edit().commit()`);
        // the encryption layer in production is transparent at this level.
        authPrefs = context.getSharedPreferences("auth_test_${System.nanoTime()}", Context.MODE_PRIVATE)
        authPrefs.edit().clear().commit()
        crypto = CryptoManager()
        detector = FakeDeviceCapacityDetector(
            snapshot = CapacitySnapshot(
                totalRamMb = 4_096L,
                isLowRamDevice = false,
                availableCores = 4,
                socModel = "unknown",
                maxArgon2MemoryMb = 256,
                recommendedPreset = KdfPreset.HARDENED,
                enabledPresets = setOf(
                    KdfPreset.STANDARD,
                    KdfPreset.STANDARD_PLUS,
                    KdfPreset.HARDENED,
                ),
            ),
            context = context,
        )
    }

    @After fun tearDown() {
        authPrefs.edit().clear().commit()
    }

    // ── resumeIfPending: no-op when no pending state exists ────────────────

    @Test fun resumeIfPending_returns_false_when_no_pending_state() = runBlocking {
        val migrator = KdfMigrator(authPrefs, crypto, detector)

        val rolledBack = migrator.resumeIfPending()

        assertFalse(
            "resumeIfPending must return false when nothing is pending",
            rolledBack,
        )
    }

    @Test fun resumeIfPending_does_not_touch_active_verifier_when_nothing_pending() = runBlocking {
        // Pre-seed an "active" verifier blob and KDF version. Recovery on a
        // clean app start (no pending state) must leave those EXACT bytes
        // alone - any clobber would mean the vault refuses to unlock on next
        // launch.
        authPrefs.edit().apply {
            putString(SP_PASSWORD_VERIFIER, "salt_b64:ct_b64:iv_b64")
            putInt(SP_KDF_VERSION, KDF_V3_STANDARD)
        }.commit()

        val migrator = KdfMigrator(authPrefs, crypto, detector)
        migrator.resumeIfPending()

        assertEquals("salt_b64:ct_b64:iv_b64", authPrefs.getString(SP_PASSWORD_VERIFIER, null))
        assertEquals(KDF_V3_STANDARD, authPrefs.getInt(SP_KDF_VERSION, -1))
    }

    // ── resumeIfPending: unconditional rollback ────────────────────────────

    @Test fun resumeIfPending_clears_all_pending_keys_when_state_exists() = runBlocking {
        // Simulate the on-disk state that survives a crash mid-Phase-1:
        // pending fields populated, active fields still pointing at the
        // OLD preset. The recovery contract is "always roll backwards"
        // (rolling forward needs the master password, which we don't have
        // during cold boot).
        seedActiveVerifier()
        seedPendingState(
            pendingVersion = KDF_V3_HARDENED,
            pendingVerifier = "newsalt:newct:newiv",
            startedAtMs = System.currentTimeMillis(),
        )

        val migrator = KdfMigrator(authPrefs, crypto, detector)
        val rolledBack = migrator.resumeIfPending()

        assertTrue(
            "resumeIfPending must return true when it cleans up pending state",
            rolledBack,
        )
        // Every pending key removed.
        assertEquals(0, authPrefs.getInt(SP_KDF_PENDING_VERSION, 0))
        assertNull(authPrefs.getString(SP_KDF_PENDING_VERIFIER, null))
        assertNull(authPrefs.getString(SP_KDF_PENDING_DIGEST, null))
        assertEquals(0L, authPrefs.getLong(SP_KDF_PENDING_STARTED_AT, 0L))
        assertEquals(0, authPrefs.getInt(SP_KDF_PENDING_CUSTOM_M, 0))
        assertEquals(0, authPrefs.getInt(SP_KDF_PENDING_CUSTOM_T, 0))
    }

    @Test fun resumeIfPending_preserves_active_verifier_and_KDF_version() = runBlocking {
        // The fundamental safety property: recovery moves the user BACK to
        // the prior config, never to the half-staged new one. Pin that the
        // ACTIVE values are byte-identical after rollback.
        val activeVerifier = "active_salt_b64:active_ct_b64:active_iv_b64"
        val activeVersion = KDF_V3_STANDARD
        authPrefs.edit().apply {
            putString(SP_PASSWORD_VERIFIER, activeVerifier)
            putInt(SP_KDF_VERSION, activeVersion)
        }.commit()
        seedPendingState(
            pendingVersion = KDF_V3_MAXIMUM,
            pendingVerifier = "different_salt:different_ct:different_iv",
            startedAtMs = System.currentTimeMillis(),
        )

        val migrator = KdfMigrator(authPrefs, crypto, detector)
        migrator.resumeIfPending()

        assertEquals(
            "Active verifier must be the OLD one after rollback",
            activeVerifier,
            authPrefs.getString(SP_PASSWORD_VERIFIER, null),
        )
        assertEquals(
            "Active KDF version must be the OLD one after rollback",
            activeVersion,
            authPrefs.getInt(SP_KDF_VERSION, -1),
        )
    }

    @Test fun resumeIfPending_clears_pending_custom_M_and_T_when_pending_was_CUSTOM() = runBlocking {
        // A CUSTOM-target pending state carries (m, t) in the side-table.
        // Recovery must wipe those alongside the rest of the pending keys -
        // a stranded SP_KDF_PENDING_CUSTOM_M after rollback would confuse
        // the next attempted migration (or, worse, leak as a stale UI
        // subtitle if it ever got read).
        seedActiveVerifier()
        seedPendingState(
            pendingVersion = KDF_V3_CUSTOM,
            pendingVerifier = "salt:ct:iv",
            startedAtMs = System.currentTimeMillis(),
            pendingCustomM = 96,
            pendingCustomT = 5,
        )

        val migrator = KdfMigrator(authPrefs, crypto, detector)
        migrator.resumeIfPending()

        assertEquals(0, authPrefs.getInt(SP_KDF_PENDING_CUSTOM_M, 0))
        assertEquals(0, authPrefs.getInt(SP_KDF_PENDING_CUSTOM_T, 0))
    }

    @Test fun resumeIfPending_handles_stale_pending_state_without_crashing() = runBlocking {
        // Anything > 24h old is "stale": the log severity flips from info
        // to warn, but the data movement is unchanged. Pin that the stale
        // path doesn't crash (a misuse of `startedAt` or its arithmetic
        // would be a bug we catch here rather than at runtime).
        seedActiveVerifier()
        val twoDaysAgo = System.currentTimeMillis() - (48L * 60L * 60L * 1000L)
        seedPendingState(
            pendingVersion = KDF_V3_HARDENED,
            pendingVerifier = "salt:ct:iv",
            startedAtMs = twoDaysAgo,
        )

        val migrator = KdfMigrator(authPrefs, crypto, detector)
        val rolledBack = migrator.resumeIfPending()

        assertTrue("Stale pending state must still trigger rollback", rolledBack)
        assertEquals(0, authPrefs.getInt(SP_KDF_PENDING_VERSION, 0))
    }

    @Test fun resumeIfPending_handles_pending_state_with_future_started_at_timestamp() = runBlocking {
        // Defensive: a clock skew (NTP fix, user-set time travel) could
        // produce a `startedAt` in the future. The implementation uses
        // `coerceAtLeast(0L)` on the age computation; this test confirms
        // the negative-delta branch doesn't crash.
        seedActiveVerifier()
        val anHourFromNow = System.currentTimeMillis() + (60L * 60L * 1000L)
        seedPendingState(
            pendingVersion = KDF_V3_HARDENED,
            pendingVerifier = "salt:ct:iv",
            startedAtMs = anHourFromNow,
        )

        val migrator = KdfMigrator(authPrefs, crypto, detector)
        val rolledBack = migrator.resumeIfPending()

        assertTrue(rolledBack)
        assertEquals(0, authPrefs.getInt(SP_KDF_PENDING_VERSION, 0))
    }

    @Test fun resumeIfPending_is_idempotent_under_repeated_calls() = runBlocking {
        // Calling resumeIfPending twice in a row (e.g. from `init` AND
        // from a defensive top-of-migration call inside migrateTo) must
        // not double-write or corrupt anything. The second call should
        // simply observe "no pending state" and return false.
        seedActiveVerifier()
        seedPendingState(
            pendingVersion = KDF_V3_HARDENED,
            pendingVerifier = "salt:ct:iv",
            startedAtMs = System.currentTimeMillis(),
        )

        val migrator = KdfMigrator(authPrefs, crypto, detector)
        val firstRollback = migrator.resumeIfPending()
        val secondRollback = migrator.resumeIfPending()

        assertTrue(firstRollback)
        assertFalse(
            "Second resumeIfPending must be a no-op (pending state already cleared)",
            secondRollback,
        )
    }

    // ── Defence-in-depth: refuse disabled preset ──────────────────────────

    @Test fun migrateTo_refuses_preset_not_in_devices_enabled_set() = runBlocking {
        // detector advertises only {STANDARD, STANDARD_PLUS, HARDENED} -
        // MAXIMUM must be rejected BEFORE any Argon2id derivation runs.
        // The error path returns Error AND leaves everything on disk
        // untouched.
        seedActiveVerifier()
        val migrator = KdfMigrator(authPrefs, crypto, detector)

        val result = migrator.migrateTo(
            newParams = KdfPreset.MAXIMUM.toKdfParams(),
            newPreset = KdfPreset.MAXIMUM,
            masterPassword = "doesnotmatter".toCharArray(),
        )

        assertTrue(
            "Migration to disabled preset must fail at the gating check",
            result is AppResult.Error,
        )
        val error = result as AppResult.Error
        assertNotNull("Error must carry a user-facing message", error.message)
        assertTrue(
            "Error message must mention the preset name or RAM: ${error.message}",
            error.message!!.contains("Maximum") || error.message!!.contains("RAM"),
        )
        // Active config untouched.
        assertEquals(KDF_V3_STANDARD, authPrefs.getInt(SP_KDF_VERSION, -1))
        // No pending state written.
        assertEquals(0, authPrefs.getInt(SP_KDF_PENDING_VERSION, 0))
    }

    @Test fun migrateTo_allows_CUSTOM_even_when_not_in_enabled_set() = runBlocking {
        // CUSTOM is always considered allowed at the migrator layer; its
        // memory cap is enforced by the dialog UI's slider valueRange. The
        // defence-in-depth check explicitly skips the enabled-set lookup
        // for CUSTOM (see `newPreset != KdfPreset.CUSTOM &&` in the impl).
        // We can't observe a successful Phase 1 derivation without Argon2id,
        // but we CAN observe that the path does NOT bail at the gating
        // check - it proceeds past it. The next observable failure (no
        // active verifier on disk, or a real verifier mismatch) is the
        // actual evidence the gate didn't short-circuit.
        //
        // To make this test deterministic without running Argon2id, we
        // start with NO active verifier - the migrator's verify step will
        // fail with "No verifier stored", proving the gate let CUSTOM
        // through past the enabled-set check.
        val migrator = KdfMigrator(authPrefs, crypto, detector)

        val result = migrator.migrateTo(
            newParams = KdfParams(mCostKiB = 96 * 1024, tCost = 5, parallelism = 1),
            newPreset = KdfPreset.CUSTOM,
            masterPassword = "anything".toCharArray(),
        )

        assertTrue(result is AppResult.Error)
        val error = result as AppResult.Error
        // The verify step fired (we got past the gate). Its specific error
        // message references the missing verifier, NOT the disabled preset
        // copy. That's the proof CUSTOM was admitted past the gating check.
        assertNotNull(error.message)
        assertFalse(
            "CUSTOM must not be rejected for being 'disabled'; got: ${error.message}",
            error.message!!.contains("not enabled") ||
                error.message!!.contains("requires more"),
        )
    }

    @Test fun migrateTo_zeros_the_master_password_array_in_finally() = runBlocking {
        // Defensive: any return path (success or failure) must zero the
        // caller's password CharArray. We exercise the failure path via the
        // disabled-preset gate (no Argon2id needed) and confirm the array
        // is all-spaces after.
        seedActiveVerifier()
        val migrator = KdfMigrator(authPrefs, crypto, detector)
        val password = "secretmasterpassword".toCharArray()

        migrator.migrateTo(
            newParams = KdfPreset.MAXIMUM.toKdfParams(),
            newPreset = KdfPreset.MAXIMUM,
            masterPassword = password,
        )

        val allSpaces = password.all { it == ' ' }
        assertTrue(
            "Password CharArray must be zeroed (filled with ' ') after migrateTo",
            allSpaces,
        )
    }

    @Test fun migrateTo_returns_error_message_with_actionable_guidance() = runBlocking {
        // The picker surfaces this message verbatim in a Toast / snackbar.
        // It must be a sentence (not an exception stack tag), and it must
        // identify the actionable problem. Pin the string shape.
        seedActiveVerifier()
        val migrator = KdfMigrator(authPrefs, crypto, detector)

        val result = migrator.migrateTo(
            newParams = KdfPreset.MAXIMUM.toKdfParams(),
            newPreset = KdfPreset.MAXIMUM,
            masterPassword = "x".toCharArray(),
        )

        val error = result as AppResult.Error
        assertTrue(
            "Error message must be a user-facing sentence, was: ${error.message}",
            (error.message ?: "").length > 10,
        )
    }

    // ── Reauth: wrong-password rejection without Argon2id ─────────────────

    @Test fun migrateTo_returns_error_when_no_verifier_is_stored() = runBlocking {
        // Phase 1a verification reads SP_PASSWORD_VERIFIER; if absent the
        // verify step throws "No verifier stored" which the migrator
        // surfaces as Error. Confirms the early-bail path doesn't write
        // any pending state.
        val migrator = KdfMigrator(authPrefs, crypto, detector)

        val result = migrator.migrateTo(
            newParams = KdfPreset.HARDENED.toKdfParams(),
            newPreset = KdfPreset.HARDENED,
            masterPassword = "anypassword".toCharArray(),
        )

        assertTrue(result is AppResult.Error)
        // No pending state should have been written.
        assertEquals(0, authPrefs.getInt(SP_KDF_PENDING_VERSION, 0))
        assertNull(authPrefs.getString(SP_KDF_PENDING_VERIFIER, null))
    }

    @Test fun migrateTo_returns_error_when_verifier_is_corrupted() = runBlocking {
        // A two-part instead of three-part verifier string forces the
        // verify branch to throw via `parts.size != 3`. Pin that this is
        // surfaced as an Error rather than crashing.
        authPrefs.edit().apply {
            putString(SP_PASSWORD_VERIFIER, "only_one_part")
            putInt(SP_KDF_VERSION, KDF_V3_STANDARD)
        }.commit()
        val migrator = KdfMigrator(authPrefs, crypto, detector)

        val result = migrator.migrateTo(
            newParams = KdfPreset.HARDENED.toKdfParams(),
            newPreset = KdfPreset.HARDENED,
            masterPassword = "anything".toCharArray(),
        )

        assertTrue(result is AppResult.Error)
        // No pending state escaped.
        assertEquals(0, authPrefs.getInt(SP_KDF_PENDING_VERSION, 0))
    }

    // ── presetToKdfVersionInt mapping ──────────────────────────────────────

    @Test fun presetToKdfVersionInt_maps_each_preset_to_its_stable_integer_code() {
        // These integer codes land in SP_KDF_VERSION on disk. Every
        // existing install on every existing device reads them back on
        // unlock. A silent shuffle (e.g. swapping 30 and 31) would brick
        // every Standard install in the next release: their stored value
        // would suddenly mean Standard-plus, the verifier wouldn't decrypt,
        // and they'd see "Incorrect master password".
        //
        // The codes documented in `kdf_version_storage`:
        //   STANDARD       -> 30
        //   STANDARD_PLUS  -> 31
        //   HARDENED       -> 32
        //   MAXIMUM        -> 33
        //   CUSTOM         -> 34
        assertEquals(30, KdfMigrator.presetToKdfVersionInt(KdfPreset.STANDARD))
        assertEquals(31, KdfMigrator.presetToKdfVersionInt(KdfPreset.STANDARD_PLUS))
        assertEquals(32, KdfMigrator.presetToKdfVersionInt(KdfPreset.HARDENED))
        assertEquals(33, KdfMigrator.presetToKdfVersionInt(KdfPreset.MAXIMUM))
        assertEquals(34, KdfMigrator.presetToKdfVersionInt(KdfPreset.CUSTOM))
    }

    @Test fun presetToKdfVersionInt_maps_to_KDF_V3_constants() {
        // Cross-check that the named constants and the literal integers
        // agree. If a refactor changes one without the other, this test
        // catches the mismatch immediately.
        assertEquals(KDF_V3_STANDARD, KdfMigrator.presetToKdfVersionInt(KdfPreset.STANDARD))
        assertEquals(KDF_V3_STANDARD_PLUS, KdfMigrator.presetToKdfVersionInt(KdfPreset.STANDARD_PLUS))
        assertEquals(KDF_V3_HARDENED, KdfMigrator.presetToKdfVersionInt(KdfPreset.HARDENED))
        assertEquals(KDF_V3_MAXIMUM, KdfMigrator.presetToKdfVersionInt(KdfPreset.MAXIMUM))
        assertEquals(KDF_V3_CUSTOM, KdfMigrator.presetToKdfVersionInt(KdfPreset.CUSTOM))
    }

    @Test fun KDF_version_codes_are_in_the_30s_range() {
        // The "30+ range" property is what differentiates v3 codes from
        // the legacy (0, 1) values. Recovery and migration paths rely on
        // a simple `>= 30` to identify v3 storage shape. If a future
        // preset code lands at, say, 9 or 100, the recovery path's
        // `else -> error("Unknown KDF version")` branch would fire on
        // existing installs.
        listOf(
            KDF_V3_STANDARD,
            KDF_V3_STANDARD_PLUS,
            KDF_V3_HARDENED,
            KDF_V3_MAXIMUM,
            KDF_V3_CUSTOM,
        ).forEach { code ->
            assertTrue(
                "KDF v3 code $code must be >= 30",
                code >= 30,
            )
            assertTrue(
                "KDF v3 code $code must be < 100",
                code < 100,
            )
        }
    }

    @Test fun KDF_version_codes_are_unique() {
        // No two presets share an integer code; otherwise the encode-and-
        // decode round trip would lose information and the "active" view
        // of the preset would be ambiguous.
        val codes = listOf(
            KDF_V3_STANDARD,
            KDF_V3_STANDARD_PLUS,
            KDF_V3_HARDENED,
            KDF_V3_MAXIMUM,
            KDF_V3_CUSTOM,
        )
        assertEquals(
            "Each v3 KDF code must be distinct",
            codes.size,
            codes.toSet().size,
        )
    }

    @Test fun KDF_legacy_codes_are_outside_v3_range() {
        // Legacy values 0 and 1 are read-only on existing installs. They
        // MUST stay outside the v3 code range so a future "I'll just bump
        // the codes" PR doesn't accidentally collide with PBKDF2 (0) or
        // legacy Argon2id (1) and re-interpret old verifiers under the
        // wrong params.
        assertEquals(0, KDF_LEGACY_PBKDF2)
        assertEquals(1, KDF_LEGACY_ARGON2ID)
        assertTrue(KDF_LEGACY_PBKDF2 < 30)
        assertTrue(KDF_LEGACY_ARGON2ID < 30)
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun seedActiveVerifier() {
        // A "valid-looking" verifier string that just needs to round-trip
        // through the migrator's split(":") and base64 decode in the case
        // we want the verify path to reach Argon2id rather than throwing
        // at the parsing step. For tests that only check rollback of
        // pending state, the exact bytes don't matter - what matters is
        // that the active verifier value is observable for the post-call
        // assertion.
        authPrefs.edit().apply {
            putString(SP_PASSWORD_VERIFIER, "active_salt_b64:active_ct_b64:active_iv_b64")
            putInt(SP_KDF_VERSION, KDF_V3_STANDARD)
        }.commit()
    }

    private fun seedPendingState(
        pendingVersion: Int,
        pendingVerifier: String,
        startedAtMs: Long,
        pendingCustomM: Int? = null,
        pendingCustomT: Int? = null,
    ) {
        authPrefs.edit().apply {
            putInt(SP_KDF_PENDING_VERSION, pendingVersion)
            putString(SP_KDF_PENDING_VERIFIER, pendingVerifier)
            putString(SP_KDF_PENDING_DIGEST, "fake_digest_b64")
            putLong(SP_KDF_PENDING_STARTED_AT, startedAtMs)
            if (pendingCustomM != null) putInt(SP_KDF_PENDING_CUSTOM_M, pendingCustomM)
            if (pendingCustomT != null) putInt(SP_KDF_PENDING_CUSTOM_T, pendingCustomT)
        }.commit()
    }

    // SharedPreferences keys mirrored from KdfMigrator's private constants.
    // The whole point of the migrator's design is that these strings are
    // append-only and changes to either side trigger a regression here.
    private companion object {
        private const val SP_PASSWORD_VERIFIER = "password_verifier"
        private const val SP_KDF_VERSION       = "kdf_version"
        private const val SP_KDF_PENDING_VERSION    = "kdf_pending_version"
        private const val SP_KDF_PENDING_CUSTOM_M   = "kdf_pending_custom_m"
        private const val SP_KDF_PENDING_CUSTOM_T   = "kdf_pending_custom_t"
        private const val SP_KDF_PENDING_VERIFIER   = "kdf_pending_verifier"
        private const val SP_KDF_PENDING_STARTED_AT = "kdf_pending_started_at"
        private const val SP_KDF_PENDING_DIGEST     = "kdf_pending_digest"
    }
}

/**
 * Test-only [DeviceCapacityDetector] that returns a caller-supplied
 * [CapacitySnapshot] regardless of the underlying Android API state. The
 * production class is `open` specifically so this kind of stub can subclass
 * it without owning a Context-backed `ActivityManager`.
 *
 * Lives co-located with [KdfMigratorTest] (not exported to a separate file)
 * because the migrator is currently its only consumer. Promote to a shared
 * test fixture when a second test needs the same fake.
 */
private class FakeDeviceCapacityDetector(
    private val snapshot: CapacitySnapshot,
    context: Context,
) : DeviceCapacityDetector(context) {
    override fun snapshot(): CapacitySnapshot = snapshot
}

package com.onekey.core.security

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.crypto.SecretKey

/**
 * Behavioural locks for [HardwareKeyIsolationProbe].
 *
 * ## Robolectric AndroidKeyStore limitation
 *
 * Robolectric 4.13 does NOT ship a shadow for the `AndroidKeyStore` JCA provider.
 * Calling `KeyStore.getInstance("AndroidKeyStore")` from any test running under
 * `RobolectricTestRunner` throws `NoSuchAlgorithmException: AndroidKeyStore
 * KeyStore not available`. That means the probe cannot reach a real
 * [android.security.keystore.KeyInfo] under unit tests - the full
 * "classify a real Keystore key" flow is only exercisable on an instrumented
 * device (covered by the separate `HardwareKeyIsolationOnDeviceTest` in the
 * androidTest source set).
 *
 * What IS testable in Robolectric:
 *  - The pre-setup placeholder branch (`probeOnce` returns
 *    `HardwareKeyIsolationStatus(tier = TEE, detail = "Before vault setup; ...")`)
 *    when both V1 and V2 keystore lookups return null.
 *  - The idempotence of [HardwareKeyIsolationProbe.start] - the `_status.value
 *    != null` guard short-circuits subsequent calls.
 *  - The `StateFlow<HardwareKeyIsolationStatus?>` lifecycle - null before
 *    `start`, non-null after the background probe completes.
 *
 * To exercise these branches without poking the real AndroidKeyStore, we
 * subclass [CryptoManager] (which became `open` for exactly this test seam)
 * and override only [CryptoManager.loadKeystoreKey] so it returns whatever
 * the test stages, never invoking `KeyStore.getInstance`. The classification
 * helpers ([HardwareKeyIsolationProbe.classifyApi31Plus] et al.) are
 * unreachable here because constructing a real `KeyInfo` requires a real
 * AndroidKeyStore-backed `SecretKey`, and Robolectric does not provide one.
 * Those code paths are pinned by manual verification on real hardware.
 *
 * `@Config(sdk = [33], application = Application::class)` mirrors the existing
 * Robolectric tests in this module: sdk=33 puts us on a modern API level for
 * any framework calls the probe makes, and `application = Application::class`
 * bypasses `HiltAndroidApp`'s eager EncryptedSharedPreferences provisioning
 * (which Robolectric's missing keystore cannot satisfy).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class HardwareKeyIsolationTest {

    // The probe's `start()` always launches on `Dispatchers.IO` regardless of
    // the scope's own dispatcher (`appScope.launch(Dispatchers.IO)` overrides
    // the parent dispatcher). That means `runTest` + a test dispatcher cannot
    // drive the probe coroutine to completion - the IO thread is real. We
    // therefore drive the StateFlow assertions with `runBlocking` + `first {
    // it != null }` under a generous timeout. The placeholder-branch probe
    // completes in microseconds (no JCE traffic), so a 2-second timeout is
    // ample.
    private lateinit var appScope: CoroutineScope
    private lateinit var fakeCrypto: FakeCryptoManager
    private lateinit var probe: HardwareKeyIsolationProbe

    @Before fun setup() {
        appScope = CoroutineScope(SupervisorJob())
        fakeCrypto = FakeCryptoManager()
        probe = HardwareKeyIsolationProbe(fakeCrypto, appScope)
    }

    @After fun tearDown() {
        appScope.cancel()
    }

    // ── Pre-setup placeholder (no key yet) ────────────────────────────────────

    @Test fun probeOnce_with_no_key_returns_before_vault_setup_placeholder() {
        // Brand-new install: setupMasterPassword has not run, so neither
        // KEYSTORE_ALIAS_V1 nor KEYSTORE_ALIAS_V2 exists. The probe must emit
        // a best-effort placeholder rather than throw or hit AndroidKeyStore.
        fakeCrypto.v1Key = null
        fakeCrypto.v2Key = null

        val status = probe.probeOnce()

        // Per the design, the placeholder reports TEE (the safer-to-claim
        // baseline) on API 28+; the detail string records "Before vault setup"
        // so consumers know the result is provisional.
        assertEquals(HardwareKeyIsolationTier.TEE, status.tier)
        assertTrue(
            "detail must signal pre-setup state, got: ${status.detail}",
            status.detail.contains("Before vault setup"),
        )
        assertTrue(
            "detail must record the API level, got: ${status.detail}",
            status.detail.contains("API 33"),
        )
    }

    @Test fun probeOnce_looks_up_V2_first_then_falls_back_to_V1() {
        // The probe documents that it loads KEYSTORE_ALIAS_V2 first and falls
        // back to KEYSTORE_ALIAS_V1. We assert the read order by tracking the
        // alias arguments seen by the fake.
        fakeCrypto.v1Key = null
        fakeCrypto.v2Key = null

        probe.probeOnce()

        // Both aliases were consulted, in the V2-then-V1 order.
        assertEquals(
            listOf(KEYSTORE_ALIAS_V2, KEYSTORE_ALIAS_V1),
            fakeCrypto.loadKeystoreKeyCalls,
        )
    }

    @Test fun probeOnce_stops_at_V2_when_V2_key_exists() {
        // When V2 exists, V1 must NOT be consulted (the `?: ` short-circuits).
        fakeCrypto.v2Key = SoftwareSecretKey
        fakeCrypto.v1Key = SoftwareSecretKey

        try {
            probe.probeOnce()
        } catch (_: Throwable) {
            // probeOnce will try to extract KeyInfo from the fake key and fail
            // since SecretKeyFactory.getInstance("AndroidKeyStore") throws on
            // Robolectric. That's fine for this assertion - we only care that
            // V2 was read before any error.
        }

        // V2 was consulted; V1 was NOT (the short-circuit held).
        assertEquals(listOf(KEYSTORE_ALIAS_V2), fakeCrypto.loadKeystoreKeyCalls)
    }

    // ── `start` -> `status` flow (pre-setup branch) ──────────────────────────

    @Test fun start_emits_placeholder_through_StateFlow_when_no_key_exists() {
        // Before start, status must be null (probe pending).
        assertNull(probe.status.value)

        probe.start()

        val status = awaitProbeResult()
        assertEquals(HardwareKeyIsolationTier.TEE, status.tier)
        assertTrue(status.detail.contains("Before vault setup"))
    }

    // ── Idempotence ──────────────────────────────────────────────────────────

    @Test fun start_is_idempotent_across_repeated_calls() {
        probe.start()
        val firstSnapshot = awaitProbeResult()

        // Record the call count after the first probe finished so we can
        // prove the second start() did NOT re-invoke loadKeystoreKey.
        val callsAfterFirstStart = fakeCrypto.loadKeystoreKeyCalls.size

        // Second call must be a no-op: the `_status.value != null` guard
        // short-circuits before any background launch happens.
        probe.start()
        val secondSnapshot = probe.status.value
        assertNotNull("status must still be populated", secondSnapshot)

        // Same instance, not a fresh probeOnce-allocated copy.
        assertSame(
            "repeated start() must keep the cached value instance",
            firstSnapshot,
            secondSnapshot,
        )
        // And the fake saw no additional loadKeystoreKey traffic.
        assertEquals(
            "repeated start() must not re-probe the crypto manager",
            callsAfterFirstStart,
            fakeCrypto.loadKeystoreKeyCalls.size,
        )
    }

    @Test fun probeOnce_called_twice_returns_equal_placeholder_results() {
        // Direct probeOnce is not cached (caching lives in start/status), but
        // the classification logic is deterministic for the same fake state.
        // Two calls back-to-back must produce status objects that compare equal.
        val first = probe.probeOnce()
        val second = probe.probeOnce()

        assertEquals(first.tier, second.tier)
        assertEquals(first.detail, second.detail)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Block the test thread until [HardwareKeyIsolationProbe.status] emits a
     * non-null value, then return it. The probe launches on `Dispatchers.IO`
     * (a real thread pool) so we cannot drive it with virtual time; a real
     * blocking await is the simplest correct synchronisation.
     *
     * 2-second timeout is far longer than the placeholder probe needs (it
     * does no JCE work) but short enough that a regression that wedges the
     * StateFlow surfaces fast rather than hanging CI.
     */
    private fun awaitProbeResult(): HardwareKeyIsolationStatus = runBlocking {
        withTimeout(2_000) { probe.status.filterNotNull().first() }
    }

    // ── Test fake ─────────────────────────────────────────────────────────────

    /**
     * Minimal stand-in for [CryptoManager] that records every
     * [loadKeystoreKey] call and serves keys from in-memory state. Does not
     * touch the JCA provider, so it works under Robolectric where the
     * AndroidKeyStore provider is missing.
     *
     * Only [loadKeystoreKey] is overridden; the rest of CryptoManager (HKDF,
     * AES-GCM, Argon2id helpers) is unused by [HardwareKeyIsolationProbe] and
     * inherits unchanged. The [CryptoManager.lastKeyCreatedWithStrongBox]
     * flag stays at its default value of `false`.
     */
    private class FakeCryptoManager : CryptoManager() {
        var v1Key: SecretKey? = null
        var v2Key: SecretKey? = null
        val loadKeystoreKeyCalls: MutableList<String> = mutableListOf()

        override fun loadKeystoreKey(alias: String): SecretKey? {
            loadKeystoreKeyCalls += alias
            return when (alias) {
                KEYSTORE_ALIAS_V1 -> v1Key
                KEYSTORE_ALIAS_V2 -> v2Key
                else -> null
            }
        }
    }

    /**
     * Sentinel non-AndroidKeyStore [SecretKey] used to signal "a key exists"
     * for the V2-short-circuit test. The probe will try to extract a KeyInfo
     * from this key and fail on Robolectric (SecretKeyFactory.getInstance
     * also throws "AndroidKeyStore not available"), which is expected and
     * caught by the test. The point of the sentinel is to confirm the call
     * ordering, not to drive a successful classification.
     */
    private object SoftwareSecretKey : SecretKey {
        override fun getAlgorithm(): String = "AES"
        override fun getFormat(): String = "RAW"
        override fun getEncoded(): ByteArray = ByteArray(32)
    }
}

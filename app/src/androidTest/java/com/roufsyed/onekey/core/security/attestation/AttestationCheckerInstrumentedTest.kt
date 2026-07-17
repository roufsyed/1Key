package com.roufsyed.onekey.core.security.attestation

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore

/**
 * On-device smoke test for [AttestationChecker]. The pure decision logic is
 * covered exhaustively by the host-JVM tests; this exercises the real
 * AndroidKeyStore keygen + attestation path, which cannot run on a plain JVM.
 *
 * Run with a connected device/emulator:
 *   ./gradlew :app:connectedFullDebugAndroidTest
 *
 * Asserts only device-INDEPENDENT invariants (the actual verdict varies by
 * device - Trusted on stock/locked, Advisory on unlocked, Unavailable on an
 * emulator without hardware attestation):
 *  - check() never throws and always returns one of the three result states.
 *  - repeated checks leave NO lingering `onekey_attest_` aliases in the keystore,
 *    validating the random-per-invocation alias + delete-in-finally cleanup.
 */
@RunWith(AndroidJUnit4::class)
class AttestationCheckerInstrumentedTest {

    @Test
    fun check_returnsAValidResult_withoutCrashing() = runBlocking {
        val result = AttestationChecker().check()
        assertTrue(
            "unexpected result type: $result",
            result is AttestationResult.Trusted ||
                result is AttestationResult.Advisory ||
                result is AttestationResult.Unavailable,
        )
    }

    @Test
    fun repeatedChecks_leaveNoLingeringKeystoreAliases() = runBlocking {
        val checker = AttestationChecker()
        repeat(3) { checker.check() }
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val lingering = keyStore.aliases().toList().filter { it.startsWith("onekey_attest_") }
        assertTrue("leftover attestation key aliases after cleanup: $lingering", lingering.isEmpty())
    }
}

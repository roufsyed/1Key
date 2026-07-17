package com.roufsyed.onekey.core.security.attestation

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end tests of [AttestationChecker.evaluate] - the full stack (chain
 * verify -> generated-key binding -> challenge binding -> parse -> policy) run
 * against real Google-issued attestation chains and the embedded pinned roots.
 * The keygen half ([AttestationChecker.check]) is device-only and covered by an
 * instrumented test + manual QA.
 */
class AttestationCheckerTest {

    private val checker = AttestationChecker()
    private val unverified = AttestationTestVectors.loadChain(AttestationTestVectors.UNVERIFIED)
    private val verified = AttestationTestVectors.loadChain(AttestationTestVectors.VERIFIED)
    private val malformed = AttestationTestVectors.loadChain(AttestationTestVectors.MALFORMED)

    private fun challengeOf(chain: List<java.security.cert.X509Certificate>): ByteArray =
        AttestationExtensionParser.parse(chain.first())!!.attestationChallenge

    // --- parser sanity on real leaves ---

    @Test
    fun `real unverified leaf parses as UNVERIFIED and unlocked`() {
        val p = AttestationExtensionParser.parse(unverified.first())!!
        assertEquals(VerifiedBootState.UNVERIFIED, p.verifiedBootState)
        assertFalse(p.deviceLocked)
    }

    @Test
    fun `real verified StrongBox leaf parses as VERIFIED, locked, StrongBox`() {
        val p = AttestationExtensionParser.parse(verified.first())!!
        assertEquals(VerifiedBootState.VERIFIED, p.verifiedBootState)
        assertTrue(p.deviceLocked)
        assertEquals(AttestationSecurityLevel.STRONG_BOX, p.securityLevel)
    }

    @Test
    fun `akita fixture challenge is the literal bytes "challenge"`() {
        assertArrayEquals(
            "challenge".toByteArray(),
            AttestationExtensionParser.parse(unverified.first())!!.attestationChallenge,
        )
    }

    // --- full evaluate() end-to-end ---

    @Test
    fun `unverified device evaluates to Advisory`() {
        val leaf = unverified.first()
        assertTrue(
            checker.evaluate(unverified, leaf.publicKey, challengeOf(unverified))
                is AttestationResult.Advisory,
        )
    }

    @Test
    fun `verified locked device evaluates to Trusted`() {
        val leaf = verified.first()
        assertEquals(
            AttestationResult.Trusted,
            checker.evaluate(verified, leaf.publicKey, challengeOf(verified)),
        )
    }

    @Test
    fun `malformed RootOfTrust evaluates to Unavailable`() {
        val leaf = malformed.first()
        assertTrue(
            checker.evaluate(malformed, leaf.publicKey, byteArrayOf(0x01))
                is AttestationResult.Unavailable,
        )
    }

    @Test
    fun `mismatched generated key yields Unavailable`() {
        val foreignKey = verified.first().publicKey // a different, real key
        assertTrue(
            checker.evaluate(unverified, foreignKey, challengeOf(unverified))
                is AttestationResult.Unavailable,
        )
    }

    @Test
    fun `mismatched challenge yields Unavailable`() {
        val leaf = unverified.first()
        assertTrue(
            checker.evaluate(unverified, leaf.publicKey, "not-the-challenge".toByteArray())
                is AttestationResult.Unavailable,
        )
    }

    @Test
    fun `chain not anchored to a pinned root yields Unavailable`() {
        val leaf = unverified.first()
        assertTrue(
            checker.evaluate(listOf(leaf), leaf.publicKey, challengeOf(unverified))
                is AttestationResult.Unavailable,
        )
    }
}

package com.roufsyed.onekey.core.security.attestation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AttestationPolicyTest {

    private fun parsed(
        level: AttestationSecurityLevel = AttestationSecurityLevel.TRUSTED_ENVIRONMENT,
        locked: Boolean = true,
        state: VerifiedBootState = VerifiedBootState.VERIFIED,
    ) = ParsedAttestation(level, locked, state, byteArrayOf(1, 2, 3))

    @Test
    fun `verified + locked TEE is trusted`() {
        assertEquals(AttestationResult.Trusted, AttestationPolicy.evaluate(parsed()))
    }

    @Test
    fun `selfsigned + locked is trusted (relocked GrapheneOS)`() {
        assertEquals(
            AttestationResult.Trusted,
            AttestationPolicy.evaluate(parsed(state = VerifiedBootState.SELF_SIGNED)),
        )
    }

    @Test
    fun `strongbox verified + locked is trusted`() {
        assertEquals(
            AttestationResult.Trusted,
            AttestationPolicy.evaluate(parsed(level = AttestationSecurityLevel.STRONG_BOX)),
        )
    }

    @Test
    fun `unlocked bootloader is advisory even when verified`() {
        assertTrue(
            AttestationPolicy.evaluate(parsed(locked = false, state = VerifiedBootState.VERIFIED))
                is AttestationResult.Advisory,
        )
    }

    @Test
    fun `unverified boot is advisory`() {
        assertTrue(
            AttestationPolicy.evaluate(parsed(state = VerifiedBootState.UNVERIFIED))
                is AttestationResult.Advisory,
        )
    }

    @Test
    fun `failed boot is advisory`() {
        assertTrue(
            AttestationPolicy.evaluate(parsed(state = VerifiedBootState.FAILED))
                is AttestationResult.Advisory,
        )
    }

    @Test
    fun `software-level attestation is unavailable, never a warning`() {
        // Even with the weakest boot signals, a software-level record is untrusted
        // and must stay silent (not warn) under Path A.
        assertTrue(
            AttestationPolicy.evaluate(
                parsed(
                    level = AttestationSecurityLevel.SOFTWARE,
                    locked = false,
                    state = VerifiedBootState.UNVERIFIED,
                )
            ) is AttestationResult.Unavailable,
        )
    }

    @Test
    fun `unrecognised boot state while locked is unavailable`() {
        assertTrue(
            AttestationPolicy.evaluate(parsed(state = VerifiedBootState.UNKNOWN))
                is AttestationResult.Unavailable,
        )
    }
}

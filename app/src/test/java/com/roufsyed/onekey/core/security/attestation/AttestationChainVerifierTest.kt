package com.roufsyed.onekey.core.security.attestation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Verifies [AttestationChainVerifier] against real Google-issued chains and the
 * embedded pinned roots. These chains terminate at the 2019 RSA root cert, which
 * shares its public key with our embedded 2022 root - so a passing test also
 * proves the "anchor on public key, not serial" design works.
 */
class AttestationChainVerifierTest {

    private val unverified = AttestationTestVectors.loadChain(AttestationTestVectors.UNVERIFIED)
    private val verified = AttestationTestVectors.loadChain(AttestationTestVectors.VERIFIED)

    @Test
    fun `real unverified chain verifies and returns the leaf`() {
        val leaf = AttestationChainVerifier.verifyToPinnedRoot(unverified)
        assertNotNull(leaf)
        assertEquals(unverified.first(), leaf)
    }

    @Test
    fun `real verified StrongBox chain verifies to a pinned root`() {
        assertNotNull(AttestationChainVerifier.verifyToPinnedRoot(verified))
    }

    @Test
    fun `empty chain does not verify`() {
        assertNull(AttestationChainVerifier.verifyToPinnedRoot(emptyList()))
    }

    @Test
    fun `a lone leaf does not verify (not directly signed by a pinned root)`() {
        assertNull(AttestationChainVerifier.verifyToPinnedRoot(listOf(unverified.first())))
    }

    @Test
    fun `a reordered chain does not verify (broken signature links)`() {
        assertNull(AttestationChainVerifier.verifyToPinnedRoot(unverified.reversed()))
    }

    @Test
    fun `a chain truncated below the root does not verify`() {
        // leaf + first intermediate only: the terminal intermediate is not signed
        // by (nor equal to) a pinned root.
        assertNull(AttestationChainVerifier.verifyToPinnedRoot(unverified.take(2)))
    }

    @Test
    fun `a lone pinned root is not returned as a trusted leaf`() {
        // Guards F2: a single-cert "chain" (even a genuine pinned root) must not
        // be treated as a verified leaf.
        val root = AttestationTrustStore.roots.first()
        assertNull(AttestationChainVerifier.verifyToPinnedRoot(listOf(root)))
    }
}

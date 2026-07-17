package com.roufsyed.onekey.core.security.attestation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey
import javax.security.auth.x500.X500Principal

/**
 * Self-validates the embedded trust anchors: proves the bundled PEM parses into
 * exactly the two expected Google roots and that their bytes are uncorrupted
 * (each root's self-signature verifies). Hermetic - CertificateFactory runs on
 * the host JVM.
 */
class AttestationTrustStoreTest {

    @Test
    fun `embeds exactly the two pinned Google roots`() {
        assertEquals(2, AttestationTrustStore.roots.size)
    }

    @Test
    fun `both roots are self-signed and self-verify (bytes uncorrupted)`() {
        AttestationTrustStore.roots.forEach { root ->
            assertEquals(root.subjectX500Principal, root.issuerX500Principal)
            // Throws (failing the test) if the embedded bytes are corrupted.
            root.verify(root.publicKey)
        }
    }

    @Test
    fun `pins the classic RSA-4096 root with serial f92009e853b6b045`() {
        val rsa = AttestationTrustStore.roots.single { it.publicKey.algorithm == "RSA" }
        assertEquals(4096, (rsa.publicKey as RSAPublicKey).modulus.bitLength())
        val subject = rsa.subjectX500Principal.getName(
            X500Principal.RFC2253,
            mapOf("2.5.4.5" to "SERIALNUMBER"),
        )
        assertTrue(subject, subject.contains("f92009e853b6b045", ignoreCase = true))
    }

    @Test
    fun `pins the 2026 EC P-384 Key Attestation CA1 root`() {
        val ec = AttestationTrustStore.roots.single { it.publicKey.algorithm == "EC" }
        assertEquals(384, (ec.publicKey as ECPublicKey).params.curve.field.fieldSize)
        assertTrue(ec.subjectX500Principal.name.contains("Key Attestation CA1"))
    }
}

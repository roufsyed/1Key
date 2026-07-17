package com.roufsyed.onekey.core.security.attestation

import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * Loads the real, byte-exact Android Key Attestation chains bundled under
 * `src/test/resources/attestation/` (captured from the official
 * `android/keyattestation` test corpus). Leaf-first.
 */
internal object AttestationTestVectors {
    const val UNVERIFIED = "attestation_chain_unverified.pem"        // akita, UNVERIFIED + unlocked (TEE)
    const val VERIFIED = "attestation_chain_verified.pem"           // caiman, VERIFIED + locked (StrongBox RKP)
    const val MALFORMED = "attestation_chain_malformed_rot.pem"     // malformed RootOfTrust

    fun loadChain(resourceName: String): List<X509Certificate> {
        val stream = AttestationTestVectors::class.java
            .getResourceAsStream("/attestation/$resourceName")
            ?: error("missing test resource: /attestation/$resourceName")
        return stream.use {
            CertificateFactory.getInstance("X.509")
                .generateCertificates(it)
                .filterIsInstance<X509Certificate>()
        }
    }
}

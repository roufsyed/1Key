package com.roufsyed.onekey.core.security.attestation

import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * The pinned Google hardware-attestation root certificates, embedded so chain
 * verification works fully offline. Loaded once (lazily) from a bundled PEM
 * resource on the classpath.
 *
 * Two roots are pinned (source: https://android.googleapis.com/attestation/root):
 *  - RSA-4096, Subject `SERIALNUMBER=f92009e853b6b045` - covers all historical
 *    and current RSA-signed attestation chains.
 *  - ECDSA P-384, Subject `CN=Key Attestation CA1` - the root that signs chains
 *    issued from February 2026 onward (Remote Key Provisioning era).
 *
 * Trust is anchored on these certificates' PUBLIC KEYS, not on any serial or
 * subject string (see [AttestationChainVerifier]). Certificate validity windows
 * and revocation are deliberately NOT checked - the app is offline (no CRL/OCSP
 * reachable) and the boot-state signal is advisory; a clock-drift-driven
 * validity failure must not turn into a false verdict.
 */
object AttestationTrustStore {

    private const val ROOTS_RESOURCE = "/attestation/google_attestation_roots.pem"

    /** The pinned root certificates. */
    val roots: Set<X509Certificate> by lazy { loadRoots() }

    private fun loadRoots(): Set<X509Certificate> {
        val stream = AttestationTrustStore::class.java.getResourceAsStream(ROOTS_RESOURCE)
            ?: error("Missing embedded attestation roots resource: $ROOTS_RESOURCE")
        return stream.use { input ->
            CertificateFactory.getInstance("X.509")
                .generateCertificates(input)
                .filterIsInstance<X509Certificate>()
                .toSet()
        }
    }
}

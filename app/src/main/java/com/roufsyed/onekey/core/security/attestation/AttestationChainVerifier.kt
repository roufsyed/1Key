package com.roufsyed.onekey.core.security.attestation

import java.security.PublicKey
import java.security.cert.X509Certificate

/**
 * Verifies that an Android Key Attestation certificate chain terminates at a
 * pinned Google hardware-attestation root ([AttestationTrustStore]).
 *
 * Intentionally minimal and offline-safe, per the security roadmap's red-team
 * guidance:
 *  - Every adjacent link's signature is verified (each child signed by its
 *    parent's public key).
 *  - Trust is anchored on the pinned root's PUBLIC KEY via a real signature
 *    check: the terminal certificate must carry a signature verifiable with a
 *    pinned root's public key. A Google root cert is self-signed, so it verifies
 *    against the pinned key it shares even when its serial/validity differ
 *    (Google reissues the root cert over time but keeps the key). We never trust
 *    by serial/subject string, nor by bare public-key equality (which would
 *    bless a forged, unsigned terminal that merely copies a pinned public key).
 *  - Certificate validity windows and revocation are deliberately NOT checked:
 *    the app is offline (no CRL/OCSP reachable), and a clock-drift validity
 *    failure must not become a false verdict. A rooted attacker can forge a
 *    fully valid chain via a leaked keybox regardless, so validity/revocation
 *    checks change no realistic threat here - the whole signal is an advisory
 *    deterrent, not a boundary.
 *
 * Returns the trusted leaf (`chain[0]`) on success, or `null` if the chain does
 * not verifiably link to a pinned root. Never throws.
 *
 * Note: the caller ([AttestationChecker]) is responsible for the remaining
 * bindings - that the returned leaf's public key equals the key it generated,
 * and that the attestation challenge matches - which this verifier does not do.
 */
object AttestationChainVerifier {

    fun verifyToPinnedRoot(chain: List<X509Certificate>): X509Certificate? {
        // A genuine attestation chain is always leaf + intermediate(s) + root;
        // a lone certificate can never be a trusted chain.
        if (chain.size < 2) return null
        return runCatching {
            // 1. Every child must be signed by the next certificate in the chain.
            for (i in 0 until chain.size - 1) {
                if (!isSignedBy(chain[i], chain[i + 1].publicKey)) return null
            }
            // 2. The terminal certificate must carry a signature verifiable with a
            //    pinned root's public key. A Google root cert is self-signed, so it
            //    verifies against the pinned key it shares even when its serial /
            //    validity differ. We anchor on the KEY via a real signature check -
            //    never on a serial/subject string, and never on bare public-key
            //    equality (which would bless a forged, unsigned terminal that only
            //    copies a pinned public key).
            val terminal = chain.last()
            val anchored = AttestationTrustStore.roots.any { root ->
                isSignedBy(terminal, root.publicKey)
            }
            if (anchored) chain.first() else null
        }.getOrNull()
    }

    private fun isSignedBy(cert: X509Certificate, issuerKey: PublicKey): Boolean =
        runCatching { cert.verify(issuerKey); true }.getOrDefault(false)
}

package com.roufsyed.onekey.core.domain.webauthn

import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.asn1.x9.X9ObjectIdentifiers

/**
 * The signature algorithms 1Key can make passkeys with, named as the IANA COSE
 * Algorithms registry names them. ES256 is what every relying party accepts;
 * EdDSA (Ed25519) and RS256 cover sites that prefer or insist on them, which
 * is the same set KeePassXC offers.
 *
 * A site lists what it accepts in order of preference and the authenticator
 * takes the first entry it can do (WebAuthn Level 3 section 6.3.2, CTAP 2
 * makeCredential), so [firstSupported] walks the site's order, never 1Key's.
 *
 * [aliases] are the "fully specified" identifiers RFC 9864 registered for the
 * same operations: ESP256 (-9) for ECDSA P-256 with SHA-256 and Ed25519 (-19).
 * A site that lists only those still gets a key; the response reports the
 * classic identifier, which is what browsers expect in `publicKeyAlgorithm`.
 */
enum class PasskeyAlgorithm(val coseId: Int, val aliases: Set<Int>, val displayName: String) {
    ES256(-7, setOf(-9), "ES256"),
    EDDSA(-8, setOf(-19), "Ed25519"),
    RS256(-257, emptySet(), "RS256");

    fun accepts(id: Int): Boolean = id == coseId || id in aliases

    companion object {
        /** Used when a site sends no list at all; the spec's own default list starts with ES256. */
        val DEFAULT: PasskeyAlgorithm = ES256

        /** First algorithm in the site's order that 1Key can produce, or null when the list names none of them. */
        fun firstSupported(requested: List<Int>): PasskeyAlgorithm? =
            if (requested.isEmpty()) DEFAULT
            else requested.firstNotNullOfOrNull { id -> entries.firstOrNull { it.accepts(id) } }

        /**
         * The algorithm a stored PKCS#8 private key belongs to, read from its
         * AlgorithmIdentifier. Null for anything 1Key cannot sign with, including
         * EC keys on curves other than P-256, so imports reject them up front.
         */
        fun ofPkcs8(pkcs8: ByteArray): PasskeyAlgorithm? = runCatching {
            val identifier = PrivateKeyInfo.getInstance(pkcs8).privateKeyAlgorithm
            when (identifier.algorithm) {
                X9ObjectIdentifiers.id_ecPublicKey ->
                    if (ASN1ObjectIdentifier.getInstance(identifier.parameters) == X9ObjectIdentifiers.prime256v1) ES256 else null
                PKCSObjectIdentifiers.rsaEncryption -> RS256
                OID_ED25519 -> EDDSA
                else -> null
            }
        }.getOrNull()

        /** RFC 8410 id-Ed25519. Spelled out rather than taken from BouncyCastle's internal package. */
        private val OID_ED25519 = ASN1ObjectIdentifier("1.3.101.112")
    }
}

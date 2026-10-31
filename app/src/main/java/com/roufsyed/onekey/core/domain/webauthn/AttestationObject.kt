package com.roufsyed.onekey.core.domain.webauthn

/**
 * Attestation object for the `none` attestation statement format (WebAuthn
 * Level 3 section 8.7): `{ "fmt": "none", "attStmt": {}, "authData": <bytes> }`.
 *
 * 1Key never attests. A software passkey encrypted in the vault has nothing an
 * attestation statement could prove, and the platform synced passkeys of
 * Apple and Google return no attestation either, so `none` places 1Key in the
 * same capability class relying parties already accept.
 */
object AttestationObject {
    const val FORMAT_NONE = "none"

    fun none(authenticatorData: ByteArray): ByteArray = Cbor.encode(
        mapOf(
            "fmt" to FORMAT_NONE,
            "attStmt" to emptyMap<String, Any?>(),
            "authData" to authenticatorData,
        ),
    )
}

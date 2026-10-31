package com.roufsyed.onekey.core.domain.webauthn

import java.security.KeyPair

/**
 * The authenticator-side ceremonies, composed from the primitives in this
 * package. Pure functions: no Android, no I/O, no state. The Credential
 * Manager layer supplies the caller-dependent inputs (RP ID after policy
 * checks, client data hash, user-verification result, backup state) and
 * persists what comes back.
 */
object WebAuthnAuthenticator {

    /** Everything a registration produces. [privateKeyPkcs8] is the only secret. */
    class Registration(
        val credentialId: ByteArray,
        val privateKeyPkcs8: ByteArray,
        val cosePublicKey: ByteArray,
        val authenticatorData: ByteArray,
        val attestationObject: ByteArray,
    )

    class Assertion(
        val authenticatorData: ByteArray,
        val signature: ByteArray,
    )

    /**
     * Registers a new discoverable ES256 credential for [rpId].
     *
     * @param backupState honest BS flag: true only when a backup containing this
     *   credential already exists, which for a brand-new credential is false.
     */
    fun register(
        rpId: String,
        userVerified: Boolean,
        backupState: Boolean = false,
        credentialId: ByteArray = Es256.newCredentialId(),
        keyPair: KeyPair = Es256.generateKeyPair(),
        extensions: ByteArray? = null,
    ): Registration {
        val cosePublicKey = CoseKey.es256(Es256.publicKeyOf(keyPair))
        val authenticatorData = AuthenticatorData.registration(
            rpId = rpId,
            credentialId = credentialId,
            cosePublicKey = cosePublicKey,
            userVerified = userVerified,
            backupState = backupState,
            extensions = extensions,
        )
        return Registration(
            credentialId = credentialId,
            privateKeyPkcs8 = Es256.privateKeyPkcs8(keyPair),
            cosePublicKey = cosePublicKey,
            authenticatorData = authenticatorData,
            attestationObject = AttestationObject.none(authenticatorData),
        )
    }

    /** Signs an assertion for an existing credential. The caller zeroes [privateKeyPkcs8] afterwards. */
    fun assert(
        rpId: String,
        privateKeyPkcs8: ByteArray,
        clientDataHash: ByteArray,
        userVerified: Boolean,
        backupState: Boolean,
        extensions: ByteArray? = null,
    ): Assertion {
        val authenticatorData = AuthenticatorData.assertion(
            rpId = rpId,
            userVerified = userVerified,
            backupState = backupState,
            extensions = extensions,
        )
        return Assertion(
            authenticatorData = authenticatorData,
            signature = Es256.signAssertion(privateKeyPkcs8, authenticatorData, clientDataHash),
        )
    }
}

package com.roufsyed.onekey.core.domain.webauthn

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
        val algorithm: PasskeyAlgorithm,
        val credentialId: ByteArray,
        val privateKeyPkcs8: ByteArray,
        val cosePublicKey: ByteArray,
        /** X.509 SubjectPublicKeyInfo DER, the `response.publicKey` field of RegistrationResponseJSON. */
        val publicKeySpki: ByteArray,
        val authenticatorData: ByteArray,
        val attestationObject: ByteArray,
    )

    class Assertion(
        val authenticatorData: ByteArray,
        val signature: ByteArray,
    )

    /**
     * Registers a new discoverable credential for [rpId] with [algorithm], the
     * first entry of the site's `pubKeyCredParams` that 1Key supports.
     *
     * @param backupState honest BS flag: true only when a backup containing this
     *   credential already exists, which for a brand-new credential is false.
     */
    fun register(
        rpId: String,
        userVerified: Boolean,
        backupState: Boolean = false,
        algorithm: PasskeyAlgorithm = PasskeyAlgorithm.ES256,
        credentialId: ByteArray = Es256.newCredentialId(),
        keys: PasskeyKeys.Generated = PasskeyKeys.generate(algorithm),
        extensions: ByteArray? = null,
    ): Registration {
        val authenticatorData = AuthenticatorData.registration(
            rpId = rpId,
            credentialId = credentialId,
            cosePublicKey = keys.cosePublicKey,
            userVerified = userVerified,
            backupState = backupState,
            extensions = extensions,
        )
        return Registration(
            algorithm = keys.algorithm,
            credentialId = credentialId,
            privateKeyPkcs8 = keys.privateKeyPkcs8,
            cosePublicKey = keys.cosePublicKey,
            publicKeySpki = keys.publicKeySpki,
            authenticatorData = authenticatorData,
            attestationObject = AttestationObject.none(authenticatorData),
        )
    }

    /** Signs an assertion for an existing credential of any supported algorithm. The caller zeroes [privateKeyPkcs8] afterwards. */
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
            signature = PasskeyKeys.signAssertion(privateKeyPkcs8, authenticatorData, clientDataHash),
        )
    }
}

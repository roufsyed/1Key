package com.roufsyed.onekey.core.domain.webauthn

import com.webauthn4j.WebAuthnManager
import com.webauthn4j.credential.CredentialRecordImpl
import com.webauthn4j.data.AuthenticationParameters
import com.webauthn4j.data.AuthenticationRequest
import com.webauthn4j.data.PublicKeyCredentialParameters
import com.webauthn4j.data.PublicKeyCredentialType
import com.webauthn4j.data.RegistrationParameters
import com.webauthn4j.data.RegistrationRequest
import com.webauthn4j.data.attestation.statement.COSEAlgorithmIdentifier
import com.webauthn4j.data.client.Origin
import com.webauthn4j.data.client.challenge.DefaultChallenge
import com.webauthn4j.server.ServerProperty
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The whole point of the authenticator core is that an independent relying
 * party accepts what it emits. webauthn4j is a spec-complete server-side
 * verifier: it parses our CBOR attestation object, the COSE key, the
 * authenticator data layout and the DER signature, and enforces origin, RP ID
 * hash, flags, counter and challenge rules. If any encoding slips, this fails.
 */
class WebAuthnVerifierRoundTripTest {

    private val manager = WebAuthnManager.createNonStrictWebAuthnManager()
    private val rpId = "example.com"
    private val origin = "https://example.com"
    private val es256Only = listOf(PublicKeyCredentialParameters(PublicKeyCredentialType.PUBLIC_KEY, COSEAlgorithmIdentifier.ES256))

    private fun serverProperty(challenge: ByteArray, originValue: String = origin) =
        ServerProperty(Origin.create(originValue), rpId, DefaultChallenge(challenge))

    @Test fun registration_then_assertion_verify_under_webauthn4j() {
        // ── Registration ──────────────────────────────────────────────────
        val regChallenge = ByteArray(32) { it.toByte() }
        val regClientData = ClientData.json(ClientData.TYPE_CREATE, Base64Url.encode(regChallenge), origin)
        val registration = WebAuthnAuthenticator.register(rpId = rpId, userVerified = true)

        val regData = manager.verify(
            RegistrationRequest(registration.attestationObject, regClientData.toByteArray(Charsets.UTF_8)),
            RegistrationParameters(serverProperty(regChallenge), es256Only, /* userVerificationRequired = */ true),
        )
        val attestationObject = regData.attestationObject
        assertNotNull(attestationObject)
        val attested = attestationObject!!.authenticatorData.attestedCredentialData
        assertNotNull(attested)
        assertArrayEquals(registration.credentialId, attested!!.credentialId)
        assertEquals(OneKeyAaguid.UUID_VALUE.toString(), attested.aaguid.toString())
        assertEquals(0L, attestationObject.authenticatorData.signCount)
        assertEquals("none", attestationObject.format)

        val record = CredentialRecordImpl(
            attestationObject,
            regData.collectedClientData,
            regData.clientExtensions,
            regData.transports,
        )

        // ── Assertion ─────────────────────────────────────────────────────
        val getChallenge = ByteArray(32) { (100 + it).toByte() }
        val getClientData = ClientData.json(ClientData.TYPE_GET, Base64Url.encode(getChallenge), origin)
        val assertion = WebAuthnAuthenticator.assert(
            rpId = rpId,
            privateKeyPkcs8 = registration.privateKeyPkcs8,
            clientDataHash = ClientData.hash(getClientData),
            userVerified = true,
            backupState = true,
        )
        val userHandle = ByteArray(16) { 5 }
        val authData = manager.verify(
            AuthenticationRequest(
                registration.credentialId,
                userHandle,
                assertion.authenticatorData,
                getClientData.toByteArray(Charsets.UTF_8),
                assertion.signature,
            ),
            AuthenticationParameters(serverProperty(getChallenge), record, listOf(registration.credentialId), /* userVerificationRequired = */ true),
        )
        assertTrue(authData.authenticatorData!!.isFlagUV)
        assertTrue(authData.authenticatorData!!.isFlagBE)
        assertTrue(authData.authenticatorData!!.isFlagBS)
        assertEquals(0L, authData.authenticatorData!!.signCount)
    }

    @Test fun assertion_with_wrong_challenge_is_rejected() {
        val regChallenge = ByteArray(32) { 1 }
        val regClientData = ClientData.json(ClientData.TYPE_CREATE, Base64Url.encode(regChallenge), origin)
        val registration = WebAuthnAuthenticator.register(rpId = rpId, userVerified = true)
        val regData = manager.verify(
            RegistrationRequest(registration.attestationObject, regClientData.toByteArray(Charsets.UTF_8)),
            RegistrationParameters(serverProperty(regChallenge), es256Only, true),
        )
        val record = CredentialRecordImpl(regData.attestationObject!!, regData.collectedClientData, regData.clientExtensions, regData.transports)

        val getClientData = ClientData.json(ClientData.TYPE_GET, Base64Url.encode(ByteArray(32) { 2 }), origin)
        val assertion = WebAuthnAuthenticator.assert(rpId, registration.privateKeyPkcs8, ClientData.hash(getClientData), true, false)
        assertThrows(Exception::class.java) {
            manager.verify(
                AuthenticationRequest(registration.credentialId, null, assertion.authenticatorData, getClientData.toByteArray(), assertion.signature),
                AuthenticationParameters(serverProperty(ByteArray(32) { 3 }), record, null, true),
            )
        }
    }

    @Test fun registration_for_a_native_app_origin_verifies() {
        // Android native callers: origin is android:apk-key-hash:<b64url(sha256(cert))>.
        val apkOrigin = ClientData.apkKeyHashOrigin(ByteArray(32) { 0x42 })
        val challenge = ByteArray(32) { 7 }
        val clientData = ClientData.json(ClientData.TYPE_CREATE, Base64Url.encode(challenge), apkOrigin, "com.example.app")
        val registration = WebAuthnAuthenticator.register(rpId = rpId, userVerified = false)
        val regData = manager.verify(
            RegistrationRequest(registration.attestationObject, clientData.toByteArray(Charsets.UTF_8)),
            RegistrationParameters(serverProperty(challenge, apkOrigin), es256Only, /* userVerificationRequired = */ false),
        )
        assertEquals(apkOrigin, regData.collectedClientData!!.origin.toString())
    }
}

package com.roufsyed.onekey.core.domain.webauthn

import com.google.gson.JsonParser
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WebAuthnJsonTest {

    private val creation = """
        {"rp":{"id":"GitHub.com","name":"GitHub"},
         "user":{"id":"a2YzeHEy","name":"rouf","displayName":"Rouf Syed"},
         "challenge":"AQIDBA",
         "pubKeyCredParams":[{"type":"public-key","alg":-7},{"type":"public-key","alg":-257}],
         "excludeCredentials":[{"type":"public-key","id":"CQkJ"}],
         "authenticatorSelection":{"residentKey":"required","userVerification":"required"},
         "attestation":"direct",
         "extensions":{"credProps":true}}
    """.trimIndent()

    @Test fun creation_options_parse_every_field_we_use() {
        val o = WebAuthnJson.parseCreationOptions(creation)
        assertEquals("github.com", o.rpId)
        assertEquals("GitHub", o.rpName)
        assertArrayEquals(Base64Url.decode("a2YzeHEy"), o.userId)
        assertEquals("rouf", o.userName)
        assertEquals("Rouf Syed", o.userDisplayName)
        assertEquals("AQIDBA", o.challenge)
        assertEquals(listOf(-7, -257), o.algorithms)
        assertEquals(PasskeyAlgorithm.ES256, o.algorithm)
        assertEquals(1, o.excludeCredentials.size)
        assertTrue(o.excludeCredentials[0].matches(byteArrayOf(9, 9, 9)))
        assertEquals(UserVerificationRequirement.REQUIRED, o.userVerification)
        assertEquals("required", o.residentKey)
        assertEquals("direct", o.attestation)
        assertTrue(o.wantsCredProps)
    }

    @Test fun creation_options_defaults_and_missing_rp_id() {
        val o = WebAuthnJson.parseCreationOptions("""{"rp":{"name":"X"},"user":{"id":"AA","name":"u","displayName":"U"},"challenge":"Zg"}""")
        assertNull(o.rpId)
        assertTrue(o.algorithms.isEmpty())
        assertEquals("no list means the spec default", PasskeyAlgorithm.ES256, o.algorithm)
        assertEquals(UserVerificationRequirement.PREFERRED, o.userVerification)
        assertEquals("none", o.attestation)
        assertFalse(o.wantsCredProps)
    }

    @Test fun creation_options_follow_the_sites_algorithm_order() {
        val rsaOnly = WebAuthnJson.parseCreationOptions("""{"rp":{"id":"a.com"},"user":{"id":"AA","name":"u","displayName":"U"},"challenge":"Zg","pubKeyCredParams":[{"type":"public-key","alg":-257}]}""")
        assertEquals(PasskeyAlgorithm.RS256, rsaOnly.algorithm)
        val edFirst = WebAuthnJson.parseCreationOptions("""{"rp":{"id":"a.com"},"user":{"id":"AA","name":"u","displayName":"U"},"challenge":"Zg","pubKeyCredParams":[{"type":"public-key","alg":-8},{"type":"public-key","alg":-7}]}""")
        assertEquals(PasskeyAlgorithm.EDDSA, edFirst.algorithm)
        val unknownOnly = WebAuthnJson.parseCreationOptions("""{"rp":{"id":"a.com"},"user":{"id":"AA","name":"u","displayName":"U"},"challenge":"Zg","pubKeyCredParams":[{"type":"public-key","alg":-48}]}""")
        assertNull(unknownOnly.algorithm)
    }

    @Test fun malformed_requests_throw_illegal_argument() {
        assertThrows(IllegalArgumentException::class.java) { WebAuthnJson.parseCreationOptions("not json") }
        assertThrows(IllegalArgumentException::class.java) { WebAuthnJson.parseCreationOptions("""{"rp":{"id":"a.com"},"challenge":"Zg"}""") }
        assertThrows(IllegalArgumentException::class.java) { WebAuthnJson.parseRequestOptions("""{"rpId":"a.com"}""") }
    }

    @Test fun request_options_parse_and_allow_list_semantics() {
        val o = WebAuthnJson.parseRequestOptions("""{"rpId":"Example.com","challenge":"Zm9v","allowCredentials":[{"type":"public-key","id":"AQID"}],"userVerification":"discouraged"}""")
        assertEquals("example.com", o.rpId)
        assertEquals(UserVerificationRequirement.DISCOURAGED, o.userVerification)
        assertFalse(o.userVerification.wantsVerification)
        assertTrue(o.allows(byteArrayOf(1, 2, 3)))
        assertFalse(o.allows(byteArrayOf(4)))
        val open = WebAuthnJson.parseRequestOptions("""{"challenge":"Zm9v"}""")
        assertNull(open.rpId)
        assertTrue(open.allows(byteArrayOf(4)))
    }

    @Test fun registration_response_json_shape() {
        val json = WebAuthnJson.registrationResponse(
            credentialId = byteArrayOf(1, 2, 3),
            clientDataJson = "{}".toByteArray(),
            attestationObject = byteArrayOf(0xA3.toByte()),
            authenticatorData = byteArrayOf(7),
            publicKeySpki = byteArrayOf(8),
            publicKeyAlgorithm = PasskeyAlgorithm.EDDSA.coseId,
            credProps = true,
        )
        val o = JsonParser.parseString(json).asJsonObject
        assertEquals("AQID", o["id"].asString)
        assertEquals("AQID", o["rawId"].asString)
        assertEquals("public-key", o["type"].asString)
        assertEquals("platform", o["authenticatorAttachment"].asString)
        val r = o["response"].asJsonObject
        assertEquals("e30", r["clientDataJSON"].asString)
        assertEquals("ow", r["attestationObject"].asString)
        assertEquals("follows the key that was made, not a constant", -8, r["publicKeyAlgorithm"].asInt)
        assertEquals("CA", r["publicKey"].asString)
        assertEquals("Bw", r["authenticatorData"].asString)
        assertEquals(listOf("internal", "hybrid"), r["transports"].asJsonArray.map { it.asString })
        assertTrue(o["clientExtensionResults"].asJsonObject["credProps"].asJsonObject["rk"].asBoolean)
    }

    @Test fun privileged_caller_gets_empty_client_data_placeholder() {
        val json = WebAuthnJson.assertionResponse(byteArrayOf(1), null, byteArrayOf(2), byteArrayOf(3), byteArrayOf(4))
        val r = JsonParser.parseString(json).asJsonObject["response"].asJsonObject
        assertEquals("", r["clientDataJSON"].asString)
        assertEquals("Ag", r["authenticatorData"].asString)
        assertEquals("Aw", r["signature"].asString)
        assertEquals("BA", r["userHandle"].asString)
        assertEquals(0, JsonParser.parseString(json).asJsonObject["clientExtensionResults"].asJsonObject.size())
    }
}

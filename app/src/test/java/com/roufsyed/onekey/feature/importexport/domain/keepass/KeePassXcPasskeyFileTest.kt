package com.roufsyed.onekey.feature.importexport.domain.keepass

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.roufsyed.onekey.core.domain.model.PasskeyOrigin
import com.roufsyed.onekey.core.domain.webauthn.Base64Url
import com.roufsyed.onekey.core.domain.webauthn.PasskeyAlgorithm
import com.roufsyed.onekey.core.domain.webauthn.PasskeyKeys
import com.roufsyed.onekey.core.domain.webauthn.Pkcs8Pem
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Base64

class KeePassXcPasskeyFileTest {

    private val gson = Gson()
    private val credentialId = ByteArray(32) { (it + 1).toByte() }
    private val userHandle = ByteArray(16) { 9 }

    /** Keys in the alphabetical order QJsonObject writes them. */
    private fun fileObject(
        pem: String = Pkcs8Pem.encode(PasskeyKeys.generate(PasskeyAlgorithm.ES256).privateKeyPkcs8),
        credentialIdText: String = Base64Url.encode(credentialId),
        userHandleText: String = Base64Url.encode(userHandle),
        url: String = "https://github.com/login",
    ) = linkedMapOf(
        "credentialId" to credentialIdText,
        "privateKey" to pem,
        "relyingParty" to "GitHub.com",
        "url" to url,
        "userHandle" to userHandleText,
        "username" to "rouf",
    )

    @Test fun keepassxc_object_becomes_an_entry_with_one_browser_passkey() {
        val parsed = KeePassXcPasskeyFile.parse(gson.toJson(fileObject()), now = 1_000L)
        assertTrue(parsed.failed.isEmpty())
        val credential = parsed.credentials.single()
        assertEquals("Github", credential.title)   // the URL title extractor capitalises the host label
        assertEquals("rouf", credential.username)
        assertEquals("https://github.com/login", credential.url)
        assertEquals("", credential.password)
        val passkey = credential.passkeys.single()
        assertEquals(credential.id, passkey.credentialId)
        assertEquals("github.com", passkey.rpId)
        assertEquals("Github", passkey.rpName)
        assertArrayEquals(credentialId, passkey.webauthnCredentialId)
        assertArrayEquals(userHandle, passkey.userHandle)
        assertEquals(PasskeyOrigin.Browser("https://github.com"), passkey.origin)
        assertEquals(PasskeyAlgorithm.ES256, PasskeyAlgorithm.ofPkcs8(passkey.privateKeyPkcs8))
        assertEquals(1_000L, passkey.backedUpAt)
    }

    @Test fun array_of_files_and_standard_base64_ids_are_accepted() {
        val second = fileObject(
            pem = Pkcs8Pem.encode(PasskeyKeys.generate(PasskeyAlgorithm.EDDSA).privateKeyPkcs8),
            credentialIdText = Base64.getEncoder().encodeToString(credentialId),
            userHandleText = Base64.getEncoder().encodeToString(userHandle),
        )
        val parsed = KeePassXcPasskeyFile.parse(gson.toJson(listOf(fileObject(), second)))
        assertEquals(2, parsed.credentials.size)
        assertArrayEquals(credentialId, parsed.credentials[1].passkeys.single().webauthnCredentialId)
        assertEquals(PasskeyAlgorithm.EDDSA, PasskeyAlgorithm.ofPkcs8(parsed.credentials[1].passkeys.single().privateKeyPkcs8))
    }

    @Test fun missing_fields_and_unsupported_keys_fail_that_object_only() {
        val p384 = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp384r1")) }.generateKeyPair()
        val bad = listOf(
            fileObject().apply { remove("userHandle") },
            fileObject(pem = Pkcs8Pem.encode(p384.private.encoded)),
            fileObject(credentialIdText = "not base64!"),
            fileObject(),
        )
        val parsed = KeePassXcPasskeyFile.parse(gson.toJson(bad))
        assertEquals(1, parsed.credentials.size)
        assertEquals(listOf(1, 2, 3), parsed.failed.map { it.rowIndex })
    }

    @Test fun looks_like_tells_passkey_files_from_backups() {
        assertTrue(KeePassXcPasskeyFile.looksLike(JsonParser.parseString(gson.toJson(fileObject()))))
        assertTrue(KeePassXcPasskeyFile.looksLike(JsonParser.parseString(gson.toJson(listOf(fileObject(), fileObject())))))
        assertFalse(KeePassXcPasskeyFile.looksLike(JsonParser.parseString("""[{"id":"a","title":"GitHub","passkeys":[]}]""")))
        assertFalse(KeePassXcPasskeyFile.looksLike(JsonParser.parseString("""{"title":"x"}""")))
        assertFalse(KeePassXcPasskeyFile.looksLike(JsonParser.parseString("[]")))
    }
}

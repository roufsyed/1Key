package com.roufsyed.onekey.feature.importexport.domain

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.roufsyed.onekey.core.domain.model.AppResult
import com.roufsyed.onekey.core.domain.model.Credential
import com.roufsyed.onekey.core.domain.model.CredentialType
import com.roufsyed.onekey.core.domain.model.Passkey
import com.roufsyed.onekey.core.domain.model.PasskeyOrigin
import com.roufsyed.onekey.core.domain.usecase.ExportFormat
import com.roufsyed.onekey.core.domain.webauthn.Base64Url
import com.roufsyed.onekey.core.domain.webauthn.Es256
import com.roufsyed.onekey.core.domain.webauthn.PasskeyAlgorithm
import com.roufsyed.onekey.core.domain.webauthn.PasskeyKeys
import com.roufsyed.onekey.core.domain.webauthn.Pkcs8Pem
import com.roufsyed.onekey.core.security.CryptoManager
import com.roufsyed.onekey.core.security.SecretKeyHolder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Round-trip contract for passkeys through the JSON backup body (plan 9.1, 9.2):
 *  - a credential without passkeys serialises exactly as before (no `passkeys` key);
 *  - the key is an array of passkey objects with PEM private keys and base64url ids;
 *  - the importer restores every field byte for byte, drops a malformed passkey
 *    without failing the credential, and never turns the array into a custom field.
 */
class PasskeyInterchangeTest {

    @get:Rule val tmp = TemporaryFolder()

    private val crypto = CryptoManager()
    private val exporter = VaultExporterImpl(crypto, SecretKeyHolder())
    private val importer = VaultImporterImpl(crypto)
    private val gson = Gson()

    private val keyPair = Es256.generateKeyPair()

    private fun passkey(origin: PasskeyOrigin = PasskeyOrigin.Browser("https://github.com")) = Passkey(
        id = "pk-1",
        credentialId = "cred-1",
        rpId = "github.com",
        rpName = "GitHub",
        userName = "rouf",
        userDisplayName = "Rouf Syed",
        userHandle = ByteArray(16) { 7 },
        webauthnCredentialId = ByteArray(32) { 9 },
        privateKeyPkcs8 = Es256.privateKeyPkcs8(keyPair),
        prfSecret = ByteArray(32) { 3 },
        origin = origin,
        createdAt = 1_700_000_000_000L,
        lastUsedAt = 1_700_000_500_000L,
        backedUpAt = 1_700_000_600_000L,
    )

    private fun credential(passkeys: List<Passkey>) = Credential(
        id = "cred-1", title = "GitHub", username = "rouf", password = "pw", url = "https://github.com", notes = "",
        otpParams = null, tags = listOf("Login"), customFields = emptyList(), createdAt = 1L, updatedAt = 2L,
        type = CredentialType.LOGIN, passkeys = passkeys,
    )

    private fun dtos(bytes: ByteArray): List<Map<String, Any>> =
        gson.fromJson(bytes.toString(Charsets.UTF_8), object : TypeToken<List<Map<String, Any>>>() {}.type)

    @Test fun credential_without_passkeys_has_no_passkeys_key() {
        val dto = dtos(exporter.serializeForSync(listOf(credential(emptyList())), ExportFormat.JSON)).single()
        assertFalse(dto.containsKey("passkeys"))
    }

    @Test fun passkey_dto_shape() {
        val dto = dtos(exporter.serializeForSync(listOf(credential(listOf(passkey(PasskeyOrigin.App("com.example", "ab".repeat(32)))))), ExportFormat.JSON)).single()
        @Suppress("UNCHECKED_CAST") val pk = (dto["passkeys"] as List<Map<String, Any>>).single()
        assertEquals("github.com", pk["rp_id"])
        assertEquals("GitHub", pk["rp_name"])
        assertEquals("rouf", pk["user_name"])
        assertEquals("Rouf Syed", pk["user_display_name"])
        assertEquals(Base64Url.encode(ByteArray(16) { 7 }), pk["user_handle"])
        assertEquals(Base64Url.encode(ByteArray(32) { 9 }), pk["credential_id"])
        assertTrue((pk["private_key_pem"] as String).startsWith("-----BEGIN PRIVATE KEY-----"))
        assertEquals(Base64Url.encode(ByteArray(32) { 3 }), pk["prf_secret"])
        @Suppress("UNCHECKED_CAST") val origin = pk["origin"] as Map<String, Any>
        assertEquals("app", origin["type"])
        assertEquals("com.example", origin["package"])
        assertEquals("ab".repeat(32), origin["cert_sha256"])
        assertEquals(1_700_000_000_000.0, pk["created_at"])
    }

    @Test fun round_trip_restores_every_field() = runBlocking {
        val bytes = exporter.serializeForSync(listOf(credential(listOf(passkey()))), ExportFormat.JSON)
        val file = tmp.newFile("backup.json").also { it.writeBytes(bytes) }
        val parsed = (importer.parse(file.path) as AppResult.Success).data
        val cred = parsed.credentials.single()
        assertTrue("passkeys array must never become a custom field", cred.customFields.isEmpty())
        val pk = cred.passkeys.single()
        assertEquals("cred-1", pk.credentialId)
        assertEquals("", pk.id)
        assertEquals("github.com", pk.rpId)
        assertEquals("GitHub", pk.rpName)
        assertEquals("rouf", pk.userName)
        assertEquals("Rouf Syed", pk.userDisplayName)
        assertArrayEquals(ByteArray(16) { 7 }, pk.userHandle)
        assertArrayEquals(ByteArray(32) { 9 }, pk.webauthnCredentialId)
        assertArrayEquals(Es256.privateKeyPkcs8(keyPair), pk.privateKeyPkcs8)
        assertArrayEquals(ByteArray(32) { 3 }, pk.prfSecret)
        assertEquals(PasskeyOrigin.Browser("https://github.com"), pk.origin)
        assertEquals(1_700_000_000_000L, pk.createdAt)
        assertEquals(1_700_000_500_000L, pk.lastUsedAt)
        assertEquals(1_700_000_600_000L, pk.backedUpAt)
        assertEquals(1, parsed.passkeyCount)
    }

    @Test fun malformed_passkey_is_dropped_but_credential_and_siblings_import() = runBlocking {
        val good = gson.toJson(
            mapOf(
                "rp_id" to "example.com", "user_handle" to "AQID", "credential_id" to "BAUG",
                "private_key_pem" to Es256.toPem(Es256.privateKeyPkcs8(keyPair)),
            ),
        )
        val json = """[{"id":"c","title":"Example","passkeys":[{"rp_id":"example.com","private_key_pem":"not a key"},$good]}]"""
        val file = tmp.newFile("bad.json").also { it.writeText(json) }
        val cred = (importer.parse(file.path) as AppResult.Success).data.credentials.single()
        assertEquals(1, cred.passkeys.size)
        val pk = cred.passkeys.single()
        assertEquals("example.com", pk.rpName)                    // defaults to rp_id
        assertEquals(PasskeyOrigin.Browser("https://example.com"), pk.origin)   // no binding: browser only
        assertNotNull(pk.backedUpAt)                             // came from a backup
        assertTrue(cred.customFields.isEmpty())
    }

    @Test fun importer_accepts_every_supported_key_type_and_rejects_others() = runBlocking {
        fun entry(pem: String, id: String) = mapOf(
            "rp_id" to "example.com", "user_handle" to "AQID", "credential_id" to id, "private_key_pem" to pem,
        )
        val p384 = java.security.KeyPairGenerator.getInstance("EC")
            .apply { initialize(java.security.spec.ECGenParameterSpec("secp384r1")) }.generateKeyPair()
        val entries = listOf(
            entry(Pkcs8Pem.encode(PasskeyKeys.generate(PasskeyAlgorithm.ES256).privateKeyPkcs8), "AQ"),
            entry(Pkcs8Pem.encode(PasskeyKeys.generate(PasskeyAlgorithm.EDDSA).privateKeyPkcs8), "Ag"),
            entry(Pkcs8Pem.encode(PasskeyKeys.generate(PasskeyAlgorithm.RS256).privateKeyPkcs8), "Aw"),
            entry(Pkcs8Pem.encode(p384.private.encoded), "BA"),
        )
        val json = gson.toJson(listOf(mapOf("id" to "c", "title" to "Example", "passkeys" to entries)))
        val file = tmp.newFile("mixed.json").also { it.writeText(json) }
        val cred = (importer.parse(file.path) as AppResult.Success).data.credentials.single()
        assertEquals("the P-384 key is dropped, the three supported types survive", 3, cred.passkeys.size)
        assertEquals(
            listOf(PasskeyAlgorithm.ES256, PasskeyAlgorithm.EDDSA, PasskeyAlgorithm.RS256),
            cred.passkeys.map { PasskeyAlgorithm.ofPkcs8(it.privateKeyPkcs8) },
        )
    }

    @Test fun plain_file_write_omits_nothing_the_dto_has_but_csv_never_carries_passkeys() = runBlocking {
        val csv = File(tmp.root, "out.csv")
        exporter.export(listOf(credential(listOf(passkey()))), ExportFormat.CSV, csv.path)
        assertFalse(csv.readText().contains("BEGIN PRIVATE KEY"))
    }
}

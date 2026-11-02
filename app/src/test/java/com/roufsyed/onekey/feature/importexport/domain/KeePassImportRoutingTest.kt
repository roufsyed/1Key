package com.roufsyed.onekey.feature.importexport.domain

import com.google.gson.Gson
import com.roufsyed.onekey.core.domain.model.AppResult
import com.roufsyed.onekey.core.domain.model.Credential
import com.roufsyed.onekey.core.domain.model.CredentialType
import com.roufsyed.onekey.core.domain.repository.FakePasskeyRepository
import com.roufsyed.onekey.core.domain.usecase.ImportVaultUseCase
import com.roufsyed.onekey.core.domain.usecase.InMemoryCredentialRepository
import com.roufsyed.onekey.core.domain.webauthn.Base64Url
import com.roufsyed.onekey.core.domain.webauthn.PasskeyAlgorithm
import com.roufsyed.onekey.core.domain.webauthn.PasskeyKeys
import com.roufsyed.onekey.core.domain.webauthn.Pkcs8Pem
import com.roufsyed.onekey.core.security.CryptoManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Base64

/**
 * The importer must send each KeePass file to the right parser before the
 * generic 1Key paths see it, and the result must flow through the normal
 * import pipeline, which attaches an imported passkey to an existing entry
 * with the same title and username (KEEPASS_IMPORT_PLAN K12).
 */
class KeePassImportRoutingTest {

    @get:Rule val tmp = TemporaryFolder()

    private val importer = VaultImporterImpl(CryptoManager())
    private val gson = Gson()
    private val pem = Pkcs8Pem.encode(PasskeyKeys.generate(PasskeyAlgorithm.ES256).privateKeyPkcs8)

    private fun passkeyFile() = linkedMapOf(
        "credentialId" to Base64Url.encode(ByteArray(32) { 1 }),
        "privateKey" to pem,
        "relyingParty" to "github.com",
        "url" to "https://github.com",
        "userHandle" to Base64Url.encode(ByteArray(8) { 2 }),
        "username" to "rouf",
    )

    private fun keePassXml(title: String, username: String) = """<?xml version="1.0" encoding="UTF-8"?>
        <KeePassFile><Meta><RecycleBinUUID>AAAAAAAAAAAAAAAAAAAAAA==</RecycleBinUUID></Meta><Root><Group><UUID>${Base64.getEncoder().encodeToString(ByteArray(16))}</UUID><Name>Root</Name>
        <Entry><UUID>${Base64.getEncoder().encodeToString(ByteArray(16) { 1 })}</UUID>
          <String><Key>Title</Key><Value>$title</Value></String>
          <String><Key>UserName</Key><Value>$username</Value></String>
          <String><Key>KPEX_PASSKEY_RELYING_PARTY</Key><Value>github.com</Value></String>
          <String><Key>KPEX_PASSKEY_USERNAME</Key><Value>$username</Value></String>
          <String><Key>KPEX_PASSKEY_CREDENTIAL_ID</Key><Value ProtectInMemory="True">${Base64Url.encode(ByteArray(32) { 1 })}</Value></String>
          <String><Key>KPEX_PASSKEY_USER_HANDLE</Key><Value ProtectInMemory="True">${Base64Url.encode(ByteArray(8) { 2 })}</Value></String>
          <String><Key>KPEX_PASSKEY_PRIVATE_KEY_PEM</Key><Value ProtectInMemory="True">$pem</Value></String>
        </Entry></Group></Root></KeePassFile>""".trimIndent()

    @Test fun passkey_file_is_routed_to_its_parser_not_filed_as_custom_fields() = runBlocking {
        val file = tmp.newFile("GitHub.passkey").also { it.writeText(gson.toJson(passkeyFile())) }
        val parsed = (importer.parse(file.path) as AppResult.Success).data
        val c = parsed.credentials.single()
        assertEquals(1, c.passkeys.size)
        assertTrue("the private key must never appear as a field", c.customFields.isEmpty())
        val array = tmp.newFile("two.passkey").also { it.writeText(gson.toJson(listOf(passkeyFile(), passkeyFile()))) }
        assertEquals(2, (importer.parse(array.path) as AppResult.Success).data.credentials.size)
    }

    @Test fun xml_is_routed_to_the_keepass_importer_even_with_a_byte_order_mark() = runBlocking {
        val file = tmp.newFile("db.xml").also { it.writeText("﻿" + keePassXml("GitHub (Passkey)", "rouf")) }
        val parsed = (importer.parse(file.path) as AppResult.Success).data
        assertEquals("GitHub", parsed.credentials.single().title)
        assertEquals(1, parsed.passkeyCount)
    }

    @Test fun onekey_json_backups_still_take_the_generic_path() = runBlocking {
        val backup = """[{"id":"c1","title":"GitHub","username":"rouf","password":"pw","passkeys":[]}]"""
        val file = tmp.newFile("backup.json").also { it.writeText(backup) }
        val parsed = (importer.parse(file.path) as AppResult.Success).data
        assertEquals("GitHub", parsed.credentials.single().title)
        assertEquals("pw", parsed.credentials.single().password)
    }

    @Test fun imported_keepass_passkey_attaches_to_the_existing_entry_for_that_site() = runBlocking {
        val existing = Credential(
            id = "existing", title = "GitHub", username = "rouf", password = "pw", url = "https://github.com", notes = "",
            otpParams = null, tags = emptyList(), customFields = emptyList(), createdAt = 1L, updatedAt = 1L, type = CredentialType.LOGIN,
        )
        val repo = InMemoryCredentialRepository(listOf(existing))
        val passkeys = FakePasskeyRepository()
        val useCase = ImportVaultUseCase(repo, importer, passkeys)
        val file = tmp.newFile("db.xml").also { it.writeText(keePassXml("GitHub (Passkey)", "rouf")) }
        val parsed = (importer.parse(file.path) as AppResult.Success).data
        val result = (useCase.saveImport(parsed, ImportFieldOptions()) as AppResult.Success).data
        assertEquals(1, result.importedPasskeys)
        assertEquals("existing", passkeys.all.single().credentialId)
        assertEquals(1, repo.active().size)
    }
}

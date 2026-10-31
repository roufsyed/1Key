package com.roufsyed.onekey.core.domain.usecase

import com.roufsyed.onekey.core.domain.model.AppResult
import com.roufsyed.onekey.core.domain.model.Credential
import com.roufsyed.onekey.core.domain.model.CredentialType
import com.roufsyed.onekey.core.domain.model.Passkey
import com.roufsyed.onekey.core.domain.model.PasskeyOrigin
import com.roufsyed.onekey.core.domain.repository.FakePasskeyRepository
import com.roufsyed.onekey.feature.importexport.domain.ConflictResolution
import com.roufsyed.onekey.feature.importexport.domain.EncryptedParseResult
import com.roufsyed.onekey.feature.importexport.domain.ImportFieldOptions
import com.roufsyed.onekey.feature.importexport.domain.ParsedImport
import com.roufsyed.onekey.feature.importexport.domain.VaultImporter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Passkeys through [ImportVaultUseCase] (plan section 9.3): they follow their
 * credential's final id, arrive even when the credential row is skipped as a
 * duplicate id, are de-duplicated by WebAuthn credential id, and never raise a
 * conflict on their own.
 */
class ImportVaultUseCasePasskeyTest {

    private val options = ImportFieldOptions()

    private fun passkey(credentialId: String, webauthnId: Byte, rpId: String = "github.com") = Passkey(
        id = "",
        credentialId = credentialId,
        rpId = rpId,
        rpName = "GitHub",
        userName = "rouf",
        userDisplayName = "Rouf",
        userHandle = byteArrayOf(1),
        webauthnCredentialId = ByteArray(32) { webauthnId },
        privateKeyPkcs8 = ByteArray(8) { 5 },
        prfSecret = null,
        origin = PasskeyOrigin.Browser("https://github.com"),
        createdAt = 1L,
        lastUsedAt = null,
        backedUpAt = 2L,
    )

    private fun login(id: String, title: String = "GitHub", username: String = "rouf", passkeys: List<Passkey> = emptyList()) = Credential(
        id = id, title = title, username = username, password = "pw", url = "https://github.com", notes = "",
        otpParams = null, tags = emptyList(), customFields = emptyList(), createdAt = 1L, updatedAt = 1L,
        type = CredentialType.LOGIN, passkeys = passkeys,
    )

    @Test fun new_item_passkeys_are_saved_under_its_id() = runBlocking {
        val repo = InMemoryCredentialRepository()
        val passkeys = FakePasskeyRepository()
        val useCase = ImportVaultUseCase(repo, UnusedImporter, passkeys)

        val result = useCase.saveImport(ParsedImport(listOf(login("new-1", passkeys = listOf(passkey("new-1", 1)))), emptyList()), options)
        assertEquals(1, (result as AppResult.Success).data.importedPasskeys)
        assertEquals("new-1", passkeys.all.single().credentialId)
        assertTrue(passkeys.all.single().id.isNotBlank())
    }

    @Test fun duplicate_id_credential_still_delivers_its_passkeys_to_the_existing_row() = runBlocking {
        val repo = InMemoryCredentialRepository(listOf(login("same")))
        val passkeys = FakePasskeyRepository()
        val useCase = ImportVaultUseCase(repo, UnusedImporter, passkeys)

        val plan = (useCase.planImport(ParsedImport(listOf(login("same", passkeys = listOf(passkey("same", 1)))), emptyList()), options) as AppResult.Success).data
        assertEquals(1, plan.skipped.size)
        assertEquals(1, plan.passkeyAttachments.size)
        val result = (useCase.applyPlan(plan, ConflictResolution.MERGE) as AppResult.Success).data
        assertEquals(1, result.importedPasskeys)
        assertEquals("same", passkeys.all.single().credentialId)
    }

    @Test fun merge_rekeys_passkeys_to_the_existing_credential_and_passkeys_never_conflict() = runBlocking {
        val repo = InMemoryCredentialRepository(listOf(login("existing")))
        val passkeys = FakePasskeyRepository()
        val useCase = ImportVaultUseCase(repo, UnusedImporter, passkeys)

        val incoming = login("other-device-id", passkeys = listOf(passkey("other-device-id", 1)))
        val plan = (useCase.planImport(ParsedImport(listOf(incoming), emptyList()), options) as AppResult.Success).data
        assertTrue("same title and username with no clashing fields is an auto merge", plan.conflicts.isEmpty())
        assertEquals(1, plan.autoMerges.size)
        useCase.applyPlan(plan, ConflictResolution.MERGE)
        assertEquals("existing", passkeys.all.single().credentialId)
    }

    @Test fun add_as_separate_mints_an_id_the_passkeys_follow() = runBlocking {
        val repo = InMemoryCredentialRepository(listOf(login("existing").copy(password = "different")))
        val passkeys = FakePasskeyRepository()
        val useCase = ImportVaultUseCase(repo, UnusedImporter, passkeys)

        val incoming = login("other", passkeys = listOf(passkey("other", 1)))
        val plan = (useCase.planImport(ParsedImport(listOf(incoming), emptyList()), options) as AppResult.Success).data
        assertEquals(1, plan.conflicts.size)
        useCase.applyPlan(plan, ConflictResolution.ADD_AS_SEPARATE)
        val separateId = repo.active().first { it.id != "existing" }.id
        assertTrue(separateId.isNotBlank() && separateId != "other")
        assertEquals(separateId, passkeys.all.single().credentialId)
    }

    @Test fun passkeys_already_present_or_repeated_in_the_batch_are_skipped() = runBlocking {
        val repo = InMemoryCredentialRepository()
        val passkeys = FakePasskeyRepository()
        passkeys.save(passkey("elsewhere", 1).copyWith(id = "already"))
        val useCase = ImportVaultUseCase(repo, UnusedImporter, passkeys)

        val parsed = ParsedImport(
            listOf(
                login("a", passkeys = listOf(passkey("a", 1), passkey("a", 2))),
                login("b", title = "Other", passkeys = listOf(passkey("b", 2))),
            ),
            emptyList(),
        )
        val result = (useCase.saveImport(parsed, options) as AppResult.Success).data
        assertEquals("only the passkey with a new WebAuthn id is written", 1, result.importedPasskeys)
        assertEquals(2, passkeys.all.size)
    }
}

private object UnusedImporter : VaultImporter {
    override suspend fun isEncrypted(path: String): Boolean = error("unused")
    override suspend fun parse(path: String): AppResult<ParsedImport> = error("unused")
    override suspend fun parseEncrypted(path: String, password: CharArray, secretKey: ByteArray?): EncryptedParseResult = error("unused")
}

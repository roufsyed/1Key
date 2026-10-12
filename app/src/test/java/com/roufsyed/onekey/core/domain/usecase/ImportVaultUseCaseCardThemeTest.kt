package com.roufsyed.onekey.core.domain.usecase

import com.roufsyed.onekey.core.domain.model.AppResult
import com.roufsyed.onekey.core.domain.model.CardTheme
import com.roufsyed.onekey.core.domain.model.Credential
import com.roufsyed.onekey.core.domain.model.CredentialType
import com.roufsyed.onekey.feature.importexport.domain.ConflictResolution
import com.roufsyed.onekey.feature.importexport.domain.EncryptedParseResult
import com.roufsyed.onekey.feature.importexport.domain.ImportFieldOptions
import com.roufsyed.onekey.feature.importexport.domain.ImportPlan
import com.roufsyed.onekey.feature.importexport.domain.ParsedImport
import com.roufsyed.onekey.feature.importexport.domain.VaultImporter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks how the card theme (DB v16) flows through [ImportVaultUseCase.planImport]
 * and [ImportVaultUseCase.applyPlan]:
 *
 *  - a theme difference alone never raises a conflict (it is a UI preference,
 *    like `isFavorite`, which is likewise excluded from `conflictingFields`);
 *  - on every merge the EXISTING row's theme wins, default included;
 *  - new items and ADD_AS_SEPARATE rows keep the incoming theme.
 */
class ImportVaultUseCaseCardThemeTest {

    private val options = ImportFieldOptions()

    @Test fun new_item_keeps_incoming_theme() = runBlocking {
        val repo = InMemoryCredentialRepository()
        val useCase = ImportVaultUseCase(repo, UnusedImporter)

        val plan = planOrThrow(useCase, parsed(card(id = "", theme = CardTheme.CORAL)))
        assertEquals(1, plan.newItems.size)
        useCase.applyPlan(plan, ConflictResolution.MERGE)

        assertEquals(CardTheme.CORAL, repo.active().single().cardTheme)
    }

    @Test fun theme_difference_alone_is_not_a_conflict_and_existing_theme_wins() = runBlocking {
        val existing = card(id = "e1", theme = CardTheme.MIDNIGHT)
        val repo = InMemoryCredentialRepository(listOf(existing))
        val useCase = ImportVaultUseCase(repo, UnusedImporter)

        val plan = planOrThrow(useCase, parsed(card(id = "", theme = CardTheme.CORAL)))
        assertEquals(0, plan.conflicts.size)
        assertEquals(1, plan.autoMerges.size)
        useCase.applyPlan(plan, ConflictResolution.MERGE)

        assertEquals(CardTheme.MIDNIGHT, repo.rows.getValue("e1").cardTheme)
    }

    @Test fun merge_on_conflict_keeps_existing_theme() = runBlocking {
        val existing = card(id = "e1", theme = CardTheme.MIDNIGHT, password = "old")
        val repo = InMemoryCredentialRepository(listOf(existing))
        val useCase = ImportVaultUseCase(repo, UnusedImporter)

        val plan = planOrThrow(useCase, parsed(card(id = "", theme = CardTheme.CORAL, password = "new")))
        assertEquals(1, plan.conflicts.size)
        useCase.applyPlan(plan, ConflictResolution.MERGE)

        val merged = repo.rows.getValue("e1")
        assertEquals("old", merged.password)
        assertEquals(CardTheme.MIDNIGHT, merged.cardTheme)
    }

    @Test fun add_as_separate_keeps_incoming_theme_and_leaves_existing_untouched() = runBlocking {
        val existing = card(id = "e1", theme = CardTheme.MIDNIGHT, password = "old")
        val repo = InMemoryCredentialRepository(listOf(existing))
        val useCase = ImportVaultUseCase(repo, UnusedImporter)

        val plan = planOrThrow(useCase, parsed(card(id = "", theme = CardTheme.CORAL, password = "new")))
        useCase.applyPlan(plan, ConflictResolution.ADD_AS_SEPARATE)

        assertEquals(CardTheme.MIDNIGHT, repo.rows.getValue("e1").cardTheme)
        val added = repo.active().single { it.id != "e1" }
        assertEquals(CardTheme.CORAL, added.cardTheme)
        assertEquals("new", added.password)
    }

    @Test fun bin_match_auto_merge_restores_row_and_keeps_existing_default_theme() = runBlocking {
        val binRow = card(id = "b1", theme = CardTheme.GRAPHITE, deletedAt = 5L)
        val repo = InMemoryCredentialRepository(listOf(binRow))
        val useCase = ImportVaultUseCase(repo, UnusedImporter)

        val plan = planOrThrow(useCase, parsed(card(id = "", theme = CardTheme.CORAL)))
        assertEquals(1, plan.autoMerges.size)
        assertTrue(plan.autoMerges.single().restoreFromBin)
        val result = useCase.applyPlan(plan, ConflictResolution.MERGE)

        assertEquals(1, (result as AppResult.Success).data.restoredFromBin)
        val restored = repo.rows.getValue("b1")
        assertNull(restored.deletedAt)
        assertEquals(CardTheme.GRAPHITE, restored.cardTheme)
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private suspend fun planOrThrow(useCase: ImportVaultUseCase, parsed: ParsedImport): ImportPlan =
        when (val r = useCase.planImport(parsed, options)) {
            is AppResult.Success -> r.data
            is AppResult.Error -> throw AssertionError("planImport failed: ${r.message}", r.exception)
        }

    private fun parsed(vararg credentials: Credential) = ParsedImport(credentials.toList(), emptyList())

    private fun card(
        id: String,
        theme: CardTheme,
        title: String = "Visa",
        username: String = "alice",
        password: String = "",
        deletedAt: Long? = null,
    ) = Credential(
        id = id, title = title, username = username, password = password, url = "", notes = "",
        otpParams = null, tags = emptyList(), customFields = emptyList(),
        createdAt = 1L, updatedAt = 2L, type = CredentialType.CREDIT_CARD,
        deletedAt = deletedAt, cardTheme = theme,
    )

    /** planImport and applyPlan never touch the importer; parsing is tested elsewhere. */
    private object UnusedImporter : VaultImporter {
        override suspend fun isEncrypted(path: String): Boolean = error("unused")
        override suspend fun parse(path: String): AppResult<ParsedImport> = error("unused")
        override suspend fun parseEncrypted(path: String, password: CharArray, secretKey: ByteArray?): EncryptedParseResult =
            error("unused")
    }
}

package com.roufsyed.onekey.core.domain.usecase

import com.roufsyed.onekey.core.domain.model.AppResult
import com.roufsyed.onekey.core.domain.model.CardTheme
import com.roufsyed.onekey.core.domain.model.Credential
import com.roufsyed.onekey.core.domain.model.CredentialHistoryEntry
import com.roufsyed.onekey.core.domain.model.CredentialType
import com.roufsyed.onekey.core.domain.repository.CredentialHistoryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behavioural locks for [RestoreFromRecycleBinUseCase.mergeInto], focused on the
 * card theme rule introduced with DB v16: the EXISTING row's theme always wins,
 * even when it is the neutral default. The alternative (treat Graphite as
 * "unset" and take the bin item's colour) would resurrect a colour the user had
 * deliberately reverted, so it is locked out here.
 */
class RestoreFromRecycleBinUseCaseTest {

    private val history = RecordingHistoryRepository()

    @Test fun mergeInto_keeps_existing_non_default_theme_over_bin_item_theme() = runBlocking {
        val existing = card(id = "e1", theme = CardTheme.MIDNIGHT)
        val binItem = card(id = "b1", theme = CardTheme.CORAL, deletedAt = 5L)
        val repo = InMemoryCredentialRepository(listOf(existing, binItem))
        val useCase = RestoreFromRecycleBinUseCase(repo, history)

        val result = useCase.mergeInto(existing, binItem)

        assertTrue(result is AppResult.Success)
        val merged = repo.saved.single()
        assertEquals("e1", merged.id)
        assertEquals(CardTheme.MIDNIGHT, merged.cardTheme)
        assertNull(merged.deletedAt)
        assertEquals(listOf("b1"), repo.purged)
        assertEquals(listOf("b1"), history.deleted)
    }

    @Test fun mergeInto_keeps_existing_default_theme_even_when_bin_item_is_decorated() = runBlocking {
        val existing = card(id = "e1", theme = CardTheme.GRAPHITE)
        val binItem = card(id = "b1", theme = CardTheme.CORAL, deletedAt = 5L)
        val repo = InMemoryCredentialRepository(listOf(existing, binItem))
        val useCase = RestoreFromRecycleBinUseCase(repo, history)

        useCase.mergeInto(existing, binItem)

        assertEquals(CardTheme.GRAPHITE, repo.saved.single().cardTheme)
    }

    @Test fun mergeInto_still_fills_blank_fields_from_bin_item() = runBlocking {
        // Regression guard: the theme rule must not disturb the documented
        // "existing wins where both have a value" field merge.
        val existing = card(id = "e1", theme = CardTheme.MIDNIGHT, username = "", password = "keep")
        val binItem = card(id = "b1", theme = CardTheme.CORAL, username = "alice", password = "drop", deletedAt = 5L)
        val repo = InMemoryCredentialRepository(listOf(existing, binItem))
        val useCase = RestoreFromRecycleBinUseCase(repo, history)

        useCase.mergeInto(existing, binItem)

        val merged = repo.saved.single()
        assertEquals("alice", merged.username)
        assertEquals("keep", merged.password)
        assertEquals(CardTheme.MIDNIGHT, merged.cardTheme)
    }

    @Test fun findConflict_matches_on_trimmed_title_and_username() = runBlocking {
        val existing = card(id = "e1", title = "Visa", username = "alice")
        val binItem = card(id = "b1", title = " Visa ", username = "alice ", deletedAt = 5L)
        val repo = InMemoryCredentialRepository(listOf(existing, binItem))
        val useCase = RestoreFromRecycleBinUseCase(repo, history)

        assertEquals(existing, useCase.findConflict(binItem))
        assertNull(useCase.findConflict(card(id = "b2", title = "Other", deletedAt = 5L)))
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private fun card(
        id: String,
        theme: CardTheme = CardTheme.GRAPHITE,
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

    private class RecordingHistoryRepository : CredentialHistoryRepository {
        val deleted = mutableListOf<String>()
        override suspend fun snapshotCredential(credential: Credential): AppResult<Unit> = AppResult.Success(Unit)
        override fun observeHistory(credentialId: String): Flow<List<CredentialHistoryEntry>> = flowOf(emptyList())
        override suspend fun deleteForCredential(credentialId: String): AppResult<Unit> {
            deleted += credentialId
            return AppResult.Success(Unit)
        }
        override suspend fun deleteAll(): AppResult<Unit> = AppResult.Success(Unit)
    }
}

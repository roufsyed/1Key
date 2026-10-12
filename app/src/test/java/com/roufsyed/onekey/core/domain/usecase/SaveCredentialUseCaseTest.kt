package com.roufsyed.onekey.core.domain.usecase

import com.roufsyed.onekey.core.domain.model.AppResult
import com.roufsyed.onekey.core.domain.model.CardTheme
import com.roufsyed.onekey.core.domain.model.Credential
import com.roufsyed.onekey.core.domain.model.CredentialType
import com.roufsyed.onekey.core.domain.model.CustomField
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SaveCredentialUseCase] validates title and field count only. The card theme
 * is a typed enum that cannot hold an invalid value, and imports bypass this use
 * case entirely, so no theme rule lives here: a theme on ANY type passes through
 * untouched. Locked so a future "coerce non-card types to default" idea is a
 * conscious decision, not an accident.
 */
class SaveCredentialUseCaseTest {

    @Test fun passes_card_theme_through_unchanged_for_any_type() = runBlocking {
        val repo = InMemoryCredentialRepository()
        val useCase = SaveCredentialUseCase(repo)

        val result = useCase(credential(type = CredentialType.LOGIN, theme = CardTheme.MIDNIGHT))

        assertTrue(result is AppResult.Success)
        val saved = repo.saved.single()
        assertEquals(CardTheme.MIDNIGHT, saved.cardTheme)
        assertEquals(CredentialType.LOGIN, saved.type)
    }

    @Test fun rejects_blank_title_without_touching_the_repository() = runBlocking {
        val repo = InMemoryCredentialRepository()
        val useCase = SaveCredentialUseCase(repo)

        val result = useCase(credential(title = "   "))

        assertTrue(result is AppResult.Error)
        assertTrue(repo.saved.isEmpty())
    }

    @Test fun rejects_more_than_max_custom_fields() = runBlocking {
        val repo = InMemoryCredentialRepository()
        val useCase = SaveCredentialUseCase(repo)
        val tooMany = List(CustomField.MAX_FIELDS + 1) { CustomField("k$it", "v", false) }

        val result = useCase(credential(customFields = tooMany))

        assertTrue(result is AppResult.Error)
        assertTrue(repo.saved.isEmpty())
    }

    private fun credential(
        title: String = "Card",
        type: CredentialType = CredentialType.CREDIT_CARD,
        theme: CardTheme = CardTheme.GRAPHITE,
        customFields: List<CustomField> = emptyList(),
    ) = Credential(
        id = "id", title = title, username = "", password = "", url = "", notes = "",
        otpParams = null, tags = emptyList(), customFields = customFields,
        createdAt = 1L, updatedAt = 2L, type = type, cardTheme = theme,
    )
}

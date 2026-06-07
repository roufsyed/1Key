package com.onekey.core.domain.usecase

import com.onekey.core.domain.model.AppResult
import com.onekey.core.domain.model.Credential
import com.onekey.core.domain.repository.CredentialHistoryRepository
import com.onekey.core.domain.repository.CredentialRepository
import javax.inject.Inject

/**
 * Conflict-aware restore from the recycle bin. The two-step API lets callers detect a
 * collision with an existing active credential (same trimmed title + username) and
 * surface the user's choice - merge into the existing item, or restore as a separate
 * row - before mutating anything. Mirrors the import flow's merge UX.
 */
class RestoreFromRecycleBinUseCase @Inject constructor(
    private val credentialRepository: CredentialRepository,
    private val historyRepository: CredentialHistoryRepository,
) {
    /** Returns the active credential that collides with [binItem], or null if none. */
    suspend fun findConflict(binItem: Credential): Credential? {
        val activeResult = credentialRepository.getAllCredentials()
        val active = (activeResult as? AppResult.Success)?.data ?: return null
        val key = binItem.title.trim() to binItem.username.trim()
        return active.firstOrNull { it.title.trim() to it.username.trim() == key }
    }

    /** Plain restore - caller has confirmed no conflict, or explicitly chose "keep both". */
    suspend fun restore(id: String): AppResult<Unit> = credentialRepository.restoreCredential(id)

    /**
     * Merge the bin item's fields into [existing] (existing wins where both have a value),
     * save it, then permanently remove the bin item along with its history. Net result:
     * one richer active row, zero duplicates.
     */
    suspend fun mergeInto(existing: Credential, binItem: Credential): AppResult<Unit> {
        val merged = merge(existing, binItem)
        val saveResult = credentialRepository.saveCredential(merged)
        if (saveResult is AppResult.Error) return saveResult
        historyRepository.deleteForCredential(binItem.id)
        return credentialRepository.purgeFromRecycleBin(binItem.id)
    }

    private fun merge(existing: Credential, binItem: Credential): Credential = existing.copy(
        username = existing.username.takeIf { it.isNotBlank() } ?: binItem.username,
        password = existing.password.takeIf { it.isNotBlank() } ?: binItem.password,
        url = existing.url.takeIf { it.isNotBlank() } ?: binItem.url,
        notes = existing.notes.takeIf { it.isNotBlank() } ?: binItem.notes,
        // Existing OTP enrolment wins as a unit - params are bound to their secret.
        otpParams = existing.otpParams ?: binItem.otpParams,
        tags = if (existing.tags.isNotEmpty()) existing.tags else binItem.tags,
        customFields = if (existing.customFields.isNotEmpty()) existing.customFields else binItem.customFields,
        isFavorite = existing.isFavorite || binItem.isFavorite,
        deletedAt = null,
    )
}

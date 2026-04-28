package com.onekey.core.domain.usecase

import com.onekey.core.domain.model.AppResult
import com.onekey.core.domain.repository.CredentialHistoryRepository
import com.onekey.core.domain.repository.CredentialRepository
import javax.inject.Inject

/**
 * Permanently removes every credential currently in the recycle bin and their history.
 * Returns the number of credentials purged.
 */
class EmptyRecycleBinUseCase @Inject constructor(
    private val credentialRepository: CredentialRepository,
    private val historyRepository: CredentialHistoryRepository,
) {
    suspend operator fun invoke(): AppResult<Int> {
        val binResult = credentialRepository.getAllInRecycleBin()
        if (binResult is AppResult.Error) return binResult
        val items = (binResult as AppResult.Success).data
        items.forEach { historyRepository.deleteForCredential(it.id) }
        return credentialRepository.emptyRecycleBin()
    }
}

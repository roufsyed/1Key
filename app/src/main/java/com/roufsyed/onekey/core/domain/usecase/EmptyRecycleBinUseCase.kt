package com.roufsyed.onekey.core.domain.usecase

import com.roufsyed.onekey.core.domain.model.AppResult
import com.roufsyed.onekey.core.domain.repository.CredentialHistoryRepository
import com.roufsyed.onekey.core.domain.repository.CredentialRepository
import com.roufsyed.onekey.core.domain.repository.PasskeyRepository
import javax.inject.Inject

/**
 * Permanently removes every credential currently in the recycle bin along with
 * their history and passkeys. Returns the number of credentials purged.
 */
class EmptyRecycleBinUseCase @Inject constructor(
    private val credentialRepository: CredentialRepository,
    private val historyRepository: CredentialHistoryRepository,
    private val passkeyRepository: PasskeyRepository,
) {
    suspend operator fun invoke(): AppResult<Int> {
        val binResult = credentialRepository.getAllInRecycleBin()
        if (binResult is AppResult.Error) return binResult
        val items = (binResult as AppResult.Success).data
        items.forEach {
            historyRepository.deleteForCredential(it.id)
            passkeyRepository.deleteForCredential(it.id)
        }
        return credentialRepository.emptyRecycleBin()
    }
}

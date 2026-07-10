package com.roufsyed.onekey.core.domain.usecase

import com.roufsyed.onekey.core.domain.model.AppResult
import com.roufsyed.onekey.core.domain.repository.CredentialHistoryRepository
import com.roufsyed.onekey.core.domain.repository.CredentialRepository
import javax.inject.Inject

/**
 * Permanently removes a credential, bypassing the recycle bin. Used by the
 * "Delete now" path on the delete confirm dialog and by the recycle bin's
 * per-item purge action. History is wiped along with the credential.
 */
class HardDeleteCredentialUseCase @Inject constructor(
    private val credentialRepository: CredentialRepository,
    private val historyRepository: CredentialHistoryRepository,
) {
    suspend operator fun invoke(id: String): AppResult<Unit> {
        historyRepository.deleteForCredential(id)
        return credentialRepository.hardDeleteCredential(id)
    }
}

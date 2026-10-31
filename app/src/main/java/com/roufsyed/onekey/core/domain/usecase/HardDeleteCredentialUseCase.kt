package com.roufsyed.onekey.core.domain.usecase

import com.roufsyed.onekey.core.domain.model.AppResult
import com.roufsyed.onekey.core.domain.repository.CredentialHistoryRepository
import com.roufsyed.onekey.core.domain.repository.CredentialRepository
import com.roufsyed.onekey.core.domain.repository.PasskeyRepository
import javax.inject.Inject

/**
 * Permanently removes a credential, bypassing the recycle bin. Used by the
 * "Delete now" path on the delete confirm dialog and by the recycle bin's
 * per-item purge action. History and passkeys are wiped along with the
 * credential (neither table has a foreign key; see PasskeyEntity for why).
 */
class HardDeleteCredentialUseCase @Inject constructor(
    private val credentialRepository: CredentialRepository,
    private val historyRepository: CredentialHistoryRepository,
    private val passkeyRepository: PasskeyRepository,
) {
    suspend operator fun invoke(id: String): AppResult<Unit> {
        historyRepository.deleteForCredential(id)
        passkeyRepository.deleteForCredential(id)
        return credentialRepository.hardDeleteCredential(id)
    }
}

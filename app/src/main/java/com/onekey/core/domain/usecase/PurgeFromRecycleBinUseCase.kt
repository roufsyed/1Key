package com.onekey.core.domain.usecase

import com.onekey.core.domain.model.AppResult
import com.onekey.core.domain.repository.CredentialHistoryRepository
import com.onekey.core.domain.repository.CredentialRepository
import javax.inject.Inject

/** Permanently removes a single recycle-bin item along with its history. */
class PurgeFromRecycleBinUseCase @Inject constructor(
    private val credentialRepository: CredentialRepository,
    private val historyRepository: CredentialHistoryRepository,
) {
    suspend operator fun invoke(id: String): AppResult<Unit> {
        historyRepository.deleteForCredential(id)
        return credentialRepository.purgeFromRecycleBin(id)
    }
}

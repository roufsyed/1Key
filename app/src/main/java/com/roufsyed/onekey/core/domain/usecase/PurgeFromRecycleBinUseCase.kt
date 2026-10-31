package com.roufsyed.onekey.core.domain.usecase

import com.roufsyed.onekey.core.domain.model.AppResult
import com.roufsyed.onekey.core.domain.repository.CredentialHistoryRepository
import com.roufsyed.onekey.core.domain.repository.CredentialRepository
import com.roufsyed.onekey.core.domain.repository.PasskeyRepository
import javax.inject.Inject

/** Permanently removes a single recycle-bin item along with its history and passkeys. */
class PurgeFromRecycleBinUseCase @Inject constructor(
    private val credentialRepository: CredentialRepository,
    private val historyRepository: CredentialHistoryRepository,
    private val passkeyRepository: PasskeyRepository,
) {
    suspend operator fun invoke(id: String): AppResult<Unit> {
        historyRepository.deleteForCredential(id)
        passkeyRepository.deleteForCredential(id)
        return credentialRepository.purgeFromRecycleBin(id)
    }
}

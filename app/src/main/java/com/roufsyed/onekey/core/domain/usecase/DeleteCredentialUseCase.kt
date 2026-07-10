package com.roufsyed.onekey.core.domain.usecase

import com.roufsyed.onekey.core.domain.model.AppResult
import com.roufsyed.onekey.core.domain.repository.CredentialRepository
import javax.inject.Inject

/**
 * Soft-deletes a credential - moves it to the recycle bin where it can be restored
 * within 30 days. History is preserved so a restored credential keeps its past versions.
 */
class DeleteCredentialUseCase @Inject constructor(
    private val credentialRepository: CredentialRepository,
) {
    suspend operator fun invoke(id: String): AppResult<Unit> =
        credentialRepository.deleteCredential(id)
}

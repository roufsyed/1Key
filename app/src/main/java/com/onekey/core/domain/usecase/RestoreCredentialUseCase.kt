package com.onekey.core.domain.usecase

import com.onekey.core.domain.model.AppResult
import com.onekey.core.domain.repository.CredentialRepository
import javax.inject.Inject

class RestoreCredentialUseCase @Inject constructor(
    private val credentialRepository: CredentialRepository,
) {
    suspend operator fun invoke(id: String): AppResult<Unit> =
        credentialRepository.restoreCredential(id)
}

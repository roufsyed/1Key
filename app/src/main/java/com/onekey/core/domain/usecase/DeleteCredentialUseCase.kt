package com.onekey.core.domain.usecase

import com.onekey.core.domain.model.AppResult
import com.onekey.core.domain.repository.CredentialRepository
import javax.inject.Inject

class DeleteCredentialUseCase @Inject constructor(
    private val repository: CredentialRepository,
) {
    suspend operator fun invoke(id: String): AppResult<Unit> = repository.deleteCredential(id)
}

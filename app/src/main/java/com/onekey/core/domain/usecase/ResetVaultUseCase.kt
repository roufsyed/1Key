package com.onekey.core.domain.usecase

import com.onekey.core.domain.model.AppResult
import com.onekey.core.domain.repository.AuthRepository
import com.onekey.core.domain.repository.CredentialRepository
import javax.inject.Inject

class ResetVaultUseCase @Inject constructor(
    private val authRepository: AuthRepository,
    private val credentialRepository: CredentialRepository,
) {
    suspend operator fun invoke(): AppResult<Unit> {
        val deleteResult = credentialRepository.deleteAllCredentials()
        if (deleteResult is AppResult.Error) return deleteResult
        return authRepository.resetVault()
    }
}

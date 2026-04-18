package com.onekey.core.domain.usecase

import com.onekey.core.domain.model.AppResult
import com.onekey.core.domain.repository.AuthRepository
import javax.inject.Inject

class SetupMasterPasswordUseCase @Inject constructor(
    private val repository: AuthRepository,
) {
    suspend operator fun invoke(password: CharArray): AppResult<Unit> {
        if (password.size < 8) {
            return AppResult.Error(IllegalArgumentException("Master password must be at least 8 characters"))
        }
        return repository.setupMasterPassword(password)
    }
}

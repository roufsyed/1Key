package com.onekey.core.domain.usecase

import com.onekey.core.domain.model.AppResult
import com.onekey.core.domain.repository.AuthRepository
import javax.inject.Inject

class UnlockVaultUseCase @Inject constructor(
    private val repository: AuthRepository,
) {
    suspend fun withPassword(password: CharArray): AppResult<Unit> =
        repository.unlockWithPassword(password)

    suspend fun withPin(pin: CharArray): AppResult<Unit> =
        repository.unlockWithPin(pin)
}

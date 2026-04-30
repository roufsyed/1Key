package com.onekey.core.domain.repository

import com.onekey.core.domain.model.AppResult
import kotlinx.coroutines.flow.Flow

interface AuthRepository {
    fun isSetupComplete(): Flow<Boolean>
    suspend fun setupMasterPassword(password: CharArray): AppResult<Unit>
    suspend fun unlockWithPassword(password: CharArray): AppResult<Unit>
    suspend fun unlockWithPin(pin: CharArray): AppResult<Unit>
    suspend fun unlockWithBiometric(): AppResult<Unit>
    /**
     * Verifies a PIN matches the stored PIN_VALID hash WITHOUT setting the vault key on
     * the [com.onekey.core.security.VaultKeyHolder]. For in-vault flows that need to confirm
     * "the user knows the current PIN" (e.g. changing the PIN) without re-emitting Unlocked
     * state to anyone observing AuthRepository.isUnlocked / AuthViewModel.state.
     */
    suspend fun verifyPin(pin: CharArray): AppResult<Unit>
    suspend fun setupPin(pin: CharArray): AppResult<Unit>
    suspend fun changePassword(oldPassword: CharArray, newPassword: CharArray): AppResult<Unit>
    suspend fun lock(): AppResult<Unit>
    fun isUnlocked(): Flow<Boolean>
    fun isPinSetup(): Flow<Boolean>
    suspend fun resetPin(): AppResult<Unit>
    suspend fun resetVault(): AppResult<Unit>
    suspend fun clearAll(): AppResult<Unit>
}

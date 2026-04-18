package com.onekey.core.domain.repository

import com.onekey.core.domain.model.AppResult
import kotlinx.coroutines.flow.Flow

interface AuthRepository {
    fun isSetupComplete(): Flow<Boolean>
    suspend fun setupMasterPassword(password: CharArray): AppResult<Unit>
    suspend fun unlockWithPassword(password: CharArray): AppResult<Unit>
    suspend fun unlockWithPin(pin: String): AppResult<Unit>
    suspend fun unlockWithBiometric(): AppResult<Unit>
    suspend fun setupPin(pin: String): AppResult<Unit>
    suspend fun changePassword(oldPassword: CharArray, newPassword: CharArray): AppResult<Unit>
    suspend fun lock(): AppResult<Unit>
    fun isUnlocked(): Flow<Boolean>
    fun isPinSetup(): Flow<Boolean>
    suspend fun clearAll(): AppResult<Unit>
}

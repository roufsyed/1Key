package com.onekey.core.domain.repository

import com.onekey.core.domain.model.LockTimeout
import kotlinx.coroutines.flow.Flow

interface AppPreferencesRepository {
    fun isDarkTheme(): Flow<Boolean>
    suspend fun setDarkTheme(dark: Boolean)
    fun isBiometricEnabled(): Flow<Boolean>
    suspend fun setBiometricEnabled(enabled: Boolean)
    fun isScreenshotsEnabled(): Flow<Boolean>
    suspend fun setScreenshotsEnabled(enabled: Boolean)
    fun getLockTimeout(): Flow<LockTimeout>
    suspend fun setLockTimeout(timeout: LockTimeout)
}

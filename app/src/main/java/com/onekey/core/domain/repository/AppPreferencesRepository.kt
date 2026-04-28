package com.onekey.core.domain.repository

import com.onekey.core.domain.model.BackgroundLockTimeout
import com.onekey.core.domain.model.CredentialSortOrder
import com.onekey.core.domain.model.InactivityLockTimeout
import com.onekey.core.domain.model.MasterPasswordInterval
import com.onekey.core.domain.model.RecycleBinRetention
import kotlinx.coroutines.flow.Flow

interface AppPreferencesRepository {
    fun isDarkTheme(): Flow<Boolean>
    suspend fun setDarkTheme(dark: Boolean)
    fun isBiometricEnabled(): Flow<Boolean>
    suspend fun setBiometricEnabled(enabled: Boolean)
    fun isScreenshotsEnabled(): Flow<Boolean>
    suspend fun setScreenshotsEnabled(enabled: Boolean)
    fun getBackgroundLockTimeout(): Flow<BackgroundLockTimeout>
    suspend fun setBackgroundLockTimeout(timeout: BackgroundLockTimeout)
    fun getInactivityLockTimeout(): Flow<InactivityLockTimeout>
    suspend fun setInactivityLockTimeout(timeout: InactivityLockTimeout)
    fun isMasterPasswordRecheckEnabled(): Flow<Boolean>
    suspend fun setMasterPasswordRecheckEnabled(enabled: Boolean)
    fun getMasterPasswordRecheckInterval(): Flow<MasterPasswordInterval>
    suspend fun setMasterPasswordRecheckInterval(interval: MasterPasswordInterval)
    fun getLastMasterPasswordTimestamp(): Flow<Long>
    suspend fun setLastMasterPasswordTimestamp(timestamp: Long)
    fun isShowFavourites(): Flow<Boolean>
    suspend fun setShowFavourites(show: Boolean)
    fun getCredentialSortOrder(): Flow<CredentialSortOrder>
    suspend fun setCredentialSortOrder(order: CredentialSortOrder)
    fun isHideTopBarOnScroll(): Flow<Boolean>
    suspend fun setHideTopBarOnScroll(enabled: Boolean)
    fun getRecycleBinRetention(): Flow<RecycleBinRetention>
    suspend fun setRecycleBinRetention(retention: RecycleBinRetention)
}

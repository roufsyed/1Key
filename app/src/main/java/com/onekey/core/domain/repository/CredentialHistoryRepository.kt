package com.onekey.core.domain.repository

import com.onekey.core.domain.model.AppResult
import com.onekey.core.domain.model.Credential
import com.onekey.core.domain.model.CredentialHistoryEntry
import kotlinx.coroutines.flow.Flow

interface CredentialHistoryRepository {
    suspend fun snapshotCredential(credential: Credential): AppResult<Unit>
    fun observeHistory(credentialId: String): Flow<List<CredentialHistoryEntry>>
    suspend fun deleteForCredential(credentialId: String): AppResult<Unit>
    suspend fun deleteAll(): AppResult<Unit>
}

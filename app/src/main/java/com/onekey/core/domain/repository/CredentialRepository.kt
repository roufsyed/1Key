package com.onekey.core.domain.repository

import androidx.paging.PagingData
import com.onekey.core.domain.model.AppResult
import com.onekey.core.domain.model.Credential
import kotlinx.coroutines.flow.Flow

interface CredentialRepository {
    fun getPagedCredentials(query: String, tag: String): Flow<PagingData<Credential>>
    fun observeCredential(id: String): Flow<Credential?>
    suspend fun getCredential(id: String): AppResult<Credential>
    suspend fun saveCredential(credential: Credential): AppResult<Unit>
    suspend fun deleteCredential(id: String): AppResult<Unit>
    suspend fun getAllCredentials(): AppResult<List<Credential>>
    suspend fun importCredentials(credentials: List<Credential>): AppResult<Int>
    fun observeCount(): Flow<Int>
    fun observeCountForTag(tag: String): Flow<Int>
    fun observeFavoriteCount(): Flow<Int>
    fun observeFavorites(): Flow<List<Credential>>
    fun observeFavoritesPaged(): Flow<PagingData<Credential>>
    fun observeWithTotp(): Flow<List<Credential>>
    suspend fun toggleFavorite(id: String, isFavorite: Boolean): AppResult<Unit>
    suspend fun deleteAllCredentials(): AppResult<Unit>
}

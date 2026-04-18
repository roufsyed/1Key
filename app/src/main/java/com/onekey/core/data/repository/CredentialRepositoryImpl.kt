package com.onekey.core.data.repository

import androidx.paging.*
import com.onekey.core.data.local.dao.CredentialDao
import com.onekey.core.data.local.entity.CredentialEntity
import com.onekey.core.data.local.entity.CustomFieldEntity
import com.onekey.core.domain.model.*
import com.onekey.core.domain.repository.CredentialRepository
import com.onekey.core.security.CryptoManager
import com.onekey.core.security.EncryptedData
import com.onekey.core.security.VaultKeyHolder
import kotlinx.coroutines.flow.*
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private const val PAGE_SIZE = 30

@Singleton
class CredentialRepositoryImpl @Inject constructor(
    private val dao: CredentialDao,
    private val crypto: CryptoManager,
    private val keyHolder: VaultKeyHolder,
) : CredentialRepository {

    override fun getPagedCredentials(query: String, tag: String): Flow<PagingData<Credential>> =
        Pager(
            config = PagingConfig(
                pageSize = PAGE_SIZE,
                enablePlaceholders = false,
                prefetchDistance = 10,
            ),
            pagingSourceFactory = { dao.pagingSource(query, tag) }
        ).flow
            .map { pagingData -> pagingData.map { it.toDomain() } }

    override fun observeCredential(id: String): Flow<Credential?> =
        dao.observeById(id)
            .map { it?.toDomain() }
            .distinctUntilChanged()

    override suspend fun getCredential(id: String): AppResult<Credential> = runCatchingResult {
        dao.getById(id)?.toDomain() ?: throw NoSuchElementException("Credential $id not found")
    }

    override suspend fun saveCredential(credential: Credential): AppResult<Unit> = runCatchingResult {
        dao.upsert(credential.toEntity())
    }

    override suspend fun deleteCredential(id: String): AppResult<Unit> = runCatchingResult {
        dao.deleteById(id)
    }

    override suspend fun getAllCredentials(): AppResult<List<Credential>> = runCatchingResult {
        dao.getAll().map { it.toDomain() }
    }

    override suspend fun importCredentials(credentials: List<Credential>): AppResult<Int> =
        runCatchingResult {
            val entities = credentials.map { it.toEntity() }
            dao.upsertAll(entities)
            entities.size
        }

    override fun observeCount(): Flow<Int> = dao.observeCount().distinctUntilChanged()

    override fun observeCountForTag(tag: String): Flow<Int> =
        dao.observeCountForTag(tag).distinctUntilChanged()

    override fun observeFavoriteCount(): Flow<Int> =
        dao.observeFavoriteCount().distinctUntilChanged()

    override fun observeFavorites(): Flow<List<Credential>> =
        dao.observeFavorites().map { list -> list.map { it.toDomain() } }

    override fun observeFavoritesPaged(): Flow<PagingData<Credential>> =
        Pager(
            config = PagingConfig(pageSize = PAGE_SIZE, enablePlaceholders = false),
            pagingSourceFactory = { dao.favoritesPagingSource() },
        ).flow.map { pagingData -> pagingData.map { it.toDomain() } }

    override fun observeWithTotp(): Flow<List<Credential>> =
        dao.observeWithTotp().map { list -> list.map { it.toDomain() } }

    override suspend fun toggleFavorite(id: String, isFavorite: Boolean): AppResult<Unit> =
        runCatchingResult { dao.setFavorite(id, isFavorite) }

    override suspend fun deleteAllCredentials(): AppResult<Unit> =
        runCatchingResult { dao.deleteAll() }

    // ── Mapping ──────────────────────────────────────────────────────────────

    private fun CredentialEntity.toDomain(): Credential {
        val key = keyHolder.requireKey()
        return Credential(
            id = id,
            title = title,
            username = crypto.decryptString(EncryptedData(usernameEncrypted, ivUsername), key),
            password = crypto.decryptString(EncryptedData(passwordEncrypted, ivPassword), key),
            url = if (urlEncrypted != null && ivUrl != null)
                crypto.decryptString(EncryptedData(urlEncrypted, ivUrl), key)
            else url, // legacy plaintext fallback for rows that pre-date v4 migration
            notes = crypto.decryptString(EncryptedData(notesEncrypted, ivNotes), key),
            totpSecret = if (totpSecretEncrypted != null && ivTotp != null)
                crypto.decryptString(EncryptedData(totpSecretEncrypted, ivTotp), key)
            else null,
            tags = tags,
            customFields = customFields.map { cf ->
                CustomField(
                    key = if (cf.keyEncrypted != null && cf.keyIv != null)
                        crypto.decryptString(EncryptedData(cf.keyEncrypted, cf.keyIv), key)
                    else cf.key, // legacy plaintext fallback
                    value = crypto.decryptString(EncryptedData(cf.valueEncrypted, cf.iv), key),
                    isSensitive = cf.isSensitive,
                )
            },
            isFavorite = isFavorite,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )
    }

    private fun Credential.toEntity(): CredentialEntity {
        val key = keyHolder.requireKey()
        val now = System.currentTimeMillis()
        val encUsername = crypto.encryptString(username, key)
        val encPassword = crypto.encryptString(password, key)
        val encNotes = crypto.encryptString(notes, key)
        val encTotp = totpSecret?.let { crypto.encryptString(it, key) }
        val encUrl = crypto.encryptString(url, key)

        return CredentialEntity(
            id = if (id.isBlank()) UUID.randomUUID().toString() else id,
            title = title,
            usernameEncrypted = encUsername.ciphertext,
            ivUsername = encUsername.iv,
            passwordEncrypted = encPassword.ciphertext,
            ivPassword = encPassword.iv,
            notesEncrypted = encNotes.ciphertext,
            ivNotes = encNotes.iv,
            totpSecretEncrypted = encTotp?.ciphertext,
            ivTotp = encTotp?.iv,
            url = "",
            urlEncrypted = encUrl.ciphertext,
            ivUrl = encUrl.iv,
            tags = tags,
            customFields = customFields.map { cf ->
                val encVal = crypto.encryptString(cf.value, key)
                val encKey = crypto.encryptString(cf.key, key)
                CustomFieldEntity(
                    key = "",
                    keyEncrypted = encKey.ciphertext,
                    keyIv = encKey.iv,
                    valueEncrypted = encVal.ciphertext,
                    iv = encVal.iv,
                    isSensitive = cf.isSensitive,
                )
            },
            isFavorite = isFavorite,
            createdAt = if (createdAt == 0L) now else createdAt,
            updatedAt = now,
        )
    }
}

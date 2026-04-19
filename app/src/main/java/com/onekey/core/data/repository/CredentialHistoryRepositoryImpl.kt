package com.onekey.core.data.repository

import com.onekey.core.data.local.dao.CredentialHistoryDao
import com.onekey.core.data.local.entity.CredentialHistoryEntity
import com.onekey.core.domain.model.AppResult
import com.onekey.core.domain.model.Credential
import com.onekey.core.domain.model.CredentialHistoryEntry
import com.onekey.core.domain.model.runCatchingResult
import com.onekey.core.domain.repository.CredentialHistoryRepository
import com.onekey.core.security.CryptoManager
import com.onekey.core.security.EncryptedData
import com.onekey.core.security.VaultKeyHolder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private const val MAX_HISTORY_ENTRIES = 20

@Singleton
class CredentialHistoryRepositoryImpl @Inject constructor(
    private val dao: CredentialHistoryDao,
    private val crypto: CryptoManager,
    private val keyHolder: VaultKeyHolder,
) : CredentialHistoryRepository {

    override suspend fun snapshotCredential(credential: Credential): AppResult<Unit> =
        runCatchingResult {
            val key = keyHolder.requireKey()
            val encUsername = crypto.encryptString(credential.username, key)
            val encPassword = crypto.encryptString(credential.password, key)
            val encUrl = if (credential.url.isNotEmpty()) {
                crypto.encryptString(credential.url, key)
            } else null

            val entity = CredentialHistoryEntity(
                id = UUID.randomUUID().toString(),
                credentialId = credential.id,
                title = credential.title,
                usernameEncrypted = encUsername.ciphertext,
                ivUsername = encUsername.iv,
                passwordEncrypted = encPassword.ciphertext,
                ivPassword = encPassword.iv,
                urlEncrypted = encUrl?.ciphertext,
                ivUrl = encUrl?.iv,
                modifiedAt = if (credential.updatedAt > 0L) credential.updatedAt else System.currentTimeMillis(),
            )
            dao.insert(entity)
            dao.trimHistory(credential.id, MAX_HISTORY_ENTRIES)
        }

    override fun observeHistory(credentialId: String): Flow<List<CredentialHistoryEntry>> =
        dao.observeHistory(credentialId).map { list ->
            val key = keyHolder.requireKey()
            list.map { entity ->
                CredentialHistoryEntry(
                    id = entity.id,
                    credentialId = entity.credentialId,
                    title = entity.title,
                    username = crypto.decryptString(EncryptedData(entity.usernameEncrypted, entity.ivUsername), key),
                    password = crypto.decryptString(EncryptedData(entity.passwordEncrypted, entity.ivPassword), key),
                    url = if (entity.urlEncrypted != null && entity.ivUrl != null)
                        crypto.decryptString(EncryptedData(entity.urlEncrypted, entity.ivUrl), key)
                    else "",
                    modifiedAt = entity.modifiedAt,
                )
            }
        }

    override suspend fun deleteForCredential(credentialId: String): AppResult<Unit> =
        runCatchingResult { dao.deleteForCredential(credentialId) }

    override suspend fun deleteAll(): AppResult<Unit> =
        runCatchingResult { dao.deleteAll() }
}

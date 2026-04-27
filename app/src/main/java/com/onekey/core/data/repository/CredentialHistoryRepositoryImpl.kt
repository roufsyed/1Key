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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private const val MAX_HISTORY_ENTRIES = 20

@OptIn(ExperimentalCoroutinesApi::class)
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

    // Same shape as the credential observers in CredentialRepositoryImpl: gate on the
    // unlocked flag, swallow per-row decrypt failures so a lock-vs-decrypt race doesn't
    // poison the upstream Flow, and shift decrypt work to Default so it doesn't block
    // the main thread when the detail screen first opens.
    override fun observeHistory(credentialId: String): Flow<List<CredentialHistoryEntry>> =
        keyHolder.isUnlocked.flatMapLatest { unlocked ->
            if (!unlocked) flowOf(emptyList())
            else dao.observeHistory(credentialId).map { list ->
                list.mapNotNull { entity -> runCatching { entity.toDomain() }.getOrNull() }
            }
        }.flowOn(Dispatchers.Default)

    override suspend fun deleteForCredential(credentialId: String): AppResult<Unit> =
        runCatchingResult { dao.deleteForCredential(credentialId) }

    override suspend fun deleteAll(): AppResult<Unit> =
        runCatchingResult { dao.deleteAll() }

    private fun CredentialHistoryEntity.toDomain(): CredentialHistoryEntry {
        val key = keyHolder.requireKey()
        return CredentialHistoryEntry(
            id = id,
            credentialId = credentialId,
            title = title,
            username = crypto.decryptString(EncryptedData(usernameEncrypted, ivUsername), key),
            password = crypto.decryptString(EncryptedData(passwordEncrypted, ivPassword), key),
            url = if (urlEncrypted != null && ivUrl != null)
                crypto.decryptString(EncryptedData(urlEncrypted, ivUrl), key)
            else "",
            modifiedAt = modifiedAt,
        )
    }
}

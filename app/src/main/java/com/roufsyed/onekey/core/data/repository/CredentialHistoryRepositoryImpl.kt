package com.roufsyed.onekey.core.data.repository

import com.roufsyed.onekey.core.data.local.dao.CredentialHistoryDao
import com.roufsyed.onekey.core.data.local.entity.CredentialHistoryEntity
import com.roufsyed.onekey.core.domain.model.AppResult
import com.roufsyed.onekey.core.domain.model.Credential
import com.roufsyed.onekey.core.domain.model.CredentialHistoryEntry
import com.roufsyed.onekey.core.domain.model.runCatchingResult
import com.roufsyed.onekey.core.domain.repository.CredentialHistoryRepository
import com.roufsyed.onekey.core.security.CryptoManager
import com.roufsyed.onekey.core.security.EncryptedData
import com.roufsyed.onekey.core.security.HKDF_FIELD_KEY_INFO
import com.roufsyed.onekey.core.security.HKDF_TITLE_KEY_INFO
import com.roufsyed.onekey.core.security.VaultKeyHolder
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
            val vaultKey = keyHolder.requireKey()
            val fieldKey = crypto.deriveSubkey(vaultKey, HKDF_FIELD_KEY_INFO)
            val titleKey = crypto.deriveSubkey(vaultKey, HKDF_TITLE_KEY_INFO)
            val historyId = UUID.randomUUID().toString()

            val encTitle    = crypto.encrypt(credential.title.toByteArray(Charsets.UTF_8), titleKey, titleAad(historyId))
            val encUsername = crypto.encrypt(credential.username.toByteArray(Charsets.UTF_8), fieldKey, fieldAad(historyId, "username"))
            val encPassword = crypto.encrypt(credential.password.toByteArray(Charsets.UTF_8), fieldKey, fieldAad(historyId, "password"))
            val encUrl = if (credential.url.isNotEmpty()) {
                crypto.encrypt(credential.url.toByteArray(Charsets.UTF_8), fieldKey, fieldAad(historyId, "url"))
            } else null

            val entity = CredentialHistoryEntity(
                id = historyId,
                credentialId = credential.id,
                // v2: plaintext title cleared; the encrypted column is the source of truth.
                title = "",
                titleEncrypted = encTitle.ciphertext,
                ivTitle = encTitle.iv,
                usernameEncrypted = encUsername.ciphertext,
                ivUsername = encUsername.iv,
                passwordEncrypted = encPassword.ciphertext,
                ivPassword = encPassword.iv,
                urlEncrypted = encUrl?.ciphertext,
                ivUrl = encUrl?.iv,
                modifiedAt = if (credential.updatedAt > 0L) credential.updatedAt else System.currentTimeMillis(),
                cipherVersion = 2,
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
        val vaultKey = keyHolder.requireKey()
        // v0 rows: raw vault key, no AAD, plaintext title (legacy, pre-DB-v14).
        // v2 rows: HKDF subkeys + per-field AAD on fields, encrypted title.
        // v1 is unused for this table; the column space is shared with the
        // credentials table for documentation alignment, not strict semantics.
        val cipherKey = if (cipherVersion >= 2) crypto.deriveSubkey(vaultKey, HKDF_FIELD_KEY_INFO) else vaultKey

        fun decryptField(ct: ByteArray, iv: ByteArray, fieldName: String): String {
            val aad = if (cipherVersion >= 2) fieldAad(id, fieldName) else null
            return crypto.decrypt(EncryptedData(ct, iv), cipherKey, aad).toString(Charsets.UTF_8)
        }

        val resolvedTitle = if (cipherVersion >= 2 && titleEncrypted != null && ivTitle != null) {
            val titleKey = crypto.deriveSubkey(vaultKey, HKDF_TITLE_KEY_INFO)
            crypto.decrypt(EncryptedData(titleEncrypted, ivTitle), titleKey, titleAad(id))
                .toString(Charsets.UTF_8)
        } else {
            title
        }

        return CredentialHistoryEntry(
            id = id,
            credentialId = credentialId,
            title = resolvedTitle,
            username = decryptField(usernameEncrypted, ivUsername, "username"),
            password = decryptField(passwordEncrypted, ivPassword, "password"),
            url = if (urlEncrypted != null && ivUrl != null) decryptField(urlEncrypted, ivUrl, "url") else "",
            modifiedAt = modifiedAt,
        )
    }

    // AAD shapes are namespaced with `h:` so a history row with the same UUID as
    // a credentials row could not have its ciphertext swapped in across tables
    // without invalidating the GCM tag. Format mirrors CredentialRepositoryImpl
    // for consistency: "1k:v1|" prefix for fields (introduced in cipher v1),
    // "1k:v2|" prefix for titles (introduced in cipher v2).
    private fun fieldAad(historyId: String, field: String): ByteArray =
        "1k:v1|h:$historyId|$field".toByteArray(Charsets.UTF_8)

    private fun titleAad(historyId: String): ByteArray =
        "1k:v2|h:$historyId|title".toByteArray(Charsets.UTF_8)
}

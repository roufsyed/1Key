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
import androidx.sqlite.db.SimpleSQLiteQuery
import com.onekey.core.di.ApplicationScope
import com.onekey.core.domain.model.CredentialSortOrder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private const val PAGE_SIZE = 30

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class CredentialRepositoryImpl @Inject constructor(
    private val dao: CredentialDao,
    private val crypto: CryptoManager,
    private val keyHolder: VaultKeyHolder,
    @ApplicationScope appScope: CoroutineScope,
) : CredentialRepository {

    private val countFlow: StateFlow<Int> =
        dao.observeCount().stateIn(appScope, SharingStarted.Eagerly, 0)

    private val favoriteCountFlow: StateFlow<Int> =
        dao.observeFavoriteCount().stateIn(appScope, SharingStarted.Eagerly, 0)

    // Gate every flow that calls toDomain() on the vault unlock state.
    // When the vault locks the inner flow is cancelled and an empty/null value
    // is emitted immediately, preventing requireKey() from being called on a
    // locked vault and crashing with IllegalStateException.

    override fun getPagedCredentials(query: String, tag: String, sortOrder: CredentialSortOrder): Flow<PagingData<Credential>> =
        keyHolder.isUnlocked.flatMapLatest { unlocked ->
            if (!unlocked) flowOf(PagingData.empty())
            else {
                // tags column is a JSON array; matching on the quoted token (`"foo"`)
                // avoids tag "foo" spuriously matching credentials tagged "foobar".
                val sql = SimpleSQLiteQuery(
                    "SELECT * FROM credentials WHERE deleted_at IS NULL AND (? = '' OR title LIKE '%' || ? || '%') AND (? = '' OR tags LIKE '%\"' || ? || '\"%') ORDER BY ${sortOrder.toOrderBy()}",
                    arrayOf(query, query, tag, tag),
                )
                Pager(
                    config = PagingConfig(pageSize = PAGE_SIZE, enablePlaceholders = false, prefetchDistance = 10),
                    pagingSourceFactory = { dao.pagingSourceRaw(sql) },
                ).flow.map { pagingData -> pagingData.toDomainPaging() }
            }
        }

    override fun observeCredential(id: String): Flow<Credential?> =
        keyHolder.isUnlocked.flatMapLatest { unlocked ->
            if (!unlocked) flowOf(null)
            else dao.observeById(id).map { it?.toDomainOrNull() }
        }
            .flowOn(Dispatchers.Default)
            .distinctUntilChanged()

    override fun observeCredentialIncludingDeleted(id: String): Flow<Credential?> =
        keyHolder.isUnlocked.flatMapLatest { unlocked ->
            if (!unlocked) flowOf(null)
            else dao.observeByIdIncludingDeleted(id).map { it?.toDomainOrNull() }
        }
            .flowOn(Dispatchers.Default)
            .distinctUntilChanged()

    override suspend fun getCredential(id: String): AppResult<Credential> = runCatchingResult {
        dao.getById(id)?.toDomain() ?: throw NoSuchElementException("Credential $id not found")
    }

    override suspend fun saveCredential(credential: Credential): AppResult<Unit> = runCatchingResult {
        dao.upsert(credential.toEntity())
    }

    override suspend fun deleteCredential(id: String): AppResult<Unit> = runCatchingResult {
        dao.softDeleteById(id, System.currentTimeMillis())
    }

    override suspend fun hardDeleteCredential(id: String): AppResult<Unit> = runCatchingResult {
        dao.deleteById(id)
    }

    override suspend fun restoreCredential(id: String): AppResult<Unit> = runCatchingResult {
        dao.restoreById(id, System.currentTimeMillis())
    }

    override suspend fun purgeFromRecycleBin(id: String): AppResult<Unit> = runCatchingResult {
        dao.deleteById(id)
    }

    override suspend fun emptyRecycleBin(): AppResult<Int> = runCatchingResult {
        val count = dao.observeRecycleBinCount().first()
        dao.emptyRecycleBin()
        count
    }

    override suspend fun purgeRecycleBinOlderThan(cutoff: Long): AppResult<Int> = runCatchingResult {
        dao.purgeOlderThan(cutoff)
    }

    override fun observeRecycleBin(): Flow<List<Credential>> =
        keyHolder.isUnlocked.flatMapLatest { unlocked ->
            if (!unlocked) flowOf(emptyList())
            else dao.observeRecycleBin().map { list -> list.toDomainListSafe() }
        }.flowOn(Dispatchers.Default)

    override fun observeRecycleBinCount(): Flow<Int> =
        dao.observeRecycleBinCount().distinctUntilChanged()

    override suspend fun getAllCredentials(): AppResult<List<Credential>> = runCatchingResult {
        // One-shot suspend call — let decrypt failures surface as AppResult.Error to the
        // caller (export, import dedupe) instead of silently dropping rows. Long-lived
        // observers use the per-row safe path because a transient throw there poisons
        // the StateFlow; here a throw is just a normal error to the user.
        dao.getAll().map { it.toDomain() }
    }

    override suspend fun getAllInRecycleBin(): AppResult<List<Credential>> = runCatchingResult {
        dao.getAllInRecycleBin().map { it.toDomain() }
    }

    override suspend fun importCredentials(credentials: List<Credential>): AppResult<Int> =
        runCatchingResult {
            val entities = credentials.map { it.toEntity() }
            dao.upsertAll(entities)
            entities.size
        }

    override fun observeCount(): Flow<Int> = countFlow

    override fun observeCountForTag(tag: String): Flow<Int> =
        dao.observeCountForTag(tag).distinctUntilChanged()

    override fun observeFavoriteCount(): Flow<Int> = favoriteCountFlow

    override fun observeFavorites(): Flow<List<Credential>> =
        keyHolder.isUnlocked.flatMapLatest { unlocked ->
            if (!unlocked) flowOf(emptyList())
            else dao.observeFavorites().map { list -> list.toDomainListSafe() }
        }.flowOn(Dispatchers.Default)

    override fun observeFavoritesPaged(sortOrder: CredentialSortOrder): Flow<PagingData<Credential>> =
        keyHolder.isUnlocked.flatMapLatest { unlocked ->
            if (!unlocked) flowOf(PagingData.empty())
            else {
                val sql = SimpleSQLiteQuery(
                    "SELECT * FROM credentials WHERE deleted_at IS NULL AND is_favorite = 1 ORDER BY ${sortOrder.toOrderBy()}",
                )
                Pager(
                    config = PagingConfig(pageSize = PAGE_SIZE, enablePlaceholders = false),
                    pagingSourceFactory = { dao.favoritesPagingSourceRaw(sql) },
                ).flow.map { pagingData -> pagingData.toDomainPaging() }
            }
        }

    override fun observeCredentials(query: String, tag: String, sortOrder: CredentialSortOrder): Flow<List<Credential>> {
        val sql = SimpleSQLiteQuery(
            "SELECT * FROM credentials WHERE deleted_at IS NULL AND (? = '' OR title LIKE '%' || ? || '%') AND (? = '' OR tags LIKE '%\"' || ? || '\"%') ORDER BY ${sortOrder.toOrderBy()}",
            arrayOf(query, query, tag, tag),
        )
        return keyHolder.isUnlocked.flatMapLatest { unlocked ->
            if (!unlocked) flowOf(emptyList())
            else dao.observeListRaw(sql).map { list -> list.toDomainListSafe() }
        }.flowOn(Dispatchers.Default)
    }

    override fun observeFavoritesSorted(sortOrder: CredentialSortOrder): Flow<List<Credential>> {
        val sql = SimpleSQLiteQuery(
            "SELECT * FROM credentials WHERE deleted_at IS NULL AND is_favorite = 1 ORDER BY ${sortOrder.toOrderBy()}",
        )
        return keyHolder.isUnlocked.flatMapLatest { unlocked ->
            if (!unlocked) flowOf(emptyList())
            else dao.observeListRaw(sql).map { list -> list.toDomainListSafe() }
        }.flowOn(Dispatchers.Default)
    }

    // Titles are stored unencrypted by schema (used for the alphabet index), so emitting
    // them while locked isn't a leak per se. Gating anyway for consistency with the rest
    // of the repo — every other observer drops out on lock, and downstream UI is allowed
    // to assume that contract.
    override fun observeAllTitlesAlphabetical(tag: String): Flow<List<String>> =
        keyHolder.isUnlocked.flatMapLatest { unlocked ->
            if (!unlocked) flowOf(emptyList())
            else dao.observeAllTitlesAlphabetical(tag)
        }

    override fun observeFavoriteTitlesAlphabetical(): Flow<List<String>> =
        keyHolder.isUnlocked.flatMapLatest { unlocked ->
            if (!unlocked) flowOf(emptyList())
            else dao.observeFavoriteTitlesAlphabetical()
        }

    private fun CredentialSortOrder.toOrderBy() = when (this) {
        CredentialSortOrder.NEWEST_FIRST -> "created_at DESC"
        CredentialSortOrder.LAST_MODIFIED -> "updated_at DESC"
        CredentialSortOrder.ALPHABETICAL -> "lower(title) ASC"
    }

    override fun observeRotatingOtp(): Flow<List<Credential>> =
        keyHolder.isUnlocked.flatMapLatest { unlocked ->
            if (!unlocked) flowOf(emptyList())
            else dao.observeRotatingOtp().map { list -> list.toDomainListSafe() }
        }.flowOn(Dispatchers.Default)

    override fun observeHotpEntries(): Flow<List<Credential>> =
        keyHolder.isUnlocked.flatMapLatest { unlocked ->
            if (!unlocked) flowOf(emptyList())
            else dao.observeHotpEntries().map { list -> list.toDomainListSafe() }
        }.flowOn(Dispatchers.Default)

    override suspend fun incrementHotpCounter(credentialId: String): AppResult<Long?> =
        runCatchingResult {
            dao.atomicIncrementHotpCounter(credentialId, System.currentTimeMillis())
        }

    override suspend fun toggleFavorite(id: String, isFavorite: Boolean): AppResult<Unit> =
        runCatchingResult { dao.setFavorite(id, isFavorite) }

    override suspend fun deleteAllCredentials(): AppResult<Unit> =
        runCatchingResult { dao.deleteAll() }

    // ── Mapping ──────────────────────────────────────────────────────────────

    /**
     * Decrypts a list of entities, swallowing per-row failures. The race we're protecting
     * against: vault locks while `toDomain()` is iterating a list emitted by Room. Once
     * the lock fires, `keyHolder.requireKey()` starts throwing partway through the loop;
     * if the exception escapes the [map] block here, it propagates up the Flow chain and
     * permanently kills the upstream `stateIn`, causing every subsequent observation to
     * sit on stale or empty data until the process restarts.
     */
    private fun List<CredentialEntity>.toDomainListSafe(): List<Credential> =
        mapNotNull { it.toDomainOrNull() }

    private fun CredentialEntity.toDomainOrNull(): Credential? =
        runCatching { toDomain() }.getOrNull()

    /**
     * Decrypts entities for Paging. PagingData<T> requires T : Any so we can't filter
     * out per-row failures the way the list helper does. Instead, an unrecoverable
     * decrypt error yields a placeholder credential — visible in the list but harmless,
     * and crucially the Flow stays alive so the StateFlow doesn't lock up.
     */
    private fun PagingData<CredentialEntity>.toDomainPaging(): PagingData<Credential> =
        map { entity ->
            runCatching { entity.toDomain() }.getOrElse { placeholderCredential(entity) }
        }

    private fun placeholderCredential(entity: CredentialEntity): Credential = Credential(
        id = entity.id,
        title = entity.title,
        username = "",
        password = "",
        url = "",
        notes = "",
        otpParams = null,
        tags = entity.tags,
        customFields = emptyList(),
        isFavorite = entity.isFavorite,
        createdAt = entity.createdAt,
        updatedAt = entity.updatedAt,
        type = CredentialType.fromNameOrDefault(entity.type),
        deletedAt = entity.deletedAt,
        accessedAt = entity.accessedAt,
    )

    private fun CredentialEntity.toDomain(): Credential {
        val key = keyHolder.requireKey()
        return Credential(
            id = id,
            title = title,
            username = crypto.decryptString(EncryptedData(usernameEncrypted, ivUsername), key),
            password = crypto.decryptString(EncryptedData(passwordEncrypted, ivPassword), key),
            url = if (urlEncrypted != null && ivUrl != null)
                crypto.decryptString(EncryptedData(urlEncrypted, ivUrl), key)
            else url,
            notes = crypto.decryptString(EncryptedData(notesEncrypted, ivNotes), key),
            otpParams = decryptOtpParams(key),
            tags = tags,
            customFields = customFields.map { cf ->
                CustomField(
                    key = if (cf.keyEncrypted != null && cf.keyIv != null)
                        crypto.decryptString(EncryptedData(cf.keyEncrypted, cf.keyIv), key)
                    else cf.key,
                    value = crypto.decryptString(EncryptedData(cf.valueEncrypted, cf.iv), key),
                    isSensitive = cf.isSensitive,
                )
            },
            isFavorite = isFavorite,
            createdAt = createdAt,
            updatedAt = updatedAt,
            type = CredentialType.fromNameOrDefault(type),
            deletedAt = deletedAt,
            accessedAt = accessedAt,
        )
    }

    /**
     * Build an [OtpParams] from this entity's persisted columns, or null when no
     * 2FA secret is enrolled. Unknown enum strings (e.g. an `otp_type` written by a
     * future schema and read back after a downgrade) collapse to TOTP / SHA-1 via
     * [OtpType.fromNameOrDefault] / [OtpAlgorithm.fromNameOrDefault] so the read
     * path never throws — the worst case is a code the user re-enrolls, never a
     * crash. Out-of-range digits or non-positive period from a corrupt row also
     * fall back to defaults rather than failing OtpParams.init's `require()` and
     * killing the entire flow emission.
     */
    private fun CredentialEntity.decryptOtpParams(key: javax.crypto.SecretKey): OtpParams? {
        if (totpSecretEncrypted == null || ivTotp == null) return null
        val secret = crypto.decryptString(EncryptedData(totpSecretEncrypted, ivTotp), key)
        val safeDigits = totpDigits.takeIf { it in OtpParams.MIN_DIGITS..OtpParams.MAX_DIGITS }
            ?: OtpParams.DEFAULT_DIGITS
        val safePeriod = totpPeriod.takeIf { it > 0L } ?: OtpParams.DEFAULT_PERIOD_SECONDS
        val type = OtpType.fromNameOrDefault(otpType)
        return OtpParams(
            type = type,
            secret = secret,
            algorithm = OtpAlgorithm.fromNameOrDefault(totpAlgorithm),
            digits = safeDigits,
            period = safePeriod,
            counter = if (type == OtpType.HOTP) hotpCounter?.coerceAtLeast(0L) ?: 0L else 0L,
        )
    }

    private fun Credential.toEntity(): CredentialEntity {
        val key = keyHolder.requireKey()
        val now = System.currentTimeMillis()
        val encUsername = crypto.encryptString(username, key)
        val encPassword = crypto.encryptString(password, key)
        val encNotes = crypto.encryptString(notes, key)
        val encTotp = otpParams?.let { crypto.encryptString(it.secret, key) }
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
            accessedAt = accessedAt,
            type = type.name,
            deletedAt = deletedAt,
            // OTP metadata only carries meaning when a secret is enrolled. When
            // otpParams is null we still write the column DEFAULTs (TOTP/SHA1/6/30)
            // so the row stays well-formed; nothing reads them while the secret
            // column is null.
            otpType = (otpParams?.type ?: OtpType.TOTP).name,
            totpAlgorithm = (otpParams?.algorithm ?: OtpAlgorithm.SHA1).name,
            totpDigits = otpParams?.digits ?: OtpParams.DEFAULT_DIGITS,
            totpPeriod = otpParams?.period ?: OtpParams.DEFAULT_PERIOD_SECONDS,
            hotpCounter = if (otpParams?.type == OtpType.HOTP) otpParams.counter else null,
        )
    }
}

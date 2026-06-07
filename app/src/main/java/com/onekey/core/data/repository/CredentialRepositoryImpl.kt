package com.onekey.core.data.repository

import androidx.paging.*
import com.onekey.core.data.local.dao.CredentialDao
import com.onekey.core.data.local.entity.CredentialEntity
import com.onekey.core.data.local.entity.CustomFieldEntity
import com.onekey.core.data.snapshot.CredentialDecryptor
import com.onekey.core.data.snapshot.fieldAad
import com.onekey.core.data.snapshot.titleAad
import com.onekey.core.domain.model.*
import com.onekey.core.domain.repository.CredentialRepository
import com.onekey.core.security.CryptoManager
import com.onekey.core.security.EncryptedData
import com.onekey.core.security.HKDF_FIELD_KEY_INFO
import com.onekey.core.security.HKDF_TITLE_KEY_INFO
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
    private val decryptor: CredentialDecryptor,
    @ApplicationScope appScope: CoroutineScope,
) : CredentialRepository {

    private val countFlow: StateFlow<Int> =
        dao.observeCount().stateIn(appScope, SharingStarted.Eagerly, 0)

    private val favoriteCountFlow: StateFlow<Int> =
        dao.observeFavoriteCount().stateIn(appScope, SharingStarted.Eagerly, 0)

    // Gate every flow that calls toDomain() on the vault unlock state.
    //
    // For LIST-shaped flows we emit `emptyList()` on lock - the list screen
    // visibly empties for a frame and the user is then routed to LockScreen,
    // so the empty state isn't confusing.
    //
    // For SINGLE-credential flows (observeCredential, observeCredentialIncludingDeleted)
    // we emit nothing on lock - `emptyFlow()`, not `flowOf(null)`. The reason:
    // `Credential?` consumers read `null` as "this credential doesn't exist",
    // but on lock the credential is fine - the vault just can't read it. Emitting
    // `flowOf(null)` would conflate "vault locked" with "credential missing" and
    // cause the detail screen's ViewModel to flip to Error("Credential not found")
    // every time the vault locks. With `emptyFlow()` the inner flow simply pauses;
    // the DAO subscription is cancelled (so requireKey() can't fire on a locked
    // vault); the consumer's last-known state survives the lock; on unlock the
    // real credential re-emits and the consumer continues normally.

    override fun getPagedCredentials(query: String, tag: String, sortOrder: CredentialSortOrder): Flow<PagingData<Credential>> =
        keyHolder.isUnlocked.flatMapLatest { unlocked ->
            if (!unlocked) flowOf(PagingData.empty())
            else if (query.isEmpty() && sortOrder != CredentialSortOrder.ALPHABETICAL) {
                // Fast path - SQL filter + date-only ordering, real paging.
                // Title is never read in SQL, so encrypted v2+ titles don't matter here.
                // tags column is a JSON array; matching on the quoted token (`"foo"`)
                // avoids tag "foo" spuriously matching credentials tagged "foobar".
                val sql = SimpleSQLiteQuery(
                    "SELECT * FROM credentials WHERE deleted_at IS NULL AND (? = '' OR tags LIKE '%\"' || ? || '\"%') ORDER BY ${sortOrder.dateOrderBy()}",
                    arrayOf(tag, tag),
                )
                Pager(
                    config = PagingConfig(pageSize = PAGE_SIZE, enablePlaceholders = false, prefetchDistance = 10),
                    pagingSourceFactory = { dao.pagingSourceRaw(sql) },
                ).flow.map { pagingData -> pagingData.toDomainPaging() }
            } else {
                // Slow path - title is involved. SQL filters by tag + deleted_at only;
                // we decrypt, filter by title query, and sort in memory. PagingData.from
                // wraps the materialised list so the public Flow<PagingData<Credential>>
                // contract stays intact.
                val sql = SimpleSQLiteQuery(
                    "SELECT * FROM credentials WHERE deleted_at IS NULL AND (? = '' OR tags LIKE '%\"' || ? || '\"%')",
                    arrayOf(tag, tag),
                )
                dao.observeListRaw(sql).map { entities ->
                    val list = entities.toDomainListSafe()
                        .let { if (query.isEmpty()) it else it.filter { c -> c.title.contains(query, ignoreCase = true) } }
                        .sortedWith(sortOrder.comparator())
                    PagingData.from(list)
                }
            }
        }.flowOn(Dispatchers.Default)

    override fun observeCredential(id: String): Flow<Credential?> =
        keyHolder.isUnlocked.flatMapLatest { unlocked ->
            if (!unlocked) emptyFlow()
            else dao.observeById(id).map { it?.let(decryptor::decryptOrNull) }
        }
            .flowOn(Dispatchers.Default)
            .distinctUntilChanged()

    override fun observeCredentialIncludingDeleted(id: String): Flow<Credential?> =
        keyHolder.isUnlocked.flatMapLatest { unlocked ->
            if (!unlocked) emptyFlow()
            else dao.observeByIdIncludingDeleted(id).map { it?.let(decryptor::decryptOrNull) }
        }
            .flowOn(Dispatchers.Default)
            .distinctUntilChanged()

    override suspend fun getCredential(id: String): AppResult<Credential> = runCatchingResult {
        dao.getById(id)?.let { decryptor.decrypt(it) }
            ?: throw NoSuchElementException("Credential $id not found")
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
        // One-shot suspend call - let decrypt failures surface as AppResult.Error to the
        // caller (export, import dedupe) instead of silently dropping rows. Long-lived
        // observers use the per-row safe path because a transient throw there poisons
        // the StateFlow; here a throw is just a normal error to the user.
        dao.getAll().map { decryptor.decrypt(it) }
    }

    override suspend fun getAllInRecycleBin(): AppResult<List<Credential>> = runCatchingResult {
        dao.getAllInRecycleBin().map { decryptor.decrypt(it) }
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

    override fun observeCredentials(query: String, tag: String, sortOrder: CredentialSortOrder): Flow<List<Credential>> =
        keyHolder.isUnlocked.flatMapLatest { unlocked ->
            if (!unlocked) flowOf(emptyList())
            else {
                val sql = SimpleSQLiteQuery(
                    "SELECT * FROM credentials WHERE deleted_at IS NULL AND (? = '' OR tags LIKE '%\"' || ? || '\"%')",
                    arrayOf(tag, tag),
                )
                dao.observeListRaw(sql).map { entities ->
                    entities.toDomainListSafe()
                        .let { if (query.isEmpty()) it else it.filter { c -> c.title.contains(query, ignoreCase = true) } }
                        .sortedWith(sortOrder.comparator())
                }
            }
        }.flowOn(Dispatchers.Default)

    override fun observeFavoritesSorted(sortOrder: CredentialSortOrder): Flow<List<Credential>> =
        keyHolder.isUnlocked.flatMapLatest { unlocked ->
            if (!unlocked) flowOf(emptyList())
            else dao.observeFavorites().map { list ->
                list.toDomainListSafe().sortedWith(sortOrder.comparator())
            }
        }.flowOn(Dispatchers.Default)

    /** Date-only SQL ordering. Caller must ensure sortOrder != ALPHABETICAL - that path uses [comparator]. */
    private fun CredentialSortOrder.dateOrderBy() = when (this) {
        CredentialSortOrder.NEWEST_FIRST -> "created_at DESC"
        CredentialSortOrder.LAST_MODIFIED -> "updated_at DESC"
        // SQLite sorts NULL as smaller than any value, so DESC puts never-accessed rows
        // at the bottom - exactly the desired UX (recent activity at the top).
        CredentialSortOrder.RECENTLY_ACCESSED -> "accessed_at DESC"
        CredentialSortOrder.ALPHABETICAL -> error("ALPHABETICAL sort takes the in-memory path; do not use SQL ordering")
    }

    /** In-memory comparator used whenever title sorting is needed (titles are encrypted on v2+ rows). */
    private fun CredentialSortOrder.comparator(): Comparator<Credential> = when (this) {
        CredentialSortOrder.NEWEST_FIRST -> compareByDescending { it.createdAt }
        CredentialSortOrder.LAST_MODIFIED -> compareByDescending { it.updatedAt }
        // toDomain() falls back to updatedAt when accessedAt is null, so this comparator
        // sees a non-null Long in practice. compareByDescending also handles raw nulls
        // correctly (Kotlin's stdlib treats null as smallest, so DESC = nulls last) -
        // matching the SQL fast path's behaviour for legacy rows.
        CredentialSortOrder.RECENTLY_ACCESSED -> compareByDescending { it.accessedAt }
        CredentialSortOrder.ALPHABETICAL -> compareBy { it.title.lowercase() }
    }

    override fun observeRotatingOtp(): Flow<List<Credential>> =
        keyHolder.isUnlocked.flatMapLatest { unlocked ->
            if (!unlocked) flowOf(emptyList())
            else dao.observeRotatingOtp().map { list ->
                list.toDomainListSafe().sortedBy { it.title.lowercase() }
            }
        }.flowOn(Dispatchers.Default)

    override fun observeHotpEntries(): Flow<List<Credential>> =
        keyHolder.isUnlocked.flatMapLatest { unlocked ->
            if (!unlocked) flowOf(emptyList())
            else dao.observeHotpEntries().map { list ->
                list.toDomainListSafe().sortedBy { it.title.lowercase() }
            }
        }.flowOn(Dispatchers.Default)

    override suspend fun incrementHotpCounter(credentialId: String): AppResult<Long?> =
        runCatchingResult {
            dao.atomicIncrementHotpCounter(credentialId, System.currentTimeMillis())
        }

    override suspend fun toggleFavorite(id: String, isFavorite: Boolean): AppResult<Unit> =
        runCatchingResult { dao.setFavorite(id, isFavorite) }

    override suspend fun deleteAllCredentials(): AppResult<Unit> =
        runCatchingResult { dao.deleteAll() }

    override suspend fun markAccessed(id: String): AppResult<Unit> = runCatchingResult {
        dao.touchAccessedAt(id, System.currentTimeMillis())
    }

    // ── Mapping ──────────────────────────────────────────────────────────────
    //
    // Decryption is delegated to [CredentialDecryptor]. The list/paging
    // helpers below keep their "per-row failure stays local" semantics:
    //
    //   toDomainListSafe - drops corrupt rows via decryptor.decryptOrNull,
    //   so the upstream Flow stays alive when one row's GCM tag verification
    //   fails. Used by long-lived observers.
    //
    //   toDomainPaging - PagingData<T> requires T : Any, so we can't filter
    //   out per-row failures. An unrecoverable decrypt error yields a
    //   placeholder credential, keeping the Flow alive and visible.

    private fun List<CredentialEntity>.toDomainListSafe(): List<Credential> =
        mapNotNull { decryptor.decryptOrNull(it) }

    private fun PagingData<CredentialEntity>.toDomainPaging(): PagingData<Credential> =
        map { entity ->
            runCatching { decryptor.decrypt(entity) }.getOrElse { placeholderCredential(entity) }
        }

    private fun placeholderCredential(entity: CredentialEntity): Credential = Credential(
        id = entity.id,
        // For v2+ rows, the plaintext column is empty; we can't decrypt the title without the key.
        // Show a neutral marker so the row is visible in the list rather than mysteriously blank.
        title = entity.title.ifEmpty { "(locked)" },
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

    // The per-row decrypt body that used to live here as `CredentialEntity.toDomain()`
    // now lives in [CredentialDecryptor] (`core/data/snapshot/CredentialDecryptor.kt`).
    // It is the single source of truth for read-path decryption - used here for
    // single-row paths (getCredential, observeCredential, toDomainPaging fallback)
    // AND by [com.onekey.core.data.snapshot.VaultSnapshotStore] for the bulk lean
    // path. Keeping one decryptor avoids drift between AAD shapes and OTP-defaults
    // fallback rules.

    private fun Credential.toEntity(): CredentialEntity {
        val vaultKey = keyHolder.requireKey()
        val fieldKey = crypto.deriveSubkey(vaultKey, HKDF_FIELD_KEY_INFO)
        val titleKey = crypto.deriveSubkey(vaultKey, HKDF_TITLE_KEY_INFO)
        val now = System.currentTimeMillis()
        val resolvedId = if (id.isBlank()) UUID.randomUUID().toString() else id

        fun encryptField(value: String, fieldName: String): EncryptedData =
            crypto.encrypt(value.toByteArray(Charsets.UTF_8), fieldKey, fieldAad(resolvedId, fieldName))

        val encUsername = encryptField(username, "username")
        val encPassword = encryptField(password, "password")
        val encNotes    = encryptField(notes, "notes")
        val encTotp     = otpParams?.let { encryptField(it.secret, "totp") }
        val encUrl      = encryptField(url, "url")
        val encTitle    = crypto.encrypt(title.toByteArray(Charsets.UTF_8), titleKey, titleAad(resolvedId))

        return CredentialEntity(
            id = resolvedId,
            // Plaintext title cleared on v2+ rows - the encrypted column is the source of truth.
            title = "",
            titleEncrypted = encTitle.ciphertext,
            ivTitle = encTitle.iv,
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
            customFields = customFields.mapIndexed { idx, cf ->
                val encVal = encryptField(cf.value, "cf|$idx|v")
                val encKey = encryptField(cf.key, "cf|$idx|k")
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
            // First write wins: foreign imports already provided a value;
            // manual creation goes through with null and gets `now` here.
            // Subsequent saves preserve whatever's already on the row.
            accessedAt = accessedAt ?: now,
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
            cipherVersion = 2,
        )
    }
}

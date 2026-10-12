package com.roufsyed.onekey.core.domain.usecase

import com.roufsyed.onekey.core.domain.model.AppResult
import com.roufsyed.onekey.core.domain.model.Credential
import com.roufsyed.onekey.core.domain.model.CredentialSortOrder
import com.roufsyed.onekey.core.domain.repository.CredentialRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import java.util.UUID

/**
 * Minimal in-memory [CredentialRepository] for use-case tests. Keeps active and
 * recycle-bin rows in one map keyed by id (bin = `deletedAt != null`) and mirrors
 * the write semantics the use cases rely on: save and import upsert by id
 * (REPLACE, blank ids get a UUID like the production repository), restore clears
 * `deletedAt`, purge removes the row. Observe methods return static flows; the
 * use cases under test never collect them.
 */
@Suppress("DEPRECATION")
class InMemoryCredentialRepository(initial: List<Credential> = emptyList()) : CredentialRepository {

    val rows = LinkedHashMap<String, Credential>()
    val saved = mutableListOf<Credential>()
    val importedBatches = mutableListOf<List<Credential>>()
    val purged = mutableListOf<String>()

    init {
        initial.forEach { rows[it.id] = it }
    }

    fun active(): List<Credential> = rows.values.filter { it.deletedAt == null }
    fun inBin(): List<Credential> = rows.values.filter { it.deletedAt != null }

    override suspend fun saveCredential(credential: Credential): AppResult<Unit> {
        val resolved = withId(credential)
        rows[resolved.id] = resolved
        saved += resolved
        return AppResult.Success(Unit)
    }

    override suspend fun importCredentials(credentials: List<Credential>): AppResult<Int> {
        val resolved = credentials.map(::withId)
        resolved.forEach { rows[it.id] = it }
        importedBatches += resolved
        return AppResult.Success(resolved.size)
    }

    override suspend fun getAllCredentials(): AppResult<List<Credential>> = AppResult.Success(active())
    override suspend fun getAllInRecycleBin(): AppResult<List<Credential>> = AppResult.Success(inBin())

    override suspend fun getCredential(id: String): AppResult<Credential> =
        rows[id]?.let { AppResult.Success(it) } ?: AppResult.Error(NoSuchElementException(id))

    override suspend fun deleteCredential(id: String): AppResult<Unit> {
        rows[id]?.let { rows[id] = it.copy(deletedAt = System.currentTimeMillis()) }
        return AppResult.Success(Unit)
    }

    override suspend fun hardDeleteCredential(id: String): AppResult<Unit> {
        rows.remove(id)
        return AppResult.Success(Unit)
    }

    override suspend fun restoreCredential(id: String): AppResult<Unit> {
        rows[id]?.let { rows[id] = it.copy(deletedAt = null) }
        return AppResult.Success(Unit)
    }

    override suspend fun purgeFromRecycleBin(id: String): AppResult<Unit> {
        rows.remove(id)
        purged += id
        return AppResult.Success(Unit)
    }

    override suspend fun emptyRecycleBin(): AppResult<Int> {
        val ids = inBin().map { it.id }
        ids.forEach { rows.remove(it) }
        return AppResult.Success(ids.size)
    }

    override suspend fun purgeRecycleBinOlderThan(cutoff: Long): AppResult<Int> = AppResult.Success(0)

    override suspend fun toggleFavorite(id: String, isFavorite: Boolean): AppResult<Unit> {
        rows[id]?.let { rows[id] = it.copy(isFavorite = isFavorite) }
        return AppResult.Success(Unit)
    }

    override suspend fun deleteAllCredentials(): AppResult<Unit> {
        rows.clear()
        return AppResult.Success(Unit)
    }

    override suspend fun markAccessed(id: String): AppResult<Unit> = AppResult.Success(Unit)
    override suspend fun incrementHotpCounter(credentialId: String): AppResult<Long?> = AppResult.Success(null)

    override fun observeCredential(id: String): Flow<Credential?> = flowOf(rows[id]?.takeIf { it.deletedAt == null })
    override fun observeCredentialIncludingDeleted(id: String): Flow<Credential?> = flowOf(rows[id])
    override fun observeRecycleBin(): Flow<List<Credential>> = flowOf(inBin())
    override fun observeRecycleBinCount(): Flow<Int> = flowOf(inBin().size)
    override fun observeCount(): Flow<Int> = flowOf(active().size)
    override fun observeCountForTag(tag: String): Flow<Int> = flowOf(active().count { tag in it.tags })
    override fun observeFavoriteCount(): Flow<Int> = flowOf(active().count { it.isFavorite })
    override fun observeFavorites(): Flow<List<Credential>> = flowOf(active().filter { it.isFavorite })
    override fun observeCredentials(query: String, tag: String, sortOrder: CredentialSortOrder): Flow<List<Credential>> =
        flowOf(active())
    override fun observeFavoritesSorted(sortOrder: CredentialSortOrder): Flow<List<Credential>> =
        flowOf(active().filter { it.isFavorite })
    override fun observeRotatingOtp(): Flow<List<Credential>> = flowOf(emptyList())
    override fun observeHotpEntries(): Flow<List<Credential>> = flowOf(emptyList())

    private fun withId(credential: Credential): Credential =
        if (credential.id.isBlank()) credential.copy(id = UUID.randomUUID().toString()) else credential
}

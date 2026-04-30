package com.onekey.core.data.local.dao

import androidx.paging.PagingSource
import androidx.room.*
import androidx.sqlite.db.SupportSQLiteQuery
import com.onekey.core.data.local.entity.CredentialEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CredentialDao {

    // Tag column is a Gson-serialized JSON array (e.g. ["foo","bar"]). Anchoring the
    // LIKE pattern to the surrounding JSON quotes (`"foo"`) prevents tag "foo" from
    // matching credentials tagged "foobar", "homework"-vs-"work", etc.
    @Query(
        """
        SELECT * FROM credentials
        WHERE deleted_at IS NULL
        AND (:query = '' OR title LIKE '%' || :query || '%')
        AND (:tag = '' OR tags LIKE '%"' || :tag || '"%')
        ORDER BY updated_at DESC
        """
    )
    fun pagingSource(query: String, tag: String): PagingSource<Int, CredentialEntity>

    @Query(
        """
        SELECT * FROM credentials
        WHERE deleted_at IS NULL
        AND (:query = '' OR title LIKE '%' || :query || '%')
        AND (:tag = '' OR tags LIKE '%"' || :tag || '"%')
        ORDER BY updated_at DESC
        LIMIT :limit OFFSET :offset
        """
    )
    fun searchFlow(query: String, tag: String, limit: Int, offset: Int): Flow<List<CredentialEntity>>

    @Query("SELECT * FROM credentials WHERE id = :id AND deleted_at IS NULL")
    fun observeById(id: String): Flow<CredentialEntity?>

    @Query("SELECT * FROM credentials WHERE id = :id AND deleted_at IS NULL")
    suspend fun getById(id: String): CredentialEntity?

    // Includes soft-deleted rows. Used by the recycle bin and by import dedup so that a
    // (title, username) match in the bin can be detected and restored.
    @Query("SELECT * FROM credentials WHERE id = :id")
    suspend fun getByIdIncludingDeleted(id: String): CredentialEntity?

    // Reactive variant that emits regardless of soft-delete state so the detail screen
    // can render a recycle-bin banner instead of hanging forever on the filtered observer.
    @Query("SELECT * FROM credentials WHERE id = :id")
    fun observeByIdIncludingDeleted(id: String): Flow<CredentialEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: CredentialEntity)

    @Delete
    suspend fun delete(entity: CredentialEntity)

    @Query("DELETE FROM credentials WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE credentials SET deleted_at = :deletedAt WHERE id = :id")
    suspend fun softDeleteById(id: String, deletedAt: Long)

    @Query("UPDATE credentials SET deleted_at = NULL, updated_at = :now WHERE id = :id")
    suspend fun restoreById(id: String, now: Long)

    @Query("DELETE FROM credentials WHERE deleted_at IS NOT NULL")
    suspend fun emptyRecycleBin()

    @Query("DELETE FROM credentials WHERE deleted_at IS NOT NULL AND deleted_at < :cutoff")
    suspend fun purgeOlderThan(cutoff: Long): Int

    @Query("SELECT COUNT(*) FROM credentials WHERE deleted_at IS NULL")
    fun observeCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM credentials WHERE deleted_at IS NULL AND tags LIKE '%\"' || :tag || '\"%'")
    fun observeCountForTag(tag: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM credentials WHERE deleted_at IS NULL AND is_favorite = 1")
    fun observeFavoriteCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM credentials WHERE deleted_at IS NOT NULL")
    fun observeRecycleBinCount(): Flow<Int>

    @Query("SELECT * FROM credentials WHERE deleted_at IS NULL")
    suspend fun getAll(): List<CredentialEntity>

    // Used by import dedup to detect (title, username) matches that live in the recycle bin.
    @Query("SELECT * FROM credentials WHERE deleted_at IS NOT NULL")
    suspend fun getAllInRecycleBin(): List<CredentialEntity>

    @Query("SELECT * FROM credentials WHERE deleted_at IS NOT NULL ORDER BY deleted_at DESC")
    fun observeRecycleBin(): Flow<List<CredentialEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<CredentialEntity>)

    @Query("DELETE FROM credentials")
    suspend fun deleteAll()

    @Query("SELECT * FROM credentials WHERE deleted_at IS NULL AND is_favorite = 1 ORDER BY updated_at DESC")
    fun observeFavorites(): Flow<List<CredentialEntity>>

    @Query("SELECT * FROM credentials WHERE deleted_at IS NULL AND is_favorite = 1 ORDER BY updated_at DESC")
    fun favoritesPagingSource(): PagingSource<Int, CredentialEntity>

    @Query("SELECT * FROM credentials WHERE deleted_at IS NULL AND totp_secret_encrypted IS NOT NULL ORDER BY title ASC")
    fun observeWithTotp(): Flow<List<CredentialEntity>>

    @Query("UPDATE credentials SET is_favorite = :isFavorite WHERE id = :id")
    suspend fun setFavorite(id: String, isFavorite: Boolean)

    @RawQuery(observedEntities = [CredentialEntity::class])
    fun pagingSourceRaw(query: SupportSQLiteQuery): PagingSource<Int, CredentialEntity>

    @RawQuery(observedEntities = [CredentialEntity::class])
    fun favoritesPagingSourceRaw(query: SupportSQLiteQuery): PagingSource<Int, CredentialEntity>

    @RawQuery(observedEntities = [CredentialEntity::class])
    fun observeListRaw(query: SupportSQLiteQuery): Flow<List<CredentialEntity>>

    @Query("""
        SELECT title FROM credentials
        WHERE deleted_at IS NULL
        AND (:tag = '' OR tags LIKE '%"' || :tag || '"%')
        ORDER BY lower(title) ASC
    """)
    fun observeAllTitlesAlphabetical(tag: String): Flow<List<String>>

    @Query("SELECT title FROM credentials WHERE deleted_at IS NULL AND is_favorite = 1 ORDER BY lower(title) ASC")
    fun observeFavoriteTitlesAlphabetical(): Flow<List<String>>
}

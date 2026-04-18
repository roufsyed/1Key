package com.onekey.core.data.local.dao

import androidx.paging.PagingSource
import androidx.room.*
import com.onekey.core.data.local.entity.CredentialEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CredentialDao {

    @Query(
        """
        SELECT * FROM credentials
        WHERE (:query = '' OR title LIKE '%' || :query || '%')
        AND (:tag = '' OR tags LIKE '%' || :tag || '%')
        ORDER BY updated_at DESC
        """
    )
    fun pagingSource(query: String, tag: String): PagingSource<Int, CredentialEntity>

    @Query(
        """
        SELECT * FROM credentials
        WHERE (:query = '' OR title LIKE '%' || :query || '%')
        AND (:tag = '' OR tags LIKE '%' || :tag || '%')
        ORDER BY updated_at DESC
        LIMIT :limit OFFSET :offset
        """
    )
    fun searchFlow(query: String, tag: String, limit: Int, offset: Int): Flow<List<CredentialEntity>>

    @Query("SELECT * FROM credentials WHERE id = :id")
    fun observeById(id: String): Flow<CredentialEntity?>

    @Query("SELECT * FROM credentials WHERE id = :id")
    suspend fun getById(id: String): CredentialEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: CredentialEntity)

    @Delete
    suspend fun delete(entity: CredentialEntity)

    @Query("DELETE FROM credentials WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT COUNT(*) FROM credentials")
    fun observeCount(): Flow<Int>

    @Query("SELECT * FROM credentials")
    suspend fun getAll(): List<CredentialEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<CredentialEntity>)

    @Query("DELETE FROM credentials")
    suspend fun deleteAll()

    @Query("SELECT * FROM credentials WHERE is_favorite = 1 ORDER BY updated_at DESC")
    fun observeFavorites(): Flow<List<CredentialEntity>>

    @Query("SELECT * FROM credentials WHERE totp_secret_encrypted IS NOT NULL ORDER BY title ASC")
    fun observeWithTotp(): Flow<List<CredentialEntity>>

    @Query("UPDATE credentials SET is_favorite = :isFavorite WHERE id = :id")
    suspend fun setFavorite(id: String, isFavorite: Boolean)
}

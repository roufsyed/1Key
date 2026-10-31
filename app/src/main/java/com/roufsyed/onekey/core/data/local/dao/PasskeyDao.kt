package com.roufsyed.onekey.core.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.roufsyed.onekey.core.data.local.entity.PasskeyEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PasskeyDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: PasskeyEntity)

    @Query("SELECT * FROM passkeys WHERE id = :id")
    suspend fun getById(id: String): PasskeyEntity?

    /** Every row, including those whose credential sits in the recycle bin. Export and sync use this. */
    @Query("SELECT * FROM passkeys ORDER BY created_at ASC")
    suspend fun getAll(): List<PasskeyEntity>

    /**
     * Rows whose owning credential is active. The join is the recycle-bin
     * filter: a passkey is never offered while its credential is soft-deleted,
     * and restoring the credential brings the passkey back without any write
     * to this table.
     */
    @Query(
        """SELECT p.* FROM passkeys p
           INNER JOIN credentials c ON c.id = p.credential_id
           WHERE c.deleted_at IS NULL
           ORDER BY p.created_at ASC"""
    )
    suspend fun getAllActive(): List<PasskeyEntity>

    @Query("SELECT * FROM passkeys WHERE credential_id = :credentialId ORDER BY created_at ASC")
    suspend fun getForCredential(credentialId: String): List<PasskeyEntity>

    @Query("SELECT * FROM passkeys WHERE credential_id = :credentialId ORDER BY created_at ASC")
    fun observeForCredential(credentialId: String): Flow<List<PasskeyEntity>>

    @Query("SELECT COUNT(*) FROM passkeys WHERE credential_id = :credentialId")
    fun observeCountForCredential(credentialId: String): Flow<Int>

    @Query("DELETE FROM passkeys WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM passkeys WHERE credential_id = :credentialId")
    suspend fun deleteForCredential(credentialId: String)

    /** Recycle-bin merge: the bin item's passkeys move to the surviving credential instead of dying with it. */
    @Query("UPDATE passkeys SET credential_id = :toCredentialId WHERE credential_id = :fromCredentialId")
    suspend fun reassignCredential(fromCredentialId: String, toCredentialId: String)

    @Query("DELETE FROM passkeys")
    suspend fun deleteAll()

    @Query("UPDATE passkeys SET last_used_at = :now WHERE id = :id")
    suspend fun touchLastUsed(id: String, now: Long)

    @Query("UPDATE passkeys SET backed_up_at = :now WHERE id IN (:ids) AND backed_up_at IS NULL")
    suspend fun markBackedUp(ids: List<String>, now: Long)
}

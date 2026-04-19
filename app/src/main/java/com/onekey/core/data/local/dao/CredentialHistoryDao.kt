package com.onekey.core.data.local.dao

import androidx.room.*
import com.onekey.core.data.local.entity.CredentialHistoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CredentialHistoryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: CredentialHistoryEntity)

    @Query("SELECT * FROM credential_history WHERE credential_id = :credentialId ORDER BY modified_at DESC")
    fun observeHistory(credentialId: String): Flow<List<CredentialHistoryEntity>>

    @Query(
        """
        DELETE FROM credential_history
        WHERE credential_id = :credentialId
        AND id NOT IN (
            SELECT id FROM credential_history
            WHERE credential_id = :credentialId
            ORDER BY modified_at DESC
            LIMIT :maxEntries
        )
        """
    )
    suspend fun trimHistory(credentialId: String, maxEntries: Int = 20)

    @Query("DELETE FROM credential_history WHERE credential_id = :credentialId")
    suspend fun deleteForCredential(credentialId: String)

    @Query("DELETE FROM credential_history")
    suspend fun deleteAll()
}

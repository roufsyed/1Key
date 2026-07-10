package com.roufsyed.onekey.core.data.local.dao

import androidx.room.*
import com.roufsyed.onekey.core.data.local.entity.CredentialHistoryEntity
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

    // Used by CredentialCipherMigrator's history pass to walk pre-v14 rows in
    // batches and re-encrypt them under HKDF subkeys + per-field AAD with the
    // title moved into title_encrypted. Newest first so the visible-recently-
    // edited entries' history convert before older ones.
    @Query("SELECT * FROM credential_history WHERE cipher_version < 2 ORDER BY modified_at DESC LIMIT :limit")
    suspend fun getLegacyCipherBatch(limit: Int): List<CredentialHistoryEntity>

    @Query("UPDATE credential_history SET title = :title, title_encrypted = :titleCt, iv_title = :titleIv, " +
        "username_encrypted = :uCt, iv_username = :uIv, password_encrypted = :pCt, iv_password = :pIv, " +
        "url_encrypted = :urlCt, iv_url = :urlIv, cipher_version = 2 WHERE id = :id")
    suspend fun upgradeRowToV2(
        id: String,
        title: String,
        titleCt: ByteArray, titleIv: ByteArray,
        uCt: ByteArray, uIv: ByteArray,
        pCt: ByteArray, pIv: ByteArray,
        urlCt: ByteArray?, urlIv: ByteArray?,
    )
}

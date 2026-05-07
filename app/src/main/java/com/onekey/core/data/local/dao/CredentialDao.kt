package com.onekey.core.data.local.dao

import androidx.paging.PagingSource
import androidx.room.*
import androidx.sqlite.db.SupportSQLiteQuery
import com.onekey.core.data.local.entity.CredentialEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CredentialDao {

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

    /**
     * Time-based OTP entries (TOTP, Steam Guard) — anything that rotates on a clock.
     * Drives the per-second recompute loop in TwoFaListViewModel. Excluding HOTP
     * here is load-bearing: HOTP codes only advance on explicit user tap; including
     * them in the recompute loop would silently regenerate codes every second
     * without persisting the counter, desyncing the user from the issuer.
     */
    @Query(
        """SELECT * FROM credentials
           WHERE deleted_at IS NULL
             AND totp_secret_encrypted IS NOT NULL
             AND otp_type IN ('TOTP', 'STEAM')
           ORDER BY title ASC"""
    )
    fun observeRotatingOtp(): Flow<List<CredentialEntity>>

    /**
     * Counter-based OTP entries (HOTP). Static list — only re-emits when a row's
     * counter is incremented or the entry itself is added/removed/edited. The 2FA
     * list combines this with [observeRotatingOtp] for display, with HOTP rows
     * showing a "Generate next code" button instead of a countdown ring.
     */
    @Query(
        """SELECT * FROM credentials
           WHERE deleted_at IS NULL
             AND totp_secret_encrypted IS NOT NULL
             AND otp_type = 'HOTP'
           ORDER BY title ASC"""
    )
    fun observeHotpEntries(): Flow<List<CredentialEntity>>

    /**
     * Atomically advance the HOTP counter for [id] by one. Returns the counter value
     * the caller should use for THIS code generation; the row is persisted at
     * `returned + 1` so the next tap derives the next code without re-reading.
     *
     * Why pre-increment-and-return: the persisted counter convention here is "value
     * to use for the next generation" — matching what `otpauth://hotp/?counter=0`
     * URIs encode (counter=0 → first code uses 0, then the entry advances to 1).
     *
     * Atomicity matters because two concurrent taps must produce two distinct codes,
     * not the same code twice. The `@Transaction` wrapping the read+update keeps it
     * in a single SQLite transaction, and Room serialises write-side coroutines so
     * two callers can't interleave against the same row.
     *
     * Returns null when no row matches [id] (deleted between observation and tap)
     * or the row's counter is null (the entry isn't a HOTP entry). Caller treats
     * null as a no-op rather than guessing.
     */
    @Transaction
    suspend fun atomicIncrementHotpCounter(id: String, now: Long): Long? {
        val current = getHotpCounter(id) ?: return null
        setHotpCounter(id, current + 1, now)
        return current
    }

    @Query("SELECT hotp_counter FROM credentials WHERE id = :id AND deleted_at IS NULL")
    suspend fun getHotpCounter(id: String): Long?

    @Query("UPDATE credentials SET hotp_counter = :counter, updated_at = :now WHERE id = :id")
    suspend fun setHotpCounter(id: String, counter: Long, now: Long)

    @Query("UPDATE credentials SET is_favorite = :isFavorite WHERE id = :id")
    suspend fun setFavorite(id: String, isFavorite: Boolean)

    /**
     * Bumps `accessed_at` to the supplied timestamp. Deliberately scoped to
     * active rows (`deleted_at IS NULL`) so a stray bump on a soft-deleted
     * credential is a silent no-op rather than reviving its "last used"
     * marker — a defence-in-depth gate for callers that don't filter
     * upstream. Does NOT touch `updated_at`: "accessed" and "modified" are
     * separate concepts (matching KeePassXC, 1Password).
     */
    @Query("UPDATE credentials SET accessed_at = :now WHERE id = :id AND deleted_at IS NULL")
    suspend fun touchAccessedAt(id: String, now: Long)

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

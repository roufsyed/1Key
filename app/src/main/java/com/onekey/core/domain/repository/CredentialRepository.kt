package com.onekey.core.domain.repository

import androidx.paging.PagingData
import com.onekey.core.domain.model.AppResult
import com.onekey.core.domain.model.Credential
import com.onekey.core.domain.model.CredentialSortOrder
import kotlinx.coroutines.flow.Flow

interface CredentialRepository {
    fun getPagedCredentials(
        query: String,
        tag: String,
        sortOrder: CredentialSortOrder = CredentialSortOrder.NEWEST_FIRST,
    ): Flow<PagingData<Credential>>
    fun observeCredential(id: String): Flow<Credential?>
    /** Emits regardless of soft-delete state — the detail screen needs to render bin items too. */
    fun observeCredentialIncludingDeleted(id: String): Flow<Credential?>
    suspend fun getCredential(id: String): AppResult<Credential>
    suspend fun saveCredential(credential: Credential): AppResult<Unit>
    /** Soft-deletes — moves the credential to the recycle bin. */
    suspend fun deleteCredential(id: String): AppResult<Unit>
    /** Permanently removes the credential, bypassing the recycle bin. */
    suspend fun hardDeleteCredential(id: String): AppResult<Unit>
    /** Restores a soft-deleted credential back to active state. */
    suspend fun restoreCredential(id: String): AppResult<Unit>
    /** Permanently removes a credential that's currently in the recycle bin. */
    suspend fun purgeFromRecycleBin(id: String): AppResult<Unit>
    /** Permanently removes every item currently in the recycle bin. */
    suspend fun emptyRecycleBin(): AppResult<Int>
    /** Permanently removes recycle-bin items deleted before [cutoff] (epoch ms). Returns rows purged. */
    suspend fun purgeRecycleBinOlderThan(cutoff: Long): AppResult<Int>
    fun observeRecycleBin(): Flow<List<Credential>>
    fun observeRecycleBinCount(): Flow<Int>
    /** Active items only. */
    suspend fun getAllCredentials(): AppResult<List<Credential>>
    /** Recycle-bin items only — used for import dedup against soft-deleted matches. */
    suspend fun getAllInRecycleBin(): AppResult<List<Credential>>
    suspend fun importCredentials(credentials: List<Credential>): AppResult<Int>
    fun observeCount(): Flow<Int>
    fun observeCountForTag(tag: String): Flow<Int>
    fun observeFavoriteCount(): Flow<Int>
    fun observeFavorites(): Flow<List<Credential>>
    fun observeFavoritesPaged(sortOrder: CredentialSortOrder = CredentialSortOrder.NEWEST_FIRST): Flow<PagingData<Credential>>
    fun observeCredentials(query: String, tag: String, sortOrder: CredentialSortOrder): Flow<List<Credential>>
    fun observeFavoritesSorted(sortOrder: CredentialSortOrder): Flow<List<Credential>>
    fun observeAllTitlesAlphabetical(tag: String): Flow<List<String>>
    fun observeFavoriteTitlesAlphabetical(): Flow<List<String>>
    /**
     * Time-based OTP enrolments (TOTP, Steam Guard) — anything that rotates on a
     * clock. Drives the per-second recompute loop in TwoFaListViewModel.
     */
    fun observeRotatingOtp(): Flow<List<Credential>>

    /**
     * Counter-based OTP enrolments (HOTP). Static list — only re-emits when a
     * row's counter changes or the entry is added/removed/edited. Combined with
     * [observeRotatingOtp] for the unified 2FA list display.
     */
    fun observeHotpEntries(): Flow<List<Credential>>

    /**
     * Atomically advance the HOTP counter for [credentialId]. Returns the counter
     * value to use for the *current* code generation; the row is persisted at
     * (returned + 1) for the next tap. See
     * [com.onekey.core.data.local.dao.CredentialDao.atomicIncrementHotpCounter] for
     * the atomicity contract.
     *
     * Returns [AppResult.Error] for IO/transaction failures and [AppResult.Success]
     * with `null` data when the credential isn't a HOTP entry (or has been deleted
     * since the user observed it). Callers should treat the null-data case as a
     * no-op rather than synthesising a counter.
     */
    suspend fun incrementHotpCounter(credentialId: String): AppResult<Long?>

    suspend fun toggleFavorite(id: String, isFavorite: Boolean): AppResult<Unit>
    suspend fun deleteAllCredentials(): AppResult<Unit>

    /**
     * Bumps `accessed_at` to the current epoch ms for an active credential.
     * Soft-deleted (recycle-bin) rows are silently skipped at the SQL level —
     * "accessed" should reflect real usage, not casual viewing of bin items.
     * Independent of `updated_at`; does not bump it.
     */
    suspend fun markAccessed(id: String): AppResult<Unit>
}

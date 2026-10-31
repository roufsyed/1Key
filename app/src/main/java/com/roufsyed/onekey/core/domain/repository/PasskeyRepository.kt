package com.roufsyed.onekey.core.domain.repository

import com.roufsyed.onekey.core.domain.model.AppResult
import com.roufsyed.onekey.core.domain.model.Passkey
import com.roufsyed.onekey.core.domain.model.PasskeySummary
import kotlinx.coroutines.flow.Flow

/**
 * Passkeys live beside credentials, never inside `Credential`: the full-decrypt
 * paths (`getAllCredentials`, used by export, sync, import planning and
 * autofill matching) must not touch private keys, and one corrupt passkey must
 * never break those flows. Callers fetch passkeys by credential or by RP ID at
 * the moment of use.
 */
interface PasskeyRepository {

    /** Inserts or replaces. A blank [Passkey.id] is assigned a UUID; the stored passkey is returned. */
    suspend fun save(passkey: Passkey): AppResult<Passkey>

    /** Full decrypt including the private key. Only the signing path should call this. */
    suspend fun getById(id: String): AppResult<Passkey>

    /** Lean projection of every passkey whose credential is active and whose RP ID equals [rpId]. */
    suspend fun listActiveForRpId(rpId: String): AppResult<List<PasskeySummary>>

    /** Lean projection for the credential detail screen. Empty while the vault is locked. */
    fun observeForCredential(credentialId: String): Flow<List<PasskeySummary>>

    /**
     * Every passkey, recycle bin included, for backup and sync. Rows that fail
     * to decrypt are skipped and counted in [ExportBatch.skipped] rather than
     * failing the whole export.
     */
    suspend fun allForExport(): AppResult<ExportBatch>

    suspend fun deleteById(id: String): AppResult<Unit>

    suspend fun deleteForCredential(credentialId: String): AppResult<Unit>

    /** Moves every passkey of [fromCredentialId] onto [toCredentialId] (recycle-bin merge). */
    suspend fun reassignCredential(fromCredentialId: String, toCredentialId: String): AppResult<Unit>

    suspend fun deleteAll(): AppResult<Unit>

    suspend fun touchLastUsed(id: String, now: Long = System.currentTimeMillis()): AppResult<Unit>

    /** Records that these passkeys are now inside an encrypted backup or sync file. */
    suspend fun markBackedUp(ids: List<String>, now: Long = System.currentTimeMillis()): AppResult<Unit>

    class ExportBatch(val passkeys: List<Passkey>, val skipped: Int)
}

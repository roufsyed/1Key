package com.roufsyed.onekey.core.data.snapshot

import com.roufsyed.onekey.core.data.local.dao.CredentialDao
import com.roufsyed.onekey.core.security.VaultLockedException
import kotlinx.coroutines.yield
import javax.inject.Inject
import javax.inject.Singleton

/**
 * On-demand search over the notes field. Notes never enter the lean
 * [VaultSnapshotStore] projection (they can be 64 KiB each and routinely hold
 * secrets), so a notes match is computed per query instead: every active row's
 * notes ciphertext is read, decrypted and compared, and only the matching ids
 * come back. Nothing is retained between calls, the plaintext of each note is
 * dropped as soon as it has been compared, and a scan that runs into a lock
 * answers with no matches rather than a partial list.
 */
interface NotesSearch {
    /** Ids of active credentials whose notes contain [query] (case-insensitive). Empty for a blank query. */
    suspend fun matchingIds(query: String): Set<String>
}

@Singleton
class NotesSearchImpl @Inject constructor(
    private val dao: CredentialDao,
    private val decryptor: CredentialDecryptor,
) : NotesSearch {

    override suspend fun matchingIds(query: String): Set<String> {
        if (query.isBlank()) return emptySet()
        val rows = dao.getActiveNotesRows()
        val hits = HashSet<String>()
        try {
            for (row in rows) {
                yield() // cancellation point: a newer query abandons this scan
                val notes = decryptor.decryptNotesOrNull(row) ?: continue
                if (notes.contains(query, ignoreCase = true)) hits += row.id
            }
        } catch (e: VaultLockedException) {
            // Locked mid-scan: the snapshot has already flipped to Locked and the
            // caller's state follows it. A partial answer must never leak out.
            return emptySet()
        }
        return hits
    }
}

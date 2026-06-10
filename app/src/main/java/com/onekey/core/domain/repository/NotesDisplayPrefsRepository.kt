package com.onekey.core.domain.repository

import kotlinx.coroutines.flow.Flow

/**
 * Per-credential UI-only state for the notes-display surface (markdown-notes
 * feature, Phase 4).
 *
 * Two independent sets are tracked:
 *
 *  - [observeIdsInPlainSourceMode] - credential IDs where the user has
 *    explicitly toggled the notes view to *plain source* mode (raw markdown
 *    text, no inline rendering). This is the user-driven preference.
 *  - [observeAutoFlippedIds] - credential IDs that the renderer auto-flipped
 *    to plain-source mode because the markdown payload was too large /
 *    too pathological to render inline. This is the renderer-driven escape
 *    hatch and is recorded so the auto-decision survives process restart
 *    without re-running the heuristic on every screen open.
 *
 * Both sets are stored as `Set<String>` of credential IDs.
 *
 * ## Security boundary - IMPORTANT
 *
 * This repository is backed by a **separate** [androidx.datastore.core.DataStore]
 * instance (`notes_display.preferences_pb`) from the main app preferences file
 * AND from the encrypted Room vault. The DataStore must **never** contain
 * encrypted vault data, decrypted vault data, master-password-derived material,
 * or any other secret. The only things that go in here are:
 *
 *   1. credential IDs (`String`, UUID-shaped) - these are *not* secret. The
 *      IDs themselves leak no credential content; they're equivalent to the
 *      `_id` column in the Room table which is also stored as plaintext.
 *   2. UI-only booleans about display preference (encoded by set membership).
 *
 * Anything else added here in the future must satisfy the same constraint.
 * If a future feature needs to persist anything secret on a per-credential
 * basis, it MUST use the encrypted Room vault, not this DataStore.
 */
interface NotesDisplayPrefsRepository {

    /**
     * Credential IDs that the user has explicitly toggled into plain-source
     * (raw markdown) mode. Membership in this set is sticky across process
     * restarts. Emits a fresh `Set<String>` whenever a [setPlainSource] call
     * changes membership.
     */
    fun observeIdsInPlainSourceMode(): Flow<Set<String>>

    /**
     * Credential IDs that the renderer auto-flipped to plain source because
     * the markdown body exceeded an inline-render budget. Membership is
     * sticky so the auto-decision is not re-litigated on every open.
     */
    fun observeAutoFlippedIds(): Flow<Set<String>>

    /**
     * Add or remove [credentialId] from the user-toggled plain-source set.
     * `true` adds; `false` removes (idempotent in both directions).
     */
    suspend fun setPlainSource(credentialId: String, isPlainSource: Boolean)

    /**
     * Record that the renderer auto-flipped [credentialId] to plain source.
     * Idempotent - re-marking an already-marked ID is a no-op write.
     */
    suspend fun markAutoFlipped(credentialId: String)
}

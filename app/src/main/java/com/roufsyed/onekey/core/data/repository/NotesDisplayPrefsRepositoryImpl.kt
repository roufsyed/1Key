package com.roufsyed.onekey.core.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.roufsyed.onekey.core.di.ApplicationScope
import com.roufsyed.onekey.core.di.NotesDisplayDataStore
import com.roufsyed.onekey.core.domain.repository.NotesDisplayPrefsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

private val KEY_PLAIN_SOURCE_IDS = stringSetPreferencesKey("plain_source_ids")
private val KEY_AUTO_FLIPPED_IDS = stringSetPreferencesKey("auto_flipped_ids")

/**
 * DataStore-backed implementation of [NotesDisplayPrefsRepository].
 *
 * Backed by a **separate** DataStore file (`notes_display.preferences_pb`) provided
 * via the [NotesDisplayDataStore] qualifier - see [com.roufsyed.onekey.core.di.DataStoreModule].
 * Keeping this file isolated from the main `onekey_prefs` file means:
 *
 *  - a future "reset vault" flow can wipe per-credential UI state by clearing
 *    one file without touching app-wide settings (theme, lock timeouts, etc.);
 *  - audits of "is the app putting anything sensitive in DataStore?" can answer
 *    the question for this surface independently of the wider preferences file.
 *
 * ### What is and isn't stored here
 *
 * The two `Set<String>` values are sets of credential IDs (UUIDs). Credential
 * IDs are **not** secret - they're stored unencrypted in the Room `credentials`
 * table's primary key column. The renderer-side flag (auto-flipped) and the
 * user-side flag (plain source) are each represented by set membership.
 *
 * **Never** put encrypted blobs, decrypted plaintext, master-password-derived
 * keys, or any other secret in this DataStore. See the class-level KDoc on
 * [NotesDisplayPrefsRepository] for the full security contract.
 *
 * ### Hot snapshot
 *
 * The full Preferences snapshot is held in a `StateFlow` started eagerly at
 * singleton construction time (i.e. app startup). This mirrors the pattern
 * used by [AppPreferencesRepositoryImpl] and guarantees that the very first
 * collector on `observeIdsInPlainSourceMode` / `observeAutoFlippedIds` gets
 * the current persisted value, not the initial empty placeholder.
 */
@Singleton
class NotesDisplayPrefsRepositoryImpl @Inject constructor(
    @NotesDisplayDataStore private val dataStore: DataStore<Preferences>,
    @ApplicationScope appScope: CoroutineScope,
) : NotesDisplayPrefsRepository {

    private val prefs: StateFlow<Preferences> = dataStore.data
        .stateIn(appScope, SharingStarted.Eagerly, emptyPreferences())

    override fun observeIdsInPlainSourceMode(): Flow<Set<String>> =
        prefs.map { it[KEY_PLAIN_SOURCE_IDS] ?: emptySet() }.distinctUntilChanged()

    override fun observeAutoFlippedIds(): Flow<Set<String>> =
        prefs.map { it[KEY_AUTO_FLIPPED_IDS] ?: emptySet() }.distinctUntilChanged()

    override suspend fun setPlainSource(credentialId: String, isPlainSource: Boolean) {
        dataStore.edit { p ->
            val current = p[KEY_PLAIN_SOURCE_IDS] ?: emptySet()
            val next = if (isPlainSource) current + credentialId else current - credentialId
            if (next.isEmpty()) p.remove(KEY_PLAIN_SOURCE_IDS) else p[KEY_PLAIN_SOURCE_IDS] = next
        }
    }

    override suspend fun markAutoFlipped(credentialId: String) {
        dataStore.edit { p ->
            val current = p[KEY_AUTO_FLIPPED_IDS] ?: emptySet()
            if (credentialId !in current) {
                p[KEY_AUTO_FLIPPED_IDS] = current + credentialId
            }
        }
    }
}

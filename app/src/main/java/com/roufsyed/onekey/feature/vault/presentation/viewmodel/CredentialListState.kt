package com.roufsyed.onekey.feature.vault.presentation.viewmodel

import com.roufsyed.onekey.core.data.snapshot.SnapshotCredential
import com.roufsyed.onekey.core.domain.model.CredentialSortOrder

/**
 * Sealed state for the snapshot-backed list screens (Favourites,
 * TaggedCredentialList).
 *
 *  - [Locked]   : vault is locked OR the shared
 *    [com.roufsyed.onekey.core.data.snapshot.VaultSnapshotStore] reports
 *    [com.roufsyed.onekey.core.data.snapshot.SnapshotState.Locked]. Screens render a
 *    spinner; the lock-screen overlay handles the actual unlock.
 *  - [Loading]  : the snapshot is mid first-decrypt, or
 *    [com.roufsyed.onekey.core.security.CredentialCipherMigrator] is rewriting legacy
 *    rows. UI shows a spinner; never the "no results" empty state.
 *  - [Loaded]   : filtered + sorted [SnapshotCredential] list ready. An empty
 *    list with the right empty-state copy is a valid Loaded value.
 *  - [Bypassed] : vault size exceeds
 *    [com.roufsyed.onekey.core.data.snapshot.VaultSnapshotStore.SNAPSHOT_CAP] (10 000).
 *    The list views surface a deterministic "vault too large" message; the
 *    user falls back to the home-screen search for navigation. We do NOT
 *    fall back to the legacy paged decrypt-all path because that would
 *    re-introduce per-row full-[com.roufsyed.onekey.core.domain.model.Credential]
 *    plaintext residency that PR4 is specifically removing.
 */
sealed interface CredentialListState {
    data object Locked : CredentialListState
    data object Loading : CredentialListState
    data class Loaded(val credentials: List<SnapshotCredential>) : CredentialListState
    data object Bypassed : CredentialListState
}

/**
 * In-memory comparator over [SnapshotCredential] matching the legacy
 * [com.roufsyed.onekey.core.data.repository.CredentialRepositoryImpl] comparator
 * semantics one-for-one:
 *
 *  - `NEWEST_FIRST`      sorts by `createdAt` descending.
 *  - `LAST_MODIFIED`     sorts by `updatedAt` descending.
 *  - `RECENTLY_ACCESSED` sorts by `accessedAt ?: updatedAt` descending. The
 *    `?: updatedAt` fall-back mirrors `Credential.toDomain()`'s coalescing
 *    of a null `accessed_at` column to `updated_at` before the legacy
 *    comparator runs; without it, never-accessed rows would silently sink
 *    to the bottom on this path. Behaviour-preserving on purpose.
 *  - `ALPHABETICAL`      sorts by `title.lowercase()` ascending. The
 *    default-locale `.lowercase()` matches the legacy path verbatim. A
 *    locale-aware comparator is a deliberate non-goal here so the legacy
 *    fast-path and this snapshot path stay in lock-step until a system-wide
 *    locale-aware sort lands as its own PR.
 *
 * Call sites should prefer a project-once form for `ALPHABETICAL`
 * (`list.map { it to it.title.lowercase() }.sortedBy { it.second }.map { it.first }`)
 * over `sortedWith(snapshotComparator())` for large lists, to avoid an
 * `O(n log n)` cascade of `lowercase()` calls inside the sort.
 */
internal fun CredentialSortOrder.snapshotComparator(): Comparator<SnapshotCredential> = when (this) {
    CredentialSortOrder.NEWEST_FIRST -> compareByDescending { it.createdAt }
    CredentialSortOrder.LAST_MODIFIED -> compareByDescending { it.updatedAt }
    CredentialSortOrder.RECENTLY_ACCESSED -> compareByDescending { it.accessedAt ?: it.updatedAt }
    CredentialSortOrder.ALPHABETICAL -> compareBy { it.title.lowercase() }
}

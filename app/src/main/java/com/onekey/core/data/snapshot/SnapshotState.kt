package com.onekey.core.data.snapshot

/**
 * Coarse-grained state of the shared decrypted vault snapshot held by
 * [VaultSnapshotStore]. ViewModels pattern-match exhaustively — the UI must
 * distinguish `Loading` from `Loaded(empty)` so empty-state copy ("No results")
 * never flashes during a transient first-decrypt window. See the design's
 * "pagination_strategy" section for the rendering contract.
 *
 * Transition table:
 *
 *   [Locked]   → vault is locked OR has just been locked (the
 *                synchronous [VaultLockHook] flipped state on the lock()
 *                caller's thread before key zero — see
 *                [VaultSnapshotStore.lock-clear semantics]).
 *
 *   [Loading]  → first-decrypt in flight, OR [CredentialCipherMigrator] is
 *                rewriting legacy rows, OR coordinator settling. Consumers
 *                show a spinner; never the "no results" empty state.
 *
 *   [Loaded]   → hot, decrypted active credentials (`deleted_at IS NULL`).
 *                Lean projection — no password, no notes, no OTP secret,
 *                no custom fields. See [SnapshotCredential].
 *
 *   [Bypassed] → vault exceeds [VaultSnapshotStore.SNAPSHOT_CAP] — fall back
 *                to SQL-streamed observers (existing repo methods). Plaintext
 *                residency is the existing per-screen footprint, not the
 *                shared snapshot's footprint.
 */
sealed interface SnapshotState {
    data object Locked : SnapshotState
    data object Loading : SnapshotState
    data class Loaded(val credentials: List<SnapshotCredential>) : SnapshotState
    data object Bypassed : SnapshotState
}

package com.onekey.core.data.snapshot

import androidx.sqlite.db.SimpleSQLiteQuery
import com.onekey.core.data.local.dao.CredentialDao
import com.onekey.core.di.MigrationStatusFlow
import com.onekey.core.di.SnapshotScope
import com.onekey.core.security.VaultKeyHolder
import com.onekey.core.security.VaultLockHook
import com.onekey.core.security.VaultLockedException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Shared, hot, lean, decrypted vault snapshot.
 *
 * Holds a [SnapshotState] that is `Loaded(List<SnapshotCredential>)` while
 * the vault is unlocked, the cipher migrator is idle, and the vault size
 * fits under [SNAPSHOT_CAP]. The store eliminates three real symptoms:
 *
 *   1. **Cold-first-search latency** in the main vault and in autofill. The
 *      coordinator is always live; subscribed consumers see `Loaded` as
 *      soon as the first decrypt pass completes - no per-screen warm-up.
 *
 *   2. **Duplicate plaintext residency** across `VaultViewModel`,
 *      `AutofillUnlockViewModel`, etc. - one decrypted list shared, lean
 *      projection only.
 *
 *   3. **Repeated whole-vault decrypts** per UI surface visit.
 *
 * **Lock-clear contract (security-critical).** [VaultKeyHolder.lock] invokes
 * [VaultLockHook.onLockBeforeKeyZero] **synchronously** before flipping the
 * `isUnlocked` flag and before zeroing key bytes. The hook (installed in
 * [init]) writes `_state.value = Locked`, calls [CredentialDecryptor.onLock]
 * (drops cached HKDF subkeys), and cancels the in-flight upstream
 * decryption [Job]. By the time `lock()` returns to its caller, the snapshot
 * has dropped its plaintext list reference and the next garbage-collection
 * pass will reclaim it. NO assumption is made about cross-dispatcher
 * StateFlow visibility - the synchronous hook is the contract.
 *
 * **Coordinator branches.** A single coordinator [collectLatest]s a
 * three-way [combine] of `isUnlocked`, `isMigrating`, and the row count.
 * Each emission cancels the prior upstream subscription (if any) and routes
 * to one of:
 *
 *   - `!unlocked` → `Locked`. No upstream.
 *   - `migrating` → `Loading`. No upstream (avoids Room invalidation storm
 *     while [CredentialCipherMigrator] rewrites legacy rows).
 *   - `count > SNAPSHOT_CAP` → `Bypassed`. No upstream - consumers fall
 *     back to SQL observers.
 *   - else → `Loading`, then launch upstream: subscribe to
 *     `dao.observeListRaw(SELECT_ACTIVE)`, conflate bursts, decrypt to the
 *     lean projection, emit `Loaded`.
 *
 * **Mid-decrypt lock safety.** The upstream's decryption loop yields between
 * rows and re-checks `keyHolder.isUnlocked.value` post-yield. If the vault
 * locks mid-loop, the decryptor throws [VaultLockedException]; the launched
 * Job's `catch` block ensures `state` is `Locked` (typically already so via
 * the hook) and exits cleanly without publishing a partial list.
 *
 * **Threading.** The coordinator and all decryption work run on
 * [Dispatchers.Default] (injected via [SnapshotScope]). CPU-bound: HKDF +
 * AES-GCM per row × per emission.
 */
@Singleton
class VaultSnapshotStore @Inject constructor(
    private val dao: CredentialDao,
    private val decryptor: CredentialDecryptor,
    private val keyHolder: VaultKeyHolder,
    @MigrationStatusFlow private val migrationStatus: StateFlow<Boolean>,
    @SnapshotScope private val snapshotScope: CoroutineScope,
) {

    private val _state = MutableStateFlow<SnapshotState>(SnapshotState.Locked)

    /**
     * Current snapshot state. Subscribers should pattern-match exhaustively
     * - the UI distinguishes `Loading` from `Loaded(empty)` so the empty
     * state never flashes during a transient first-decrypt window. See
     * [SnapshotState] for transition contract.
     */
    val state: StateFlow<SnapshotState> = _state.asStateFlow()

    /** Active upstream decrypt job; cancelled on every state transition. */
    @Volatile private var upstreamJob: Job? = null

    init {
        // STEP 1 - Install the synchronous lock hook FIRST, before launching
        // the coordinator. A `lock()` that races against the coordinator's
        // first scheduling will still hit the hook on the lock() caller's
        // thread and synchronously transition `_state.value` to `Locked`.
        keyHolder.snapshotHook = VaultLockHook {
            // Synchronous on lock() caller's thread. NO suspending or
            // blocking calls. The plaintext list reference becomes
            // unreachable from `state.value` before lock() proceeds to
            // zero key bytes.
            _state.value = SnapshotState.Locked
            decryptor.onLock()
            // Cancel via the public Job API - safe from any thread. The
            // upstream coroutine sees CancellationException at its next
            // suspension point (yield() inside decryptAllLeanWithLockCheck
            // OR the next collect resumption from dao.observeListRaw).
            upstreamJob?.cancel()
            upstreamJob = null
        }

        // STEP 2 - Launch the always-live coordinator. `snapshotScope` is
        // SupervisorJob + Dispatchers.Default (see SnapshotModule), so a
        // single CancellationException in an upstream branch cannot kill
        // the coordinator itself.
        snapshotScope.launch {
            combine(
                keyHolder.isUnlocked,
                migrationStatus,
                dao.observeCount(),
            ) { unlocked, migrating, count ->
                Triple(unlocked, migrating, count)
            }.collectLatest { (unlocked, migrating, count) ->
                // Every emission cancels the prior upstream subscription
                // (if any) and re-routes. collectLatest's own cancellation
                // semantics handle the body suspension; we additionally
                // cancel the launched upstream Job explicitly because the
                // hook may have already done so, and we want the field
                // nulled before the next branch potentially launches a new
                // one.
                upstreamJob?.cancel()
                upstreamJob = null
                when {
                    !unlocked -> _state.value = SnapshotState.Locked
                    migrating -> _state.value = SnapshotState.Loading
                    count > SNAPSHOT_CAP -> _state.value = SnapshotState.Bypassed
                    else -> {
                        _state.value = SnapshotState.Loading
                        // Inherits the parent's dispatcher (snapshotScope:
                        // Dispatchers.Default in production, TestScope's
                        // dispatcher in tests). Explicit `Dispatchers.Default`
                        // here would override the test dispatcher and make
                        // the upstream loop untestable on virtual time.
                        // Threading guarantee is preserved at the
                        // SnapshotModule level - see provideSnapshotScope.
                        upstreamJob = launch {
                            try {
                                dao.observeListRaw(SELECT_ACTIVE_SQL)
                                    .conflate()
                                    .collect { entities ->
                                        val list = decryptor.decryptAllLeanWithLockCheck(entities)
                                        _state.value = SnapshotState.Loaded(list)
                                    }
                            } catch (e: VaultLockedException) {
                                // Vault locked mid-decrypt. The synchronous
                                // VaultLockHook has already transitioned
                                // `_state` to `Locked`; this catch keeps the
                                // launched Job from completing exceptionally
                                // and ensures `Locked` regardless of hook
                                // ordering.
                                if (_state.value !is SnapshotState.Locked) {
                                    _state.value = SnapshotState.Locked
                                }
                            }
                            // CancellationException is intentionally NOT
                            // caught - collectLatest's body cancellation
                            // propagates upward as designed.
                        }
                    }
                }
            }
        }
    }

    /**
     * Idempotent no-op. The store is always live; subscribers do not need
     * to "prime" anything. This method exists so call sites can document
     * intent ("I depend on the snapshot being responsive when I render")
     * without sprouting magic comments. Removing all `prime()` calls would
     * not change behaviour.
     */
    fun prime(): Unit = Unit

    companion object {
        /**
         * Maximum active-credential count for which the snapshot maintains
         * a hot decrypted list. Vaults larger than this transition to
         * [SnapshotState.Bypassed] and consumers fall back to per-screen
         * SQL observers. Tuned empirically before GA - 10 000 is the
         * starting point covering >99% of real password-manager vaults
         * while bounding heap residency to ~1.2 MB at the lean projection
         * size (~120 bytes/row).
         */
        const val SNAPSHOT_CAP: Int = 10_000

        /**
         * The single canonical query for the snapshot's upstream. Active
         * rows only (`deleted_at IS NULL`); ordering deferred to consumers
         * which apply their own sort comparators.
         */
        private val SELECT_ACTIVE_SQL: SimpleSQLiteQuery =
            SimpleSQLiteQuery("SELECT * FROM credentials WHERE deleted_at IS NULL")
    }
}

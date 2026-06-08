package com.onekey.feature.sync.domain

import kotlinx.coroutines.flow.StateFlow

/**
 * Coordinates the Sync-on-Master-Password-Unlock feature.
 *
 * Called from [com.onekey.core.data.repository.AuthRepositoryImpl] at the moment of
 * a successful master-password unlock. The contract is intentionally narrow:
 *
 *  - [maybeTriggerSync] takes the master password the user just typed (as a fresh
 *    `password.copyOf()`). The engine reads sync preferences directly from
 *    [com.onekey.core.domain.repository.AppPreferencesRepository.getSyncGateDirect],
 *    and IF sync is enabled with a configured location, derives a one-shot backup
 *    encryption key via [com.onekey.core.security.CryptoManager.deriveBackupKey].
 *    The password CharArray is consumed (zeroed in place) by the time this returns
 *    or throws, regardless of branch. The derived key lives only inside the engine
 *    for the duration of the sync coroutine, then is zeroed in a `finally` block.
 *
 *  - [state] is a hot [StateFlow] consumed by the top-bar chip (via AppViewModel)
 *    and the Settings -> Sync screen.
 *
 *  - [dismissChip] resets the state to [SyncState.Idle] - used by the chip's X
 *    button and the auto-dismiss timer for `Synced` and `Failed` states.
 *
 * Lock-clear safety: the engine installs a [com.onekey.core.security.VaultLockHook]
 * on [com.onekey.core.security.VaultKeyHolder.syncHook] at construction time.
 * When the vault locks while a sync is in flight, the hook fires synchronously on
 * the lock() caller's thread, cancels the sync coroutine, and the coroutine's
 * `finally` block zeros the derived key and reports
 * `SyncState.Failed(VAULT_LOCKED)`. No partial `vault-backup.1key` file is ever
 * exposed - sync writes to `vault-backup.1key.part` first, then atomically renames.
 */
interface SyncEngine {

    val state: StateFlow<SyncState>

    /**
     * Fire-and-forget. Reads sync gate, derives backup key if enabled, launches the
     * sync coroutine. Consumes [password]: the CharArray is zeroed in place. The
     * unlock flow returns to UI immediately.
     */
    fun maybeTriggerSync(password: CharArray)

    /**
     * Resets `state` to `Idle`. Idempotent. No-op if the current state is
     * [SyncState.Syncing] (a sync in flight cannot be cancelled by the user; the
     * UI's chip does not show an X during `Syncing`).
     */
    fun dismissChip()
}

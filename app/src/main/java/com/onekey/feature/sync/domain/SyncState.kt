package com.onekey.feature.sync.domain

/**
 * State machine for the Sync-on-Master-Password-Unlock feature, observed by the
 * top-bar chip and the Settings -> Sync screen.
 *
 * Transitions:
 *  - [Idle]    -> [Syncing] when a master-password unlock arrives and sync is enabled
 *  - [Syncing] -> [Synced] on successful atomic write
 *  - [Syncing] -> [Failed] on any error path (probe fail, IOException, vault locked, etc.)
 *  - [Synced] / [Failed] -> [Idle] when the chip is dismissed (auto-dismiss timer or X)
 *
 * NOT a persistent state - lives in [SyncEngine.state] only. Last-success timestamp is
 * persisted separately via [com.onekey.core.domain.repository.AppPreferencesRepository.setSyncLastSuccessAt].
 */
sealed class SyncState {
    data object Idle : SyncState()
    data object Syncing : SyncState()
    data object Synced : SyncState()
    data class Failed(val reason: SyncFailureReason) : SyncState()
}

/**
 * Discrete failure causes for a Sync attempt. The mapping is intentionally narrow so
 * the user-visible chip text never reveals filesystem paths, URIs, or stack-trace
 * details. The precise diagnostic is read from this enum in the Settings -> Sync
 * screen when the user taps the failure chip; the chip body itself shows a generic
 * "Backup didn't save" label.
 */
enum class SyncFailureReason {
    /** SAF tree URI permission was revoked (user uninstalled cloud app, revoked in Settings). */
    STORAGE_ACCESS_REVOKED,
    /** Target tree URI no longer resolves to a document. */
    STORAGE_NOT_FOUND,
    /** Disk full / quota exceeded. */
    STORAGE_FULL,
    /** Vault locked mid-sync (auto-lock fired, user pressed lock). */
    VAULT_LOCKED,
    /** Anything else - generic catch-all. */
    INTERNAL_ERROR;

    /** Human-readable text rendered in Settings -> Sync. NO paths, NO filenames, NO PII. */
    fun userVisibleText(): String = when (this) {
        STORAGE_ACCESS_REVOKED -> "Couldn't reach the backup folder. Tap Sync location to re-authorize."
        STORAGE_NOT_FOUND -> "The backup folder is no longer available. Pick a new one."
        STORAGE_FULL -> "Wasn't enough free space on the device."
        VAULT_LOCKED -> "Stopped because the vault locked."
        INTERNAL_ERROR -> "Something went wrong saving the backup."
    }
}

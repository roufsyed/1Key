package com.roufsyed.onekey.feature.sync.domain

/**
 * Posts the (optional, opt-in) "Vault backed up" completion notification after a
 * successful sync. Implementations:
 *
 *  - Honor the `syncCompletionNotificationEnabled` preference (no-op when off).
 *  - Honor the system `POST_NOTIFICATIONS` permission state (no-op when missing).
 *  - Use `NotificationCompat.VISIBILITY_SECRET` so the preview is hidden on the
 *    lock screen.
 *  - Use `CATEGORY_STATUS` and `setLocalOnly(true)` (no Wear / Auto mirroring).
 *  - Body text MUST be generic ("Vault backed up. Just now."). No path, no
 *    filename, no vault size.
 *  - Group key shared across all sync notifications, with `setOnlyAlertOnce(true)`
 *    so back-to-back syncs do not stack alerts.
 *
 * Injected into [SyncEngine] as an interface so the engine never depends on a
 * Context-bound implementation directly. A `NoOp` binding is provided until the
 * real implementation lands in the notification layer.
 */
fun interface SyncCompletionNotifier {
    suspend fun notifyCompletion()
}

/** Placeholder until the real `AndroidSyncCompletionNotifier` lands in L10. */
class NoOpSyncCompletionNotifier @javax.inject.Inject constructor() : SyncCompletionNotifier {
    override suspend fun notifyCompletion() = Unit
}

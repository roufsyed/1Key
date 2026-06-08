package com.onekey.feature.sync.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.onekey.R
import com.onekey.core.security.VaultKeyHolder
import com.onekey.feature.sync.domain.SyncCompletionNotifier
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Posts the optional "Vault backed up" completion notification.
 *
 * Constraints enforced here:
 *  - **Channel idempotency.** Creates the `sync_completion` channel on API 26+ on
 *    first post if not already present. NotificationManager treats channel ID as
 *    primary key, so repeated creation is safe.
 *  - **Lock state.** Skips posting if the vault is no longer unlocked at post
 *    time (e.g. the user manually locked between sync success and this call).
 *  - **POST_NOTIFICATIONS gating.** On API 33+ checks the runtime permission; if
 *    not granted, silently returns. The Settings -> Sync screen is responsible
 *    for requesting the permission when the user toggles the preference ON.
 *  - **Lock-screen visibility.** Notification builds with
 *    `VISIBILITY_SECRET` so previews never appear on the lock screen. Even a
 *    generic "Vault backed up" line could telegraph timing patterns to anyone
 *    glancing at the device.
 *  - **No PII in body.** Title and body are fixed generic strings - no path,
 *    no filename, no folder, no vault size.
 *  - **Single-line group.** `setGroup` + `setOnlyAlertOnce` so back-to-back
 *    syncs (rare but possible) do not stack alerts.
 */
@Singleton
class AndroidSyncCompletionNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val keyHolder: VaultKeyHolder,
) : SyncCompletionNotifier {

    override suspend fun notifyCompletion() {
        // Vault may have locked between the sync coroutine setting Synced state and
        // this notifier being invoked. Skip posting in that race window.
        if (!keyHolder.isUnlocked()) return

        // Runtime permission (API 33+). The Settings screen requests this when the
        // user toggles the preference ON; if denied, sync still runs silently.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }

        ensureChannel()

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher) // existing app icon - no new asset added
            .setContentTitle("Vault backed up")
            .setContentText("Just now")
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .setLocalOnly(true)
            .setOnlyAlertOnce(true)
            .setGroup(GROUP_KEY)
            .setShowWhen(true)
            .build()

        // Use a stable notification ID so the second sync replaces the first instead
        // of stacking. The Settings -> Sync screen never deep-links to the notification
        // (no PendingIntent), so the auto-cancel-on-tap behaviour just dismisses it.
        runCatching {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Sync completion",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shown after each successful encrypted backup sync."
            setShowBadge(false)
            enableLights(false)
            enableVibration(false)
            lockscreenVisibility = android.app.Notification.VISIBILITY_SECRET
        }
        manager.createNotificationChannel(channel)
    }

    private companion object {
        const val CHANNEL_ID = "sync_completion"
        const val NOTIFICATION_ID = 4901 // arbitrary, stable
        const val GROUP_KEY = "1key.sync"
    }
}

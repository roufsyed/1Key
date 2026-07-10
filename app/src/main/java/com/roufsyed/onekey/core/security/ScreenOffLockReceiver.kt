package com.roufsyed.onekey.core.security

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.roufsyed.onekey.core.di.ApplicationScope
import com.roufsyed.onekey.core.domain.repository.AuthRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Defence-in-depth backstop for the inactivity auto-lock.
 *
 * Why this exists alongside the per-surface `Modifier.lockAware()` compensation:
 * the in-app touch / IME ping correctly resets the inactivity timer for everything
 * the user does *inside* the app, but it depends on every dialog / sheet / popup
 * / TextField using the lock-aware wrappers. If a future surface slips through
 * (raw `AlertDialog` somewhere, a third-party library that ships its own dialog),
 * a vault could stay unlocked under an idle user.
 *
 * `ACTION_SCREEN_OFF` is a stable Android system broadcast that fires the moment
 * the device display turns off - power button, display timeout, AOD transition.
 * That's an unambiguous "user is no longer looking at this device" signal that
 * doesn't depend on any of our compensation working correctly.
 *
 * Registered for the lifetime of the process. We never unregister because:
 *  - the receiver IS process-singleton scoped;
 *  - `Application.onTerminate` is unreliable in production (skipped on real
 *    devices when the OS kills the process);
 *  - on process death the OS itself drops dynamically registered receivers.
 *
 * Locking is gated on `keyHolder.isUnlocked()` so a `SCREEN_OFF` while the vault
 * is already locked is a cheap no-op rather than a redundant DB write. The
 * `authRepository.lock()` itself is also idempotent - this gate is purely an
 * optimisation.
 */
@Singleton
class ScreenOffLockReceiver @Inject constructor(
    @ApplicationContext private val context: Context,
    private val authRepository: AuthRepository,
    private val keyHolder: VaultKeyHolder,
    @ApplicationScope private val appScope: CoroutineScope,
) {

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_SCREEN_OFF) return
            if (!keyHolder.isUnlocked()) return
            // lock() is suspending (writes to DataStore-backed VaultKeyHolder
            // sentinel state in some impls); launch on the application scope so
            // the receiver returns immediately and the OS doesn't ANR us.
            appScope.launch { authRepository.lock() }
        }
    }

    /**
     * Idempotent. Safe to call multiple times - the second registration would
     * leak duplicate receivers, so guard with [registered]. In practice
     * [register] is called exactly once from [com.roufsyed.onekey.OneKeyApp.onCreate].
     */
    fun register() {
        if (registered) return
        // RECEIVER_NOT_EXPORTED on API 33+: this receiver is for system
        // broadcasts (ACTION_SCREEN_OFF is OS-emitted), so other apps must
        // never be able to spoof events into it.
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        registered = true
    }

    @Volatile private var registered = false
}

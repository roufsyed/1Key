package com.onekey.core.security

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.onekey.core.domain.model.BackgroundLockTimeout
import com.onekey.core.domain.repository.AppPreferencesRepository
import com.onekey.core.domain.repository.AuthRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AutoLockManager @Inject constructor(
    private val authRepository: AuthRepository,
    private val appPrefs: AppPreferencesRepository,
) : DefaultLifecycleObserver {

    // Application-scoped scope: lives as long as the process. SupervisorJob ensures
    // one cancelled child does not tear down sibling timers.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var idleJob: Job? = null
    private var backgroundJob: Job? = null
    private var isVaultUnlocked = false
    private var lastActivityResetMs = 0L

    // Suppresses auto-lock while a system file picker is in the foreground.
    // The picker causes onStop to fire even though the vault must stay unlocked
    // so that the picker callback can complete its export/import work.
    @Volatile private var pickerActive = false

    // Counter-based inactivity-timer suppression for in-app flows that hold the
    // user's attention without producing touch events - camera previews (OCR,
    // QR scanners). Composes under picker suppression: pickers acquire this
    // and additionally flip pickerActive to also disable the background timer.
    // Cameras only acquire this; they do NOT disable the background timer, so
    // turning the screen off while a camera is open still locks the vault.
    private val inactivitySuppressionCount = AtomicInteger(0)

    /**
     * Suppresses the inactivity timer until [releaseInactivitySuppression] is
     * called. Re-entrant - N acquires require N releases. Callers MUST pair
     * each acquire with exactly one release (typically via DisposableEffect).
     */
    fun acquireInactivitySuppression() {
        if (inactivitySuppressionCount.getAndIncrement() == 0) {
            idleJob?.cancel()
            idleJob = null
        }
    }

    fun releaseInactivitySuppression() {
        // CAS-loop clamp: never let the count go negative if a buggy caller
        // releases without acquiring. The counter is the source of truth for
        // onUserActivity's skip check, so a stuck-negative value would leave
        // the idle timer permanently disabled.
        var prev: Int
        do {
            prev = inactivitySuppressionCount.get()
            if (prev <= 0) return
        } while (!inactivitySuppressionCount.compareAndSet(prev, prev - 1))
        if (prev - 1 == 0 && isVaultUnlocked) resetIdleTimer()
    }

    fun suppressForPicker() {
        pickerActive = true
        // Picker time must not count against either timer. Picker composes on top
        // of inactivity suppression, then additionally cancels backgroundJob since
        // pickers also fire onStop when they take focus (an unwanted background
        // lock). onStart re-arms the idle timer through clearPickerSuppression.
        acquireInactivitySuppression()
        backgroundJob?.cancel()
        backgroundJob = null
    }

    fun clearPickerSuppression() {
        if (!pickerActive) return
        pickerActive = false
        releaseInactivitySuppression()
    }

    init {
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        scope.launch {
            authRepository.isUnlocked().collect { unlocked ->
                isVaultUnlocked = unlocked
                if (!unlocked) {
                    idleJob?.cancel()
                    backgroundJob?.cancel()
                    idleJob = null
                    backgroundJob = null
                } else {
                    resetIdleTimer()
                }
            }
        }
    }

    // Called from MainActivity.onUserInteraction(). Throttled to avoid coroutine
    // churn during continuous gestures (scrolling, etc.). Skipped while any
    // inactivity suppression is held - re-arming the idle timer underneath an
    // active suppression would defeat the whole point of holding it.
    fun onUserActivity() {
        if (!isVaultUnlocked) return
        if (inactivitySuppressionCount.get() > 0) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastActivityResetMs < ACTIVITY_THROTTLE_MS) return
        lastActivityResetMs = now
        resetIdleTimer()
    }

    override fun onStop(owner: LifecycleOwner) {
        if (!isVaultUnlocked || pickerActive) return
        idleJob?.cancel()
        idleJob = null
        backgroundJob?.cancel()
        backgroundJob = scope.launch {
            val timeout = appPrefs.getBackgroundLockTimeout().first()
            if (pickerActive) return@launch
            if (timeout == BackgroundLockTimeout.IMMEDIATE) {
                authRepository.lock()
            } else {
                delay(timeout.millis)
                if (isVaultUnlocked && !pickerActive) authRepository.lock()
            }
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        backgroundJob?.cancel()
        backgroundJob = null
        // If a suppression is active (camera flow re-foregrounding mid-session),
        // leave idleJob alone - the suppressor still owns it.
        if (isVaultUnlocked && inactivitySuppressionCount.get() == 0) resetIdleTimer()
    }

    private fun resetIdleTimer() {
        idleJob?.cancel()
        idleJob = scope.launch {
            val timeout = appPrefs.getInactivityLockTimeout().first()
            if (!timeout.isEnabled) return@launch
            delay(timeout.millis)
            if (isVaultUnlocked) authRepository.lock()
        }
    }

    private companion object {
        const val ACTIVITY_THROTTLE_MS = 5_000L
    }
}

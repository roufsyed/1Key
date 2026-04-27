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

    fun suppressForPicker() {
        pickerActive = true
        // Cancel any background lock job that fired in onStop just before this flag
        // was set — otherwise it would lock the vault while the picker is open.
        backgroundJob?.cancel()
        backgroundJob = null
    }
    fun clearPickerSuppression() { pickerActive = false }

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
    // churn during continuous gestures (scrolling, etc.).
    fun onUserActivity() {
        if (!isVaultUnlocked) return
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
        if (isVaultUnlocked) resetIdleTimer()
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

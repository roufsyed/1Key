package com.onekey.core.security

import com.onekey.core.di.ApplicationScope
import com.onekey.core.domain.repository.AppPreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Why was the vault locked? Set by callers immediately before [com.onekey.core.domain.repository.AuthRepository.lock]
 * so the [LockScreen][com.onekey.feature.auth.presentation.screen.LockScreen] can show a one-shot
 * explanation dialog and pause biometric until the user re-authenticates with their master password.
 */
sealed class LockReason {
    /** User entered the wrong master password too many times during a sensitive action. */
    data class TooManyFailedAttempts(val context: String) : LockReason()
    /** User entered the wrong PIN too many times on the LockScreen. */
    data object TooManyFailedPinAttempts : LockReason()
    /** User failed biometric authentication too many times on the LockScreen. */
    data object TooManyFailedBiometricAttempts : LockReason()
}

/**
 * Persistent (DataStore-backed) holder for the most-recent vault-lock reason. Persistence
 * matters: if the app is force-stopped or process-killed mid-lockout, the biometric pause
 * has to survive across the restart so the user still has to enter their master password.
 *
 * Cleared by [com.onekey.feature.auth.presentation.viewmodel.AuthViewModel.unlockWithPassword]
 * on a successful master-password unlock - that's how the user proves identity and earns
 * biometric back for the next session.
 */
@Singleton
class LockReasonStore @Inject constructor(
    private val appPrefs: AppPreferencesRepository,
    @ApplicationScope appScope: CoroutineScope,
) {
    val reason: StateFlow<LockReason?> = appPrefs.getLockReasonContext()
        .map(::contextToReason)
        .stateIn(appScope, SharingStarted.Eagerly, null)

    /**
     * Reads the current reason **directly from DataStore**, bypassing the
     * [reason] StateFlow AND the cached `prefs` StateFlow that the regular
     * getter composes on (which would have the same propagation lag).
     * Used by security-critical unlock paths that must see writes from a
     * concurrent [set] without waiting for the StateFlow collector on
     * `appScope` to propagate the next emission. `set` awaits the DataStore
     * commit, so any [latest] call sequenced after a `set` sees the new
     * value with no race window.
     */
    suspend fun latest(): LockReason? =
        contextToReason(appPrefs.getLockReasonContextDirect())

    /**
     * Suspends until the DataStore write commits. Callers MUST await this before triggering
     * a vault lock - otherwise a quick app-kill between [set] and the navigation to LockScreen
     * leaves the lock reason unpersisted, and biometric resumes on the next start.
     */
    suspend fun set(reason: LockReason) {
        // PIN-failure variant carries no context, so we use a sentinel string that round-trips
        // back to TooManyFailedPinAttempts on read. Keeps a single DataStore key authoritative
        // for "is the vault locked due to too-many-failed-attempts" - important for
        // BiometricUnlockGate which checks just that key for the locked-with-reason state.
        when (reason) {
            is LockReason.TooManyFailedAttempts -> appPrefs.setLockReasonContext(reason.context)
            LockReason.TooManyFailedPinAttempts -> appPrefs.setLockReasonContext(PIN_SENTINEL)
            LockReason.TooManyFailedBiometricAttempts -> appPrefs.setLockReasonContext(BIOMETRIC_SENTINEL)
        }
    }

    suspend fun clear() {
        appPrefs.setLockReasonContext(null)
    }

    private fun contextToReason(ctx: String?): LockReason? = when (ctx) {
        null -> null
        PIN_SENTINEL -> LockReason.TooManyFailedPinAttempts
        BIOMETRIC_SENTINEL -> LockReason.TooManyFailedBiometricAttempts
        else -> LockReason.TooManyFailedAttempts(ctx)
    }

    private companion object {
        // Sentinels chosen so they can't collide with real master-password-failure
        // contexts (those are human-readable activity names like "biometric setup").
        // Confined to this file - callers always see typed LockReason subclasses.
        private const val PIN_SENTINEL = "__pin_failure__"
        private const val BIOMETRIC_SENTINEL = "__biometric_failure__"
    }
}

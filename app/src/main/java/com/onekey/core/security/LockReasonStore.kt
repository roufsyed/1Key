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
}

/**
 * Persistent (DataStore-backed) holder for the most-recent vault-lock reason. Persistence
 * matters: if the app is force-stopped or process-killed mid-lockout, the biometric pause
 * has to survive across the restart so the user still has to enter their master password.
 *
 * Cleared by [com.onekey.feature.auth.presentation.viewmodel.AuthViewModel.unlockWithPassword]
 * on a successful master-password unlock — that's how the user proves identity and earns
 * biometric back for the next session.
 */
@Singleton
class LockReasonStore @Inject constructor(
    private val appPrefs: AppPreferencesRepository,
    @ApplicationScope appScope: CoroutineScope,
) {
    val reason: StateFlow<LockReason?> = appPrefs.getLockReasonContext()
        .map { ctx -> ctx?.let { LockReason.TooManyFailedAttempts(it) } }
        .stateIn(appScope, SharingStarted.Eagerly, null)

    /**
     * Suspends until the DataStore write commits. Callers MUST await this before triggering
     * a vault lock — otherwise a quick app-kill between [set] and the navigation to LockScreen
     * leaves the lock reason unpersisted, and biometric resumes on the next start.
     */
    suspend fun set(reason: LockReason) {
        when (reason) {
            is LockReason.TooManyFailedAttempts -> appPrefs.setLockReasonContext(reason.context)
        }
    }

    suspend fun clear() {
        appPrefs.setLockReasonContext(null)
    }
}

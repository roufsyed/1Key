package com.roufsyed.onekey.core.security

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val KEY_FAILED_BIOMETRIC_ATTEMPTS = intPreferencesKey("biometric_failed_attempts")

/**
 * DataStore-backed tracker for biometric unlock failures.
 *
 * Why DataStore (vs. an in-memory ViewModel field):
 *  - The autofill activities own their own `AuthViewModel` instance, so an
 *    in-memory counter would not be shared with `LockScreen`. An attacker
 *    holding the device could rotate between surfaces and effectively triple
 *    the attempt budget. Persistence shares the counter across all surfaces.
 *  - Swipe-from-recents between failures must not reset the counter.
 *
 * Threshold semantics - *unlike* [PinAttemptTracker]/[PasswordAttemptTracker]
 * this tracker does NOT define a tiered cooldown. The OS [BiometricPrompt]
 * imposes its own per-sensor lockout (typically 30s after 5 hardware wrong
 * reads, escalating). Our counter is the app-level "after 3 wrong, force
 * master-password fallback" guard. The cooldown is conceptually owned by the
 * platform; our store only persists the count.
 *
 * The counter resets on a successful biometric unlock OR a successful
 * master-password unlock - both are "user proved identity" signals consistent
 * with [PinAttemptTracker.reset].
 */
@Singleton
class BiometricAttemptTracker @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {

    companion object {
        /**
         * App-level threshold. After this many wrong biometric reads, the
         * vault locks and the master-password fallback is forced via
         * [LockReason.TooManyFailedBiometricAttempts]. The OS will also have
         * imposed its own lockout long before this on most devices.
         */
        const val MAX_FAILURES = 3
    }

    /**
     * Live count of failures persisted to disk. Suitable for "X attempts
     * remaining" UI surfaces. Distinct-until-changed so callers observing
     * via Compose `collectAsState` only recompose on real changes.
     */
    val failureCount: Flow<Int> = dataStore.data
        .map { prefs -> prefs[KEY_FAILED_BIOMETRIC_ATTEMPTS] ?: 0 }
        .distinctUntilChanged()

    /**
     * Reads the count directly from DataStore (NOT from a cached StateFlow).
     * Used by unlock paths that must avoid the propagation lag between a
     * concurrent [recordFailure] write and a StateFlow re-emission.
     */
    suspend fun currentFailureCount(): Int =
        dataStore.data.first()[KEY_FAILED_BIOMETRIC_ATTEMPTS] ?: 0

    /**
     * Records one failed biometric attempt and returns the post-increment
     * count. `DataStore.edit` is internally serialized so the read-modify-
     * write of the counter is atomic under concurrent callers.
     */
    suspend fun recordFailure(): Int {
        var newCount = 0
        dataStore.edit { prefs ->
            newCount = (prefs[KEY_FAILED_BIOMETRIC_ATTEMPTS] ?: 0) + 1
            prefs[KEY_FAILED_BIOMETRIC_ATTEMPTS] = newCount
        }
        return newCount
    }

    /** Clears the counter on successful unlock (biometric or master password). */
    suspend fun reset() {
        dataStore.edit { prefs -> prefs.remove(KEY_FAILED_BIOMETRIC_ATTEMPTS) }
    }
}

package com.onekey.core.security

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val KEY_FAILED_PIN_ATTEMPTS = intPreferencesKey("pin_failed_attempts")
private val KEY_LAST_PIN_FAILURE_MS = longPreferencesKey("pin_last_failure_ms")

/**
 * DataStore-backed tracker for PIN unlock failures on the LockScreen.
 *
 * Mirrors [PasswordAttemptTracker] for the same reason: an in-memory ViewModel field
 * is reset by every process kill, so an attacker holding the device can swipe the
 * app from recents between attempts and bypass the in-session "3 strikes" guard
 * entirely. Persisting the counter to DataStore means a force-stop, swipe-from-
 * recents, or even a process death between attempts cannot reset the lockout -
 * the app starts cold with the prior failure state intact.
 *
 * Tier shape (matches password):
 *   3-4   failures →  30 seconds
 *   5-9   failures →   5 minutes
 *   10+   failures →   1 hour
 *
 * Counter is reset on a successful PIN unlock OR a successful master-password
 * unlock - both are valid "user has proved identity" signals. It intentionally
 * survives individual lockout windows so repeated failures escalate to longer tiers.
 */
@Singleton
class PinAttemptTracker @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {

    companion object {
        /**
         * Lockout duration in milliseconds for a given failure count, or null if no
         * lockout applies yet. Identical thresholds to [PasswordAttemptTracker] -
         * keep them in lock-step so neither path is the weak link.
         */
        fun lockoutDurationMs(attempts: Int): Long? = when {
            attempts >= 10 -> 3_600_000L
            attempts >= 5  ->   300_000L
            attempts >= 3  ->    30_000L
            else           -> null
        }
    }

    /**
     * Emits the epoch-ms timestamp when the current lockout window expires, or null
     * if fewer than 3 failures have been recorded.
     *
     * The emitted value may lie in the past if the lockout window has already elapsed.
     * Callers must compare against [System.currentTimeMillis] to determine whether
     * the lockout is still active.
     */
    val lockoutUntilMs: Flow<Long?> = dataStore.data.map { prefs ->
        val attempts = prefs[KEY_FAILED_PIN_ATTEMPTS] ?: 0
        val lastMs = prefs[KEY_LAST_PIN_FAILURE_MS] ?: 0L
        val duration = lockoutDurationMs(attempts) ?: return@map null
        lastMs + duration
    }

    /**
     * Returns the cumulative failure count. Used to drive the existing UX where the
     * 3rd persistent failure also sets [LockReason.TooManyFailedPinAttempts] and
     * forces the master-password fallback for the rest of the session.
     */
    suspend fun failureCount(): Int =
        dataStore.data.first()[KEY_FAILED_PIN_ATTEMPTS] ?: 0

    /**
     * Records one failed PIN attempt. Increments the failure count and updates the
     * last-failure timestamp, (re)starting the lockout window for the current tier.
     *
     * DataStore.edit is internally serialized and atomic, so the read-modify-write
     * of the counter is safe under concurrent callers (e.g. a paranoid UI that
     * fires a second submit while the first is still in flight).
     *
     * Returns the post-increment failure count so callers can react to threshold
     * crossings (e.g. setting a LockReason at count == 3) without a second read.
     */
    suspend fun recordFailure(): Int {
        var newCount = 0
        dataStore.edit { prefs ->
            newCount = (prefs[KEY_FAILED_PIN_ATTEMPTS] ?: 0) + 1
            prefs[KEY_FAILED_PIN_ATTEMPTS] = newCount
            prefs[KEY_LAST_PIN_FAILURE_MS] = System.currentTimeMillis()
        }
        return newCount
    }

    /** Clears the failure counter and last-failure timestamp on successful unlock. */
    suspend fun reset() {
        dataStore.edit { prefs ->
            prefs.remove(KEY_FAILED_PIN_ATTEMPTS)
            prefs.remove(KEY_LAST_PIN_FAILURE_MS)
        }
    }
}

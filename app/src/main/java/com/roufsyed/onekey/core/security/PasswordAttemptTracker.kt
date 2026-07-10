package com.roufsyed.onekey.core.security

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val KEY_FAILED_PW_ATTEMPTS = intPreferencesKey("pw_failed_attempts")
private val KEY_LAST_PW_FAILURE_MS = longPreferencesKey("pw_last_failure_ms")

/**
 * DataStore-backed tracker for master-password unlock failures on the LockScreen.
 *
 * Persisting to DataStore (rather than an in-memory var) means a force-stop or
 * swipe-from-recents between attempts cannot reset the lockout - the app starts
 * cold with the prior failure state intact, preventing an adversary from bypassing
 * the lockout by killing the process.
 *
 * Counter resets only on a successful master-password unlock; it intentionally
 * survives individual lockout windows so repeated failures escalate to longer tiers.
 */
@Singleton
class PasswordAttemptTracker @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {

    companion object {
        /**
         * Returns the lockout duration in milliseconds for a given failure count,
         * or null if no lockout applies yet.
         *
         *   3-4  failures ->  30 seconds
         *   5-9  failures ->   5 minutes
         *   10+  failures ->   1 hour
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
        val attempts = prefs[KEY_FAILED_PW_ATTEMPTS] ?: 0
        val lastMs = prefs[KEY_LAST_PW_FAILURE_MS] ?: 0L
        val duration = lockoutDurationMs(attempts) ?: return@map null
        lastMs + duration
    }

    /**
     * Records one failed unlock attempt. Increments the failure count and updates the
     * last-failure timestamp, (re)starting the lockout window for this tier.
     *
     * DataStore.edit is internally serialized and atomic, so the read-modify-write
     * of the counter is safe even under concurrent callers.
     */
    suspend fun recordFailure() {
        dataStore.edit { prefs ->
            prefs[KEY_FAILED_PW_ATTEMPTS] = (prefs[KEY_FAILED_PW_ATTEMPTS] ?: 0) + 1
            prefs[KEY_LAST_PW_FAILURE_MS] = System.currentTimeMillis()
        }
    }

    /** Clears both the failure counter and last-failure timestamp on successful unlock. */
    suspend fun reset() {
        dataStore.edit { prefs ->
            prefs.remove(KEY_FAILED_PW_ATTEMPTS)
            prefs.remove(KEY_LAST_PW_FAILURE_MS)
        }
    }
}

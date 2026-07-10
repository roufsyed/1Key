package com.roufsyed.onekey.core.security

import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory, app-singleton counter for sensitive in-vault verification flows
 * (biometric-enable confirmation, Secret Key enable / disable / rotate, etc.).
 * Singleton-scoped on purpose: the count must survive subscreen navigation
 * so a user can't reset the lockout by simply leaving and re-entering the
 * Security settings page.
 *
 * Reset on successful verification or after the lockout fires; cleared again on
 * vault lock/unlock cycles since [LockReasonStore] takes over from there.
 *
 * # Separate counters per flow (challenger Issue 6)
 *
 * Each sensitive flow that re-prompts for the master password owns its own
 * counter. A single shared counter would let three biometric-enable failures
 * lock out a user from rotating their Secret Key, and vice versa - the
 * UX cost (forcing a full vault re-unlock to clear an unrelated lockout)
 * outweighs the small extra state. The KdfMigrator runs its own counter
 * inside its mutex and is not represented here.
 *
 * Backed by AtomicInteger so the counter stays correct if a future caller invokes
 * from a non-Main dispatcher - the read-modify-write of `++` on a plain Int would
 * lose updates and let a user exceed the lockout cap.
 */
@Singleton
class AuthAttemptsStore @Inject constructor() {
    private val biometricEnableAttempts = AtomicInteger(0)
    private val secretKeyAttempts = AtomicInteger(0)

    /** Returns the new attempts count after incrementing. */
    fun incrementBiometricEnable(): Int = biometricEnableAttempts.incrementAndGet()

    fun resetBiometricEnable() { biometricEnableAttempts.set(0) }

    /**
     * Returns the new Secret Key attempts count after incrementing. Used by
     * SecretKeySettingsViewModel to track wrong-password attempts across the
     * enable / disable / rotate flows (a single shared SK budget; the user
     * is going through the same reauth dialog regardless of which
     * transition they pick).
     */
    fun incrementSecretKey(): Int = secretKeyAttempts.incrementAndGet()

    fun resetSecretKey() { secretKeyAttempts.set(0) }
}

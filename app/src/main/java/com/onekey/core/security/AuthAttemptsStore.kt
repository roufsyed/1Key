package com.onekey.core.security

import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory, app-singleton counter for sensitive in-vault verification flows
 * (biometric-enable confirmation, etc.). Singleton-scoped on purpose: the count
 * must survive subscreen navigation so a user can't reset the lockout by simply
 * leaving and re-entering the Security settings page.
 *
 * Reset on successful verification or after the lockout fires; cleared again on
 * vault lock/unlock cycles since [LockReasonStore] takes over from there.
 *
 * Backed by AtomicInteger so the counter stays correct if a future caller invokes
 * from a non-Main dispatcher — the read-modify-write of `++` on a plain Int would
 * lose updates and let a user exceed the lockout cap.
 */
@Singleton
class AuthAttemptsStore @Inject constructor() {
    private val biometricEnableAttempts = AtomicInteger(0)

    /** Returns the new attempts count after incrementing. */
    fun incrementBiometricEnable(): Int = biometricEnableAttempts.incrementAndGet()

    fun resetBiometricEnable() { biometricEnableAttempts.set(0) }
}

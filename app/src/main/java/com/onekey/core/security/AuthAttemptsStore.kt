package com.onekey.core.security

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
 */
@Singleton
class AuthAttemptsStore @Inject constructor() {
    private var biometricEnableAttempts = 0

    /** Returns the new attempts count after incrementing. */
    fun incrementBiometricEnable(): Int = ++biometricEnableAttempts

    fun resetBiometricEnable() { biometricEnableAttempts = 0 }
}

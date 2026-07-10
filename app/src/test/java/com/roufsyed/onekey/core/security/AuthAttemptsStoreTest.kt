package com.roufsyed.onekey.core.security

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Behavioural locks for [AuthAttemptsStore].
 *
 * The store is pure JVM state (an `AtomicInteger` per flow), no Android
 * dependencies. Tests run without Robolectric so they exercise the same
 * code paths the production singleton uses on real devices.
 *
 * What we pin here:
 *  - Biometric-enable and Secret Key counters are independent. Three failed
 *    biometric-enable attempts must NOT lock the user out of an SK rotate
 *    (and vice versa). Challenger Issue 6.
 *  - The counters start at 0, increment monotonically, and reset to 0 on
 *    demand. The reset is per-flow and does not touch the other flow's
 *    counter.
 *  - `incrementSecretKey` returns the NEW count after incrementing - the
 *    same shape as `incrementBiometricEnable` so the call sites that gate
 *    on `>= 3` work identically for both flows.
 */
class AuthAttemptsStoreTest {

    @Test fun biometricEnable_and_secretKey_counters_are_independent() {
        // Three failed biometric attempts: count climbs from 0 to 3.
        // Meanwhile the SK counter is untouched.
        val store = AuthAttemptsStore()

        repeat(3) { store.incrementBiometricEnable() }
        assertEquals(
            "Biometric counter must reflect three increments",
            3,
            store.incrementBiometricEnable() - 1,
        )
        // SK counter has never been touched - must be at 0.
        assertEquals(
            "SK counter must NOT be affected by biometric increments",
            1,
            store.incrementSecretKey(),
        )
    }

    @Test fun incrementSecretKey_returns_new_count() {
        val store = AuthAttemptsStore()

        assertEquals(1, store.incrementSecretKey())
        assertEquals(2, store.incrementSecretKey())
        assertEquals(3, store.incrementSecretKey())
    }

    @Test fun resetSecretKey_zeros_the_counter() {
        val store = AuthAttemptsStore()
        repeat(3) { store.incrementSecretKey() }

        store.resetSecretKey()
        assertEquals(
            "Next increment after reset must return 1",
            1,
            store.incrementSecretKey(),
        )
    }

    @Test fun resetSecretKey_does_not_touch_biometric_counter() {
        val store = AuthAttemptsStore()
        store.incrementBiometricEnable()
        store.incrementSecretKey()

        store.resetSecretKey()

        // Biometric counter is still at 1; next increment returns 2.
        assertEquals(2, store.incrementBiometricEnable())
    }

    @Test fun resetBiometricEnable_does_not_touch_secretKey_counter() {
        val store = AuthAttemptsStore()
        store.incrementBiometricEnable()
        store.incrementSecretKey()

        store.resetBiometricEnable()

        // SK counter is still at 1; next increment returns 2.
        assertEquals(2, store.incrementSecretKey())
    }
}

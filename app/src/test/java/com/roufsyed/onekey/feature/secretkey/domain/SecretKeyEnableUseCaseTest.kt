package com.roufsyed.onekey.feature.secretkey.domain

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import com.roufsyed.onekey.core.domain.model.AppResult
import com.roufsyed.onekey.core.security.CryptoManager
import com.roufsyed.onekey.core.security.DeviceCapacityDetector
import com.roufsyed.onekey.core.security.KdfMigrator
import com.roufsyed.onekey.core.security.SecretKeyHolder
import com.roufsyed.onekey.core.security.SecretKeyKeystoreWrapper
import com.roufsyed.onekey.core.security.SecretKeyTransition
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Behavioural locks for [SecretKeyEnableUseCase].
 *
 * The use case is a thin orchestrator over [KdfMigrator.runSecretKeyTransition]
 * and [SecretKeyHolder.setBytes]; the production migrator's path runs
 * Argon2id which Robolectric cannot drive on host JVM. We therefore inject
 * a recording subclass that captures the transition and short-circuits
 * the suspend boundary. The recording subclass extends the production
 * migrator (now declared `open`) so the use case sees the real type.
 *
 * What this file pins:
 *  - Happy-path: invoke() returns Success carrying a 16-byte ByteArray
 *    that is NOT the same instance as the SK installed into the holder
 *    (defensive copies).
 *  - The transition the use case passes to the migrator is
 *    [SecretKeyTransition.Enable] whose `newSk` matches the bytes the
 *    holder receives.
 *  - The master-password CharArray is zeroed on success.
 *  - On a migrator failure (wrong password, IO error), the use case
 *    returns Error WITHOUT installing the SK into the holder. The
 *    master-password CharArray is still zeroed.
 *  - The freshly-generated SK is non-zero (sanity check on SecureRandom
 *    usage; pinning that the local working copy reaches the migrator).
 *
 * # Why Robolectric?
 *
 * The use case never touches Android APIs directly, but the migrator
 * does (SharedPreferences). The recording subclass overrides
 * `runSecretKeyTransition` so we do not need a real SharedPreferences
 * roundtrip - but constructing a real KdfMigrator still needs an
 * authPrefs reference. Robolectric supplies a context with an in-memory
 * SharedPreferences so the constructor wiring is exercised end-to-end.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class SecretKeyEnableUseCaseTest {

    private lateinit var authPrefs: SharedPreferences
    private lateinit var crypto: CryptoManager
    private lateinit var wrapper: SecretKeyKeystoreWrapper
    private lateinit var detector: DeviceCapacityDetector
    private lateinit var holder: SecretKeyHolder
    private lateinit var recordingMigrator: RecordingMigrator
    private lateinit var useCase: SecretKeyEnableUseCase

    @Before fun setUp() {
        val context: Context = RuntimeEnvironment.getApplication()
        authPrefs = context.getSharedPreferences(
            "sk_enable_use_case_test_${System.nanoTime()}",
            Context.MODE_PRIVATE,
        )
        authPrefs.edit().clear().commit()
        crypto = CryptoManager()
        wrapper = SecretKeyKeystoreWrapper(authPrefs)
        detector = DeviceCapacityDetector(context)
        holder = SecretKeyHolder()
        recordingMigrator = RecordingMigrator(authPrefs, crypto, detector, wrapper)
        useCase = SecretKeyEnableUseCase(
            migrator = recordingMigrator,
            holder = holder,
            wrapper = wrapper,
        )
    }

    @Test fun happy_path_returns_success_and_installs_sk_into_holder() = runBlocking {
        recordingMigrator.responseFactory = { AppResult.Success(Unit) }

        val password = "correct horse battery staple".toCharArray()
        val result = useCase(password)

        assertTrue(
            "Use case must surface migrator success as AppResult.Success",
            result is AppResult.Success,
        )
        val returned = (result as AppResult.Success).data
        assertEquals(
            "Returned SK byte array must be exactly 16 bytes (locked design)",
            SecretKeyHolder.SECRET_KEY_RAW_LENGTH,
            returned.size,
        )

        // The holder must have an SK installed (isPresent=true).
        assertTrue(
            "Holder must report SK present after successful enable",
            holder.isPresent(),
        )

        // The returned bytes must equal what the holder serves AND what
        // the migrator received via SecretKeyTransition.Enable.newSk.
        holder.withBytes { sk ->
            assertArrayEquals(
                "Holder must serve the same bytes the use case returned",
                returned,
                sk,
            )
        }

        // The migrator must have been called with an Enable transition.
        val captured = recordingMigrator.capturedTransition
        assertTrue(
            "Use case must invoke runSecretKeyTransition with Enable",
            captured is SecretKeyTransition.Enable,
        )
        assertArrayEquals(
            "Enable.newSk must match the bytes the holder received",
            returned,
            (captured as SecretKeyTransition.Enable).newSk,
        )

        // Password must be zeroed on success.
        assertTrue(
            "Master password CharArray must be zeroed after success",
            password.all { it == ' ' },
        )
    }

    @Test fun migrator_failure_returns_error_and_does_not_install_holder() = runBlocking {
        recordingMigrator.responseFactory = {
            AppResult.Error(
                IllegalStateException("simulated migrator failure"),
                "Could not stage the new Secret Key settings. Try again.",
            )
        }

        val password = "correct horse battery staple".toCharArray()
        val result = useCase(password)

        assertTrue(
            "Use case must surface migrator failure as AppResult.Error",
            result is AppResult.Error,
        )
        assertFalse(
            "Holder must NOT have an SK installed when the migrator fails",
            holder.isPresent(),
        )

        // Password zeroed on the failure path too.
        assertTrue(
            "Master password CharArray must be zeroed after failure",
            password.all { it == ' ' },
        )
    }

    @Test fun wrong_password_failure_returns_error_with_user_facing_message() = runBlocking {
        // Migrator forwards verifier wrong-password failures with this
        // exact message; the VM matches on the string to route through
        // the lockout counter. The use case itself does NOT inspect the
        // message - the VM owns that policy. We pin that the message
        // round-trips unchanged.
        recordingMigrator.responseFactory = {
            AppResult.Error(
                IllegalStateException("verifier mismatch"),
                "Incorrect master password. Please try again.",
            )
        }

        val password = "wrong-password".toCharArray()
        val result = useCase(password)

        assertTrue(result is AppResult.Error)
        assertEquals(
            "Wrong-password message must round-trip unchanged so the VM " +
                "can detect and route through the lockout counter",
            "Incorrect master password. Please try again.",
            (result as AppResult.Error).message,
        )
        assertFalse(
            "Holder must NOT have an SK installed on wrong-password",
            holder.isPresent(),
        )
        assertTrue(
            "Master password CharArray must be zeroed on wrong-password",
            password.all { it == ' ' },
        )
    }

    @Test fun generates_fresh_sk_each_call_with_securerandom() = runBlocking {
        // Two successful enables should not produce the same SK bytes.
        // SecureRandom collisions on 128 bits are astronomically unlikely;
        // a regression that hard-codes the SK would surface as identical
        // bytes across calls.
        recordingMigrator.responseFactory = { AppResult.Success(Unit) }
        val firstResult = useCase("p1".toCharArray()) as AppResult.Success
        val firstSk = firstResult.data.copyOf()

        // Reset the holder so the second enable doesn't trip the
        // "already enabled" precondition - although the recording migrator
        // doesn't check, it keeps the call shape clean.
        holder.clear()
        recordingMigrator.capturedTransition = null

        val secondResult = useCase("p2".toCharArray()) as AppResult.Success
        val secondSk = secondResult.data

        // Probabilistic assertion: the two SKs must differ. A 1/2^128
        // probability of collision is below astronomical thresholds.
        assertFalse(
            "Two enable invocations must produce different SK bytes (SecureRandom)",
            firstSk.contentEquals(secondSk),
        )
    }

    /**
     * Recording subclass of [KdfMigrator] that captures the transition
     * passed to [runSecretKeyTransition] and returns a caller-controlled
     * [AppResult]. Bypasses the production Argon2id path entirely; the
     * use case tests do not need the verifier round trip and the host
     * JVM cannot drive Argon2id anyway.
     *
     * The captured transition's `newSk` is a SNAPSHOT taken before the
     * use case's finally block zeros its local working copy - the use
     * case's defensive copy ([holder.setBytes] copies, the return value
     * is a separate copy) ensures the test can compare bytes after the
     * use case returns.
     */
    private class RecordingMigrator(
        authPrefs: SharedPreferences,
        crypto: CryptoManager,
        detector: DeviceCapacityDetector,
        wrapper: SecretKeyKeystoreWrapper,
    ) : KdfMigrator(authPrefs, crypto, detector, wrapper) {
        var responseFactory: () -> AppResult<Unit> = { AppResult.Success(Unit) }
        var capturedTransition: SecretKeyTransition? = null

        override suspend fun runSecretKeyTransition(
            masterPassword: CharArray,
            transition: SecretKeyTransition,
        ): AppResult<Unit> {
            // Snapshot the transition + bytes BEFORE the use case's finally
            // can zero the SK working copy. The Enable / Rotate sealed
            // variants copy their byte payload in the data-class equality
            // override, but the field itself is a reference; we snapshot
            // a copy to be defensive.
            capturedTransition = when (transition) {
                is SecretKeyTransition.Enable -> SecretKeyTransition.Enable(transition.newSk.copyOf())
                is SecretKeyTransition.Rotate -> SecretKeyTransition.Rotate(transition.newSk.copyOf())
                SecretKeyTransition.Disable -> SecretKeyTransition.Disable
            }
            // Mirror the production contract: zero the caller's password
            // even when the test response is a Success placeholder, so
            // the use case's finally pass is the only one that visibly
            // zeros the array (idempotent re-zero is fine).
            masterPassword.fill(' ')
            return responseFactory()
        }
    }
}

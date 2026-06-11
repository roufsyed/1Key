package com.onekey.feature.secretkey.domain

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import com.onekey.core.domain.model.AppResult
import com.onekey.core.security.CryptoManager
import com.onekey.core.security.DeviceCapacityDetector
import com.onekey.core.security.KdfMigrator
import com.onekey.core.security.SecretKeyHolder
import com.onekey.core.security.SecretKeyKeystoreWrapper
import com.onekey.core.security.SecretKeyTransition
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Behavioural locks for [SecretKeyDisableUseCase].
 *
 * What this file pins:
 *  - Happy path returns Success and clears the in-memory holder. The
 *    on-disk wipe + Keystore alias delete is the migrator's
 *    responsibility (tested in KdfMigrator + SecretKeyTransition).
 *  - The transition the use case passes to the migrator is
 *    [SecretKeyTransition.Disable] (the singleton).
 *  - Migrator failure does NOT clear the holder - the active SK is
 *    untouched and a subsequent `holder.withBytes` continues to serve
 *    the live SK.
 *  - The master-password CharArray is zeroed on every branch.
 *
 * Follows the same RecordingMigrator pattern as [SecretKeyEnableUseCaseTest]
 * to bypass the production Argon2id path.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class SecretKeyDisableUseCaseTest {

    private lateinit var authPrefs: SharedPreferences
    private lateinit var crypto: CryptoManager
    private lateinit var wrapper: SecretKeyKeystoreWrapper
    private lateinit var detector: DeviceCapacityDetector
    private lateinit var holder: SecretKeyHolder
    private lateinit var recordingMigrator: RecordingMigrator
    private lateinit var useCase: SecretKeyDisableUseCase

    @Before fun setUp() {
        val context: Context = RuntimeEnvironment.getApplication()
        authPrefs = context.getSharedPreferences(
            "sk_disable_use_case_test_${System.nanoTime()}",
            Context.MODE_PRIVATE,
        )
        authPrefs.edit().clear().commit()
        crypto = CryptoManager()
        wrapper = SecretKeyKeystoreWrapper(authPrefs)
        detector = DeviceCapacityDetector(context)
        holder = SecretKeyHolder()
        recordingMigrator = RecordingMigrator(authPrefs, crypto, detector, wrapper)
        useCase = SecretKeyDisableUseCase(
            migrator = recordingMigrator,
            holder = holder,
        )
    }

    @Test fun happy_path_returns_success_and_clears_holder() = runBlocking {
        // Seed the holder with a current SK to simulate an unlocked vault.
        holder.setBytes(ByteArray(SecretKeyHolder.SECRET_KEY_RAW_LENGTH) { 0xAB.toByte() })
        assertTrue("Pre-condition: holder must be loaded before disable", holder.isPresent())

        recordingMigrator.responseFactory = { AppResult.Success(Unit) }
        val password = "correct horse battery staple".toCharArray()

        val result = useCase(password)

        assertTrue(
            "Use case must surface migrator success as AppResult.Success",
            result is AppResult.Success,
        )
        assertFalse(
            "Holder must be cleared after a successful disable",
            holder.isPresent(),
        )
        assertTrue(
            "Master password CharArray must be zeroed after success",
            password.all { it == ' ' },
        )
        assertTrue(
            "Use case must invoke runSecretKeyTransition with Disable",
            recordingMigrator.capturedTransition === SecretKeyTransition.Disable,
        )
    }

    @Test fun migrator_failure_leaves_holder_intact() = runBlocking {
        // Seed an SK so we can confirm the holder is NOT cleared on failure.
        holder.setBytes(ByteArray(SecretKeyHolder.SECRET_KEY_RAW_LENGTH) { 0xCC.toByte() })
        recordingMigrator.responseFactory = {
            AppResult.Error(
                IllegalStateException("simulated failure"),
                "Could not stage the new Secret Key settings. Try again.",
            )
        }
        val password = "anything".toCharArray()

        val result = useCase(password)

        assertTrue(result is AppResult.Error)
        assertTrue(
            "Holder must remain loaded when the migrator fails - the active " +
                "SK is untouched and the unlocked session continues",
            holder.isPresent(),
        )
        assertTrue(
            "Master password CharArray must be zeroed on failure",
            password.all { it == ' ' },
        )
    }

    @Test fun wrong_password_failure_routes_message_to_caller() = runBlocking {
        recordingMigrator.responseFactory = {
            AppResult.Error(
                IllegalStateException("verifier mismatch"),
                "Incorrect master password. Please try again.",
            )
        }
        val password = "wrong".toCharArray()

        val result = useCase(password)
        assertTrue(result is AppResult.Error)
        val err = result as AppResult.Error
        assertTrue(
            "Wrong-password message must round-trip so the VM can route through the lockout counter",
            err.message?.contains("Incorrect master password") == true,
        )
        assertTrue(
            "Master password CharArray must be zeroed on wrong-password",
            password.all { it == ' ' },
        )
    }

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
            capturedTransition = transition
            masterPassword.fill(' ')
            return responseFactory()
        }
    }
}

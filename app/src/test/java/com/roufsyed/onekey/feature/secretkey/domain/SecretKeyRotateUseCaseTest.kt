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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Behavioural locks for [SecretKeyRotateUseCase].
 *
 * What this file pins:
 *  - Happy path returns Success carrying the FRESH SK (not the previous
 *    SK that was in the holder before the rotate). This is the "drop
 *    old SK on rotate" property surfaced at the use-case layer; the
 *    wrapper's `wrap()` does the matching deletion at the Keystore layer.
 *  - The transition the use case passes to the migrator is
 *    [SecretKeyTransition.Rotate] (NOT Enable) - distinguishing the two
 *    matters because the migrator's precondition check refuses Enable
 *    when an SK is already active and refuses Rotate when one is not.
 *  - After a successful rotate the holder serves the NEW SK (not the
 *    pre-rotate one). The OLD bytes are zeroed by [SecretKeyHolder.setBytes]
 *    before publishing the new reference.
 *  - The master-password CharArray is zeroed on every branch.
 *  - The fresh SK ByteArray returned to the caller is independent of the
 *    holder's internal copy (defensive-copy contract).
 *  - Migrator failure does NOT install the new SK into the holder; the
 *    pre-rotate SK stays live.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class SecretKeyRotateUseCaseTest {

    private lateinit var authPrefs: SharedPreferences
    private lateinit var crypto: CryptoManager
    private lateinit var wrapper: SecretKeyKeystoreWrapper
    private lateinit var detector: DeviceCapacityDetector
    private lateinit var holder: SecretKeyHolder
    private lateinit var recordingMigrator: RecordingMigrator
    private lateinit var useCase: SecretKeyRotateUseCase

    @Before fun setUp() {
        val context: Context = RuntimeEnvironment.getApplication()
        authPrefs = context.getSharedPreferences(
            "sk_rotate_use_case_test_${System.nanoTime()}",
            Context.MODE_PRIVATE,
        )
        authPrefs.edit().clear().commit()
        crypto = CryptoManager()
        wrapper = SecretKeyKeystoreWrapper(authPrefs)
        detector = DeviceCapacityDetector(context)
        holder = SecretKeyHolder()
        recordingMigrator = RecordingMigrator(authPrefs, crypto, detector, wrapper)
        useCase = SecretKeyRotateUseCase(
            migrator = recordingMigrator,
            holder = holder,
            wrapper = wrapper,
        )
    }

    @Test fun happy_path_returns_fresh_sk_and_replaces_holder_contents() = runBlocking {
        val oldSk = ByteArray(SecretKeyHolder.SECRET_KEY_RAW_LENGTH) { 0xAA.toByte() }
        holder.setBytes(oldSk)
        assertTrue(holder.isPresent())

        recordingMigrator.responseFactory = { AppResult.Success(Unit) }
        val password = "correct horse battery staple".toCharArray()

        val result = useCase(password)

        assertTrue(result is AppResult.Success)
        val newSk = (result as AppResult.Success).data
        assertFalse(
            "Rotate must produce a DIFFERENT SK than the one in the holder " +
                "before the call (drop old SK on rotate)",
            newSk.contentEquals(oldSk),
        )

        // Holder must now serve the new SK, not the old one.
        holder.withBytes { sk ->
            assertArrayEquals(
                "Holder must serve the freshly-generated SK after rotate",
                newSk,
                sk,
            )
            assertFalse(
                "Holder must NOT serve the pre-rotate SK after a successful rotate",
                sk.contentEquals(oldSk),
            )
        }

        assertTrue(
            "Use case must invoke runSecretKeyTransition with Rotate (not Enable)",
            recordingMigrator.capturedTransition is SecretKeyTransition.Rotate,
        )
        assertArrayEquals(
            "Rotate.newSk handed to the migrator must match the use case's return",
            newSk,
            (recordingMigrator.capturedTransition as SecretKeyTransition.Rotate).newSk,
        )
        assertTrue(
            "Master password CharArray must be zeroed after success",
            password.all { it == ' ' },
        )
    }

    @Test fun two_rotates_produce_different_sks() = runBlocking {
        // Probabilistic guard: SecureRandom collisions on 128 bits are
        // negligible, so two consecutive rotates must surface as two
        // distinct SKs. A regression that hard-coded the SK would fail
        // this assertion.
        recordingMigrator.responseFactory = { AppResult.Success(Unit) }
        holder.setBytes(ByteArray(SecretKeyHolder.SECRET_KEY_RAW_LENGTH))

        val first = (useCase("p1".toCharArray()) as AppResult.Success).data.copyOf()
        recordingMigrator.capturedTransition = null
        val second = (useCase("p2".toCharArray()) as AppResult.Success).data

        assertFalse(
            "Two consecutive rotates must produce different SK bytes (SecureRandom)",
            first.contentEquals(second),
        )
        assertNotEquals(
            "Returned array identity must differ on consecutive calls (defensive copy)",
            first,
            second,
        )
    }

    @Test fun migrator_failure_leaves_old_sk_in_holder() = runBlocking {
        val oldSk = ByteArray(SecretKeyHolder.SECRET_KEY_RAW_LENGTH) { 0xCC.toByte() }
        holder.setBytes(oldSk)

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
            "Holder must remain loaded with the OLD SK on rotate failure",
            holder.isPresent(),
        )
        holder.withBytes { sk ->
            assertArrayEquals(
                "Holder must still serve the pre-rotate SK after a failed rotate",
                oldSk,
                sk,
            )
        }
        assertTrue(
            "Master password CharArray must be zeroed on failure",
            password.all { it == ' ' },
        )
    }

    @Test fun returned_sk_is_independent_of_holder_internal_copy() = runBlocking {
        recordingMigrator.responseFactory = { AppResult.Success(Unit) }
        val result = useCase("p".toCharArray()) as AppResult.Success
        val returned = result.data

        // Mutate the returned array (simulating the caller zeroing it).
        // The holder MUST still serve the original SK bytes.
        val original = returned.copyOf()
        returned.fill(0)

        holder.withBytes { sk ->
            assertArrayEquals(
                "Returned SK and holder's defensive copy must be independent " +
                    "(use-case copyOf contract): caller zeroing the returned " +
                    "array does NOT zero the holder's bytes",
                original,
                sk,
            )
        }
    }

    @Test fun wrong_password_failure_routes_message_to_caller() = runBlocking {
        holder.setBytes(ByteArray(SecretKeyHolder.SECRET_KEY_RAW_LENGTH))
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
            capturedTransition = when (transition) {
                is SecretKeyTransition.Enable -> SecretKeyTransition.Enable(transition.newSk.copyOf())
                is SecretKeyTransition.Rotate -> SecretKeyTransition.Rotate(transition.newSk.copyOf())
                SecretKeyTransition.Disable -> SecretKeyTransition.Disable
            }
            masterPassword.fill(' ')
            return responseFactory()
        }
    }
}

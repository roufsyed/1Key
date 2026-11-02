package com.roufsyed.onekey.feature.vault.presentation.viewmodel

import com.roufsyed.onekey.core.domain.model.Passkey
import com.roufsyed.onekey.core.domain.model.PasskeyOrigin
import com.roufsyed.onekey.core.domain.repository.FakePasskeyRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** The badge set follows the passkey table: one id per entry, live across deletes. */
@OptIn(ExperimentalCoroutinesApi::class)
class PasskeyBadgesViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var passkeys: FakePasskeyRepository

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        passkeys = FakePasskeyRepository()
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun passkey(id: String, credentialId: String) = Passkey(
        id = id, credentialId = credentialId, rpId = "example.com", rpName = "", userName = "", userDisplayName = "",
        userHandle = byteArrayOf(1), webauthnCredentialId = ByteArray(16) { id.hashCode().toByte() }, privateKeyPkcs8 = ByteArray(4),
        prfSecret = null, origin = PasskeyOrigin.Browser("https://example.com"), createdAt = 0L, lastUsedAt = null, backedUpAt = null,
    )

    @Test fun entries_with_passkeys_are_badged_and_unbadged_live() = runTest(dispatcher) {
        passkeys.save(passkey("a", "c1"))
        passkeys.save(passkey("b", "c1"))
        passkeys.save(passkey("c", "c2"))
        val vm = PasskeyBadgesViewModel(passkeys)
        val subscriber = launch { vm.credentialIdsWithPasskeys.collect {} }
        advanceUntilIdle()
        assertEquals(setOf("c1", "c2"), vm.credentialIdsWithPasskeys.value)

        passkeys.deleteById("c")
        advanceUntilIdle()
        assertEquals(setOf("c1"), vm.credentialIdsWithPasskeys.value)

        passkeys.deleteById("a")
        advanceUntilIdle()
        assertEquals(setOf("c1"), vm.credentialIdsWithPasskeys.value)
        subscriber.cancel()
    }
}

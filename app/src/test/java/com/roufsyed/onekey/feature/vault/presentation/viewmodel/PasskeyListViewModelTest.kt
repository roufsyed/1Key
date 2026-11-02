package com.roufsyed.onekey.feature.vault.presentation.viewmodel

import com.roufsyed.onekey.core.domain.model.BackgroundLockTimeout
import com.roufsyed.onekey.core.domain.model.CredentialSortOrder
import com.roufsyed.onekey.core.domain.model.InactivityLockTimeout
import com.roufsyed.onekey.core.domain.model.MasterPasswordInterval
import com.roufsyed.onekey.core.domain.model.Passkey
import com.roufsyed.onekey.core.domain.model.PasskeyOrigin
import com.roufsyed.onekey.core.domain.model.RecycleBinRetention
import com.roufsyed.onekey.core.domain.model.ThemeMode
import com.roufsyed.onekey.core.domain.repository.AppPreferencesRepository
import com.roufsyed.onekey.core.domain.repository.BiometricUnlockGate
import com.roufsyed.onekey.core.domain.repository.FakePasskeyRepository
import com.roufsyed.onekey.core.domain.repository.SyncGate
import com.roufsyed.onekey.feature.vault.presentation.viewmodel.PasskeyListViewModel.ListState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The Passkeys tab's list: sorted by site then account, and live across
 * deletions made elsewhere.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PasskeyListViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var passkeys: FakePasskeyRepository

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        passkeys = FakePasskeyRepository()
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun vm() = PasskeyListViewModel(passkeys, PrefsForPasskeyTab(), dispatcher)

    private fun passkey(id: String, credentialId: String, rpId: String, rpName: String = "", userName: String = "") = Passkey(
        id = id, credentialId = credentialId, rpId = rpId, rpName = rpName, userName = userName, userDisplayName = "",
        userHandle = byteArrayOf(1), webauthnCredentialId = ByteArray(16) { id.hashCode().toByte() }, privateKeyPkcs8 = ByteArray(4),
        prfSecret = null, origin = PasskeyOrigin.Browser("https://$rpId"), createdAt = 0L, lastUsedAt = null, backedUpAt = null,
    )

    @Test fun starts_loading_then_lists_sorted_by_site_then_account() = runTest(dispatcher) {
        passkeys.save(passkey("b", "c2", "github.com", rpName = "GitHub", userName = "zed"))
        passkeys.save(passkey("a", "c1", "github.com", rpName = "GitHub", userName = "amy"))
        passkeys.save(passkey("c", "c3", "example.org", userName = "amy"))
        val vm = vm()
        assertEquals(ListState.Loading, vm.listState.value)
        val subscriber = launch { vm.listState.collect {} }
        advanceUntilIdle()
        assertEquals(listOf("c", "a", "b"), (vm.listState.value as ListState.Loaded).passkeys.map { it.id })
        subscriber.cancel()
    }

    @Test fun a_deletion_elsewhere_drops_the_row() = runTest(dispatcher) {
        passkeys.save(passkey("a", "c1", "github.com"))
        val vm = vm()
        val subscriber = launch { vm.listState.collect {} }
        advanceUntilIdle()
        assertEquals(1, (vm.listState.value as ListState.Loaded).passkeys.size)
        passkeys.deleteById("a")
        advanceUntilIdle()
        assertTrue((vm.listState.value as ListState.Loaded).passkeys.isEmpty())
        subscriber.cancel()
    }

    @Test fun deleting_from_the_tab_removes_only_that_passkey() = runTest(dispatcher) {
        passkeys.save(passkey("a", "c1", "github.com", userName = "amy"))
        passkeys.save(passkey("b", "c1", "github.com", userName = "bob"))
        val vm = vm()
        val subscriber = launch { vm.listState.collect {} }
        advanceUntilIdle()
        vm.deletePasskey("a")
        advanceUntilIdle()
        assertEquals(listOf("b"), (vm.listState.value as ListState.Loaded).passkeys.map { it.id })
        assertTrue(passkeys.getById("a").isError)
        assertTrue(passkeys.getById("b").isSuccess)
        subscriber.cancel()
    }

    /** Only the top-bar preference is read; everything else is unreachable from this ViewModel. */
    private class PrefsForPasskeyTab : AppPreferencesRepository {
        override fun isHideTopBarOnScroll(): Flow<Boolean> = flowOf(true)

        override suspend fun setHideTopBarOnScroll(enabled: Boolean) = error("unused")
        override fun getCredentialSortOrder(): Flow<CredentialSortOrder> = error("unused")
        override suspend fun setCredentialSortOrder(order: CredentialSortOrder) = error("unused")
        override fun isRecycleBinEnabled(): Flow<Boolean> = error("unused")
        override suspend fun setRecycleBinEnabled(enabled: Boolean) = error("unused")
        override fun isNotesRenderMarkdownEnabled(): Flow<Boolean> = error("unused")
        override suspend fun setNotesRenderMarkdownEnabled(enabled: Boolean) = error("unused")
        override fun isVaultFooterVisible(): Flow<Boolean> = error("unused")
        override suspend fun setVaultFooterVisible(visible: Boolean) = error("unused")
        override fun getThemeMode(): Flow<ThemeMode> = error("unused")
        override suspend fun setThemeMode(mode: ThemeMode) = error("unused")
        override fun isBiometricEnabled(): Flow<Boolean> = error("unused")
        override suspend fun setBiometricEnabled(enabled: Boolean) = error("unused")
        override fun isScreenshotsEnabled(): Flow<Boolean> = error("unused")
        override suspend fun setScreenshotsEnabled(enabled: Boolean) = error("unused")
        override fun getBackgroundLockTimeout(): Flow<BackgroundLockTimeout> = error("unused")
        override suspend fun setBackgroundLockTimeout(timeout: BackgroundLockTimeout) = error("unused")
        override fun getInactivityLockTimeout(): Flow<InactivityLockTimeout> = error("unused")
        override suspend fun setInactivityLockTimeout(timeout: InactivityLockTimeout) = error("unused")
        override fun isMasterPasswordRecheckEnabled(): Flow<Boolean> = error("unused")
        override suspend fun setMasterPasswordRecheckEnabled(enabled: Boolean) = error("unused")
        override fun getMasterPasswordRecheckInterval(): Flow<MasterPasswordInterval> = error("unused")
        override suspend fun setMasterPasswordRecheckInterval(interval: MasterPasswordInterval) = error("unused")
        override fun getLastMasterPasswordTimestamp(): Flow<Long> = error("unused")
        override suspend fun setLastMasterPasswordTimestamp(timestamp: Long) = error("unused")
        override fun isShowFavourites(): Flow<Boolean> = error("unused")
        override suspend fun setShowFavourites(show: Boolean) = error("unused")
        override fun isShowPasskeys(): Flow<Boolean> = error("unused")
        override suspend fun setShowPasskeys(show: Boolean) = error("unused")
        override fun getRecycleBinRetention(): Flow<RecycleBinRetention> = error("unused")
        override suspend fun setRecycleBinRetention(retention: RecycleBinRetention) = error("unused")
        override fun isRestoreLastScreenOnUnlock(): Flow<Boolean> = error("unused")
        override suspend fun setRestoreLastScreenOnUnlock(enabled: Boolean) = error("unused")
        override fun isAutofillEnabled(): Flow<Boolean> = error("unused")
        override suspend fun setAutofillEnabled(enabled: Boolean) = error("unused")
        override fun isAutofillCategoryFilterEnabled(): Flow<Boolean> = error("unused")
        override suspend fun setAutofillCategoryFilterEnabled(enabled: Boolean) = error("unused")
        override fun isAutofillSaveUrlOnCrossHostEnabled(): Flow<Boolean> = error("unused")
        override suspend fun setAutofillSaveUrlOnCrossHostEnabled(enabled: Boolean) = error("unused")
        override fun getLockReasonContext(): Flow<String?> = error("unused")
        override suspend fun getLockReasonContextDirect(): String? = error("unused")
        override suspend fun setLockReasonContext(context: String?) = error("unused")
        override fun getAcknowledgedAttestationReason(): Flow<String?> = error("unused")
        override suspend fun setAcknowledgedAttestationReason(reason: String) = error("unused")
        override fun getBiometricUnlockGate(): Flow<BiometricUnlockGate> = error("unused")
        override fun isSyncEnabled(): Flow<Boolean> = error("unused")
        override suspend fun setSyncEnabled(enabled: Boolean) = error("unused")
        override fun getSyncLocationUri(): Flow<String?> = error("unused")
        override suspend fun setSyncLocationUri(uri: String?) = error("unused")
        override fun isSyncCompletionNotificationEnabled(): Flow<Boolean> = error("unused")
        override suspend fun setSyncCompletionNotificationEnabled(enabled: Boolean) = error("unused")
        override fun getSyncLastSuccessAt(): Flow<Long> = error("unused")
        override suspend fun setSyncLastSuccessAt(timestamp: Long) = error("unused")
        override suspend fun getSyncGateDirect(): SyncGate = error("unused")
    }
}

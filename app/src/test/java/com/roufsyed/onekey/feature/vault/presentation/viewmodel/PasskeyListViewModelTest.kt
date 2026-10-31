package com.roufsyed.onekey.feature.vault.presentation.viewmodel

import com.roufsyed.onekey.core.data.snapshot.SnapshotCredential
import com.roufsyed.onekey.core.data.snapshot.SnapshotState
import com.roufsyed.onekey.core.domain.model.BackgroundLockTimeout
import com.roufsyed.onekey.core.domain.model.CredentialSortOrder
import com.roufsyed.onekey.core.domain.model.CredentialType
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
import kotlinx.coroutines.flow.MutableStateFlow
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
 * The Passkeys tab's list: sorted by site then account, labelled with the
 * owning entry's title from the snapshot (falling back to the site when the
 * snapshot cannot say), and live across deletes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PasskeyListViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var passkeys: FakePasskeyRepository
    private lateinit var snapshot: MutableStateFlow<SnapshotState>

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        passkeys = FakePasskeyRepository()
        snapshot = MutableStateFlow(SnapshotState.Loaded(emptyList()))
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun vm() = PasskeyListViewModel(passkeys, PrefsForPasskeyTab(), snapshot, dispatcher)

    private fun passkey(id: String, credentialId: String, rpId: String, rpName: String = "", userName: String = "") = Passkey(
        id = id, credentialId = credentialId, rpId = rpId, rpName = rpName, userName = userName, userDisplayName = "",
        userHandle = byteArrayOf(1), webauthnCredentialId = ByteArray(16) { id.hashCode().toByte() }, privateKeyPkcs8 = ByteArray(4),
        prfSecret = null, origin = PasskeyOrigin.Browser("https://$rpId"), createdAt = 0L, lastUsedAt = null, backedUpAt = null,
    )

    private fun snap(id: String, title: String) = SnapshotCredential(
        id = id, title = title, username = "u", url = "", tags = emptyList(), isFavorite = false, type = CredentialType.LOGIN,
        createdAt = 0L, updatedAt = 0L, accessedAt = null, hasOtp = false,
    )

    @Test fun lists_sorted_by_site_then_account_with_owning_entry_titles() = runTest(dispatcher) {
        passkeys.save(passkey("b", "c2", "github.com", rpName = "GitHub", userName = "zed"))
        passkeys.save(passkey("a", "c1", "github.com", rpName = "GitHub", userName = "amy"))
        passkeys.save(passkey("c", "c3", "example.org", userName = "amy"))
        snapshot.value = SnapshotState.Loaded(listOf(snap("c1", "GitHub personal"), snap("c2", "GitHub work")))
        val vm = vm()
        assertEquals(ListState.Loading, vm.listState.value)
        val subscriber = launch { vm.listState.collect {} }
        advanceUntilIdle()
        val rows = (vm.listState.value as ListState.Loaded).rows
        assertEquals(listOf("c", "a", "b"), rows.map { it.passkey.id })
        assertEquals("entry without a snapshot row falls back to the site", listOf("example.org", "GitHub personal", "GitHub work"), rows.map { it.credentialTitle })
        subscriber.cancel()
    }

    @Test fun titles_fall_back_to_the_site_while_the_snapshot_is_bypassed() = runTest(dispatcher) {
        passkeys.save(passkey("a", "c1", "github.com", rpName = "GitHub"))
        snapshot.value = SnapshotState.Bypassed
        val vm = vm()
        val subscriber = launch { vm.listState.collect {} }
        advanceUntilIdle()
        assertEquals("GitHub", (vm.listState.value as ListState.Loaded).rows.single().credentialTitle)
        subscriber.cancel()
    }

    @Test fun delete_removes_the_row() = runTest(dispatcher) {
        passkeys.save(passkey("a", "c1", "github.com"))
        val vm = vm()
        val subscriber = launch { vm.listState.collect {} }
        advanceUntilIdle()
        assertEquals(1, (vm.listState.value as ListState.Loaded).rows.size)
        vm.deletePasskey("a")
        advanceUntilIdle()
        assertTrue((vm.listState.value as ListState.Loaded).rows.isEmpty())
        assertTrue(passkeys.all.isEmpty())
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

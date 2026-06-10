package com.onekey.feature.vault.presentation.viewmodel

import android.app.Application
import app.cash.turbine.test
import com.onekey.core.data.snapshot.SnapshotCredential
import com.onekey.core.data.snapshot.SnapshotState
import com.onekey.core.domain.model.AppResult
import com.onekey.core.domain.model.BackgroundLockTimeout
import com.onekey.core.domain.model.Credential
import com.onekey.core.domain.model.CredentialHistoryEntry
import com.onekey.core.domain.model.CredentialSortOrder
import com.onekey.core.domain.model.CredentialType
import com.onekey.core.domain.model.InactivityLockTimeout
import com.onekey.core.domain.model.MasterPasswordInterval
import com.onekey.core.domain.model.RecycleBinRetention
import com.onekey.core.domain.model.ThemeMode
import com.onekey.core.domain.repository.AppPreferencesRepository
import com.onekey.core.domain.repository.BiometricUnlockGate
import com.onekey.core.domain.repository.CredentialHistoryRepository
import com.onekey.core.domain.repository.CredentialRepository
import com.onekey.core.domain.usecase.DeleteCredentialUseCase
import com.onekey.core.domain.usecase.HardDeleteCredentialUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Behavioural locks for [FavouritesViewModel] after the snapshot-store
 * convergence. The VM derives `listState` from a shared
 * `StateFlow<SnapshotState>` (`@SnapshotStateFlow`); tests drive the snapshot
 * directly via a `MutableStateFlow<SnapshotState>` and the filter dispatcher
 * via a `StandardTestDispatcher` so virtual time crosses the `flowOn`
 * boundary.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class FavouritesViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var repo: FakeCredentialRepository
    private lateinit var historyRepo: FakeCredentialHistoryRepository
    private lateinit var prefs: FakeAppPreferencesRepository
    private lateinit var snapshot: MutableStateFlow<SnapshotState>
    private lateinit var deleteUseCase: DeleteCredentialUseCase
    private lateinit var hardDeleteUseCase: HardDeleteCredentialUseCase

    @Before fun setup() {
        Dispatchers.setMain(testDispatcher)
        repo = FakeCredentialRepository()
        historyRepo = FakeCredentialHistoryRepository()
        prefs = FakeAppPreferencesRepository()
        // Default to Loaded(empty) so the init-block snapshotState observer
        // does NOT clear selections set by mutation-flow tests before they
        // can act. Tests that exercise Locked/Loading/Bypassed transitions
        // override this default explicitly.
        snapshot = MutableStateFlow(SnapshotState.Loaded(emptyList()))
        deleteUseCase = DeleteCredentialUseCase(repo)
        hardDeleteUseCase = HardDeleteCredentialUseCase(repo, historyRepo)
    }

    @After fun teardown() {
        Dispatchers.resetMain()
    }

    // ── snapshot-state mapping ──────────────────────────────────────────────

    @Test fun snapshot_Locked_yields_listState_Locked() = runTest(testDispatcher) {
        snapshot.value = SnapshotState.Locked
        val vm = buildVm()
        keepAlive(vm)
        advanceUntilIdle()
        assertEquals(CredentialListState.Locked, vm.listState.value)
    }

    @Test fun snapshot_Loading_yields_listState_Loading_not_Loaded_empty() = runTest(testDispatcher) {
        snapshot.value = SnapshotState.Loading
        val vm = buildVm()
        keepAlive(vm)
        advanceUntilIdle()
        assertEquals(CredentialListState.Loading, vm.listState.value)
    }

    @Test fun snapshot_Bypassed_yields_listState_Bypassed_no_fallback() = runTest(testDispatcher) {
        snapshot.value = SnapshotState.Bypassed
        val vm = buildVm()
        keepAlive(vm)
        advanceUntilIdle()
        assertEquals(CredentialListState.Bypassed, vm.listState.value)
    }

    @Test fun snapshot_Loaded_filters_to_isFavorite_true_only() = runTest(testDispatcher) {
        snapshot.value = SnapshotState.Loaded(
            listOf(
                snap("1", "GitHub", isFavorite = true),
                snap("2", "Twitter", isFavorite = false),
                snap("3", "Bank", isFavorite = true),
            ),
        )
        val vm = buildVm()
        keepAlive(vm)
        advanceUntilIdle()
        val s = vm.listState.value as CredentialListState.Loaded
        assertEquals(setOf("1", "3"), s.credentials.map { it.id }.toSet())
    }

    @Test fun snapshot_Loaded_with_no_favourites_yields_Loaded_empty() = runTest(testDispatcher) {
        snapshot.value = SnapshotState.Loaded(listOf(snap("1", "Twitter", isFavorite = false)))
        val vm = buildVm()
        keepAlive(vm)
        advanceUntilIdle()
        val s = vm.listState.value
        assertTrue("Loaded(empty) NOT Locked", s is CredentialListState.Loaded)
        assertEquals(0, (s as CredentialListState.Loaded).credentials.size)
    }

    // ── sort orders ─────────────────────────────────────────────────────────

    @Test fun sort_NEWEST_FIRST_orders_by_createdAt_desc() = runTest(testDispatcher) {
        prefs.sortOrder.value = CredentialSortOrder.NEWEST_FIRST
        snapshot.value = SnapshotState.Loaded(
            listOf(
                snap("a", "Aaa", createdAt = 1_000, isFavorite = true),
                snap("b", "Bbb", createdAt = 3_000, isFavorite = true),
                snap("c", "Ccc", createdAt = 2_000, isFavorite = true),
            ),
        )
        val vm = buildVm()
        keepAlive(vm)
        advanceUntilIdle()
        val ids = (vm.listState.value as CredentialListState.Loaded).credentials.map { it.id }
        assertEquals(listOf("b", "c", "a"), ids)
    }

    @Test fun sort_LAST_MODIFIED_orders_by_updatedAt_desc() = runTest(testDispatcher) {
        prefs.sortOrder.value = CredentialSortOrder.LAST_MODIFIED
        snapshot.value = SnapshotState.Loaded(
            listOf(
                snap("a", "Aaa", updatedAt = 1_000, isFavorite = true),
                snap("b", "Bbb", updatedAt = 3_000, isFavorite = true),
                snap("c", "Ccc", updatedAt = 2_000, isFavorite = true),
            ),
        )
        val vm = buildVm()
        keepAlive(vm)
        advanceUntilIdle()
        val ids = (vm.listState.value as CredentialListState.Loaded).credentials.map { it.id }
        assertEquals(listOf("b", "c", "a"), ids)
    }

    @Test fun sort_RECENTLY_ACCESSED_falls_back_to_updatedAt_for_null_accessedAt() = runTest(testDispatcher) {
        // Regression pin for the snapshotComparator() fall-back. Pre-PR4
        // CredentialRepositoryImpl.toDomain() coalesced a null accessed_at
        // to updated_at BEFORE the comparator ran. Snapshot.accessedAt can
        // be null; the comparator must apply the same coalescing or
        // never-accessed rows silently sink to the bottom.
        prefs.sortOrder.value = CredentialSortOrder.RECENTLY_ACCESSED
        snapshot.value = SnapshotState.Loaded(
            listOf(
                snap("a", "Aaa", updatedAt = 1_000, accessedAt = null, isFavorite = true),
                snap("b", "Bbb", updatedAt = 4_000, accessedAt = 2_000, isFavorite = true),
                snap("c", "Ccc", updatedAt = 5_000, accessedAt = null, isFavorite = true),
            ),
        )
        val vm = buildVm()
        keepAlive(vm)
        advanceUntilIdle()
        val ids = (vm.listState.value as CredentialListState.Loaded).credentials.map { it.id }
        // Effective keys: a=1000, b=2000, c=5000. Descending: c, b, a.
        // WITHOUT the ?: updatedAt fall-back, b=2000 would beat c (null sinks).
        assertEquals(listOf("c", "b", "a"), ids)
    }

    @Test fun sort_ALPHABETICAL_orders_case_insensitive_via_project_once() = runTest(testDispatcher) {
        prefs.sortOrder.value = CredentialSortOrder.ALPHABETICAL
        snapshot.value = SnapshotState.Loaded(
            listOf(
                snap("a", "bravo", isFavorite = true),
                snap("b", "ALPHA", isFavorite = true),
                snap("c", "Charlie", isFavorite = true),
            ),
        )
        val vm = buildVm()
        keepAlive(vm)
        advanceUntilIdle()
        val ids = (vm.listState.value as CredentialListState.Loaded).credentials.map { it.id }
        assertEquals(listOf("b", "a", "c"), ids)
    }

    @Test fun setSortOrder_persists_via_appPrefs() = runTest(testDispatcher) {
        val vm = buildVm()
        vm.setSortOrder(CredentialSortOrder.ALPHABETICAL)
        advanceUntilIdle()
        assertEquals(CredentialSortOrder.ALPHABETICAL, prefs.sortOrder.value)
    }

    // ── letter index ────────────────────────────────────────────────────────

    @Test fun letterIndex_populated_only_when_ALPHABETICAL_and_Loaded() = runTest(testDispatcher) {
        snapshot.value = SnapshotState.Loaded(listOf(snap("a", "Apple", isFavorite = true)))
        prefs.sortOrder.value = CredentialSortOrder.NEWEST_FIRST
        val vm = buildVm()
        keepAlive(vm)
        backgroundScope.launch { vm.letterIndex.collect {} }
        advanceUntilIdle()
        assertTrue("non-ALPHABETICAL must yield empty index", vm.letterIndex.value.isEmpty())

        prefs.sortOrder.value = CredentialSortOrder.ALPHABETICAL
        advanceUntilIdle()
        assertTrue("ALPHABETICAL + Loaded must populate index", vm.letterIndex.value.isNotEmpty())

        snapshot.value = SnapshotState.Locked
        advanceUntilIdle()
        assertTrue("Locked must drop the index", vm.letterIndex.value.isEmpty())
    }

    @Test fun letterIndex_first_position_per_uppercase_letter() = runTest(testDispatcher) {
        prefs.sortOrder.value = CredentialSortOrder.ALPHABETICAL
        snapshot.value = SnapshotState.Loaded(
            listOf(
                snap("1", "Alpha", isFavorite = true),
                snap("2", "anchor", isFavorite = true),
                snap("3", "Beta", isFavorite = true),
                snap("4", "charlie", isFavorite = true),
            ),
        )
        val vm = buildVm()
        keepAlive(vm)
        backgroundScope.launch { vm.letterIndex.collect {} }
        advanceUntilIdle()
        val index = vm.letterIndex.value
        // Sorted: ALPHA(0), anchor(1), Beta(2), charlie(3). Letters: A, B, C.
        assertEquals(0, index['A'])
        assertEquals(2, index['B'])
        assertEquals(3, index['C'])
    }

    // ── selection ───────────────────────────────────────────────────────────

    @Test fun toggleSelection_round_trip_then_clearSelection() = runTest(testDispatcher) {
        val vm = buildVm()
        vm.toggleSelection("a")
        vm.toggleSelection("b")
        assertEquals(setOf("a", "b"), vm.selectedIds.value)
        vm.toggleSelection("a")
        assertEquals(setOf("b"), vm.selectedIds.value)
        vm.clearSelection()
        assertTrue(vm.selectedIds.value.isEmpty())
    }

    @Test fun selectedAreAllFavourite_true_when_all_selected_favourites() = runTest(testDispatcher) {
        snapshot.value = SnapshotState.Loaded(
            listOf(
                snap("a", "Aaa", isFavorite = true),
                snap("b", "Bbb", isFavorite = true),
            ),
        )
        val vm = buildVm()
        keepAlive(vm)
        backgroundScope.launch { vm.selectedAreAllFavourite.collect {} }
        advanceUntilIdle()
        vm.toggleSelection("a")
        vm.toggleSelection("b")
        advanceUntilIdle()
        assertTrue(vm.selectedAreAllFavourite.value)
    }

    @Test fun selectedAreAllFavourite_false_when_selection_includes_removed_id() = runTest(testDispatcher) {
        snapshot.value = SnapshotState.Loaded(listOf(snap("a", "Aaa", isFavorite = true)))
        val vm = buildVm()
        keepAlive(vm)
        backgroundScope.launch { vm.selectedAreAllFavourite.collect {} }
        advanceUntilIdle()
        vm.toggleSelection("a")
        vm.toggleSelection("ghost")
        advanceUntilIdle()
        assertFalse(vm.selectedAreAllFavourite.value)
    }

    @Test fun lock_transition_clears_selectedIds_via_snapshotState_observer() = runTest(testDispatcher) {
        snapshot.value = SnapshotState.Loaded(listOf(snap("a", "Aaa", isFavorite = true)))
        val vm = buildVm()
        vm.toggleSelection("a")
        advanceUntilIdle()
        assertEquals(setOf("a"), vm.selectedIds.value)

        snapshot.value = SnapshotState.Locked
        advanceUntilIdle()
        assertTrue(vm.selectedIds.value.isEmpty())
    }

    @Test fun bypass_transition_clears_selectedIds() = runTest(testDispatcher) {
        snapshot.value = SnapshotState.Loaded(listOf(snap("a", "Aaa", isFavorite = true)))
        val vm = buildVm()
        vm.toggleSelection("a")
        advanceUntilIdle()
        snapshot.value = SnapshotState.Bypassed
        advanceUntilIdle()
        assertTrue(vm.selectedIds.value.isEmpty())
    }

    // ── mutations ───────────────────────────────────────────────────────────

    @Test fun deleteSelected_emits_DeleteError_when_use_case_fails() = runTest(testDispatcher) {
        repo.deleteCredentialResult = AppResult.Error(RuntimeException("boom"))
        val vm = buildVm()
        vm.toggleSelection("a")
        vm.event.test {
            vm.deleteSelected()
            advanceUntilIdle()
            val ev = awaitItem()
            assertTrue(ev is CredentialListEvent.DeleteError)
            assertEquals(1, (ev as CredentialListEvent.DeleteError).count)
        }
    }

    @Test fun deleteSelectedNow_routes_to_hardDeleteCredentialUseCase() = runTest(testDispatcher) {
        val vm = buildVm()
        vm.toggleSelection("a")
        vm.deleteSelectedNow()
        advanceUntilIdle()
        assertEquals(listOf("a"), repo.hardDeleted)
        assertEquals(listOf("a"), historyRepo.historyDeleted)
    }

    @Test fun setFavouriteOnSelected_calls_repo_toggleFavorite() = runTest(testDispatcher) {
        val vm = buildVm()
        vm.toggleSelection("a")
        vm.toggleSelection("b")
        vm.setFavouriteOnSelected(makeFavourite = false)
        advanceUntilIdle()
        assertEquals(setOf("a" to false, "b" to false), repo.favouriteToggles.toSet())
    }

    // ── distinct-until-changed + initial value ──────────────────────────────

    @Test fun distinctUntilChanged_collapses_identical_payload_reemissions() = runTest(testDispatcher) {
        val initial = listOf(
            snap("a", "Aaa", isFavorite = true),
            snap("b", "Bbb", isFavorite = false),
        )
        snapshot.value = SnapshotState.Loaded(initial)
        val vm = buildVm()
        keepAlive(vm)
        advanceUntilIdle()

        vm.listState.test {
            val first = awaitItem() as CredentialListState.Loaded
            assertEquals(listOf("a"), first.credentials.map { it.id })

            // Snapshot re-fires with a structurally-identical list (e.g. an
            // unrelated row mutated and Room invalidation propagated).
            snapshot.value = SnapshotState.Loaded(initial.map { it.copy() })
            advanceUntilIdle()
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun initial_value_is_Locked_not_Loading() = runTest(testDispatcher) {
        val vm = buildVm()
        // Cold .value read BEFORE any subscriber: stateIn returns the initial.
        assertEquals(CredentialListState.Locked, vm.listState.value)
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private fun TestScope.buildVm(): FavouritesViewModel = FavouritesViewModel(
        credentialRepository = repo,
        deleteCredential = deleteUseCase,
        hardDeleteCredential = hardDeleteUseCase,
        appPrefs = prefs,
        snapshotState = snapshot,
        filterDispatcher = testDispatcher,
    )

    private fun TestScope.keepAlive(vm: FavouritesViewModel) {
        // listState is WhileSubscribed(5s); without a subscriber the combine
        // body never runs and vm.listState.value would stay on the initial.
        backgroundScope.launch { vm.listState.collect { } }
    }

    private fun snap(
        id: String,
        title: String,
        createdAt: Long = 0L,
        updatedAt: Long = 0L,
        accessedAt: Long? = null,
        isFavorite: Boolean = true,
    ) = SnapshotCredential(
        id = id, title = title, username = "u", url = "", tags = emptyList(),
        isFavorite = isFavorite, type = CredentialType.LOGIN,
        createdAt = createdAt, updatedAt = updatedAt, accessedAt = accessedAt, hasOtp = false,
    )

    @Suppress("DEPRECATION")
    private class FakeCredentialRepository : CredentialRepository {
        var deleteCredentialResult: AppResult<Unit> = AppResult.Success(Unit)
        val softDeleted = mutableListOf<String>()
        val hardDeleted = mutableListOf<String>()
        val favouriteToggles = mutableListOf<Pair<String, Boolean>>()

        override suspend fun deleteCredential(id: String): AppResult<Unit> {
            softDeleted += id
            return deleteCredentialResult
        }
        override suspend fun hardDeleteCredential(id: String): AppResult<Unit> {
            hardDeleted += id
            return AppResult.Success(Unit)
        }
        override suspend fun toggleFavorite(id: String, isFavorite: Boolean): AppResult<Unit> {
            favouriteToggles += id to isFavorite
            return AppResult.Success(Unit)
        }

        // Everything else is unused by FavouritesViewModel post-PR4.
        override fun observeCredential(id: String): Flow<Credential?> = error("unused")
        override fun observeCredentialIncludingDeleted(id: String): Flow<Credential?> = error("unused")
        override suspend fun getCredential(id: String): AppResult<Credential> = error("unused")
        override suspend fun saveCredential(credential: Credential): AppResult<Unit> = error("unused")
        override suspend fun restoreCredential(id: String): AppResult<Unit> = error("unused")
        override suspend fun purgeFromRecycleBin(id: String): AppResult<Unit> = error("unused")
        override suspend fun emptyRecycleBin(): AppResult<Int> = error("unused")
        override suspend fun purgeRecycleBinOlderThan(cutoff: Long): AppResult<Int> = error("unused")
        override fun observeRecycleBin(): Flow<List<Credential>> = flowOf(emptyList())
        override fun observeRecycleBinCount(): Flow<Int> = flowOf(0)
        override suspend fun getAllCredentials(): AppResult<List<Credential>> = error("unused")
        override suspend fun getAllInRecycleBin(): AppResult<List<Credential>> = error("unused")
        override suspend fun importCredentials(credentials: List<Credential>): AppResult<Int> = error("unused")
        override fun observeCount(): Flow<Int> = flowOf(0)
        override fun observeCountForTag(tag: String): Flow<Int> = flowOf(0)
        override fun observeFavoriteCount(): Flow<Int> = flowOf(0)
        override fun observeFavorites(): Flow<List<Credential>> = flowOf(emptyList())
        override fun observeCredentials(query: String, tag: String, sortOrder: CredentialSortOrder): Flow<List<Credential>> = flowOf(emptyList())
        override fun observeFavoritesSorted(sortOrder: CredentialSortOrder): Flow<List<Credential>> = flowOf(emptyList())
        override fun observeRotatingOtp(): Flow<List<Credential>> = flowOf(emptyList())
        override fun observeHotpEntries(): Flow<List<Credential>> = flowOf(emptyList())
        override suspend fun incrementHotpCounter(credentialId: String): AppResult<Long?> = error("unused")
        override suspend fun deleteAllCredentials(): AppResult<Unit> = error("unused")
        override suspend fun markAccessed(id: String): AppResult<Unit> = error("unused")
    }

    private class FakeCredentialHistoryRepository : CredentialHistoryRepository {
        val historyDeleted = mutableListOf<String>()
        override suspend fun snapshotCredential(credential: Credential): AppResult<Unit> = AppResult.Success(Unit)
        override fun observeHistory(credentialId: String): Flow<List<CredentialHistoryEntry>> = flowOf(emptyList())
        override suspend fun deleteForCredential(credentialId: String): AppResult<Unit> {
            historyDeleted += credentialId
            return AppResult.Success(Unit)
        }
        override suspend fun deleteAll(): AppResult<Unit> = AppResult.Success(Unit)
    }

    private class FakeAppPreferencesRepository : AppPreferencesRepository {
        val sortOrder = MutableStateFlow(CredentialSortOrder.NEWEST_FIRST)
        val recycleBinEnabled = MutableStateFlow(true)
        val hideTopBarOnScroll = MutableStateFlow(true)

        override fun getCredentialSortOrder(): Flow<CredentialSortOrder> = sortOrder
        override suspend fun setCredentialSortOrder(order: CredentialSortOrder) { sortOrder.value = order }
        override fun isRecycleBinEnabled(): Flow<Boolean> = recycleBinEnabled
        override fun isHideTopBarOnScroll(): Flow<Boolean> = hideTopBarOnScroll

        // unused
        override suspend fun setRecycleBinEnabled(enabled: Boolean) = error("unused")
        override suspend fun setHideTopBarOnScroll(enabled: Boolean) = error("unused")
        override fun isNotesRenderMarkdownEnabled(): Flow<Boolean> = flowOf(true)
        override suspend fun setNotesRenderMarkdownEnabled(enabled: Boolean) = error("unused")
        override fun isVaultFooterVisible(): Flow<Boolean> = flowOf(true)
        override suspend fun setVaultFooterVisible(visible: Boolean) = error("unused")
        override fun getThemeMode(): Flow<ThemeMode> = flowOf(ThemeMode.LIGHT)
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
        override fun getRecycleBinRetention(): Flow<RecycleBinRetention> = error("unused")
        override suspend fun setRecycleBinRetention(retention: RecycleBinRetention) = error("unused")
        override fun isRestoreLastScreenOnUnlock(): Flow<Boolean> = error("unused")
        override suspend fun setRestoreLastScreenOnUnlock(enabled: Boolean) = error("unused")
        override fun isAutofillEnabled(): Flow<Boolean> = error("unused")
        override suspend fun setAutofillEnabled(enabled: Boolean) = error("unused")
        override fun isAutofillCategoryFilterEnabled(): Flow<Boolean> = flowOf(false)
        override suspend fun setAutofillCategoryFilterEnabled(enabled: Boolean) = error("unused")
        override fun getLockReasonContext(): Flow<String?> = error("unused")
        override suspend fun getLockReasonContextDirect(): String? = error("unused")
        override suspend fun setLockReasonContext(context: String?) = error("unused")
        override fun getBiometricUnlockGate(): Flow<BiometricUnlockGate> = error("unused")
        override fun isSyncEnabled(): Flow<Boolean> = flowOf(false)
        override suspend fun setSyncEnabled(enabled: Boolean) = Unit
        override fun getSyncLocationUri(): Flow<String?> = flowOf(null)
        override suspend fun setSyncLocationUri(uri: String?) = Unit
        override fun isSyncCompletionNotificationEnabled(): Flow<Boolean> = flowOf(false)
        override suspend fun setSyncCompletionNotificationEnabled(enabled: Boolean) = Unit
        override fun getSyncLastSuccessAt(): Flow<Long> = flowOf(0L)
        override suspend fun setSyncLastSuccessAt(timestamp: Long) = Unit
        override suspend fun getSyncGateDirect(): com.onekey.core.domain.repository.SyncGate =
            com.onekey.core.domain.repository.SyncGate(enabled = false, locationUri = null)
    }
}

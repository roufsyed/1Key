package com.roufsyed.onekey.feature.vault.presentation.viewmodel

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import com.roufsyed.onekey.core.data.snapshot.SnapshotCredential
import com.roufsyed.onekey.core.data.snapshot.SnapshotState
import com.roufsyed.onekey.core.domain.model.AppResult
import com.roufsyed.onekey.core.domain.model.BackgroundLockTimeout
import com.roufsyed.onekey.core.domain.model.Credential
import com.roufsyed.onekey.core.domain.model.CredentialHistoryEntry
import com.roufsyed.onekey.core.domain.model.CredentialSortOrder
import com.roufsyed.onekey.core.domain.model.CredentialType
import com.roufsyed.onekey.core.domain.model.InactivityLockTimeout
import com.roufsyed.onekey.core.domain.model.MasterPasswordInterval
import com.roufsyed.onekey.core.domain.model.RecycleBinRetention
import com.roufsyed.onekey.core.domain.model.ThemeMode
import com.roufsyed.onekey.core.domain.repository.AppPreferencesRepository
import com.roufsyed.onekey.core.domain.repository.BiometricUnlockGate
import com.roufsyed.onekey.core.domain.repository.CredentialHistoryRepository
import com.roufsyed.onekey.core.domain.repository.CredentialRepository
import com.roufsyed.onekey.core.domain.usecase.DeleteCredentialUseCase
import com.roufsyed.onekey.core.domain.usecase.HardDeleteCredentialUseCase
import com.roufsyed.onekey.feature.vault.presentation.screen.TAG_ALL
import com.roufsyed.onekey.feature.vault.presentation.screen.TAG_FAVORITES
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Behavioural locks for [TaggedCredentialListViewModel] post-PR4.
 *
 * Drives the shared `@SnapshotStateFlow` via a `MutableStateFlow<SnapshotState>`
 * and injects the test dispatcher as `@DefaultDispatcher` so virtual time
 * propagates across the `flowOn` boundary inside the VM's combine pipeline.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TaggedCredentialListViewModelTest {

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
        // Default Loaded(empty) so the init-block selection observer doesn't
        // wipe selections set by mutation-flow tests. State-transition tests
        // override this default explicitly.
        snapshot = MutableStateFlow(SnapshotState.Loaded(emptyList()))
        deleteUseCase = DeleteCredentialUseCase(repo)
        hardDeleteUseCase = HardDeleteCredentialUseCase(repo, historyRepo)
    }

    @After fun teardown() {
        Dispatchers.resetMain()
    }

    // ── tag routing ─────────────────────────────────────────────────────────

    @Test fun TAG_ALL_routing_passes_through_all_active_credentials() = runTest(testDispatcher) {
        snapshot.value = SnapshotState.Loaded(
            listOf(
                snap("1", "A", tags = listOf("Banking")),
                snap("2", "B", tags = emptyList(), isFavorite = true),
                snap("3", "C", tags = listOf("Work")),
            ),
        )
        val vm = buildVm(tag = TAG_ALL)
        keepAlive(vm)
        advanceUntilIdle()
        val ids = (vm.listState.value as CredentialListState.Loaded).credentials.map { it.id }.toSet()
        assertEquals(setOf("1", "2", "3"), ids)
    }

    @Test fun empty_rawTag_routing_treated_as_TAG_ALL() = runTest(testDispatcher) {
        snapshot.value = SnapshotState.Loaded(
            listOf(snap("1", "A"), snap("2", "B", tags = listOf("Banking"))),
        )
        val vm = buildVm(tag = "")
        keepAlive(vm)
        advanceUntilIdle()
        val ids = (vm.listState.value as CredentialListState.Loaded).credentials.map { it.id }.toSet()
        assertEquals(setOf("1", "2"), ids)
    }

    @Test fun TAG_FAVORITES_routing_filters_to_isFavorite_true() = runTest(testDispatcher) {
        snapshot.value = SnapshotState.Loaded(
            listOf(
                snap("1", "A", isFavorite = true),
                snap("2", "B", isFavorite = false),
                snap("3", "C", isFavorite = true),
            ),
        )
        val vm = buildVm(tag = TAG_FAVORITES)
        keepAlive(vm)
        advanceUntilIdle()
        val ids = (vm.listState.value as CredentialListState.Loaded).credentials.map { it.id }.toSet()
        assertEquals(setOf("1", "3"), ids)
    }

    @Test fun real_tag_routing_filters_by_list_contains_exact() = runTest(testDispatcher) {
        snapshot.value = SnapshotState.Loaded(
            listOf(
                snap("1", "A", tags = listOf("Banking")),
                snap("2", "B", tags = listOf("Work")),
                snap("3", "C", tags = listOf("Banking", "Personal")),
            ),
        )
        val vm = buildVm(tag = "Banking")
        keepAlive(vm)
        advanceUntilIdle()
        val ids = (vm.listState.value as CredentialListState.Loaded).credentials.map { it.id }.toSet()
        assertEquals(setOf("1", "3"), ids)
    }

    @Test fun real_tag_does_not_match_substring_tag() = runTest(testDispatcher) {
        // Regression pin: rawTag "Bank" must NOT match a row tagged "Banking".
        // The legacy SQL LIKE was anchored on JSON quotes so this already
        // worked there; we keep the same guarantee post-PR4 by using
        // List.contains (exact equality), not String.contains.
        snapshot.value = SnapshotState.Loaded(
            listOf(snap("1", "A", tags = listOf("Banking"))),
        )
        val vm = buildVm(tag = "Bank")
        keepAlive(vm)
        advanceUntilIdle()
        val state = vm.listState.value as CredentialListState.Loaded
        assertTrue("Bank must not absorb Banking", state.credentials.isEmpty())
    }

    @Test fun displayName_All_Items_for_TAG_ALL() {
        val vm = buildVm(tag = TAG_ALL)
        assertEquals("All Items", vm.displayName)
    }

    @Test fun displayName_Favorites_for_TAG_FAVORITES() {
        val vm = buildVm(tag = TAG_FAVORITES)
        assertEquals("Favorites", vm.displayName)
    }

    @Test fun displayName_raw_for_real_tag() {
        val vm = buildVm(tag = "Banking")
        assertEquals("Banking", vm.displayName)
    }

    // ── search ──────────────────────────────────────────────────────────────

    @Test fun search_filters_title_case_insensitive() = runTest(testDispatcher) {
        snapshot.value = SnapshotState.Loaded(
            listOf(
                snap("1", "GitHub"),
                snap("2", "Gmail"),
                snap("3", "Twitter"),
            ),
        )
        val vm = buildVm(tag = TAG_ALL)
        keepAlive(vm)
        vm.setSearchQuery("g")
        advanceUntilIdle()
        val ids = (vm.listState.value as CredentialListState.Loaded).credentials.map { it.id }.toSet()
        assertEquals(setOf("1", "2"), ids)
    }

    @Test fun search_does_not_match_username_only_title() = runTest(testDispatcher) {
        // Matches VaultViewModel's title-only semantic; autofill's broader
        // title+username path lives in AutofillUnlockViewModel.
        snapshot.value = SnapshotState.Loaded(
            listOf(snap("1", "Twitter", username = "alice@example.com")),
        )
        val vm = buildVm(tag = TAG_ALL)
        keepAlive(vm)
        vm.setSearchQuery("alice")
        advanceUntilIdle()
        val state = vm.listState.value as CredentialListState.Loaded
        assertTrue("username must not match", state.credentials.isEmpty())
    }

    @Test fun search_debounce_collapses_rapid_typing_into_one_emission() = runTest(testDispatcher) {
        snapshot.value = SnapshotState.Loaded(
            listOf(snap("1", "GitHub"), snap("2", "Twitter")),
        )
        val vm = buildVm(tag = TAG_ALL)
        keepAlive(vm)
        vm.setSearchQuery("g")
        advanceTimeBy(20)
        vm.setSearchQuery("gi")
        advanceTimeBy(20)
        vm.setSearchQuery("git")
        advanceTimeBy(20)
        vm.setSearchQuery("gith")
        advanceTimeBy(20)
        vm.setSearchQuery("github")
        advanceUntilIdle()
        val state = vm.listState.value as CredentialListState.Loaded
        assertEquals(listOf("1"), state.credentials.map { it.id })
    }

    @Test fun search_query_persists_in_SavedStateHandle_with_rawTag_scoped_key() = runTest(testDispatcher) {
        // Cross-tag leakage regression pin: typing in tag "Work" must NOT
        // be visible from tag "Banking". The key shape is
        // "tagged_search_query_$rawTag" so each tag owns its own slot.
        val saved = SavedStateHandle(mapOf("tagName" to "Work"))
        val vmWork = TaggedCredentialListViewModel(
            saved, repo, deleteUseCase, hardDeleteUseCase,
            com.roufsyed.onekey.core.domain.usecase.RestoreFromRecycleBinUseCase(repo, historyRepo),
            prefs, snapshot, testDispatcher,
        )
        vmWork.setSearchQuery("github")
        advanceUntilIdle()
        assertEquals("github", saved.get<String>("tagged_search_query_Work"))

        val savedBanking = SavedStateHandle(mapOf("tagName" to "Banking"))
        val vmBank = TaggedCredentialListViewModel(
            savedBanking, repo, deleteUseCase, hardDeleteUseCase,
            com.roufsyed.onekey.core.domain.usecase.RestoreFromRecycleBinUseCase(repo, historyRepo),
            prefs, snapshot, testDispatcher,
        )
        assertEquals("", vmBank.searchQuery.value)
    }

    @Test fun search_combined_with_tag_uses_AND_semantics() = runTest(testDispatcher) {
        snapshot.value = SnapshotState.Loaded(
            listOf(
                snap("1", "Chase Bank", tags = listOf("Banking")),
                snap("2", "Chase Credit Card", tags = listOf("Cards")),
                snap("3", "HSBC", tags = listOf("Banking")),
            ),
        )
        val vm = buildVm(tag = "Banking")
        keepAlive(vm)
        vm.setSearchQuery("chase")
        advanceUntilIdle()
        val ids = (vm.listState.value as CredentialListState.Loaded).credentials.map { it.id }
        assertEquals(listOf("1"), ids)
    }

    @Test fun search_has_no_effect_when_listState_is_Locked() = runTest(testDispatcher) {
        snapshot.value = SnapshotState.Locked
        val vm = buildVm(tag = TAG_ALL)
        keepAlive(vm)
        vm.setSearchQuery("anything")
        advanceUntilIdle()
        assertEquals(CredentialListState.Locked, vm.listState.value)
    }

    // ── snapshot-state mapping ──────────────────────────────────────────────

    @Test fun snapshot_Locked_yields_listState_Locked() = runTest(testDispatcher) {
        snapshot.value = SnapshotState.Locked
        val vm = buildVm(tag = TAG_ALL)
        keepAlive(vm)
        advanceUntilIdle()
        assertEquals(CredentialListState.Locked, vm.listState.value)
    }

    @Test fun snapshot_Loading_yields_listState_Loading() = runTest(testDispatcher) {
        snapshot.value = SnapshotState.Loading
        val vm = buildVm(tag = TAG_ALL)
        keepAlive(vm)
        advanceUntilIdle()
        assertEquals(CredentialListState.Loading, vm.listState.value)
    }

    @Test fun snapshot_Bypassed_yields_listState_Bypassed() = runTest(testDispatcher) {
        snapshot.value = SnapshotState.Bypassed
        val vm = buildVm(tag = TAG_ALL)
        keepAlive(vm)
        advanceUntilIdle()
        assertEquals(CredentialListState.Bypassed, vm.listState.value)
    }

    // ── sort orders ─────────────────────────────────────────────────────────

    @Test fun sort_NEWEST_FIRST_orders_by_createdAt_desc() = runTest(testDispatcher) {
        prefs.sortOrder.value = CredentialSortOrder.NEWEST_FIRST
        snapshot.value = SnapshotState.Loaded(
            listOf(
                snap("a", "A", createdAt = 1_000),
                snap("b", "B", createdAt = 3_000),
                snap("c", "C", createdAt = 2_000),
            ),
        )
        val vm = buildVm(tag = TAG_ALL)
        keepAlive(vm)
        advanceUntilIdle()
        val ids = (vm.listState.value as CredentialListState.Loaded).credentials.map { it.id }
        assertEquals(listOf("b", "c", "a"), ids)
    }

    @Test fun sort_LAST_MODIFIED_orders_by_updatedAt_desc() = runTest(testDispatcher) {
        prefs.sortOrder.value = CredentialSortOrder.LAST_MODIFIED
        snapshot.value = SnapshotState.Loaded(
            listOf(
                snap("a", "A", updatedAt = 1_000),
                snap("b", "B", updatedAt = 3_000),
                snap("c", "C", updatedAt = 2_000),
            ),
        )
        val vm = buildVm(tag = TAG_ALL)
        keepAlive(vm)
        advanceUntilIdle()
        val ids = (vm.listState.value as CredentialListState.Loaded).credentials.map { it.id }
        assertEquals(listOf("b", "c", "a"), ids)
    }

    @Test fun sort_RECENTLY_ACCESSED_falls_back_to_updatedAt_for_null() = runTest(testDispatcher) {
        prefs.sortOrder.value = CredentialSortOrder.RECENTLY_ACCESSED
        snapshot.value = SnapshotState.Loaded(
            listOf(
                snap("a", "A", updatedAt = 1_000, accessedAt = null),
                snap("b", "B", updatedAt = 4_000, accessedAt = 2_000),
                snap("c", "C", updatedAt = 5_000, accessedAt = null),
            ),
        )
        val vm = buildVm(tag = TAG_ALL)
        keepAlive(vm)
        advanceUntilIdle()
        val ids = (vm.listState.value as CredentialListState.Loaded).credentials.map { it.id }
        assertEquals(listOf("c", "b", "a"), ids)
    }

    @Test fun sort_ALPHABETICAL_orders_case_insensitive_project_once() = runTest(testDispatcher) {
        prefs.sortOrder.value = CredentialSortOrder.ALPHABETICAL
        snapshot.value = SnapshotState.Loaded(
            listOf(snap("a", "bravo"), snap("b", "ALPHA"), snap("c", "Charlie")),
        )
        val vm = buildVm(tag = TAG_ALL)
        keepAlive(vm)
        advanceUntilIdle()
        val ids = (vm.listState.value as CredentialListState.Loaded).credentials.map { it.id }
        assertEquals(listOf("b", "a", "c"), ids)
    }

    @Test fun setSortOrder_persists_via_appPrefs() = runTest(testDispatcher) {
        val vm = buildVm(tag = TAG_ALL)
        vm.setSortOrder(CredentialSortOrder.ALPHABETICAL)
        advanceUntilIdle()
        assertEquals(CredentialSortOrder.ALPHABETICAL, prefs.sortOrder.value)
    }

    @Test fun letterIndex_populated_only_when_ALPHABETICAL_and_Loaded() = runTest(testDispatcher) {
        prefs.sortOrder.value = CredentialSortOrder.NEWEST_FIRST
        snapshot.value = SnapshotState.Loaded(listOf(snap("a", "Apple")))
        val vm = buildVm(tag = TAG_ALL)
        keepAlive(vm)
        backgroundScope.launch { vm.letterIndex.collect {} }
        advanceUntilIdle()
        assertTrue(vm.letterIndex.value.isEmpty())

        prefs.sortOrder.value = CredentialSortOrder.ALPHABETICAL
        advanceUntilIdle()
        assertTrue(vm.letterIndex.value.isNotEmpty())
    }

    // ── selection ───────────────────────────────────────────────────────────

    @Test fun toggleSelection_round_trip_then_clearSelection() = runTest(testDispatcher) {
        val vm = buildVm(tag = TAG_ALL)
        vm.toggleSelection("a")
        vm.toggleSelection("b")
        assertEquals(setOf("a", "b"), vm.selectedIds.value)
        vm.clearSelection()
        assertTrue(vm.selectedIds.value.isEmpty())
    }

    @Test fun selectedAreAllFavourite_true_when_all_selected_favs() = runTest(testDispatcher) {
        snapshot.value = SnapshotState.Loaded(
            listOf(
                snap("a", "A", isFavorite = true),
                snap("b", "B", isFavorite = true),
            ),
        )
        val vm = buildVm(tag = TAG_ALL)
        keepAlive(vm)
        backgroundScope.launch { vm.selectedAreAllFavourite.collect {} }
        advanceUntilIdle()
        vm.toggleSelection("a")
        vm.toggleSelection("b")
        advanceUntilIdle()
        assertTrue(vm.selectedAreAllFavourite.value)
    }

    @Test fun deleteSelected_routes_to_soft_delete_use_case() = runTest(testDispatcher) {
        val vm = buildVm(tag = TAG_ALL)
        vm.toggleSelection("a")
        vm.deleteSelected()
        advanceUntilIdle()
        assertEquals(listOf("a"), repo.softDeleted)
    }

    @Test fun deleteSelectedNow_routes_to_hard_delete_use_case() = runTest(testDispatcher) {
        val vm = buildVm(tag = TAG_ALL)
        vm.toggleSelection("a")
        vm.deleteSelectedNow()
        advanceUntilIdle()
        assertEquals(listOf("a"), repo.hardDeleted)
    }

    @Test fun setFavouriteOnSelected_makeTrue_routes_to_toggleFavorite_true() = runTest(testDispatcher) {
        val vm = buildVm(tag = TAG_ALL)
        vm.toggleSelection("a")
        vm.setFavouriteOnSelected(makeFavourite = true)
        advanceUntilIdle()
        assertEquals(listOf("a" to true), repo.favouriteToggles)
    }

    @Test fun lock_transition_clears_selectedIds_via_snapshotState_observer() = runTest(testDispatcher) {
        val vm = buildVm(tag = TAG_ALL)
        vm.toggleSelection("a")
        advanceUntilIdle()
        snapshot.value = SnapshotState.Locked
        advanceUntilIdle()
        assertTrue(vm.selectedIds.value.isEmpty())
    }

    @Test fun bypass_transition_clears_selectedIds() = runTest(testDispatcher) {
        val vm = buildVm(tag = TAG_ALL)
        vm.toggleSelection("a")
        advanceUntilIdle()
        snapshot.value = SnapshotState.Bypassed
        advanceUntilIdle()
        assertTrue(vm.selectedIds.value.isEmpty())
    }

    @Test fun tag_with_no_matching_rows_yields_Loaded_empty_not_Loading() = runTest(testDispatcher) {
        snapshot.value = SnapshotState.Loaded(
            listOf(snap("1", "A", tags = listOf("Other"))),
        )
        val vm = buildVm(tag = "Empty")
        keepAlive(vm)
        advanceUntilIdle()
        val s = vm.listState.value
        assertTrue(s is CredentialListState.Loaded)
        assertTrue((s as CredentialListState.Loaded).credentials.isEmpty())
    }

    @Test fun initial_value_is_Locked_not_Loading() {
        val vm = buildVm(tag = TAG_ALL)
        assertEquals(CredentialListState.Locked, vm.listState.value)
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private fun buildVm(tag: String): TaggedCredentialListViewModel {
        val saved = SavedStateHandle(mapOf("tagName" to tag))
        return TaggedCredentialListViewModel(
            saved, repo, deleteUseCase, hardDeleteUseCase,
            com.roufsyed.onekey.core.domain.usecase.RestoreFromRecycleBinUseCase(repo, historyRepo),
            prefs, snapshot, testDispatcher,
        )
    }

    private fun TestScope.keepAlive(vm: TaggedCredentialListViewModel) {
        backgroundScope.launch { vm.listState.collect {} }
    }

    private fun snap(
        id: String,
        title: String,
        username: String = "u",
        tags: List<String> = emptyList(),
        createdAt: Long = 0L,
        updatedAt: Long = 0L,
        accessedAt: Long? = null,
        isFavorite: Boolean = false,
    ) = SnapshotCredential(
        id = id, title = title, username = username, url = "", tags = tags,
        isFavorite = isFavorite, type = CredentialType.LOGIN,
        createdAt = createdAt, updatedAt = updatedAt, accessedAt = accessedAt, hasOtp = false,
    )

    @Suppress("DEPRECATION")
    private class FakeCredentialRepository : CredentialRepository {
        val softDeleted = mutableListOf<String>()
        val hardDeleted = mutableListOf<String>()
        val favouriteToggles = mutableListOf<Pair<String, Boolean>>()

        override suspend fun deleteCredential(id: String): AppResult<Unit> {
            softDeleted += id
            return AppResult.Success(Unit)
        }
        override suspend fun hardDeleteCredential(id: String): AppResult<Unit> {
            hardDeleted += id
            return AppResult.Success(Unit)
        }
        override suspend fun toggleFavorite(id: String, isFavorite: Boolean): AppResult<Unit> {
            favouriteToggles += id to isFavorite
            return AppResult.Success(Unit)
        }

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
        override suspend fun snapshotCredential(credential: Credential): AppResult<Unit> = AppResult.Success(Unit)
        override fun observeHistory(credentialId: String): Flow<List<CredentialHistoryEntry>> = flowOf(emptyList())
        override suspend fun deleteForCredential(credentialId: String): AppResult<Unit> = AppResult.Success(Unit)
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
        override fun isAutofillSaveUrlOnCrossHostEnabled(): Flow<Boolean> = flowOf(false)
        override suspend fun setAutofillSaveUrlOnCrossHostEnabled(enabled: Boolean) = error("unused")
        override fun getLockReasonContext(): Flow<String?> = error("unused")
        override suspend fun getLockReasonContextDirect(): String? = error("unused")
        override suspend fun setLockReasonContext(context: String?) = error("unused")
        override fun getAcknowledgedAttestationReason(): Flow<String?> = error("unused")
        override suspend fun setAcknowledgedAttestationReason(reason: String) = error("unused")
        override fun getBiometricUnlockGate(): Flow<BiometricUnlockGate> = error("unused")
        override fun isSyncEnabled(): Flow<Boolean> = flowOf(false)
        override suspend fun setSyncEnabled(enabled: Boolean) = Unit
        override fun getSyncLocationUri(): Flow<String?> = flowOf(null)
        override suspend fun setSyncLocationUri(uri: String?) = Unit
        override fun isSyncCompletionNotificationEnabled(): Flow<Boolean> = flowOf(false)
        override suspend fun setSyncCompletionNotificationEnabled(enabled: Boolean) = Unit
        override fun getSyncLastSuccessAt(): Flow<Long> = flowOf(0L)
        override suspend fun setSyncLastSuccessAt(timestamp: Long) = Unit
        override suspend fun getSyncGateDirect(): com.roufsyed.onekey.core.domain.repository.SyncGate =
            com.roufsyed.onekey.core.domain.repository.SyncGate(enabled = false, locationUri = null)
    }
}

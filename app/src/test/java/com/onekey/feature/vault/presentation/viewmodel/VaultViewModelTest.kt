package com.onekey.feature.vault.presentation.viewmodel

import android.app.Application
import androidx.paging.PagingData
import com.onekey.core.data.snapshot.SnapshotCredential
import com.onekey.core.data.snapshot.SnapshotState
import com.onekey.core.domain.model.AppResult
import com.onekey.core.domain.model.BackgroundLockTimeout
import com.onekey.core.domain.model.Credential
import com.onekey.core.domain.model.CredentialSortOrder
import com.onekey.core.domain.model.CredentialType
import com.onekey.core.domain.model.InactivityLockTimeout
import com.onekey.core.domain.model.MasterPasswordInterval
import com.onekey.core.domain.model.RecycleBinRetention
import com.onekey.core.domain.model.Tag
import com.onekey.core.domain.model.TagWithCount
import com.onekey.core.domain.repository.AppPreferencesRepository
import com.onekey.core.domain.repository.BiometricUnlockGate
import com.onekey.core.domain.repository.CredentialRepository
import com.onekey.core.domain.repository.TagRepository
import app.cash.turbine.test
import kotlinx.coroutines.CoroutineScope
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
 * Behavioural locks for [VaultViewModel.searchResults] — the SearchState
 * sealed flow derived from the shared
 * [com.onekey.core.data.snapshot.VaultSnapshotStore]:
 *
 *  - blank query yields Idle regardless of snapshot state,
 *  - snapshot Locked with non-blank query yields Idle (lock-screen overlay
 *    transition window — the search composable should not be reachable
 *    while truly locked, but Idle is the safe terminal),
 *  - snapshot Loading with non-blank query yields Loading (never flashes
 *    "No results" before the first decrypt pass completes),
 *  - snapshot Bypassed with non-blank query yields Bypassed,
 *  - snapshot Loaded with non-blank query yields a case-insensitive
 *    title-substring filter, sorted newest-first by createdAt,
 *  - 150 ms debounce coalesces rapid keystrokes into one filter pass,
 *  - vault lock mid-search drops the SearchState back to Idle and preserves
 *    the query so the user doesn't have to retype after re-unlock.
 *
 * Robolectric is needed for `application = Application::class` to bypass
 * HiltAndroidApp's eager EncryptedSharedPreferences provisioning, which
 * Robolectric's shadow KeyStore cannot resolve.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class VaultViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var tags: FakeTagRepository
    private lateinit var repo: FakeCredentialRepository
    private lateinit var prefs: FakeAppPreferencesRepository
    private lateinit var snapshot: MutableStateFlow<SnapshotState>

    @Before fun setup() {
        Dispatchers.setMain(testDispatcher)
        tags = FakeTagRepository()
        repo = FakeCredentialRepository()
        prefs = FakeAppPreferencesRepository()
        snapshot = MutableStateFlow(SnapshotState.Locked)
    }

    @After fun teardown() {
        Dispatchers.resetMain()
    }

    @Test fun blank_query_yields_Idle_even_when_snapshot_Loaded() = runTest(testDispatcher) {
        snapshot.value = SnapshotState.Loaded(listOf(snap("1", "GitHub", 1_000)))
        val vm = buildVm()
        advanceUntilIdle()
        assertTrue(vm.searchResults.value is VaultViewModel.SearchState.Idle)
    }

    @Test fun snapshot_Locked_with_query_yields_Idle() = runTest(testDispatcher) {
        snapshot.value = SnapshotState.Locked
        val vm = buildVm()
        vm.setSearchQuery("g")
        advanceUntilIdle()
        assertTrue(vm.searchResults.value is VaultViewModel.SearchState.Idle)
    }

    @Test fun snapshot_Loading_with_query_yields_Loading() = runTest(testDispatcher) {
        snapshot.value = SnapshotState.Loading
        val vm = buildVm()
        vm.setSearchQuery("g")
        advanceUntilIdle()
        assertTrue(vm.searchResults.value is VaultViewModel.SearchState.Loading)
    }

    @Test fun snapshot_Bypassed_with_query_yields_Bypassed() = runTest(testDispatcher) {
        snapshot.value = SnapshotState.Bypassed
        val vm = buildVm()
        vm.setSearchQuery("g")
        advanceUntilIdle()
        assertTrue(vm.searchResults.value is VaultViewModel.SearchState.Bypassed)
    }

    @Test fun snapshot_Loaded_filters_by_title_case_insensitive() = runTest(testDispatcher) {
        snapshot.value = SnapshotState.Loaded(
            listOf(
                snap("1", "GitHub", 1_000),
                snap("2", "Gmail", 2_000),
                snap("3", "Twitter", 3_000),
            ),
        )
        val vm = buildVm()
        vm.setSearchQuery("G")
        advanceUntilIdle()
        val r = vm.searchResults.value as VaultViewModel.SearchState.Loaded
        assertEquals(setOf("1", "2"), r.credentials.map { it.id }.toSet())
    }

    @Test fun snapshot_Loaded_does_not_filter_by_username_only_title() = runTest(testDispatcher) {
        // Pre-PR3 vault search filtered on title only (legacy slow-path) —
        // preserve that semantic. Autofill's broader title+username search
        // is a deliberately distinct surface.
        snapshot.value = SnapshotState.Loaded(
            listOf(
                snap("1", "Twitter", 1_000, username = "alice@example.com"),
            ),
        )
        val vm = buildVm()
        vm.setSearchQuery("alice")
        advanceUntilIdle()
        val r = vm.searchResults.value as VaultViewModel.SearchState.Loaded
        assertTrue("must not match on username", r.credentials.isEmpty())
    }

    @Test fun snapshot_Loaded_sorts_newest_first_by_createdAt() = runTest(testDispatcher) {
        snapshot.value = SnapshotState.Loaded(
            listOf(
                snap("1", "Google A", createdAt = 1_000),
                snap("2", "Google B", createdAt = 3_000),
                snap("3", "Google C", createdAt = 2_000),
            ),
        )
        val vm = buildVm()
        vm.setSearchQuery("Google")
        advanceUntilIdle()
        val r = vm.searchResults.value as VaultViewModel.SearchState.Loaded
        assertEquals(listOf("2", "3", "1"), r.credentials.map { it.id })
    }

    @Test fun debounce_collapses_rapid_typing_into_one_filter_pass() = runTest(testDispatcher) {
        snapshot.value = SnapshotState.Loaded(
            listOf(
                snap("1", "GitHub", 1_000),
                snap("2", "Gmail", 2_000),
            ),
        )
        val vm = buildVm()
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
        val r = vm.searchResults.value as VaultViewModel.SearchState.Loaded
        assertEquals(1, r.credentials.size)
        assertEquals("GitHub", r.credentials.single().title)
    }

    @Test fun lock_after_loaded_search_drops_to_Idle_and_preserves_query() = runTest(testDispatcher) {
        snapshot.value = SnapshotState.Loaded(listOf(snap("1", "GitHub", 1_000)))
        val vm = buildVm()
        vm.setSearchQuery("g")
        advanceUntilIdle()
        assertTrue(vm.searchResults.value is VaultViewModel.SearchState.Loaded)

        snapshot.value = SnapshotState.Locked
        advanceUntilIdle()
        assertTrue(vm.searchResults.value is VaultViewModel.SearchState.Idle)
        assertEquals("g", vm.searchQuery.value)
    }

    @Test fun unlock_after_lock_with_existing_query_re_emits_Loaded() = runTest(testDispatcher) {
        snapshot.value = SnapshotState.Loaded(listOf(snap("1", "GitHub", 1_000)))
        val vm = buildVm()
        vm.setSearchQuery("g")
        advanceUntilIdle()

        snapshot.value = SnapshotState.Locked
        advanceUntilIdle()
        assertTrue(vm.searchResults.value is VaultViewModel.SearchState.Idle)

        // Vault re-unlocks; snapshot reloads with the same row.
        snapshot.value = SnapshotState.Loaded(listOf(snap("1", "GitHub", 1_000)))
        advanceUntilIdle()
        val r = vm.searchResults.value as VaultViewModel.SearchState.Loaded
        assertEquals(1, r.credentials.size)
    }

    @Test fun snapshot_Loaded_empty_with_query_yields_Loaded_empty() = runTest(testDispatcher) {
        // Unlocked, decrypted, but the vault is empty. The search composable
        // should reach the "No results for X" branch — same UX as pre-PR3.
        snapshot.value = SnapshotState.Loaded(emptyList())
        val vm = buildVm()
        vm.setSearchQuery("g")
        advanceUntilIdle()
        val r = vm.searchResults.value as VaultViewModel.SearchState.Loaded
        assertTrue(r.credentials.isEmpty())
    }

    @Test fun whitespace_only_query_yields_Idle() = runTest(testDispatcher) {
        snapshot.value = SnapshotState.Loaded(listOf(snap("1", "GitHub", 1_000)))
        val vm = buildVm()
        vm.setSearchQuery("   ")
        advanceUntilIdle()
        // String.isBlank() is true for whitespace-only — must not run a real
        // filter pass that would surface every credential whose title contains
        // a space (i.e. almost all of them).
        assertTrue(vm.searchResults.value is VaultViewModel.SearchState.Idle)
    }

    @Test fun unrelated_upstream_refire_does_not_re_emit_loaded() = runTest(testDispatcher) {
        // Simulates a save / toggle / delete that fires Room invalidation but
        // doesn't change the filtered slice for the active query. With
        // distinctUntilChanged on the combine output, the VM must NOT publish
        // a fresh Loaded — gratuitous Compose State churn otherwise.
        val initial = listOf(
            snap("1", "GitHub", 1_000),
            snap("2", "Gmail", 2_000),
            snap("3", "Twitter", 3_000),
        )
        snapshot.value = SnapshotState.Loaded(initial)
        val vm = buildVm()
        vm.setSearchQuery("g")
        advanceUntilIdle()

        vm.searchResults.test {
            // Drain the current Loaded.
            val first = awaitItem() as VaultViewModel.SearchState.Loaded
            assertEquals(setOf("1", "2"), first.credentials.map { it.id }.toSet())

            // Upstream re-fires with a structurally-identical list (could be
            // a bump on row "3" that doesn't change the filter outcome).
            snapshot.value = SnapshotState.Loaded(initial.map { it.copy() })
            advanceUntilIdle()
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun migration_in_flight_then_settled_promotes_to_Loaded() = runTest(testDispatcher) {
        // Reproduces the cipher-migration window: snapshot reports Loading
        // while CredentialCipherMigrator rewrites legacy rows. UI must show
        // the spinner — never the empty state — for the duration.
        snapshot.value = SnapshotState.Loading
        val vm = buildVm()
        vm.setSearchQuery("g")
        advanceUntilIdle()
        assertTrue(vm.searchResults.value is VaultViewModel.SearchState.Loading)

        snapshot.value = SnapshotState.Loaded(listOf(snap("1", "GitHub", 1_000)))
        advanceUntilIdle()
        val r = vm.searchResults.value as VaultViewModel.SearchState.Loaded
        assertEquals(1, r.credentials.size)
    }

    /**
     * Builds the VM and primes a long-lived subscriber on [scope] so the
     * `WhileSubscribed(5_000)` upstream in `searchResults` stays active for
     * the whole test. Mirrors production, where Compose holds the collector
     * via `collectAsStateWithLifecycle` for as long as the screen is visible.
     */
    private fun TestScope.buildVm(): VaultViewModel {
        val vm = VaultViewModel(tags, repo, snapshot, prefs)
        keepAlive(backgroundScope, vm)
        return vm
    }

    private fun keepAlive(scope: CoroutineScope, vm: VaultViewModel) {
        scope.launch { vm.searchResults.collect { } }
    }

    private fun snap(
        id: String,
        title: String,
        createdAt: Long = 0L,
        username: String = "u",
    ) = SnapshotCredential(
        id = id, title = title, username = username, url = "",
        tags = emptyList(), isFavorite = false, type = CredentialType.LOGIN,
        createdAt = createdAt, updatedAt = createdAt, accessedAt = null, hasOtp = false,
    )

    private class FakeTagRepository : TagRepository {
        override fun observeTags(): Flow<List<Tag>> = error("unused")
        override fun observeTagsWithCounts(): Flow<List<TagWithCount>> = flowOf(emptyList())
        override suspend fun getTags(): AppResult<List<Tag>> = error("unused")
        override suspend fun addTag(tag: Tag): AppResult<Unit> = error("unused")
        override suspend fun deleteTag(name: String): AppResult<Unit> = error("unused")
    }

    private class FakeCredentialRepository : CredentialRepository {
        override fun observeCount(): Flow<Int> = flowOf(0)
        override fun observeFavoriteCount(): Flow<Int> = flowOf(0)
        override fun observeRecycleBinCount(): Flow<Int> = flowOf(0)
        override fun observeCountForTag(tag: String): Flow<Int> = flowOf(0)

        override fun getPagedCredentials(
            query: String,
            tag: String,
            sortOrder: CredentialSortOrder,
        ): Flow<PagingData<Credential>> = error("unused — VaultViewModel uses snapshot path")
        override fun observeCredential(id: String): Flow<Credential?> = error("unused")
        override fun observeCredentialIncludingDeleted(id: String): Flow<Credential?> = error("unused")
        override suspend fun getCredential(id: String): AppResult<Credential> = error("unused")
        override suspend fun saveCredential(credential: Credential): AppResult<Unit> = error("unused")
        override suspend fun deleteCredential(id: String): AppResult<Unit> = error("unused")
        override suspend fun hardDeleteCredential(id: String): AppResult<Unit> = error("unused")
        override suspend fun restoreCredential(id: String): AppResult<Unit> = error("unused")
        override suspend fun purgeFromRecycleBin(id: String): AppResult<Unit> = error("unused")
        override suspend fun emptyRecycleBin(): AppResult<Int> = error("unused")
        override suspend fun purgeRecycleBinOlderThan(cutoff: Long): AppResult<Int> = error("unused")
        override fun observeRecycleBin(): Flow<List<Credential>> = error("unused")
        override suspend fun getAllCredentials(): AppResult<List<Credential>> = error("unused")
        override suspend fun getAllInRecycleBin(): AppResult<List<Credential>> = error("unused")
        override suspend fun importCredentials(credentials: List<Credential>): AppResult<Int> = error("unused")
        override fun observeFavorites(): Flow<List<Credential>> = error("unused")
        override fun observeFavoritesPaged(sortOrder: CredentialSortOrder): Flow<PagingData<Credential>> = error("unused")
        override fun observeCredentials(query: String, tag: String, sortOrder: CredentialSortOrder): Flow<List<Credential>> = error("unused")
        override fun observeFavoritesSorted(sortOrder: CredentialSortOrder): Flow<List<Credential>> = error("unused")
        override fun observeAllTitlesAlphabetical(tag: String): Flow<List<String>> = error("unused")
        override fun observeFavoriteTitlesAlphabetical(): Flow<List<String>> = error("unused")
        override fun observeRotatingOtp(): Flow<List<Credential>> = error("unused")
        override fun observeHotpEntries(): Flow<List<Credential>> = error("unused")
        override suspend fun incrementHotpCounter(credentialId: String): AppResult<Long?> = error("unused")
        override suspend fun toggleFavorite(id: String, isFavorite: Boolean): AppResult<Unit> = error("unused")
        override suspend fun deleteAllCredentials(): AppResult<Unit> = error("unused")
        override suspend fun markAccessed(id: String): AppResult<Unit> = error("unused")
    }

    private class FakeAppPreferencesRepository : AppPreferencesRepository {
        override fun isHideTopBarOnScroll(): Flow<Boolean> = flowOf(true)
        override fun isVaultFooterVisible(): Flow<Boolean> = flowOf(true)
        override fun isRecycleBinEnabled(): Flow<Boolean> = flowOf(true)
        override suspend fun setHideTopBarOnScroll(enabled: Boolean) = error("unused")
        override suspend fun setVaultFooterVisible(visible: Boolean) = error("unused")
        override suspend fun setRecycleBinEnabled(enabled: Boolean) = error("unused")

        override fun isDarkTheme(): Flow<Boolean> = error("unused")
        override suspend fun setDarkTheme(dark: Boolean) = error("unused")
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
        override fun getCredentialSortOrder(): Flow<CredentialSortOrder> = error("unused")
        override suspend fun setCredentialSortOrder(order: CredentialSortOrder) = error("unused")
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
    }
}

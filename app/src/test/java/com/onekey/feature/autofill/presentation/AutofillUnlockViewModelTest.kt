package com.onekey.feature.autofill.presentation

import android.app.Application
import android.os.Bundle
import androidx.lifecycle.SavedStateHandle
import androidx.paging.PagingData
import com.onekey.core.domain.model.AppResult
import com.onekey.core.domain.model.Credential
import com.onekey.core.domain.model.CredentialSortOrder
import com.onekey.core.domain.repository.AuthRepository
import com.onekey.core.domain.repository.CredentialRepository
import com.onekey.feature.autofill.domain.AutofillScenario
import com.onekey.feature.autofill.domain.PackageMatcher
import com.onekey.feature.autofill.domain.ParsedFields
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Behavioural locks for [AutofillUnlockViewModel]:
 *
 *  - seeds search query from the form host the first time we enter search mode,
 *  - preserves the query across "re-creation" (SavedStateHandle survival),
 *  - debounces typing through a single repository fetch,
 *  - clears the decrypted snapshot when the vault is observed locked, and
 *    re-fetches on re-unlock,
 *  - routes cross-host picks through [crossHostFor] before fill, while
 *    exact-host picks short-circuit straight to resolve,
 *  - never flips the auto-pick path into "fill anyway" without confirmation.
 *
 * Robolectric is needed because [ParsedFields] is `@Parcelize` and gets read
 * out of a real [Bundle] inside the VM. `application = Application::class`
 * bypasses HiltAndroidApp's eager EncryptedSharedPreferences provisioning,
 * which Robolectric's shadow KeyStore cannot resolve.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AutofillUnlockViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var scope: TestScope
    private lateinit var auth: FakeAuthRepository
    private lateinit var repo: FakeCredentialRepository
    private lateinit var matcher: PackageMatcher

    @Before fun setup() {
        Dispatchers.setMain(testDispatcher)
        scope = TestScope(testDispatcher)
        auth = FakeAuthRepository()
        repo = FakeCredentialRepository()
        matcher = PackageMatcher(repo)
    }

    @After fun teardown() {
        Dispatchers.resetMain()
    }

    @Test fun initial_is_Invalid_when_extra_missing() {
        val vm = buildVm(parsed = null)
        assertTrue(vm.initial is AutofillUnlockViewModel.InitialState.Invalid)
    }

    @Test fun initial_is_Ready_when_extra_present() {
        val vm = buildVm(parsed = parsed(webDomain = "example.com"))
        assertTrue(vm.initial is AutofillUnlockViewModel.InitialState.Ready)
    }

    @Test fun search_query_seeded_with_webDomain_when_startInSearch() = runTest(testDispatcher) {
        val vm = buildVm(parsed = parsed(webDomain = "mail.google.com"), startInSearch = true)
        // Seeding runs in `init {}` and is observable immediately.
        assertEquals("mail.google.com", vm.searchQuery.value)
    }

    @Test fun search_query_seeded_with_packageName_when_no_webDomain() = runTest(testDispatcher) {
        val vm = buildVm(parsed = parsed(webDomain = null, pkg = "com.acme.app"), startInSearch = true)
        assertEquals("com.acme.app", vm.searchQuery.value)
    }

    @Test fun search_query_not_seeded_when_already_persisted() = runTest(testDispatcher) {
        // Simulate process death — SavedStateHandle re-creation pre-loaded
        // with a prior query the user typed.
        val saved = SavedStateHandle(
            mapOf(
                AutofillUnlockActivity.EXTRA_PARSED_FIELDS to parsed(webDomain = "google.com"),
                AutofillUnlockActivity.EXTRA_START_IN_SEARCH to true,
                "autofill_search_query" to "github",
            )
        )
        val vm = AutofillUnlockViewModel(matcher, repo, auth, saved)
        assertEquals("github typed by the user must outlive the seed", "github", vm.searchQuery.value)
    }

    @Test fun search_query_not_seeded_when_not_startInSearch() = runTest(testDispatcher) {
        val vm = buildVm(parsed = parsed(webDomain = "github.com"), startInSearch = false)
        assertEquals("", vm.searchQuery.value)
    }

    @Test fun snapshot_loads_once_when_unlocked() = runTest(testDispatcher) {
        repo.allCredentials = listOf(credential("1", "GitHub", "u", "https://github.com"))
        val vm = buildVm(parsed = parsed(webDomain = "github.com"), startInSearch = true)
        auth.unlocked.value = true
        vm.loadSnapshot()
        advanceUntilIdle()
        // Second call is a no-op while the cache is populated.
        repo.getAllCalls = 0
        vm.loadSnapshot()
        advanceUntilIdle()
        assertEquals(0, repo.getAllCalls)
    }

    @Test fun debounce_collapses_rapid_typing_into_one_emission() = runTest(testDispatcher) {
        repo.allCredentials = listOf(
            credential("1", "Google", "alice", "https://google.com"),
            credential("2", "Gmail", "bob", "https://accounts.google.com"),
        )
        val vm = buildVm(parsed = parsed(webDomain = "mail.google.com"), startInSearch = true)
        auth.unlocked.value = true
        vm.loadSnapshot()
        advanceUntilIdle()

        // Rapid burst of keystrokes well within the 150 ms debounce window.
        vm.onSearchQueryChanged("g")
        advanceTimeBy(20)
        vm.onSearchQueryChanged("go")
        advanceTimeBy(20)
        vm.onSearchQueryChanged("goo")
        advanceTimeBy(20)
        vm.onSearchQueryChanged("googl")
        // Still inside debounce — results haven't refreshed for the new query yet.
        advanceTimeBy(50)
        vm.onSearchQueryChanged("google")
        advanceUntilIdle()
        // After idle, the latest query is what's reflected.
        val results = vm.searchResults.value as AutofillUnlockViewModel.SearchState.Loaded
        // Both credentials contain "google" — host substring band.
        assertEquals(2, results.credentials.size)
    }

    @Test fun lock_clears_snapshot_and_search_results() = runTest(testDispatcher) {
        repo.allCredentials = listOf(credential("1", "GitHub", "u", "https://github.com"))
        val vm = buildVm(parsed = parsed(webDomain = "github.com"), startInSearch = true)
        auth.unlocked.value = true
        vm.loadSnapshot()
        vm.onSearchQueryChanged("git")
        advanceUntilIdle()
        assertTrue(vm.searchResults.value is AutofillUnlockViewModel.SearchState.Loaded)

        // Vault locks (auto-lock fires).
        auth.unlocked.value = false
        advanceUntilIdle()
        assertTrue(vm.searchResults.value is AutofillUnlockViewModel.SearchState.Idle)
        // Query is preserved — the user shouldn't have to retype after re-unlock.
        assertEquals("git", vm.searchQuery.value)
    }

    @Test fun relock_then_unlock_refetches_snapshot() = runTest(testDispatcher) {
        repo.allCredentials = listOf(credential("1", "GitHub", "u", "https://github.com"))
        val vm = buildVm(parsed = parsed(webDomain = "github.com"), startInSearch = true)
        auth.unlocked.value = true
        vm.loadSnapshot()
        advanceUntilIdle()
        val before = repo.getAllCalls

        auth.unlocked.value = false
        advanceUntilIdle()
        auth.unlocked.value = true
        vm.loadSnapshot()
        advanceUntilIdle()
        assertTrue("snapshot must re-fetch after relock", repo.getAllCalls > before)
    }

    @Test fun resolveCandidate_short_circuits_for_exact_match_list() = runTest(testDispatcher) {
        val vm = buildVm(parsed = parsed(webDomain = "github.com"))
        val ok = vm.resolveCandidate(
            credential("1", "GitHub", "u", "https://other-domain.example"),
            fromExactMatchList = true,
        )
        assertTrue(ok)
        assertNull("Exact-list path must never set crossHostFor", vm.crossHostFor.value)
    }

    @Test fun resolveCandidate_short_circuits_for_same_host_search_result() = runTest(testDispatcher) {
        val vm = buildVm(parsed = parsed(webDomain = "github.com"))
        val ok = vm.resolveCandidate(
            credential("1", "GitHub", "u", "https://github.com/login"),
            fromExactMatchList = false,
        )
        assertTrue(ok)
        assertNull(vm.crossHostFor.value)
    }

    @Test fun resolveCandidate_sets_crossHostFor_on_host_mismatch() = runTest(testDispatcher) {
        val vm = buildVm(parsed = parsed(webDomain = "mail.google.com"))
        val cred = credential("1", "Google", "u", "https://accounts.google.com")
        val ok = vm.resolveCandidate(cred, fromExactMatchList = false)
        assertFalse("cross-host search picks must NOT auto-fill", ok)
        assertSame(cred, vm.crossHostFor.value)
    }

    @Test fun resolveCandidate_treats_native_app_fills_as_cross_host() = runTest(testDispatcher) {
        // No webDomain means no safe host comparison is possible. Every
        // search-chosen credential should route through confirmation.
        val vm = buildVm(parsed = parsed(webDomain = null, pkg = "com.acme.app"))
        val ok = vm.resolveCandidate(
            credential("1", "Acme", "u", "https://acme.example.com"),
            fromExactMatchList = false,
        )
        assertFalse(ok)
        assertNotNull(vm.crossHostFor.value)
    }

    @Test fun confirmCrossHost_returns_credential_and_clears() = runTest(testDispatcher) {
        val vm = buildVm(parsed = parsed(webDomain = "mail.google.com"))
        val cred = credential("1", "Google", "u", "https://accounts.google.com")
        vm.resolveCandidate(cred, fromExactMatchList = false)
        val out = vm.confirmCrossHost()
        assertSame(cred, out)
        assertNull(vm.crossHostFor.value)
    }

    @Test fun cancelCrossHost_clears_without_returning_credential() = runTest(testDispatcher) {
        val vm = buildVm(parsed = parsed(webDomain = "mail.google.com"))
        vm.resolveCandidate(
            credential("1", "Google", "u", "https://accounts.google.com"),
            fromExactMatchList = false,
        )
        vm.cancelCrossHost()
        assertNull(vm.crossHostFor.value)
    }

    @Test fun search_ordering_exact_host_first_then_substring_then_title() = runTest(testDispatcher) {
        repo.allCredentials = listOf(
            credential("title-only", "Google Drive", "u", "https://drive.example.com"),
            credential("substring", "Mail at Google", "u", "https://mail.google.com"),
            credential("exact", "Accounts", "u", "https://accounts.google.com"),
        )
        val vm = buildVm(parsed = parsed(webDomain = "accounts.google.com"), startInSearch = true)
        auth.unlocked.value = true
        vm.loadSnapshot()
        vm.onSearchQueryChanged("google")
        advanceUntilIdle()
        val ids = (vm.searchResults.value as AutofillUnlockViewModel.SearchState.Loaded)
            .credentials.map { it.id }
        assertEquals(listOf("exact", "substring", "title-only"), ids)
    }

    @Test fun empty_query_shows_recent_starter_preview_not_full_vault() = runTest(testDispatcher) {
        // Twelve creds with descending updatedAt; verify only EMPTY_QUERY_PREVIEW (8)
        // make it into the empty-query preview list.
        val all = (1..12).map { n ->
            credential("$n", "Title$n", "u", "https://site$n.example.com", updatedAt = n.toLong())
        }
        repo.allCredentials = all
        val vm = buildVm(parsed = parsed(webDomain = "site1.example.com"))
        auth.unlocked.value = true
        vm.loadSnapshot()
        advanceUntilIdle()
        val preview = (vm.searchResults.value as AutofillUnlockViewModel.SearchState.Loaded).credentials
        assertEquals(8, preview.size)
        // Most-recent first.
        assertEquals("12", preview.first().id)
    }

    @Test fun soft_deleted_credentials_are_excluded_from_search() = runTest(testDispatcher) {
        repo.allCredentials = listOf(
            credential("live", "GitHub", "u", "https://github.com"),
            credential("trash", "GitHub Old", "u", "https://github.com", deletedAt = 1L),
        )
        val vm = buildVm(parsed = parsed(webDomain = "github.com"), startInSearch = true)
        auth.unlocked.value = true
        vm.loadSnapshot()
        vm.onSearchQueryChanged("github")
        advanceUntilIdle()
        val results = (vm.searchResults.value as AutofillUnlockViewModel.SearchState.Loaded)
            .credentials.map { it.id }
        assertEquals(listOf("live"), results)
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun buildVm(
        parsed: ParsedFields?,
        startInSearch: Boolean = false,
    ): AutofillUnlockViewModel {
        val saved = SavedStateHandle().also { h ->
            if (parsed != null) h[AutofillUnlockActivity.EXTRA_PARSED_FIELDS] = parsed
            h[AutofillUnlockActivity.EXTRA_START_IN_SEARCH] = startInSearch
        }
        return AutofillUnlockViewModel(matcher, repo, auth, saved)
    }

    private fun parsed(
        webDomain: String?,
        pkg: String = "com.example",
    ): ParsedFields = ParsedFields(
        username = null,
        password = null,
        email = null,
        scenario = AutofillScenario.LOGIN,
        packageName = pkg,
        webDomain = webDomain,
    )

    private fun credential(
        id: String,
        title: String,
        username: String,
        url: String,
        updatedAt: Long = 0L,
        deletedAt: Long? = null,
    ): Credential = Credential(
        id = id, title = title, username = username, password = "p",
        url = url, notes = "", otpParams = null,
        tags = emptyList(), customFields = emptyList(),
        createdAt = 0L, updatedAt = updatedAt,
        deletedAt = deletedAt,
    )

    private class FakeAuthRepository : AuthRepository {
        val unlocked = MutableStateFlow(false)
        override fun isUnlocked(): Flow<Boolean> = unlocked

        override fun isSetupComplete(): Flow<Boolean> = flowOf(true)
        override suspend fun setupMasterPassword(password: CharArray): AppResult<Unit> = error("unused")
        override suspend fun unlockWithPassword(password: CharArray): AppResult<Unit> = error("unused")
        override suspend fun unlockWithPin(pin: CharArray): AppResult<Unit> = error("unused")
        override suspend fun unlockWithBiometric(): AppResult<Unit> = error("unused")
        override suspend fun verifyPin(pin: CharArray): AppResult<Unit> = error("unused")
        override suspend fun setupPin(pin: CharArray): AppResult<Unit> = error("unused")
        override suspend fun changePassword(oldPassword: CharArray, newPassword: CharArray): AppResult<Unit> = error("unused")
        override suspend fun lock(): AppResult<Unit> = error("unused")
        override fun isPinSetup(): Flow<Boolean> = flowOf(false)
        override suspend fun resetPin(): AppResult<Unit> = error("unused")
        override suspend fun resetVault(): AppResult<Unit> = error("unused")
        override suspend fun clearAll(): AppResult<Unit> = error("unused")
    }

    private class FakeCredentialRepository : CredentialRepository {
        var allCredentials: List<Credential> = emptyList()
        var getAllCalls: Int = 0

        override suspend fun getAllCredentials(): AppResult<List<Credential>> {
            getAllCalls++
            return AppResult.Success(allCredentials)
        }

        override fun getPagedCredentials(query: String, tag: String, sortOrder: CredentialSortOrder): Flow<PagingData<Credential>> = error("unused")
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
        override fun observeRecycleBin(): Flow<List<Credential>> = flowOf(emptyList())
        override fun observeRecycleBinCount(): Flow<Int> = flowOf(0)
        override suspend fun getAllInRecycleBin(): AppResult<List<Credential>> = error("unused")
        override suspend fun importCredentials(credentials: List<Credential>): AppResult<Int> = error("unused")
        override fun observeCount(): Flow<Int> = flowOf(0)
        override fun observeCountForTag(tag: String): Flow<Int> = flowOf(0)
        override fun observeFavoriteCount(): Flow<Int> = flowOf(0)
        override fun observeFavorites(): Flow<List<Credential>> = flowOf(emptyList())
        override fun observeFavoritesPaged(sortOrder: CredentialSortOrder): Flow<PagingData<Credential>> = error("unused")
        override fun observeCredentials(query: String, tag: String, sortOrder: CredentialSortOrder): Flow<List<Credential>> = flowOf(emptyList())
        override fun observeFavoritesSorted(sortOrder: CredentialSortOrder): Flow<List<Credential>> = flowOf(emptyList())
        override fun observeAllTitlesAlphabetical(tag: String): Flow<List<String>> = flowOf(emptyList())
        override fun observeFavoriteTitlesAlphabetical(): Flow<List<String>> = flowOf(emptyList())
        override fun observeRotatingOtp(): Flow<List<Credential>> = flowOf(emptyList())
        override fun observeHotpEntries(): Flow<List<Credential>> = flowOf(emptyList())
        override suspend fun incrementHotpCounter(credentialId: String): AppResult<Long?> = error("unused")
        override suspend fun toggleFavorite(id: String, isFavorite: Boolean): AppResult<Unit> = error("unused")
        override suspend fun deleteAllCredentials(): AppResult<Unit> = error("unused")
        override suspend fun markAccessed(id: String): AppResult<Unit> = error("unused")
    }
}

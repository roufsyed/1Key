package com.roufsyed.onekey.feature.autofill.domain

import android.app.Application
import com.roufsyed.onekey.core.domain.model.AppResult
import com.roufsyed.onekey.core.domain.model.Credential
import com.roufsyed.onekey.core.domain.model.CredentialSortOrder
import com.roufsyed.onekey.core.domain.repository.CredentialRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Locks in the host-matching contract described in [PackageMatcher]:
 *
 *  - exact host equality is required; no eTLD+1 collapse,
 *  - URLs without a scheme parse correctly,
 *  - `www.` is stripped on both sides before comparison,
 *  - native-app requests (no webDomain) return empty,
 *  - the soft cap on results works.
 *
 * Robolectric only because ParsedFields is @Parcelize. The actual matching
 * code does not touch the Android URI framework - it uses java.net.URI - so
 * the test could run on the bare JVM, but @Parcelize-generated Parcelable
 * methods pull in android.os.Parcel during class init.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PackageMatcherTest {

    @Test fun returns_empty_when_webDomain_is_null() = runBlocking {
        val matcher = PackageMatcher(StubRepo(emptyList()))
        val parsed = parsed(webDomain = null)
        assertTrue(matcher.findMatches(parsed).isEmpty())
    }

    @Test fun returns_only_exact_host_matches() = runBlocking {
        val matcher = PackageMatcher(StubRepo(listOf(
            credential(id = "1", url = "https://github.com/login"),
            credential(id = "2", url = "https://accounts.google.com/signin"),
            credential(id = "3", url = "https://google.com"),
        )))
        val parsed = parsed(webDomain = "google.com")
        val matches = matcher.findMatches(parsed)
        assertEquals(listOf("3"), matches.map { it.id })
    }

    @Test fun strips_www_on_credential_side() = runBlocking {
        val matcher = PackageMatcher(StubRepo(listOf(
            credential(id = "1", url = "https://www.github.com/login"),
        )))
        val matches = matcher.findMatches(parsed(webDomain = "github.com"))
        assertEquals(listOf("1"), matches.map { it.id })
    }

    @Test fun bare_host_url_without_scheme_is_extracted() = runBlocking {
        val matcher = PackageMatcher(StubRepo(listOf(
            credential(id = "1", url = "example.com"),
        )))
        val matches = matcher.findMatches(parsed(webDomain = "example.com"))
        assertEquals(listOf("1"), matches.map { it.id })
    }

    @Test fun malformed_credential_urls_are_ignored() = runBlocking {
        val matcher = PackageMatcher(StubRepo(listOf(
            credential(id = "1", url = "::not::a::url::"),
            credential(id = "2", url = "https://example.com"),
        )))
        val matches = matcher.findMatches(parsed(webDomain = "example.com"))
        assertEquals(listOf("2"), matches.map { it.id })
    }

    @Test fun cross_subdomain_does_not_match() = runBlocking {
        // The whole point of the no-eTLD+1 policy: a github.io account must
        // never offer itself on user.github.io, and vice versa.
        val matcher = PackageMatcher(StubRepo(listOf(
            credential(id = "alice", url = "https://alice.github.io"),
        )))
        val crossSub = matcher.findMatches(parsed(webDomain = "bob.github.io"))
        assertTrue("Sibling subdomains must not match", crossSub.isEmpty())
    }

    @Test fun respects_soft_cap_limit() = runBlocking {
        val matcher = PackageMatcher(
            StubRepo((1..20).map { credential(id = it.toString(), url = "https://example.com") })
        )
        val matches = matcher.findMatches(parsed(webDomain = "example.com"), limit = 3)
        assertEquals(3, matches.size)
    }

    @Test fun zero_limit_returns_empty() = runBlocking {
        val matcher = PackageMatcher(
            StubRepo(listOf(credential(id = "1", url = "https://example.com")))
        )
        assertTrue(matcher.findMatches(parsed(webDomain = "example.com"), limit = 0).isEmpty())
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun parsed(webDomain: String?): ParsedFields = ParsedFields(
        username = null,
        password = null,
        email = null,
        scenario = AutofillScenario.LOGIN,
        packageName = "com.example",
        webDomain = webDomain,
    )

    private fun credential(id: String, url: String): Credential = Credential(
        id = id, title = id, username = "u", password = "p",
        url = url, notes = "", otpParams = null,
        tags = emptyList(), customFields = emptyList(),
        createdAt = 0L, updatedAt = 0L,
    )

    /**
     * Only [getAllCredentials] is reached by [PackageMatcher.findMatches]; the
     * rest of the interface is implemented as `error()` so any accidental drift
     * fails loudly in the test rather than silently returning empty data.
     */
    private class StubRepo(private val rows: List<Credential>) : CredentialRepository {
        override suspend fun getAllCredentials(): AppResult<List<Credential>> = AppResult.Success(rows)

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
        override fun observeCredentials(query: String, tag: String, sortOrder: CredentialSortOrder): Flow<List<Credential>> = flowOf(emptyList())
        override fun observeFavoritesSorted(sortOrder: CredentialSortOrder): Flow<List<Credential>> = flowOf(emptyList())
        override fun observeRotatingOtp(): Flow<List<Credential>> = flowOf(emptyList())
        override fun observeHotpEntries(): Flow<List<Credential>> = flowOf(emptyList())
        override suspend fun incrementHotpCounter(credentialId: String): AppResult<Long?> = error("unused")
        override suspend fun toggleFavorite(id: String, isFavorite: Boolean): AppResult<Unit> = error("unused")
        override suspend fun deleteAllCredentials(): AppResult<Unit> = error("unused")
        override suspend fun markAccessed(id: String): AppResult<Unit> = error("unused")
    }
}

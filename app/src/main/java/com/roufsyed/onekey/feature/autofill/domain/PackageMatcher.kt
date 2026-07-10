package com.roufsyed.onekey.feature.autofill.domain

import com.roufsyed.onekey.core.domain.model.AppResult
import com.roufsyed.onekey.core.domain.model.Credential
import com.roufsyed.onekey.core.domain.repository.CredentialRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Matches a [ParsedFields] against the user's vault and returns every
 * credential whose stored host equals the form's host, favourites first.
 *
 * Strategy (deliberately conservative - see `project_autofill.md` and the
 * skeptic review's BLOCKER 13):
 *
 *  - **Exact host match only.** We do not collapse domains to eTLD+1. Without
 *    a Public Suffix List embedded, the eTLD+1 heuristic can match
 *    `mary.co.uk` to `john.co.uk` - a cross-tenant credential-leak vector.
 *    `accounts.google.com` therefore does not match `google.com`. Less
 *    convenient, but a password manager must not silently fill credentials
 *    across origin boundaries.
 *
 *  - **Native-app fills require a webDomain.** If [ParsedFields.webDomain] is
 *    `null` (no WebView, raw native form) we return `emptyList()` - there's
 *    no safe way to map a package name to a saved URL without explicit user
 *    linking. Path-A defers explicit linking to v1.1.
 *
 *  - **No deduplication, no soft cap.** Every host-matching credential is
 *    returned. A credential visible to the user inside 1Key must always be
 *    reachable from autofill on its matching site; silently dropping rows
 *    because they look like duplicates - or because we hit an arbitrary
 *    `take(N)` - is worse than chip-row clutter. The `limit` parameter
 *    survives for tests and future targeted callers, defaulted to
 *    `Int.MAX_VALUE` so production traffic is uncapped.
 *
 *  - **Graceful overflow.** If returning every match overflows the IPC
 *    binder budget at parcel time, [OneKeyAutofillService.handleFill]
 *    catches the throw and falls back to the search-only response, so a
 *    pathological match count degrades to "Search 1Key" rather than
 *    crashing.
 */
@Singleton
class PackageMatcher @Inject constructor(
    private val credentialRepository: CredentialRepository,
) {

    /**
     * @param limit upper bound on returned matches. Defaults to
     *   [Int.MAX_VALUE] (uncapped). Provided for tests and future callers
     *   that have a specific reason to cap; the autofill service path does
     *   not pass a limit.
     */
    suspend fun findMatches(parsed: ParsedFields, limit: Int = Int.MAX_VALUE): List<Credential> {
        val host = parsed.webDomain ?: return emptyList()
        if (limit <= 0) return emptyList()
        val result = withContext(Dispatchers.Default) { credentialRepository.getAllCredentials() }
        val all = (result as? AppResult.Success)?.data ?: return emptyList()
        // Every host-matching credential surfaces - we do not dedupe by
        // username or collapse import-duplicates. A credential the user can
        // see in 1Key must always be reachable from autofill on its matching
        // site; filtering it out silently because of a similar entry is
        // worse than chip-row clutter. Favourites sort to the top.
        //
        // If the chip row's parcel size overflows the IPC binder budget,
        // OneKeyAutofillService.handleFill catches the throw and falls back
        // to the search-only response, so an over-large match list degrades
        // gracefully rather than crashing.
        return all.asSequence()
            .filter { HostExtractor.hostOf(it.url) == host }
            .sortedByDescending { it.isFavorite }
            .take(limit)
            .toList()
    }
}

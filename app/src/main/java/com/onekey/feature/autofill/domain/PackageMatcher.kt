package com.onekey.feature.autofill.domain

import com.onekey.core.domain.model.AppResult
import com.onekey.core.domain.model.Credential
import com.onekey.core.domain.repository.CredentialRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Matches a [ParsedFields] against the user's vault and returns the top-N
 * credentials by match strength.
 *
 * v1 strategy (deliberately conservative - see `project_autofill.md` and the
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
 *  - **Soft cap on returned matches** prevents `TransactionTooLargeException`
 *    when the framework parcels the `FillResponse` back across IPC.
 */
@Singleton
class PackageMatcher @Inject constructor(
    private val credentialRepository: CredentialRepository,
) {

    /**
     * @param limit upper bound on returned matches. Defaults to [DEFAULT_LIMIT].
     *   Bitwarden caps at 20 partitions; we ship a tighter default both for
     *   the Binder budget and for chip UX.
     */
    suspend fun findMatches(parsed: ParsedFields, limit: Int = DEFAULT_LIMIT): List<Credential> {
        val host = parsed.webDomain ?: return emptyList()
        if (limit <= 0) return emptyList()
        val result = withContext(Dispatchers.Default) { credentialRepository.getAllCredentials() }
        val all = (result as? AppResult.Success)?.data ?: return emptyList()
        return all.asSequence()
            .filter { HostExtractor.hostOf(it.url) == host }
            .take(limit)
            .toList()
    }

    private companion object {
        const val DEFAULT_LIMIT = 10
    }
}

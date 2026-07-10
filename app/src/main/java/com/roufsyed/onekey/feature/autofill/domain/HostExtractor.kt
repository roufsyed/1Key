package com.roufsyed.onekey.feature.autofill.domain

import java.net.URI

/**
 * Single source of truth for host normalisation across the autofill path.
 *
 * Two independent code paths need to ask "what host is this URL?":
 *   1. [PackageMatcher] - extracting the host from a credential's saved `url`
 *      to compare against the form's `webDomain` for exact-host matching.
 *   2. The unlock activity's search snapshot - both to render the credential's
 *      stored host in the picker and to compute the cross-host warning when
 *      the user picks a credential saved under a different domain.
 *
 * Keeping the rule in one place prevents the two paths from drifting (a
 * mismatch would cause silently inconsistent UX or, worse, a credential
 * showing up as a "same-host match" in one place but a "cross-host" warning
 * in the other).
 */
internal object HostExtractor {

    /**
     * Returns the lower-cased host with a leading `www.` stripped, or `null`
     * if the URL is blank, malformed, or has no host component.
     */
    fun hostOf(rawUrl: String?): String? {
        if (rawUrl.isNullOrBlank()) return null
        val cleaned = rawUrl.trim()
        val withScheme = if (cleaned.contains("://")) cleaned else "https://$cleaned"
        return try {
            URI(withScheme).host?.lowercase()?.removePrefix("www.")
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Returns the registrable-domain *name* (the brand label) for a URL or
     * host. E.g. `github.com` / `login.github.com` / `github.co.uk` all return
     * `"github"`. Used by the autofill search surface to pre-populate the
     * query when the user lands without exact-host matches - they almost
     * certainly want to type the brand name and we can save them the keys.
     *
     * NOT used as a security primitive. This is a search-prefill heuristic
     * only; matching credentials to fill targets still goes through the
     * strict `hostOf` exact-host check elsewhere in the autofill path.
     *
     * Algorithm: take the second-to-last dotted label of the host, except
     * when the last two labels match a known multi-part eTLD (`.co.uk` etc),
     * in which case take the third-to-last. Returns `null` if the host has
     * too few labels to identify a brand.
     */
    fun registrableNameOf(rawUrl: String?): String? {
        val host = hostOf(rawUrl) ?: return null
        val labels = host.split(".")
        if (labels.size < 2) return null
        val lastTwo = labels.takeLast(2).joinToString(".")
        return if (lastTwo in MULTI_PART_ETLDS && labels.size >= 3) {
            labels[labels.size - 3]
        } else {
            labels[labels.size - 2]
        }
    }

    /**
     * Small list of multi-part eTLDs that need an extra hop when extracting
     * the brand label. Not exhaustive - the search-prefill use case is
     * forgiving (user can edit the prefilled query). Sourced from the most
     * common second-level country code suffixes.
     */
    private val MULTI_PART_ETLDS = setOf(
        "co.uk", "co.jp", "co.in", "co.kr", "co.za", "co.nz", "co.id", "co.th",
        "com.au", "com.br", "com.cn", "com.mx", "com.tr", "com.sg", "com.tw", "com.hk",
        "ac.uk", "gov.uk", "org.uk", "net.uk", "ne.jp", "or.jp", "ac.jp",
    )
}

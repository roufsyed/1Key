package com.onekey.feature.autofill.domain

import java.net.URI

/**
 * Single source of truth for host normalisation across the autofill path.
 *
 * Two independent code paths need to ask "what host is this URL?":
 *   1. [PackageMatcher] — extracting the host from a credential's saved `url`
 *      to compare against the form's `webDomain` for exact-host matching.
 *   2. The unlock activity's search snapshot — both to render the credential's
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
}

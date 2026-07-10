package com.roufsyed.onekey.feature.importexport.domain

/**
 * Derives a human-friendly title from a URL when the import row's `title`
 * field is blank. Used by the import flow when the
 * "Auto-fill missing titles from URL" toggle is on.
 *
 * Heuristic, in order:
 *   1. Strip protocol (`http://`, `https://`, etc).
 *   2. Cut everything after the first `/`, `?`, or `#` - paths, queries,
 *      and fragments don't belong in the title.
 *   3. If the host part is an IPv4 address (with optional port), return it
 *      as-is including the port (`192.168.10.12:5253`).
 *   4. If the host has no dot (e.g. `localhost`), return host:port verbatim.
 *   5. Otherwise: drop the port, strip a leading `www.` (case-insensitive),
 *      then drop the last label (TLD). Subdomains are preserved
 *      (`mail.google.com` -> `mail.google`, `screener.com` -> `screener`).
 *   6. Capitalise the first character (digits and uppercase pass through).
 *
 * Multi-label TLDs (`.co.uk`, `.com.au`) get a bit ugly under this rule -
 * `bbc.co.uk` becomes `bbc.co` rather than `bbc`. Picking the brand cleanly
 * would need a public-suffix list, which isn't worth the bundle cost here.
 *
 * Returns null when the input is blank, has no recognisable host, or the
 * host has no usable labels - caller falls back to the original blank title.
 */
object UrlTitleExtractor {
    private val PROTOCOL = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")
    private val IPV4 = Regex("""^\d{1,3}(?:\.\d{1,3}){3}$""")

    fun extractTitle(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val s = url.trim()
            .replaceFirst(PROTOCOL, "")
            .substringBefore('/').substringBefore('?').substringBefore('#')
        if (s.isBlank()) return null

        val hostPart = s.substringBefore(':')
        val title = when {
            // IP literal - port matters for disambiguation, keep host:port intact.
            IPV4.matches(hostPart) -> s
            // Single-label host (`localhost`, `myrouter`) - no TLD to strip; keep
            // host:port intact for the same reason as IP.
            !hostPart.contains('.') -> s
            // Domain-shaped host: drop port, strip `www.`, drop TLD.
            else -> {
                val stripped = if (hostPart.startsWith("www.", ignoreCase = true))
                    hostPart.substring(4) else hostPart
                val labels = stripped.split('.').filter { it.isNotBlank() }
                when {
                    labels.isEmpty() -> return null
                    labels.size == 1 -> labels[0]
                    else -> labels.dropLast(1).joinToString(".")
                }
            }
        }
        if (title.isBlank()) return null
        return title.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }
}

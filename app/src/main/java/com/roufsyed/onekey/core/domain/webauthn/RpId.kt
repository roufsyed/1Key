package com.roufsyed.onekey.core.domain.webauthn

/**
 * Relying Party ID validation for requests that reach the provider without a
 * trusted browser vouching for them (native-app callers). A browser on the
 * privileged allowlist enforces RP ID rules itself; for everything else the
 * provider must refuse shapes that could never be a legitimate RP ID.
 *
 * Rules: a lowercase DNS name of at least two labels, no scheme, path, port,
 * query or fragment, no IP literal, no label longer than 63 characters or
 * starting or ending with a hyphen, and not one of the public suffixes in
 * [PUBLIC_SUFFIX_DENY_LIST]. The deny list is a deliberately small embedded
 * subset: 1Key has no network and no full Public Suffix List, so it refuses
 * the suffixes under which arbitrary parties can register names (a passkey
 * scoped to `github.io` would be usable by every `*.github.io` site).
 */
object RpId {

    enum class Problem {
        EMPTY,
        TOO_LONG,
        HAS_SCHEME_OR_PATH,
        HAS_PORT,
        NOT_LOWERCASE,
        IP_LITERAL,
        TOO_FEW_LABELS,
        INVALID_LABEL,
        PUBLIC_SUFFIX,
    }

    /** Returns null when [rpId] is acceptable, otherwise the first problem found. */
    fun validate(rpId: String): Problem? {
        if (rpId.isEmpty()) return Problem.EMPTY
        if (rpId.length > MAX_LENGTH) return Problem.TOO_LONG
        if (rpId.any { it == '/' || it == '?' || it == '#' || it == '@' || it.isWhitespace() }) return Problem.HAS_SCHEME_OR_PATH
        // IPv6 literals ("[::1]", "fe80::1") carry colons too, so classify them before the port check.
        if (rpId.startsWith("[") || rpId.endsWith("]")) return Problem.IP_LITERAL
        if (rpId.contains(':')) {
            return when {
                rpId.contains("://") -> Problem.HAS_SCHEME_OR_PATH
                rpId.count { it == ':' } >= 2 -> Problem.IP_LITERAL
                else -> Problem.HAS_PORT
            }
        }
        if (rpId != rpId.lowercase()) return Problem.NOT_LOWERCASE
        val labels = rpId.split('.')
        if (labels.size < 2) return Problem.TOO_FEW_LABELS
        if (labels.all { label -> label.isNotEmpty() && label.all { it.isDigit() } }) return Problem.IP_LITERAL
        for (label in labels) {
            if (label.isEmpty() || label.length > MAX_LABEL_LENGTH) return Problem.INVALID_LABEL
            if (label.first() == '-' || label.last() == '-') return Problem.INVALID_LABEL
            if (!label.all { it in 'a'..'z' || it in '0'..'9' || it == '-' }) return Problem.INVALID_LABEL
        }
        if (rpId in PUBLIC_SUFFIX_DENY_LIST) return Problem.PUBLIC_SUFFIX
        return null
    }

    fun isValid(rpId: String): Boolean = validate(rpId) == null

    private const val MAX_LENGTH = 253
    private const val MAX_LABEL_LENGTH = 63

    /**
     * Multi-label public suffixes an RP ID must never equal. Single-label
     * names (`com`, `org`, ...) are already rejected by the two-label rule.
     */
    val PUBLIC_SUFFIX_DENY_LIST: Set<String> = setOf(
        // Country second-level suffixes
        "co.uk", "org.uk", "ac.uk", "gov.uk", "me.uk", "ltd.uk", "plc.uk",
        "co.in", "net.in", "org.in", "firm.in", "gen.in", "ind.in",
        "co.jp", "ne.jp", "or.jp", "ac.jp", "go.jp",
        "com.au", "net.au", "org.au", "edu.au", "gov.au",
        "com.br", "net.br", "org.br",
        "co.nz", "net.nz", "org.nz",
        "co.za", "org.za", "net.za",
        "com.mx", "com.ar", "com.tr", "com.cn", "com.sg", "com.hk", "com.tw",
        "co.kr", "or.kr", "co.id", "co.il", "com.my", "com.ph", "com.pk",
        // Hosting platforms that hand out names to anyone
        "github.io", "gitlab.io", "bitbucket.io", "herokuapp.com", "vercel.app",
        "netlify.app", "pages.dev", "web.app", "firebaseapp.com", "appspot.com",
        "azurewebsites.net", "cloudfront.net", "amazonaws.com", "s3.amazonaws.com",
        "blogspot.com", "wordpress.com", "tumblr.com", "glitch.me", "repl.co",
        "surge.sh", "fly.dev", "onrender.com", "railway.app", "workers.dev",
        "ngrok.io", "ngrok.app", "ngrok-free.app", "trycloudflare.com",
        "nip.io", "sslip.io", "xip.io", "duckdns.org", "no-ip.org", "dyndns.org",
    )
}

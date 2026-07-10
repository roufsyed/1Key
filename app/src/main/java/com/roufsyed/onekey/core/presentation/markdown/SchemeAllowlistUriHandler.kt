package com.roufsyed.onekey.core.presentation.markdown

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.UriHandler
import java.util.Locale

/**
 * A user-clicked markdown link that has passed the scheme allowlist and is
 * being handed off to the call site for confirmation. Carries enough context
 * for [LinkConfirmDialog] (Phase 6) to render both the visible label and the
 * resolved destination separately - the two must always be displayed on
 * independent rows so the user can spot label/destination divergence.
 *
 * @param rawUrl the URL string exactly as written in the markdown source. Used
 *               as the verbatim `Destination` text in the dialog. Untrimmed
 *               leading whitespace was already stripped by the handler before
 *               this object was constructed; control bytes are rejected
 *               upstream so this field is safe to render directly.
 * @param parsedUri the [Uri.parse] result for [rawUrl]; the dialog reads
 *                  [Uri.getHost] from this to feed [HomographDetector].
 * @param label the visible link text from a markdown `[label](url)` link, or
 *              `null` for autolinks (`<https://example.com>` or bare URLs)
 *              where the label is identical to the URL. The dialog displays a
 *              separate `Label` row only when this is non-null.
 */
data class LinkRequest(
    val rawUrl: String,
    val parsedUri: Uri,
    val label: String?,
)

/**
 * Compose [UriHandler] that funnels every link click through a confirmation
 * callback and refuses any scheme outside `{http, https, mailto}`.
 *
 * Responsibility split (intentional):
 *  - This class classifies the scheme and either delegates to
 *    [onLinkTapped] (allowed) or [onBlocked] (rejected). It is stateless and
 *    does NOT know about Compose state holders, snackbar hosts, or dialogs.
 *  - The call site (Phase 6 notes screen) keeps a `mutableStateOf<LinkRequest?>`
 *    and feeds it to [LinkConfirmDialog] when [onLinkTapped] fires; the same
 *    call site shows a snackbar when [onBlocked] fires.
 *
 * Allowed schemes (lowercased, ASCII-only normalisation via [Locale.ROOT]):
 *  - `http`
 *  - `https`
 *  - `mailto`
 *
 * Everything else - including `javascript`, `data`, `file`, `content`,
 * `intent`, `vbscript`, `tel`, `sms`, `ftp`, `ws`, `wss`, and any unknown
 * scheme - is routed to [onBlocked] with a short user-visible string.
 *
 * URL-encoded scheme prefixes like `javascript%3Aalert(1)` are also blocked:
 * [Uri.parse] treats the percent-escaped colon as part of the path, so the
 * parsed [Uri.getScheme] is `null` and the allowlist short-circuits the URL
 * into the blocked branch. (Robolectric test
 * `urlEncodedJavascriptPrefix_isBlocked` pins this behaviour.)
 *
 * Whitespace and NUL handling:
 *  - Empty and whitespace-only inputs are silently ignored - no callback fires.
 *  - Leading/trailing whitespace is trimmed before [Uri.parse].
 *  - Any [Char] with code <= 0x1F (control bytes, including NUL) anywhere in
 *    the trimmed input causes the URL to be blocked - these bytes can confuse
 *    downstream parsers and there is no legitimate reason for them in a
 *    markdown link.
 *  - Inputs whose first non-whitespace character is `%` are blocked outright
 *    on the assumption that no legitimate URL starts with a percent-escape
 *    (the scheme itself cannot be encoded).
 *
 * NOT a `@Composable`: safe to retain across recomposition. Construct via
 * [rememberSchemeAllowlistUriHandler] inside the host composable so the
 * instance survives recomposition without leaking across navigation.
 */
class SchemeAllowlistUriHandler(
    private val onLinkTapped: (LinkRequest) -> Unit,
    private val onBlocked: (String) -> Unit,
) : UriHandler {

    /**
     * Compose calls this when the user taps a markdown link. The full
     * classification pipeline is:
     *
     *  1. Trim leading and trailing whitespace; return early on empty.
     *  2. Reject inputs containing any control byte (code <= 0x1F).
     *  3. Reject inputs whose first character is `%` (percent-prefix bypass
     *     attempt).
     *  4. [Uri.parse] the trimmed input; reject when the parsed scheme is
     *     null (handles both URL-encoded scheme prefixes and malformed inputs).
     *  5. Lowercase the scheme via [Locale.ROOT]; route through [ALLOWED_SCHEMES].
     *  6. On allowed: invoke [onLinkTapped] with a [LinkRequest] carrying the
     *     raw URL (post-trim) and the parsed [Uri]. The label is `null` because
     *     this entry point does not have access to the markdown source; if the
     *     call site has a label it should bypass this handler entirely and
     *     stage the request itself.
     *  7. On blocked: invoke [onBlocked] with a `Blocked unsafe link: <scheme>`
     *     string suitable for surfacing as a snackbar.
     */
    override fun openUri(uri: String) {
        val trimmed = uri.trim()
        if (trimmed.isEmpty()) return

        // Control-byte / NUL injection - no callback at all; this is treated
        // as a malformed input rather than a blocked scheme.
        if (trimmed.any { it.code <= 0x1F }) {
            onBlocked("Blocked unsafe link: malformed URL")
            return
        }

        // Percent-prefix bypass attempt - reject before parsing.
        if (trimmed.startsWith("%")) {
            onBlocked("Blocked unsafe link: malformed URL")
            return
        }

        // Normalize bare-domain inputs (e.g. `google.com`, hand-typed by users
        // in the markdown source) to `https://google.com` so the click-time
        // path matches the dialog-time path. Without this symmetric normalize,
        // a user who manually types `[label](google.com)` rather than going
        // through the insert dialog would see "Blocked unsafe link: unknown
        // scheme" on click - confusing because the link looks perfectly valid.
        // Disallowed schemes already present (e.g. `javascript:`) are NOT
        // normalised here; normalizeInsertUrl only touches strings with no
        // scheme prefix, so the allow-list still blocks them.
        val normalized = normalizeInsertUrl(trimmed)

        val parsed = runCatching { Uri.parse(normalized) }.getOrNull()
        val rawScheme = parsed?.scheme
        if (parsed == null || rawScheme.isNullOrEmpty()) {
            // Either parse failed entirely or there is no scheme. Includes the
            // `javascript%3A` case where Uri.parse returns scheme=null because
            // the percent-encoded colon is not recognised as a scheme delimiter.
            onBlocked("Blocked unsafe link: unknown scheme")
            return
        }

        val scheme = rawScheme.lowercase(Locale.ROOT)
        if (scheme in ALLOWED_SCHEMES) {
            // rawUrl carries the normalised destination so [LinkConfirmDialog]
            // shows the user the actual URL that will open (handy when they
            // typed a bare domain). parsedUri.host is reliable for the
            // homograph check because normalize only ever prepends `https://`
            // when no scheme was present.
            onLinkTapped(LinkRequest(rawUrl = normalized, parsedUri = parsed, label = null))
        } else {
            onBlocked("Blocked unsafe link: $scheme")
        }
    }

    internal companion object {
        /**
         * The only schemes that bypass [onBlocked]. Lowercased, ASCII-only.
         * Kept tight on purpose: every additional scheme expands the trust
         * surface and there is no current product requirement for anything
         * beyond plain web links and email.
         */
        val ALLOWED_SCHEMES: Set<String> = setOf("http", "https", "mailto")
    }
}

/**
 * Factory composable. Returns a [SchemeAllowlistUriHandler] that is remembered
 * across recomposition; both lambdas are captured by-reference, so the host
 * composable should pass stable references (e.g. lambdas that close over a
 * `SnackbarHostState` and a `mutableStateOf<LinkRequest?>` setter).
 *
 * Intended use:
 * ```
 * val pending = remember { mutableStateOf<LinkRequest?>(null) }
 * val handler = rememberSchemeAllowlistUriHandler(
 *     onLinkTapped = { pending.value = it },
 *     onBlocked = { msg -> scope.launch { snackbarHostState.showSnackbar(msg) } },
 * )
 * CompositionLocalProvider(LocalUriHandler provides handler) { ... }
 * pending.value?.let { LinkConfirmDialog(it) { pending.value = null } }
 * ```
 */
@Composable
fun rememberSchemeAllowlistUriHandler(
    onLinkTapped: (LinkRequest) -> Unit,
    onBlocked: (String) -> Unit,
): SchemeAllowlistUriHandler = remember(onLinkTapped, onBlocked) {
    SchemeAllowlistUriHandler(onLinkTapped = onLinkTapped, onBlocked = onBlocked)
}

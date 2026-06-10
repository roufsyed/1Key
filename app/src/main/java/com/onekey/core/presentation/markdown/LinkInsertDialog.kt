package com.onekey.core.presentation.markdown

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import com.onekey.core.presentation.lockaware.LockAwareDialog
import com.onekey.core.presentation.lockaware.LockAwareOutlinedTextField
import java.util.Locale

/**
 * Dialog that prompts the user for a URL (and optionally a label) when the
 * format bar's link button fires. The bar passes the current selection text
 * as [initialLabel]; on confirm we return the (label, url) pair to the caller
 * which inserts `[label](url)` at the captured selection range.
 *
 * UI shape:
 *  - Title: "Insert link".
 *  - First row: an editable "Link text" [LockAwareOutlinedTextField] pre-
 *    populated with the selection text. The user may edit the label here. If
 *    the label is left empty at confirm time, the caller falls back to using
 *    the URL itself as the visible label so the resulting markdown does not
 *    collapse to a stray `[]`.
 *  - Second row: a URL [LockAwareOutlinedTextField]. The IME hints
 *    `KeyboardType.Uri`, autocorrect off, no auto-capitalisation. The field
 *    autofocuses on first composition so the user can start typing
 *    immediately. Error state and inline supporting text surface validation
 *    failures.
 *  - Confirm "Insert" button: validates the URL once more via
 *    [validateInsertUrl] (defence-in-depth against the dialog being opened
 *    while the allowlist constant changed). On error the dialog stays open
 *    with the error rendered in `supportingText`; on success [onConfirm] is
 *    called with the (label, trimmed-url) pair.
 *  - Dismiss "Cancel" button: closes via [onDismiss] with no side effect.
 *
 * Why [LockAwareDialog] over raw [androidx.compose.material3.AlertDialog]:
 * the project's lint rule `UnsafeUnlockableSurface` blocks raw AlertDialog
 * outside the `lockaware` package because dialogs that don't wire
 * `lockAware()` into their Surface fail to keep the inactivity timer alive
 * for taps inside the dialog. LockAwareDialog is the project-wide
 * AlertDialog-styled wrapper that handles this AND IME insets.
 *
 * Validation reuses the live-link allowlist
 * [SchemeAllowlistUriHandler.ALLOWED_SCHEMES] so insert-time and click-time
 * validation share one source of truth. A user cannot insert a link with a
 * scheme that would later be blocked at click time.
 */
@Composable
fun LinkInsertDialog(
    initialLabel: String,
    onConfirm: (label: String, url: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var label by remember { mutableStateOf(initialLabel) }
    var url by remember { mutableStateOf("") }
    var validation: LinkValidationError? by remember { mutableStateOf(null) }

    val urlFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        // Autofocus the URL field on first composition so the keyboard rises
        // immediately and the user can start typing. runCatching guards against
        // the rare focus-request-before-attach race when the dialog is paired
        // with a not-yet-composed Surface.
        runCatching { urlFocus.requestFocus() }
    }

    LockAwareDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Insert link",
                style = MaterialTheme.typography.headlineSmall,
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                LockAwareOutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Link text (optional)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        autoCorrectEnabled = false,
                        capitalization = KeyboardCapitalization.None,
                    ),
                )
                Spacer(Modifier.size(2.dp))
                LockAwareOutlinedTextField(
                    value = url,
                    onValueChange = {
                        url = it
                        // Clear inline error as soon as the user resumes typing
                        // so the red helper text doesn't linger after they've
                        // started to fix it.
                        validation = null
                    },
                    label = { Text("URL") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(urlFocus),
                    isError = validation != null,
                    supportingText = validation?.let { v ->
                        {
                            Text(
                                text = errorMessageFor(v),
                                color = MaterialTheme.colorScheme.error,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                    keyboardOptions = KeyboardOptions(
                        autoCorrectEnabled = false,
                        capitalization = KeyboardCapitalization.None,
                        keyboardType = KeyboardType.Uri,
                    ),
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    // Normalize first so the user can type bare domains like
                    // `google.com` and have https:// auto-prepended, then
                    // validate the normalized form. This means MissingScheme
                    // is effectively unreachable on the happy path - it can
                    // still fire for inputs like `://x` that normalize to a
                    // still-malformed string and re-trip the parser.
                    val normalized = normalizeInsertUrl(url)
                    val err = validateInsertUrl(normalized)
                    if (err != null) {
                        validation = err
                    } else {
                        onConfirm(label, normalized)
                    }
                },
                enabled = url.isNotBlank(),
            ) { Text("Insert") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
        properties = DialogProperties(),
    )
}

/**
 * Validation outcomes for a candidate insert URL. Each variant maps to a
 * single short helper-text string surfaced under the URL field.
 */
internal sealed interface LinkValidationError {
    /** Empty after trim. Cannot happen if the confirm button is `enabled = url.isNotBlank()` but kept for completeness. */
    object EmptyUrl : LinkValidationError

    /** Contains a control byte (code <= 0x1F). [SchemeAllowlistUriHandler] would reject this at click time too. */
    object ControlByte : LinkValidationError

    /** Starts with a percent-escape - no legitimate URL can encode its own scheme. */
    object PercentPrefix : LinkValidationError

    /** [Uri.parse] threw or otherwise failed. */
    object MalformedUrl : LinkValidationError

    /**
     * Parsed but the scheme was empty (the user typed something like
     * `example.com` without `http(s)://`). We hint at the fix in the message.
     */
    object MissingScheme : LinkValidationError

    /** Parsed with a scheme but not one of [SchemeAllowlistUriHandler.ALLOWED_SCHEMES]. */
    data class DisallowedScheme(val scheme: String) : LinkValidationError
}

/**
 * Trims the input and, when it looks like a bare domain that the user just
 * forgot to scheme, prepends `https://`. Returns the input unchanged in
 * every other case so the validator (and the click-time handler that shares
 * this helper) can still flag malformed or disallowed URLs.
 *
 * The "looks like a bare domain" rule, in order:
 *  1. If there is already a scheme prefix matching [SCHEME_PREFIX] - e.g.
 *     `http://`, `https://`, `mailto:`, `javascript:`, `tel:` - return as
 *     is. The validator keeps responsibility for allow-listing.
 *  2. If the input contains `%`, return as is. Percent-encoding inside the
 *     pre-host portion is a known smuggling pattern (`javascript%3Aalert`)
 *     and the user is unlikely to be typing percent escapes by hand.
 *  3. If the input contains whitespace inside (after trim), return as is.
 *     Real bare-domain URLs do not contain inner whitespace.
 *  4. If the first character is not a letter or digit, return as is. Rules
 *     out relative paths (`/x`, `./x`) and other non-host inputs.
 *  5. Otherwise prepend `https://`.
 *
 * The scheme regex is intentionally strict: it disallows `.` in scheme
 * names (RFC 3986 grammar allows it, but no real-world scheme uses it),
 * so inputs like `google.com:8080` (bare host with port) DO NOT match the
 * scheme branch and the bare-domain branch picks them up.
 */
internal fun normalizeInsertUrl(input: String): String {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) return trimmed
    if (SCHEME_PREFIX.containsMatchIn(trimmed)) return trimmed
    if ('%' in trimmed) return trimmed
    if (trimmed.any { it.isWhitespace() }) return trimmed
    val first = trimmed[0]
    val isAlphanumeric = first in 'a'..'z' || first in 'A'..'Z' || first in '0'..'9'
    if (!isAlphanumeric) return trimmed
    return "https://$trimmed"
}

// Strict scheme regex: ALPHA *( ALPHA / DIGIT / "+" / "-" ) ":". RFC 3986
// also allows "." in scheme names but no real-world scheme uses it; rejecting
// dot here lets `google.com:8080` (bare host with port) fall through to the
// bare-domain branch rather than being misread as `scheme=google.com`.
private val SCHEME_PREFIX = Regex("^[a-zA-Z][a-zA-Z0-9+\\-]*:")

/**
 * Mirrors the validation steps of [SchemeAllowlistUriHandler.openUri] so a URL
 * that passes here will also be accepted at click time. Pure function, no
 * Compose / Android lifecycle dependency beyond [Uri.parse] which is a thin
 * static call.
 *
 * Returns `null` on success, or the specific [LinkValidationError] otherwise.
 */
internal fun validateInsertUrl(input: String): LinkValidationError? {
    if (input.isEmpty()) return LinkValidationError.EmptyUrl
    if (input.any { it.code <= 0x1F }) return LinkValidationError.ControlByte
    if (input.startsWith("%")) return LinkValidationError.PercentPrefix
    val parsed = runCatching { Uri.parse(input) }.getOrNull()
        ?: return LinkValidationError.MalformedUrl
    val scheme = parsed.scheme?.lowercase(Locale.ROOT)
    if (scheme.isNullOrEmpty()) return LinkValidationError.MissingScheme
    if (scheme !in SchemeAllowlistUriHandler.ALLOWED_SCHEMES) {
        return LinkValidationError.DisallowedScheme(scheme)
    }
    return null
}

/** Human-readable copy for the URL field's inline error helper text. */
private fun errorMessageFor(error: LinkValidationError): String = when (error) {
    LinkValidationError.EmptyUrl -> "Enter a URL"
    LinkValidationError.ControlByte -> "URL contains invalid characters"
    LinkValidationError.PercentPrefix -> "URL cannot start with %"
    LinkValidationError.MalformedUrl -> "URL is not valid"
    LinkValidationError.MissingScheme -> "Add http:// or https:// before the address"
    is LinkValidationError.DisallowedScheme ->
        "Only http, https, and mailto links are allowed (got ${error.scheme})"
}

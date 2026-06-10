package com.onekey.core.presentation.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.onekey.core.presentation.lockaware.LockAwareDialog

/**
 * Confirmation dialog for an allowed-scheme markdown link click staged by
 * [SchemeAllowlistUriHandler]. Always renders when called - the caller is
 * expected to gate on `pending != null` and supply that request.
 *
 * Layout (top to bottom):
 *  1. Title "Open link?".
 *  2. Optional homograph warning row, rendered only when
 *     [HomographDetector.detect] returns non-null for [LinkRequest.parsedUri]'s
 *     host. The warning sits in a [Surface] tinted with
 *     [androidx.compose.material3.ColorScheme.tertiaryContainer]; the leading
 *     icon is [Icons.Default.Warning].
 *  3. "Link text" caption + visible label row. If the click came through the
 *     [SchemeAllowlistUriHandler] entry point (autolink / bare URL with no
 *     markdown label) [LinkRequest.label] is `null` and we fall back to
 *     [LinkRequest.rawUrl] so the row is always rendered - the UI shape stays
 *     uniform regardless of whether the link was an autolink.
 *  4. "Destination" caption + URL row. The URL is in a monospaced font, sits
 *     inside [androidx.compose.foundation.horizontalScroll], uses
 *     `softWrap = false`, and has `overflow = TextOverflow.Clip`. There is no
 *     ellipsis - the user must be able to read the entire URL by scrolling.
 *  5. Confirm button "Open in browser" and dismiss button "Cancel". Cancel
 *     gets default focus via a [FocusRequester] + [LaunchedEffect] - the safe
 *     default for confirmation dialogs is the leaving action.
 *
 * Dispatch: on confirm we invoke [onConfirm] and the caller decides what to do
 * with the URL (Phase 7 wiring fires `Intent.ACTION_VIEW` wrapped in
 * `runCatching` to swallow `ActivityNotFoundException`). This dialog itself
 * does not touch [android.content.Context] or [android.content.Intent] so it
 * can be unit-tested without an Android stub for those types.
 *
 * Dialog properties: default-constructed [DialogProperties]. Crucially, the
 * default [androidx.compose.ui.window.SecureFlagPolicy.Inherit] means the
 * `FLAG_SECURE` bit propagates from the host activity (notes screen) without
 * being overridden here. Overriding to `SecureExternal` would force the dialog
 * into screen-capture-blocking mode regardless of host policy; overriding to
 * `SecureOff` would leak a sensitive dialog into screen recordings if the
 * notes activity sets FLAG_SECURE. Inheriting is the safe middle.
 *
 * Note on the AlertDialog vs LockAwareDialog choice: this module's lint rule
 * `UnsafeUnlockableSurface` blocks raw `androidx.compose.material3.AlertDialog`
 * outside the `lockaware` package. [LockAwareDialog] is the project's M3
 * AlertDialog-styled drop-in - same `title` / `text` / `confirmButton` /
 * `dismissButton` / `properties` surface, plus it wires `lockAware()` into the
 * Surface so taps and key events inside the dialog keep the inactivity timer
 * alive. The visible result is identical to M3's AlertDialog.
 *
 * @param request the staged link - raw markdown URL, parsed [android.net.Uri],
 *   and optional visible label.
 * @param onConfirm invoked when the user taps "Open in browser". The caller is
 *   responsible for any side effect (launching an intent) AND for clearing the
 *   pending request - this dialog does NOT call [onDismiss] from inside
 *   [onConfirm] because doing so would force a particular ordering on the
 *   caller (some callers may want to keep the dialog mounted while the intent
 *   resolves). The standard caller pattern is
 *   `onConfirm = { launch(uri); pending = null }`.
 * @param onDismiss invoked when the user taps "Cancel", taps outside, or
 *   presses Back. The caller should clear the pending request from this.
 */
@Composable
fun LinkConfirmDialog(
    request: LinkRequest,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val warning = remember(request.parsedUri) {
        HomographDetector.detect(request.parsedUri.host)
    }
    val cancelFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        // Default focus on Cancel - confirmation dialogs default to the safe
        // leaving action. Must run after first composition so the
        // FocusRequester has a node to attach to.
        runCatching { cancelFocus.requestFocus() }
    }

    // Fallback so the "Link text" row always has something to display, even
    // for autolink requests where the markdown source had no separate label.
    // Keeping the row present regardless of label nullability matches the
    // brief's UI shape requirement ("still show both rows to keep the UI
    // uniform").
    val labelText = request.label ?: request.rawUrl

    LockAwareDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Open link?",
                style = MaterialTheme.typography.headlineSmall,
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (warning != null) {
                    HomographWarningRow(warning = warning)
                }

                LinkLabelRow(label = labelText)

                DestinationRow(url = request.rawUrl)
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Open in browser")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.focusRequester(cancelFocus),
            ) {
                Text("Cancel")
            }
        },
        // Default DialogProperties. The default for `securePolicy` is
        // SecureFlagPolicy.Inherit which is what we want: FLAG_SECURE on the
        // host activity propagates automatically into this dialog's window
        // without being overridden either way. See class KDoc for why we do
        // NOT pin this explicitly.
        properties = DialogProperties(),
    )
}

/**
 * Caption + content row used for both "Link text" and "Destination". The
 * caption uses [androidx.compose.material3.ColorScheme.onSurfaceVariant] in
 * [androidx.compose.material3.Typography.labelSmall]; content styling is the
 * caller's job because the two rows differ (label uses bodyMedium, destination
 * uses monospaced + horizontalScroll).
 */
@Composable
private fun CaptionedRow(
    caption: String,
    content: @Composable () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = caption,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(2.dp))
        content()
    }
}

@Composable
private fun LinkLabelRow(label: String) {
    CaptionedRow(caption = "Link text") {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * Destination URL row. Monospaced (clearer character disambiguation for
 * homograph review), `softWrap = false`, no ellipsis, wrapped in
 * `horizontalScroll(rememberScrollState())` so long URLs are inspectable in
 * full by scrolling rather than truncated.
 */
@Composable
private fun DestinationRow(url: String) {
    val scroll = rememberScrollState()
    CaptionedRow(caption = "Destination") {
        Text(
            text = url,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            fontFamily = FontFamily.Monospace,
            softWrap = false,
            // Clip (not Ellipsis) - we want the leading characters visible and
            // the rest reachable by scroll, NOT replaced with "...". The
            // assertion in LinkConfirmDialogInstrumentedTest pins this.
            overflow = TextOverflow.Clip,
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(scroll)
                .semantics { contentDescription = "Destination URL: $url" },
        )
    }
}

/**
 * Tertiary-container Surface that calls attention to a suspicious host
 * classification from [HomographDetector]. Copy is intentionally short and
 * reason-agnostic so the warning fits on one line at typical widths; the
 * specific reason can be inferred from the destination row itself.
 */
@Composable
private fun HomographWarningRow(warning: HomographWarning) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.tertiaryContainer)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = warningCopy(warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }
    }
}

/**
 * Reason-specific copy for the warning row. All three strings start with the
 * same phrasing ("This domain looks similar...") so a screen-recorder reader
 * gets a consistent cue, but each adds a short tail clarifying which kind of
 * lookalike was detected.
 */
private fun warningCopy(warning: HomographWarning): String = when (warning) {
    HomographWarning.Punycode ->
        "This domain looks similar to a well-known site. It is in punycode form and may decode to non-ASCII characters."
    HomographWarning.MixedScript ->
        "This domain looks similar to a well-known site. It contains non-ASCII characters that can imitate Latin letters."
    is HomographWarning.DigitLetter ->
        "This domain looks similar to a well-known site. It uses digits that can be mistaken for letters."
}

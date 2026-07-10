package com.roufsyed.onekey.core.presentation.markdown

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.roufsyed.onekey.core.presentation.lockaware.LockAwareOutlinedTextField

/**
 * Live-preview markdown editor wrapper for the credential edit screen's notes field
 * (Flavour 2 of the Phase 8 design).
 *
 * Wraps the TextFieldValue overload of [LockAwareOutlinedTextField] with a content-dependent
 * [MarkdownEditorTransformation] so balanced markdown delimiters render Notion-style DIMMED
 * (visible at a quieter colour) while the user is typing. The transformation renders every source
 * byte verbatim and layers spans for semantics; source length always equals transformed length and
 * the returned [androidx.compose.ui.text.input.OffsetMapping] is
 * [androidx.compose.ui.text.input.OffsetMapping.Identity]. Cursor mechanics, copy/cut, and arrow
 * keys therefore use the platform default behaviour - one keypress moves one source character,
 * selection copies the raw source bytes, and backspace deletes a single character at a time.
 *
 * Behaviour:
 *  - The 64-KiB UTF-8 source cap is enforced at the [onValueChange] boundary BEFORE the new value
 *    is forwarded upstream. Over-cap values are rejected wholesale (the upstream `onValueChange`
 *    is not called, so the underlying notes state stays at its previous value) and
 *    [onSizeCapExceeded] is invoked once per rejection so the host can surface a Snackbar.
 *  - The transformation itself is remembered keyed on (text, styles) so a typed keystroke runs the
 *    parse exactly once. Style equality is structural via [MarkdownEditorStyles] so re-composition
 *    without a text change does not invalidate the transformation reference.
 *  - [KeyboardOptions.autoCorrectEnabled] is `false` because autocorrect would happily mangle
 *    markdown delimiters (`**bold**` -> `**bold.**`). [KeyboardOptions.capitalization] is
 *    `Sentences` so prose notes still capitalize after a period - this is the same setting the
 *    plain notes field used implicitly via its default `Sentences` capitalization.
 *  - Plain-source fallback for the rest of the session (after a parse exception or once the cap is
 *    exceeded) is the CALLER'S responsibility: the parent screen decides between
 *    [MarkdownEditorField] and a plain [LockAwareOutlinedTextField] based on
 *    `isNotesMarkdownEnabled && credential.id !in plainSourceCredentialIds`. This composable
 *    handles the WITHIN-render fallback by relying on [MarkdownEditorTransformation.filter] which
 *    short-circuits to [androidx.compose.ui.text.input.OffsetMapping.Identity] on parse failure /
 *    over-cap; the visible text is then the raw source unchanged.
 *  - The trailing-icon slot is forwarded straight through so existing OCR-scanner buttons attach
 *    in the same visual position.
 *
 * Security posture: same as [MarkdownNotesView] - no I/O, no image loader, no link follow-through.
 * Compose Text never interprets tag bytes; in edit mode the IMAGE source bytes are rendered
 * verbatim with dim styling so a `![evil](javascript:...)` cannot turn into a clickable link.
 *
 * Thread-safety: composable; runs on the main thread only.
 */
@Composable
fun MarkdownEditorField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    label: @Composable (() -> Unit)? = null,
    supportingText: @Composable (() -> Unit)? = null,
    isError: Boolean = false,
    enabled: Boolean = true,
    minLines: Int = 3,
    maxLines: Int = Int.MAX_VALUE,
    trailingIcon: @Composable (() -> Unit)? = null,
    onSizeCapExceeded: (() -> Unit)? = null,
) {
    val styles = rememberMarkdownEditorStyles()
    // remember keyed on (text, styles): a fresh transformation per keystroke parse, stable identity
    // when only unrelated state recomposes. Keeping `value.text` in the key (rather than `value`)
    // avoids invalidating the cache on a cursor-only change.
    val transformation = remember(value.text, styles) { MarkdownEditorTransformation(styles) }

    // Shared interaction source so the format bar's visibility can read focus
    // state from the same source the OutlinedTextField writes into. Without
    // this, the bar would need its own focus listener and we'd risk visual
    // drift between "field reports focused" and "bar reports visible".
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    // Bar visibility: only while the field has focus, because the bar is now
    // docked to the top of the IME (see Popup below) and shows next to the
    // keyboard - rendering it without the keyboard up would float it at the
    // very bottom of the screen, which looks broken.
    val showBar = enabled && isFocused

    // Captures the selection text at link-button press. When non-null, the
    // [LinkInsertDialog] is rendered; on confirm we apply the markdown insert
    // through the same onValueChange path the bar uses so the size-cap
    // bookkeeping applies identically.
    var linkPrompt by remember { mutableStateOf<LinkPromptRequest?>(null) }

    LockAwareOutlinedTextField(
        value = value,
        onValueChange = { newValue ->
            if (exceedsSizeCap(newValue.text)) {
                onSizeCapExceeded?.invoke()
                return@LockAwareOutlinedTextField
            }
            onValueChange(newValue)
        },
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        label = label,
        supportingText = supportingText,
        isError = isError,
        trailingIcon = trailingIcon,
        visualTransformation = transformation,
        keyboardOptions = KeyboardOptions(
            autoCorrectEnabled = false,
            capitalization = KeyboardCapitalization.Sentences,
        ),
        textStyle = MaterialTheme.typography.bodyLarge,
        minLines = minLines,
        maxLines = maxLines,
        interactionSource = interactionSource,
    )

    // Docked toolbar: a Popup positions the bar in WINDOW coordinates so it
    // stays glued to the bottom of the screen even when the surrounding column
    // scrolls. The position provider subtracts the IME or nav-bar bottom inset
    // (whichever is larger) from windowSize.height so the bar sits flush on
    // top of the soft keyboard. We read insets in the activity composition
    // (where they are available) and pass the pixel count into the position
    // provider, sidestepping the question of whether window insets propagate
    // into popup subwindows in this Compose version.
    //
    // focusable=false prevents the popup from stealing focus from the
    // underlying text field on every tap; dismissOnBackPress=false leaves the
    // back key to the IME's normal close-keyboard behaviour (which also
    // unfocuses the field, hiding the bar via showBar=false).
    if (showBar) {
        val density = LocalDensity.current
        val imeBottomPx = WindowInsets.ime.getBottom(density)
        val navBottomPx = WindowInsets.navigationBars.getBottom(density)
        val bottomInsetPx = maxOf(imeBottomPx, navBottomPx)
        val positionProvider = remember(bottomInsetPx) {
            BottomAlignedPopupPositionProvider(bottomInsetPx)
        }
        Popup(
            popupPositionProvider = positionProvider,
            properties = PopupProperties(
                focusable = false,
                dismissOnBackPress = false,
                dismissOnClickOutside = false,
                clippingEnabled = false,
            ),
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 3.dp,
                shadowElevation = 4.dp,
            ) {
                MarkdownFormatBar(
                    value = value,
                    onValueChange = { proposed ->
                        // Reject the wholesale insert if it would push past
                        // the size cap. Identical guard to the field's own
                        // onValueChange so a bar tap that would exceed 64 KiB
                        // becomes a no-op rather than silently dropping the
                        // markers.
                        if (exceedsSizeCap(proposed.text)) {
                            onSizeCapExceeded?.invoke()
                            return@MarkdownFormatBar
                        }
                        onValueChange(proposed)
                    },
                    visible = true,
                    modifier = Modifier.fillMaxWidth(),
                    onLinkInsertRequested = { selectionText ->
                        // Snapshot the selection at the moment the link
                        // button was pressed; the user may move the cursor
                        // or type while the dialog is open and we must
                        // still insert at the originally selected range.
                        linkPrompt = LinkPromptRequest(
                            label = selectionText,
                            range = value.selection,
                        )
                    },
                )
            }
        }
    }

    linkPrompt?.let { req ->
        LinkInsertDialog(
            initialLabel = req.label,
            onConfirm = { typedLabel, url ->
                // Visible label fallback: if the user wiped the label field
                // empty (or the selection was empty and they didn't type a
                // label), use the URL itself so the inserted markdown does
                // not collapse to a stray `[]`.
                val effectiveLabel = typedLabel.ifBlank { url }
                val inserted = "[$effectiveLabel]($url)"
                val baseText = value.text
                val insertStart = req.range.min.coerceAtMost(baseText.length)
                val insertEnd = req.range.max.coerceAtMost(baseText.length)
                val newText = buildString(baseText.length + inserted.length) {
                    append(baseText, 0, insertStart)
                    append(inserted)
                    append(baseText, insertEnd, baseText.length)
                }
                if (exceedsSizeCap(newText)) {
                    onSizeCapExceeded?.invoke()
                    linkPrompt = null
                    return@LinkInsertDialog
                }
                val newValue = value.copy(
                    text = newText,
                    // Place the cursor at the end of the inserted span so the
                    // user can keep typing after the link.
                    selection = TextRange(insertStart + inserted.length),
                )
                onValueChange(newValue)
                linkPrompt = null
            },
            onDismiss = { linkPrompt = null },
        )
    }
}

/**
 * Internal record captured at the moment the format bar's link button is
 * tapped. We snapshot both the selection text (so the dialog can pre-populate
 * its label field) and the selection [TextRange] so that the insert always
 * lands at the originally selected position even if the user's cursor moved
 * while the dialog was open.
 */
private data class LinkPromptRequest(
    val label: String,
    val range: TextRange,
)

/**
 * Returns true when [text] would exceed the editor's UTF-8 source cap. Cheap: the JVM
 * `String#toByteArray(UTF_8)` runs in O(N) over the char buffer with no allocation surprises
 * for our typical < 64 KiB inputs.
 */
private fun exceedsSizeCap(text: String): Boolean =
    text.toByteArray(Charsets.UTF_8).size > MARKDOWN_EDITOR_MAX_BYTES

/**
 * Positions a [Popup] flush with the bottom of the window, lifted by
 * [bottomInsetPx] so it sits on top of the IME (or above the system nav bar
 * when the keyboard is hidden). The popup content fills the window width.
 *
 * Centralising the maths here keeps the [MarkdownEditorField] body short and
 * makes the position rule trivially unit-testable later if we add tests for
 * the docked toolbar geometry.
 */
private class BottomAlignedPopupPositionProvider(
    private val bottomInsetPx: Int,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset = IntOffset(
        x = 0,
        y = windowSize.height - popupContentSize.height - bottomInsetPx,
    )
}

/**
 * Pulls the live-preview editor's styling bundle from the active [MaterialTheme] once per
 * composition. Same colour mapping as [rememberMarkdownInlineStyles] so styled spans (links,
 * code background, dim secondary text) render identically between view-mode and edit-mode.
 */
@Composable
@ReadOnlyComposable
private fun rememberMarkdownEditorStyles(): MarkdownEditorStyles = MarkdownEditorStyles(
    linkColor = MaterialTheme.colorScheme.primary,
    // Markers are hints, not content - they will not appear in the rendered view.
    // Fade further than the M3 onSurfaceVariant default (which is calibrated for
    // legible secondary body text) so the eye reads them as guidance rather than
    // as part of the prose. 0.45f keeps them discoverable but visibly washed-out
    // against both light and dark surfaces.
    dimColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
    codeBackground = MaterialTheme.colorScheme.surfaceContainerHighest,
)

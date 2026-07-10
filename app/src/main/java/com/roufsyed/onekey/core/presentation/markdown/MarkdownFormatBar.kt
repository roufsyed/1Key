package com.roufsyed.onekey.core.presentation.markdown

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.InsertLink
import androidx.compose.material.icons.filled.StrikethroughS
import androidx.compose.material.icons.filled.Title
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp

/**
 * Floating helper bar above the markdown editor that exposes the nine formatting actions
 * (Bold, Italic, Strike, Inline code, Heading, Bullet list, Numbered list, Blockquote, Link).
 *
 * The bar is intentionally stateless w.r.t. text: every button invokes a pure helper inside
 * [MarkdownFormatActions] that takes the current [TextFieldValue] and returns a new one, then
 * forwards through [onValueChange] so the host's existing size-cap + backspace-expansion
 * bookkeeping (see MarkdownEditorField) applies identically to bar edits.
 *
 * The single piece of bar-local state is the heading cycle anchor: tapping the heading button
 * on the same line repeatedly cycles H1 -> H2 -> H3 -> none -> H1. The anchor invalidates if
 * the caret moves to a different line or the line's text changes (hashCode mismatch).
 *
 * The Link button does NOT mutate the text; it captures the current selection text and calls
 * [onLinkInsertRequested] so the host can open a dialog and apply the insert.
 *
 * Visibility is driven from the host via [visible] (typically: field-focused OR field-non-empty)
 * and animated with a vertical expand/shrink + fade so the bar slides in when the editor takes
 * focus and out when it loses focus on an empty field.
 */
@Composable
fun MarkdownFormatBar(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    visible: Boolean,
    modifier: Modifier = Modifier,
    onLinkInsertRequested: (currentSelection: String) -> Unit,
) {
    // Heading cycle anchor lives here (not inside MarkdownFormatActions) because composables
    // need a remember-stable holder and pure helpers must stay state-free.
    var headingAnchor by remember { mutableStateOf<HeadingAnchor?>(null) }

    AnimatedVisibility(
        visible = visible,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
    ) {
        Row(
            modifier = modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FormatBarIconButton(
                icon = Icons.Default.FormatBold,
                label = "Bold",
                onClick = {
                    headingAnchor = null
                    onValueChange(MarkdownFormatActions.wrapInline(value, BOLD_MARKER, BOLD_TOGGLE))
                },
            )
            FormatBarIconButton(
                icon = Icons.Default.FormatItalic,
                label = "Italic",
                onClick = {
                    headingAnchor = null
                    onValueChange(MarkdownFormatActions.wrapInline(value, ITALIC_MARKER, ITALIC_TOGGLE))
                },
            )
            FormatBarIconButton(
                icon = Icons.Default.StrikethroughS,
                label = "Strikethrough",
                onClick = {
                    headingAnchor = null
                    onValueChange(MarkdownFormatActions.wrapInline(value, STRIKE_MARKER, STRIKE_TOGGLE))
                },
            )
            FormatBarIconButton(
                icon = Icons.Default.Code,
                label = "Inline code",
                onClick = {
                    headingAnchor = null
                    onValueChange(MarkdownFormatActions.applyInlineCode(value))
                },
            )
            FormatBarIconButton(
                icon = Icons.Default.Title,
                label = "Heading",
                tooltipText = "Heading (cycles H1, H2, H3)",
                onClick = {
                    val result = MarkdownFormatActions.cycleHeading(value, headingAnchor)
                    headingAnchor = result.nextAnchor
                    onValueChange(result.value)
                },
            )
            FormatBarIconButton(
                icon = Icons.AutoMirrored.Filled.FormatListBulleted,
                label = "Bullet list",
                onClick = {
                    headingAnchor = null
                    onValueChange(MarkdownFormatActions.prefixLines(value, BULLET_PREFIX, BULLET_STRIP))
                },
            )
            FormatBarIconButton(
                icon = Icons.Default.FormatListNumbered,
                label = "Numbered list",
                onClick = {
                    headingAnchor = null
                    onValueChange(MarkdownFormatActions.applyNumberedList(value))
                },
            )
            FormatBarIconButton(
                icon = Icons.Default.FormatQuote,
                label = "Blockquote",
                onClick = {
                    headingAnchor = null
                    onValueChange(MarkdownFormatActions.prefixLines(value, QUOTE_PREFIX, QUOTE_STRIP))
                },
            )
            FormatBarIconButton(
                icon = Icons.Default.InsertLink,
                label = "Insert link",
                onClick = {
                    headingAnchor = null
                    val sel = value.selection
                    val selectionText = if (sel.collapsed) "" else value.text.substring(sel.min, sel.max)
                    onLinkInsertRequested(selectionText)
                },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FormatBarIconButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    tooltipText: String = label,
) {
    val tooltipState = rememberTooltipState()
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(tooltipText) } },
        state = tooltipState,
    ) {
        IconButton(
            onClick = onClick,
            modifier = Modifier.size(48.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

// region Heading cycle anchor

/**
 * Tracks the heading cycle for a single line: which line the cycle is anchored on, the hashCode
 * of that line's text (so a content change invalidates the cycle), and what level the NEXT tap
 * should produce. Levels follow [0 .. 3] where 1/2/3 are H1/H2/H3 and 0 = strip.
 */
internal data class HeadingAnchor(
    val lineStart: Int,
    val lineHash: Int,
    val nextLevel: Int,
)

// endregion

// region Marker / regex constants shared by buttons and helpers

private const val BOLD_MARKER = "**"
private const val ITALIC_MARKER = "_"
private const val STRIKE_MARKER = "~~"

// Bold: ** wraps. DOTALL via RegexOption.DOT_MATCHES_ALL so the inner group can span newlines.
private val BOLD_TOGGLE = Regex("""^\*\*(.+)\*\*$""", RegexOption.DOT_MATCHES_ALL)

// Italic: _ wraps. Underscore (not single asterisk) so chained Bold + Italic renders as
// `**_text_**` instead of the renderer-ambiguous `***text***`.
//
// The boundary guard (no leading/trailing underscore in the inner) prevents `__double__` from
// being falsely treated as italic-wrapped - `__` is bold in some Markdown variants and we
// don't want to strip it as italic.
private val ITALIC_TOGGLE = Regex("""^_(?!_)(.+?)(?<!_)_$""", RegexOption.DOT_MATCHES_ALL)

private val STRIKE_TOGGLE = Regex("""^~~(.+)~~$""", RegexOption.DOT_MATCHES_ALL)

// Inline-code: backtick wraps. Toggle regex disallows internal backticks (CommonMark inline
// code can't contain unescaped backticks in single-backtick form).
private val INLINE_CODE_TOGGLE = Regex("""^`([^`]+)`$""")
private val FENCED_CODE_TOGGLE = Regex("""^```\n([\s\S]+)\n```$""")

private const val BULLET_PREFIX = "- "
private val BULLET_STRIP = Regex("""^- """)

private const val QUOTE_PREFIX = "> "
private val QUOTE_STRIP = Regex("""^>\s?""")

private val HEADING_STRIP = Regex("""^#{1,3} """)
private val NUMBERED_STRIP = Regex("""^\d+\.\s""")
private val NUMBERED_PREV_CONTINUATION = Regex("""^(\d+)\. """)

// endregion

/**
 * Pure (no Compose, no state) helpers that take a [TextFieldValue] and return a new one with
 * the formatting transformation applied. Splitting these out makes them trivially unit-testable
 * and keeps the @Composable above readable.
 */
internal object MarkdownFormatActions {

    /**
     * Inline wrap with toggle-off detection.
     *
     * Five cases, in order:
     *  (A) Empty selection: insert [marker]+[marker] and park the cursor between them, so a
     *      follow-up tap on another inline button wraps the zero-length inner range and the
     *      user can type into the freshly-opened span.
     *  (B) Toggle-off A: selection already begins+ends with [marker] (user selected the WHOLE
     *      `**bold**` including the asterisks). Strip the markers and keep the inner selected.
     *  (C) Toggle-off B: selection is the unwrapped inner content but the markers sit just
     *      outside the selection (user double-tapped `bold` inside `**bold**`). Strip the
     *      surrounding markers and keep the inner selected.
     *  (D) Wrap non-empty selection: insert [marker] on each side. Single wrap pair across the
     *      whole span, NOT per-line.
     *  (E) Post-tap selection covers the just-wrapped span INCLUDING the markers, so the next
     *      tap on the same button hits the toggle-off branch (B).
     */
    fun wrapInline(current: TextFieldValue, marker: String, toggleRegex: Regex): TextFieldValue {
        val sel = current.selection
        val text = current.text
        val mLen = marker.length

        // (A) Empty selection -> insert markers, park cursor between them.
        if (sel.collapsed) {
            val cursor = sel.start
            val newText = buildString(text.length + 2 * mLen) {
                append(text, 0, cursor)
                append(marker)
                append(marker)
                append(text, cursor, text.length)
            }
            return current.copy(
                text = newText,
                selection = TextRange(cursor + mLen),
            )
        }

        val inner = text.substring(sel.min, sel.max)

        // (B) Toggle-off: selection itself starts+ends with marker.
        val toggleMatch = toggleRegex.matchEntire(inner)
        if (toggleMatch != null) {
            val stripped = toggleMatch.groupValues[1]
            val newText = buildString(text.length - 2 * mLen) {
                append(text, 0, sel.min)
                append(stripped)
                append(text, sel.max, text.length)
            }
            return current.copy(
                text = newText,
                selection = TextRange(sel.min + stripped.length),
            )
        }

        // (C) Toggle-off: markers live just OUTSIDE the selection.
        val outerStart = sel.min - mLen
        val outerEnd = sel.max + mLen
        if (outerStart >= 0 && outerEnd <= text.length &&
            text.regionMatches(outerStart, marker, 0, mLen) &&
            text.regionMatches(sel.max, marker, 0, mLen)
        ) {
            val newText = buildString(text.length - 2 * mLen) {
                append(text, 0, outerStart)
                append(inner)
                append(text, outerEnd, text.length)
            }
            return current.copy(
                text = newText,
                selection = TextRange(outerStart + inner.length),
            )
        }

        // (D) Wrap. Cursor collapses to the end of the wrapped span (after the
        // closing marker) so the action feels "one and done" - no lingering
        // blue highlight to dismiss manually. Trade-off: a B->I chain on the
        // same span no longer hits the toggle-off branch; the user re-selects
        // if they want to stack styles, which they do far less often than they
        // tap a single format button on a single selection.
        val newText = buildString(text.length + 2 * mLen) {
            append(text, 0, sel.min)
            append(marker)
            append(inner)
            append(marker)
            append(text, sel.max, text.length)
        }
        return current.copy(
            text = newText,
            selection = TextRange(sel.max + 2 * mLen),
        )
    }

    /**
     * Inline code - special-cased because backticks inside the inner selection need a fenced
     * code block (```...```) to be a legal CommonMark source.
     */
    fun applyInlineCode(current: TextFieldValue): TextFieldValue {
        val sel = current.selection
        val text = current.text

        // (A) Empty selection -> insert ``, park cursor between.
        if (sel.collapsed) {
            val cursor = sel.start
            val newText = buildString(text.length + 2) {
                append(text, 0, cursor)
                append('`')
                append('`')
                append(text, cursor, text.length)
            }
            return current.copy(text = newText, selection = TextRange(cursor + 1))
        }

        val inner = text.substring(sel.min, sel.max)

        // Toggle-off A: selection is `...` already.
        INLINE_CODE_TOGGLE.matchEntire(inner)?.let { m ->
            val stripped = m.groupValues[1]
            val newText = buildString(text.length - 2) {
                append(text, 0, sel.min)
                append(stripped)
                append(text, sel.max, text.length)
            }
            return current.copy(
                text = newText,
                selection = TextRange(sel.min + stripped.length),
            )
        }

        // Toggle-off B: selection is ```\n...\n``` already.
        FENCED_CODE_TOGGLE.matchEntire(inner)?.let { m ->
            val stripped = m.groupValues[1]
            val newText = buildString(text.length - 8) {
                append(text, 0, sel.min)
                append(stripped)
                append(text, sel.max, text.length)
            }
            return current.copy(
                text = newText,
                selection = TextRange(sel.min + stripped.length),
            )
        }

        // Toggle-off C: markers sit just outside the selection - single-backtick form.
        if (sel.min - 1 >= 0 && sel.max + 1 <= text.length &&
            text[sel.min - 1] == '`' && text[sel.max] == '`' && !inner.contains('`')
        ) {
            val newText = buildString(text.length - 2) {
                append(text, 0, sel.min - 1)
                append(inner)
                append(text, sel.max + 1, text.length)
            }
            return current.copy(
                text = newText,
                selection = TextRange(sel.min - 1 + inner.length),
            )
        }

        // Wrap. If the inner contains a backtick, fall back to fenced. Cursor
        // collapses to the end of the wrapped span in both branches so the
        // post-tap UX matches wrapInline.
        return if (inner.contains('`')) {
            val newText = buildString(text.length + 8) {
                append(text, 0, sel.min)
                append("```\n")
                append(inner)
                append("\n```")
                append(text, sel.max, text.length)
            }
            current.copy(
                text = newText,
                selection = TextRange(sel.max + 8),
            )
        } else {
            val newText = buildString(text.length + 2) {
                append(text, 0, sel.min)
                append('`')
                append(inner)
                append('`')
                append(text, sel.max, text.length)
            }
            current.copy(
                text = newText,
                selection = TextRange(sel.max + 2),
            )
        }
    }

    /**
     * Block-prefix transform for static prefixes (bullet `- `, blockquote `> `).
     *
     * Selection-spanning lines are computed by expanding the [TextFieldValue.selection] to its
     * containing line boundaries. Toggle-off triggers when every non-blank selected line
     * already begins with [stripRegex]. Blank lines are SKIPPED (no prefix on empty lines so
     * paragraph breaks inside a multi-line selection don't get stray dashes).
     *
     * The post-tap selection covers the whole transformed span so chained taps on the same
     * button toggle back off.
     */
    fun prefixLines(current: TextFieldValue, prefix: String, stripRegex: Regex): TextFieldValue {
        val lines = lineSpansInSelection(current.text, current.selection)
        if (lines.isEmpty()) return current

        val nonBlank = lines.filter { it.content.isNotBlank() }
        val shouldStrip = nonBlank.isNotEmpty() && nonBlank.all { stripRegex.containsMatchIn(it.content) }

        val sb = StringBuilder(current.text.length + lines.size * prefix.length)
        sb.append(current.text, 0, lines.first().start)

        var deltaSoFar = 0
        val transformed = lines.map { line ->
            val newContent = when {
                line.content.isBlank() -> line.content
                shouldStrip -> line.content.replaceFirst(stripRegex, "")
                else -> prefix + line.content
            }
            deltaSoFar += newContent.length - line.content.length
            newContent
        }
        for ((i, newContent) in transformed.withIndex()) {
            sb.append(newContent)
            if (i != transformed.lastIndex) sb.append('\n')
        }
        sb.append(current.text, lines.last().endExclusive, current.text.length)

        // Cursor collapses to the end of the transformed span so the post-tap UX
        // matches the inline wrap actions - no lingering blue highlight to dismiss.
        val newEnd = current.selection.max + deltaSoFar
        return current.copy(
            text = sb.toString(),
            selection = TextRange(newEnd),
        )
    }

    /** Result of a heading cycle tap. */
    internal data class HeadingResult(val value: TextFieldValue, val nextAnchor: HeadingAnchor?)

    /**
     * Heading cycle: H1 -> H2 -> H3 -> none -> H1 on the line containing the caret.
     *
     * If [anchor] is valid (same lineStart AND same lineHash as the line under the caret), the
     * next level in the cycle is used; otherwise the cycle restarts at H1. The returned anchor
     * is `null` after a multi-line apply (selection spanned multiple lines) so the next single-
     * line tap starts fresh.
     */
    fun cycleHeading(current: TextFieldValue, anchor: HeadingAnchor?): HeadingResult {
        val text = current.text
        val sel = current.selection

        val lines = lineSpansInSelection(text, sel)
        if (lines.isEmpty()) {
            return HeadingResult(current, null)
        }

        // Multi-line: apply the SAME level to every non-blank line, no cycling, reset anchor.
        if (lines.size > 1) {
            val nonBlank = lines.filter { it.content.isNotBlank() }
            val allHeaded = nonBlank.isNotEmpty() && nonBlank.all { HEADING_STRIP.containsMatchIn(it.content) }
            val nextLevel = if (allHeaded) 0 else 1

            val sb = StringBuilder()
            sb.append(text, 0, lines.first().start)
            var delta = 0
            for ((i, line) in lines.withIndex()) {
                val stripped = line.content.replaceFirst(HEADING_STRIP, "")
                val newContent = when {
                    line.content.isBlank() -> line.content
                    nextLevel == 1 -> "# $stripped"
                    nextLevel == 2 -> "## $stripped"
                    nextLevel == 3 -> "### $stripped"
                    else -> stripped
                }
                delta += newContent.length - line.content.length
                sb.append(newContent)
                if (i != lines.lastIndex) sb.append('\n')
            }
            sb.append(text, lines.last().endExclusive, text.length)
            // Cursor collapses to the end of the transformed span - consistent with
            // the inline wrap actions and the prefixLines block actions.
            val newCursor = (sel.max + delta).coerceAtMost(sb.length)
            return HeadingResult(
                current.copy(text = sb.toString(), selection = TextRange(newCursor)),
                null,
            )
        }

        // Single line.
        val line = lines.first()
        val lineHash = line.content.hashCode()
        val isValid = anchor != null && anchor.lineStart == line.start && anchor.lineHash == lineHash
        val nextLevel = if (isValid) anchor!!.nextLevel else 1

        val stripped = line.content.replaceFirst(HEADING_STRIP, "")
        val rewritten = when (nextLevel) {
            1 -> "# $stripped"
            2 -> "## $stripped"
            3 -> "### $stripped"
            else -> stripped
        }
        val newText = buildString(text.length + rewritten.length - line.content.length) {
            append(text, 0, line.start)
            append(rewritten)
            append(text, line.endExclusive, text.length)
        }
        val newCursor = (sel.max + (rewritten.length - line.content.length))
            .coerceAtLeast(line.start)
            .coerceAtMost(newText.length)
        val nextAnchor = HeadingAnchor(
            lineStart = line.start,
            lineHash = rewritten.hashCode(),
            nextLevel = (nextLevel + 1) % 4,
        )
        return HeadingResult(
            current.copy(text = newText, selection = TextRange(newCursor)),
            nextAnchor,
        )
    }

    /**
     * Numbered list: insert `1. `, `2. `, ... on selected lines OR strip leading `n. ` if every
     * non-blank line is already numbered.
     *
     * Continuation: if the line above the selection is `n. ...`, numbering starts at n+1.
     * Otherwise it starts at 1. Blank lines inside the selection don't get a prefix and don't
     * advance the counter.
     */
    fun applyNumberedList(current: TextFieldValue): TextFieldValue {
        val text = current.text
        val sel = current.selection
        val lines = lineSpansInSelection(text, sel)
        if (lines.isEmpty()) return current

        val nonBlank = lines.filter { it.content.isNotBlank() }
        val shouldStrip = nonBlank.isNotEmpty() && nonBlank.all { NUMBERED_STRIP.containsMatchIn(it.content) }

        val startNum: Int = if (shouldStrip) {
            1
        } else {
            val firstStart = lines.first().start
            if (firstStart == 0) {
                1
            } else {
                // Walk back to the previous line: char at firstStart - 1 is '\n' (otherwise
                // firstStart would not be a line boundary).
                val prevNl = text.lastIndexOf('\n', firstStart - 2)
                val prevLineStart = if (prevNl < 0) 0 else prevNl + 1
                val prevLine = text.substring(prevLineStart, firstStart - 1)
                NUMBERED_PREV_CONTINUATION.find(prevLine)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()
                    ?.let { it + 1 }
                    ?: 1
            }
        }

        val sb = StringBuilder(text.length + lines.size * 4)
        sb.append(text, 0, lines.first().start)
        var n = startNum
        var delta = 0
        for ((i, line) in lines.withIndex()) {
            val newContent = when {
                line.content.isBlank() -> line.content
                shouldStrip -> line.content.replaceFirst(NUMBERED_STRIP, "")
                else -> {
                    // Strip any existing number first so re-numbering is idempotent.
                    val stripped = line.content.replaceFirst(NUMBERED_STRIP, "")
                    val out = "$n. $stripped"
                    n++
                    out
                }
            }
            delta += newContent.length - line.content.length
            sb.append(newContent)
            if (i != lines.lastIndex) sb.append('\n')
        }
        sb.append(text, lines.last().endExclusive, text.length)

        // Cursor collapses to the end of the transformed span - consistent with
        // every other format-bar action.
        val newEnd = sel.max + delta
        return current.copy(
            text = sb.toString(),
            selection = TextRange(newEnd),
        )
    }

    /** One line in the selection range. [endExclusive] points just past the line's last char. */
    internal data class LineSpan(val start: Int, val endExclusive: Int, val content: String)

    /**
     * Returns the (non-empty) list of lines that the selection touches, expanded to line
     * boundaries. A collapsed selection returns exactly one line (the line the caret sits on).
     */
    internal fun lineSpansInSelection(text: String, sel: TextRange): List<LineSpan> {
        val from = sel.min
        val to = sel.max

        val firstLineStart = if (from <= 0) {
            0
        } else {
            val nl = text.lastIndexOf('\n', from - 1)
            if (nl < 0) 0 else nl + 1
        }

        val lastLineEnd = if (from == to) {
            val nl = text.indexOf('\n', from)
            if (nl < 0) text.length else nl
        } else {
            val nl = text.indexOf('\n', (to - 1).coerceAtLeast(from))
            if (nl < 0) text.length else nl
        }

        val slice = text.substring(firstLineStart, lastLineEnd)
        val out = mutableListOf<LineSpan>()
        var cursor = firstLineStart
        for (line in slice.split('\n')) {
            out += LineSpan(cursor, cursor + line.length, line)
            cursor += line.length + 1
        }
        return out
    }
}

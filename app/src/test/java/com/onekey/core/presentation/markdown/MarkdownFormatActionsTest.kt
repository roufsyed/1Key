package com.onekey.core.presentation.markdown

import android.app.Application
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Behavioural locks for [MarkdownFormatActions] - the pure helpers the format bar buttons
 * dispatch through. Each test builds a [TextFieldValue] by hand (text + selection), calls a
 * single helper, and asserts the resulting text AND selection. Selection coverage matters
 * because the post-tap selection is what powers chain-tap toggling (Bold then Bold strips;
 * Bold then Italic wraps the already-bold span).
 *
 * Robolectric is not strictly required - none of the helpers under test touch Android APIs -
 * but kept for parity with the rest of the markdown test suite (Uri.parse-using neighbours
 * share the same runner and SDK level).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class MarkdownFormatActionsTest {

    // Marker / regex constants are duplicated here (the production file holds them as private
    // top-level vals). The duplication is intentional and tiny - the alternative is to
    // promote the constants to internal, which leaks implementation detail through the public
    // surface of the module just for tests.
    private val boldMarker = "**"
    private val boldToggle = Regex("""^\*\*(.+)\*\*$""", RegexOption.DOT_MATCHES_ALL)
    private val italicMarker = "_"
    private val italicToggle = Regex("""^_(?!_)(.+?)(?<!_)_$""", RegexOption.DOT_MATCHES_ALL)
    private val strikeMarker = "~~"
    private val strikeToggle = Regex("""^~~(.+)~~$""", RegexOption.DOT_MATCHES_ALL)
    private val bulletPrefix = "- "
    private val bulletStrip = Regex("""^- """)
    private val quotePrefix = "> "
    private val quoteStrip = Regex("""^>\s?""")

    /** Helper: build a TextFieldValue with selection covering the substring `inner` in `text`. */
    private fun selecting(text: String, inner: String): TextFieldValue {
        val start = text.indexOf(inner)
        require(start >= 0) { "inner '$inner' not found in '$text'" }
        return TextFieldValue(text = text, selection = TextRange(start, start + inner.length))
    }

    // ---- Bold ---------------------------------------------------------------

    @Test
    fun boldWrap_addsAsterisksAroundSelection() {
        val input = selecting("foo", "foo")
        val out = MarkdownFormatActions.wrapInline(input, boldMarker, boldToggle)
        assertEquals("**foo**", out.text)
        // Cursor collapses to the end of the wrapped span (after the closing `**`)
        // so the tap feels "one and done" - no lingering blue highlight to dismiss.
        assertEquals(TextRange(7), out.selection)
    }

    @Test
    fun boldToggleOff_stripsAsterisks() {
        // Selection covers the entire `**foo**` including the asterisks - this is branch (B):
        // the selection itself starts+ends with the marker, so we strip and collapse the cursor
        // to just past the inner content.
        val input = selecting("**foo**", "**foo**")
        val out = MarkdownFormatActions.wrapInline(input, boldMarker, boldToggle)
        assertEquals("foo", out.text)
        assertEquals(TextRange(3), out.selection)
    }

    @Test
    fun emptySelectionBold_insertsMarkersAndParksCursorBetween() {
        // Branch (A): collapsed selection at offset 0 in an empty document. The helper inserts
        // BOTH markers then parks the cursor between them so the user can type into the freshly-
        // opened span (or chain into another inline button to nest the marker).
        val input = TextFieldValue(text = "", selection = TextRange(0))
        val out = MarkdownFormatActions.wrapInline(input, boldMarker, boldToggle)
        assertEquals("****", out.text)
        // Cursor sits between the two `**` pairs - offset 2 is the boundary between marker 1 and
        // marker 2.
        assertEquals(TextRange(2), out.selection)
    }

    // ---- Italic -------------------------------------------------------------

    @Test
    fun italicUsesUnderscore_notAsterisk() {
        // Italic uses `_` (not `*`) so that re-selecting and tapping Bold over an italic span
        // produces `**_text_**` rather than the ambiguous `***text***` (which the renderer
        // disambiguates via AST but is confusing to read in source).
        val input = selecting("foo", "foo")
        val out = MarkdownFormatActions.wrapInline(input, italicMarker, italicToggle)
        assertEquals("_foo_", out.text)
        assertEquals(TextRange(5), out.selection)
    }

    // ---- Strike -------------------------------------------------------------

    @Test
    fun strikeWrap_addsTildePairAroundSelection() {
        val input = selecting("foo", "foo")
        val out = MarkdownFormatActions.wrapInline(input, strikeMarker, strikeToggle)
        assertEquals("~~foo~~", out.text)
        assertEquals(TextRange(7), out.selection)
    }

    // ---- Inline code --------------------------------------------------------

    @Test
    fun inlineCodeWrap_addsBackticksAroundSelection() {
        // Inline code goes through the dedicated applyInlineCode helper (special-cased because
        // a backtick inside the inner selection needs a fenced ```...``` block to be a legal
        // CommonMark source). For backtick-free input the wrap is identical to wrapInline with
        // a single backtick marker.
        val input = selecting("foo", "foo")
        val out = MarkdownFormatActions.applyInlineCode(input)
        assertEquals("`foo`", out.text)
        assertEquals(TextRange(5), out.selection)
    }

    // ---- Heading ------------------------------------------------------------

    @Test
    fun headingPrefixOnEmptySelection_addsLeadingHash() {
        // Collapsed cursor on an empty document: the heading helper treats the empty line as a
        // line of content and prefixes it with `# ` (level 1). The cursor lands at the end of
        // the prefix (after the space).
        val input = TextFieldValue(text = "", selection = TextRange(0))
        val out = MarkdownFormatActions.cycleHeading(input, anchor = null)
        assertEquals("# ", out.value.text)
        // Cursor is at the end of the rewritten line (offset 2 = length of "# ").
        assertEquals(TextRange(2), out.value.selection)
        assertNotNull("expected a fresh anchor pointing at level 2", out.nextAnchor)
        assertEquals(2, out.nextAnchor!!.nextLevel)
    }

    @Test
    fun headingPrefixOnExistingLine_addsLevelOnePrefix() {
        // Cursor in the middle of "title" with no anchor -> fresh cycle starting at H1.
        val input = TextFieldValue(text = "title", selection = TextRange(2))
        val out = MarkdownFormatActions.cycleHeading(input, anchor = null)
        assertEquals("# title", out.value.text)
        assertNotNull(out.nextAnchor)
        assertEquals(2, out.nextAnchor!!.nextLevel)
    }

    @Test
    fun headingCycle_h1ToH2ToH3ToStrip() {
        // The cycle: H1 -> H2 -> H3 -> none -> H1. We chain three taps using the previous tap's
        // returned anchor so the cycle is detected as valid (same lineStart, same lineHash).
        val step1 = MarkdownFormatActions.cycleHeading(
            TextFieldValue(text = "# title", selection = TextRange(7)),
            anchor = HeadingAnchor(lineStart = 0, lineHash = "# title".hashCode(), nextLevel = 2),
        )
        assertEquals("## title", step1.value.text)
        assertNotNull(step1.nextAnchor)
        assertEquals(3, step1.nextAnchor!!.nextLevel)

        val step2 = MarkdownFormatActions.cycleHeading(
            TextFieldValue(text = "## title", selection = TextRange(8)),
            anchor = step1.nextAnchor,
        )
        assertEquals("### title", step2.value.text)
        assertNotNull(step2.nextAnchor)
        assertEquals(0, step2.nextAnchor!!.nextLevel)

        val step3 = MarkdownFormatActions.cycleHeading(
            TextFieldValue(text = "### title", selection = TextRange(9)),
            anchor = step2.nextAnchor,
        )
        // Level 0 strips the heading prefix entirely.
        assertEquals("title", step3.value.text)
    }

    // ---- Bullet list --------------------------------------------------------

    @Test
    fun bulletList_multilineSelection_prefixesEveryLine() {
        // Three lines selected. The helper expands the selection to line boundaries and adds
        // `- ` to each line. Toggle-off does NOT fire because none of the lines starts with
        // the bullet prefix yet.
        val text = "a\nb\nc"
        val input = TextFieldValue(text = text, selection = TextRange(0, text.length))
        val out = MarkdownFormatActions.prefixLines(input, bulletPrefix, bulletStrip)
        assertEquals("- a\n- b\n- c", out.text)
    }

    // ---- Numbered list ------------------------------------------------------

    @Test
    fun numberedList_multilineSelection_addsSequentialNumbers() {
        // Three lines, no prior numbering. The helper inserts `1. `, `2. `, `3. ` in order.
        // Continuation does not apply because the line above the selection does not exist
        // (selection starts at offset 0).
        val text = "a\nb\nc"
        val input = TextFieldValue(text = text, selection = TextRange(0, text.length))
        val out = MarkdownFormatActions.applyNumberedList(input)
        assertEquals("1. a\n2. b\n3. c", out.text)
    }

    // ---- Blockquote ---------------------------------------------------------

    @Test
    fun blockquote_multilineSelection_prefixesEveryLineWithGreaterThan() {
        // Same shape as bullet list but with `> ` instead of `- `. Toggle-off does NOT fire
        // because the lines do not start with the quote prefix yet.
        val text = "a\nb\nc"
        val input = TextFieldValue(text = text, selection = TextRange(0, text.length))
        val out = MarkdownFormatActions.prefixLines(input, quotePrefix, quoteStrip)
        assertEquals("> a\n> b\n> c", out.text)
    }

    // ---- Chain-friendly ------------------------------------------------------

    @Test
    fun boldThenItalic_byReselection_producesNestedMarkers() {
        // Post-tap the cursor collapses to the end of the wrapped span (no lingering
        // highlight), so chaining requires the user to re-select. This test mirrors that
        // flow: Bold "foo" -> cursor lands at 7; user re-selects the whole `**foo**`
        // (positions 0..7); Italic produces `_**foo**_` because the italic toggle regex
        // does not match `**foo**`, falling through to wrap.
        val afterBold = MarkdownFormatActions.wrapInline(
            selecting("foo", "foo"),
            boldMarker,
            boldToggle,
        )
        assertEquals("**foo**", afterBold.text)
        assertEquals(TextRange(7), afterBold.selection)

        // Simulate the user re-selecting the bold span.
        val reselected = afterBold.copy(selection = TextRange(0, 7))
        val afterItalic = MarkdownFormatActions.wrapInline(
            reselected,
            italicMarker,
            italicToggle,
        )
        assertEquals("_**foo**_", afterItalic.text)
        // Italic wrap also collapses to the end of its own wrapped span (offset 9 in
        // `_**foo**_`).
        assertEquals(TextRange(9), afterItalic.selection)
    }

    // ---- Sanity --------------------------------------------------------------

    @Test
    fun italicToggleRegex_doesNotFalselyMatchDoubleUnderscore() {
        // The italic regex has a boundary guard so `__bold__` (which some Markdown variants
        // treat as bold via underscores) is NOT mistakenly stripped as italic. This is a unit
        // assertion on the regex shape we're relying on above.
        val match = italicToggle.matchEntire("__bold__")
        assertNull("italic regex must not match `__bold__`", match)
    }
}

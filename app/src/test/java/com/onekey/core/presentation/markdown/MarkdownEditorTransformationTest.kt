package com.onekey.core.presentation.markdown

import android.app.Application
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.style.TextDecoration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Behavioural locks for [MarkdownEditorTransformation] under the dimmed-markers model.
 *
 * Tests run under Robolectric because AnnotatedString construction touches Compose ui-text
 * classes that pull in android.graphics stubs during SpanStyle initialisation.
 *
 * Invariants under the new model:
 *  - Every source byte is emitted verbatim into the transformed string; source length always
 *    equals transformed length.
 *  - The transformation always returns [OffsetMapping.Identity]; the custom offset-mapping path
 *    is gone (so any IME that asks for [TransformedText.offsetMapping].transformedToOriginal /
 *    originalToTransformed walks a 1:1 identity map and cannot crash by demanding offsets that
 *    don't exist).
 *  - Marker leaves (asterisks, hashes, brackets, parens, angle brackets, tildes, list bullets,
 *    blockquote leaders, link destinations, fenced-code fence rows, etc.) are styled with
 *    [MarkdownEditorStyles.dimColor].
 *  - Content leaves (bold text, italic text, code body, link label, etc.) get the semantic
 *    SpanStyle (Bold weight, Italic style, Monospace family, Underline + linkColor, etc.).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class MarkdownEditorTransformationTest {

    private val styles = MarkdownEditorStyles(
        linkColor = Color(0xFF1976D2),
        dimColor = Color(0xFF666666),
        codeBackground = Color(0xFFEEEEEE),
    )

    private val transformation = MarkdownEditorTransformation(styles)

    private fun filter(source: String) = transformation.filter(AnnotatedString(source))

    /** Finds the first SpanStyle range that satisfies [predicate]. */
    private fun TransformedText.findSpan(predicate: (SpanStyle) -> Boolean) =
        text.spanStyles.firstOrNull { predicate(it.item) }

    /**
     * Returns true iff every byte in the range [start, end) is covered by at least one SpanStyle
     * satisfying [predicate]. The walker often emits one span per marker leaf, so a marker run
     * like `**` becomes two adjacent one-char dim spans; per-byte coverage is the correct check.
     */
    private fun TransformedText.hasSpanOver(start: Int, end: Int, predicate: (SpanStyle) -> Boolean): Boolean {
        if (end <= start) return true
        for (i in start until end) {
            val covered = text.spanStyles.any { it.start <= i && it.end > i && predicate(it.item) }
            if (!covered) return false
        }
        return true
    }

    /** Assert the transformation's offsetMapping is Identity (the new model invariant). */
    private fun assertIdentity(result: TransformedText) {
        assertSame(
            "transformation must always return OffsetMapping.Identity under the dim-markers model",
            OffsetMapping.Identity,
            result.offsetMapping,
        )
    }

    // ---------------------------------------------------------------------
    // Guards: empty / over-cap / parse error
    // ---------------------------------------------------------------------

    @Test
    fun emptySource_returnsIdentityMapping() {
        val result = filter("")
        assertEquals("", result.text.text)
        assertIdentity(result)
    }

    @Test
    fun overCapSource_returnsIdentityMapping() {
        // 64 KiB + 1 of plain ASCII (1 byte per char) trips the byte cap.
        val source = "a".repeat(MARKDOWN_EDITOR_MAX_BYTES + 1)
        val result = filter(source)
        assertEquals(source, result.text.text)
        assertIdentity(result)
    }

    // ---------------------------------------------------------------------
    // Identity invariant: visible text matches source for every corpus input
    // ---------------------------------------------------------------------

    @Test
    fun everySource_yieldsIdentityMapping() {
        val corpus = listOf(
            "",
            "plain text",
            "**bold**",
            "*ital*",
            "_ital_",
            "~~gone~~",
            "`code`",
            "**b** *i*",
            "# Title",
            "## H2",
            "### H3",
            "#### H4",
            "##### H5",
            "###### H6",
            "- item",
            "1. item",
            "> quoted",
            "```\nhello\n```",
            "    code\n",
            "| a | b |\n|---|---|\n| 1 | 2 |\n",
            "[label](https://example.test)",
            "[label](https://example.test \"title\")",
            "https://example.test",
            "<https://example.test>",
            "user@example.test",
            "![alt text](https://example.test/img.png)",
            "![](https://example.test/img.png)",
            "<b>x</b>",
            "# Hi\n\nThis is **bold** and _ital_.\n",
            "[ref][1]\n\n[1]: https://example.test",
            "[short]\n\n[short]: https://example.test",
            "---",
            "***",
        )
        for (source in corpus) {
            val result = filter(source)
            assertEquals(
                "visible text must equal source for: '${source.replace("\n", "\\n")}'",
                source,
                result.text.text,
            )
            assertSame(
                "offsetMapping must be Identity for: '${source.replace("\n", "\\n")}'",
                OffsetMapping.Identity,
                result.offsetMapping,
            )
        }
    }

    // ---------------------------------------------------------------------
    // Bold / italic / strikethrough / code - markers kept visible and dimmed
    // ---------------------------------------------------------------------

    @Test
    fun balancedBold_keepsAsterisksVisibleAndDimmed() {
        val source = "**bold**"
        val result = filter(source)
        assertEquals(source, result.text.text)
        assertIdentity(result)
        // Bold weight covers the visible content range [2, 6) ("bold").
        assertTrue(
            "expected a Bold span covering 'bold'",
            result.hasSpanOver(2, 6) { it.fontWeight == FontWeight.Bold },
        )
        // Dim spans cover both marker runs: [0, 2) "**" and [6, 8) "**".
        assertTrue(
            "expected a dim span covering opening '**'",
            result.hasSpanOver(0, 2) { it.color == styles.dimColor },
        )
        assertTrue(
            "expected a dim span covering closing '**'",
            result.hasSpanOver(6, 8) { it.color == styles.dimColor },
        )
    }

    @Test
    fun unbalancedBold_keepsAsterisksVisible() {
        // No closing `**`; the parser does NOT emit a STRONG composite so the markers stay as
        // plain TEXT leaves with no styling. They must still appear in the visible string.
        val source = "**bold"
        val result = filter(source)
        assertEquals(source, result.text.text)
        assertIdentity(result)
        assertTrue(
            "unbalanced markers must remain visible verbatim; got: '${result.text.text}'",
            result.text.text.contains("**"),
        )
    }

    @Test
    fun balancedItalic_keepsAsterisksVisibleAndDimmed() {
        val source = "*ital*"
        val result = filter(source)
        assertEquals(source, result.text.text)
        assertIdentity(result)
        assertTrue(
            "expected an Italic span over 'ital'",
            result.hasSpanOver(1, 5) { it.fontStyle == FontStyle.Italic },
        )
        assertTrue(
            "expected dim spans on '*' markers",
            result.hasSpanOver(0, 1) { it.color == styles.dimColor },
        )
        assertTrue(
            result.hasSpanOver(5, 6) { it.color == styles.dimColor },
        )
    }

    @Test
    fun balancedItalicUnderscore_keepsUnderscoresVisibleAndDimmed() {
        val source = "_ital_"
        val result = filter(source)
        assertEquals(source, result.text.text)
        assertIdentity(result)
        assertTrue(
            "expected an Italic span over 'ital'",
            result.hasSpanOver(1, 5) { it.fontStyle == FontStyle.Italic },
        )
        assertTrue(
            result.hasSpanOver(0, 1) { it.color == styles.dimColor },
        )
        assertTrue(
            result.hasSpanOver(5, 6) { it.color == styles.dimColor },
        )
    }

    @Test
    fun strikethrough_keepsTildesVisibleAndDimmed() {
        val source = "~~gone~~"
        val result = filter(source)
        assertEquals(source, result.text.text)
        assertIdentity(result)
        assertTrue(
            "expected LineThrough over 'gone'",
            result.hasSpanOver(2, 6) { it.textDecoration == TextDecoration.LineThrough },
        )
        assertTrue(
            "expected dim spans on '~~' markers",
            result.hasSpanOver(0, 2) { it.color == styles.dimColor },
        )
        assertTrue(
            result.hasSpanOver(6, 8) { it.color == styles.dimColor },
        )
    }

    @Test
    fun codeSpan_keepsBackticksVisibleAndDimmed() {
        val source = "`code`"
        val result = filter(source)
        assertEquals(source, result.text.text)
        assertIdentity(result)
        // Monospace family covers the visible content range [1, 5) ("code").
        assertTrue(
            "expected a Monospace span over 'code'",
            result.hasSpanOver(1, 5) { it.fontFamily == FontFamily.Monospace },
        )
        // Dim spans (which also carry Monospace per design) cover the marker bytes at [0, 1) and [5, 6).
        assertTrue(
            "expected dim span over opening backtick",
            result.hasSpanOver(0, 1) { it.color == styles.dimColor },
        )
        assertTrue(
            "expected dim span over closing backtick",
            result.hasSpanOver(5, 6) { it.color == styles.dimColor },
        )
    }

    @Test
    fun mixedBoldAndItalic_keepsBothPairsVisibleAndDimmed() {
        val source = "**b** *i*"
        val result = filter(source)
        assertEquals(source, result.text.text)
        assertIdentity(result)
        // Bold span over 'b' at [2, 3); Italic span over 'i' at [7, 8).
        assertTrue(
            "expected Bold over 'b'",
            result.hasSpanOver(2, 3) { it.fontWeight == FontWeight.Bold },
        )
        assertTrue(
            "expected Italic over 'i'",
            result.hasSpanOver(7, 8) { it.fontStyle == FontStyle.Italic },
        )
        // Dim spans over each marker run.
        assertTrue(result.hasSpanOver(0, 2) { it.color == styles.dimColor }) // opening **
        assertTrue(result.hasSpanOver(3, 5) { it.color == styles.dimColor }) // closing **
        assertTrue(result.hasSpanOver(6, 7) { it.color == styles.dimColor }) // opening *
        assertTrue(result.hasSpanOver(8, 9) { it.color == styles.dimColor }) // closing *
    }

    // ---------------------------------------------------------------------
    // Headings - hash prefix visible + dim, content at heading size
    // ---------------------------------------------------------------------

    @Test
    fun atxHeading_keepsHashPrefixVisibleAndDimmed() {
        val source = "# Title"
        val result = filter(source)
        assertEquals(source, result.text.text)
        assertIdentity(result)
        // Heading font size span covers the whole line (markers included). The '# ' run is also
        // wrapped in a dim push.
        assertTrue(
            "expected a heading SpanStyle (font size set) over the line",
            result.findSpan { it.fontSize == styles.h1Size } != null,
        )
        assertTrue(
            "expected dim span over '# '",
            result.hasSpanOver(0, 2) { it.color == styles.dimColor },
        )
    }

    @Test
    fun atxHeadingLevels_allKeepTheirHashPrefixVisibleAndDimmed() {
        val sizes = listOf(
            styles.h1Size, styles.h2Size, styles.h3Size,
            styles.h4Size, styles.h5Size, styles.h6Size,
        )
        for (level in 1..6) {
            val prefix = "#".repeat(level)
            val source = "$prefix Title"
            val result = filter(source)
            assertEquals(
                "level $level: visible text must equal source",
                source,
                result.text.text,
            )
            assertIdentity(result)
            // Heading font size span exists.
            assertNotNull(
                "level $level: expected heading font size span",
                result.findSpan { it.fontSize == sizes[level - 1] },
            )
            // Dim span covers the leading '#'s and the trailing space (level + 1 chars).
            assertTrue(
                "level $level: expected dim span over '$prefix ' run",
                result.hasSpanOver(0, level + 1) { it.color == styles.dimColor },
            )
        }
    }

    @Test
    fun hashPrefix_inheritsHeadingFontSize_andStaysDim() {
        // Decision lock: the '#' chars render at h1 font size AND dim color simultaneously.
        // Concretely, byte 0 (the '#') must sit inside both an h1-sized span and a dim-color span.
        val source = "# Title"
        val result = filter(source)
        assertEquals(source, result.text.text)
        val byteZeroHasHeadingSize = result.text.spanStyles.any {
            it.start <= 0 && it.end > 0 && it.item.fontSize == styles.h1Size
        }
        val byteZeroIsDim = result.text.spanStyles.any {
            it.start <= 0 && it.end > 0 && it.item.color == styles.dimColor
        }
        assertTrue("hash glyph must render at heading font size", byteZeroHasHeadingSize)
        assertTrue("hash glyph must render dim", byteZeroIsDim)
    }

    // ---------------------------------------------------------------------
    // Lists / blockquotes / code blocks - markers stay VISIBLE and dim
    // ---------------------------------------------------------------------

    @Test
    fun unorderedListMarker_remainsVisible() {
        val source = "- item"
        val result = filter(source)
        assertEquals(source, result.text.text)
        assertIdentity(result)
        assertTrue(
            "bullet must be dim",
            result.hasSpanOver(0, 1) { it.color == styles.dimColor },
        )
    }

    @Test
    fun orderedListMarker_remainsVisible() {
        val source = "1. item"
        val result = filter(source)
        assertEquals(source, result.text.text)
        assertIdentity(result)
        assertTrue(
            "ordered list marker must be dim",
            result.hasSpanOver(0, 2) { it.color == styles.dimColor },
        )
    }

    @Test
    fun blockquoteLeader_remainsVisible() {
        val source = "> quoted"
        val result = filter(source)
        assertEquals(source, result.text.text)
        assertIdentity(result)
        assertTrue(
            "blockquote '>' leader must be dim",
            result.hasSpanOver(0, 1) { it.color == styles.dimColor },
        )
    }

    @Test
    fun fencedCodeBlock_passesThroughWithMonospaceAndDimFences() {
        val source = "```\nhello\n```"
        val result = filter(source)
        assertEquals(source, result.text.text)
        assertIdentity(result)
        // Whole block is monospace.
        assertNotNull(
            "expected a Monospace span over the fenced block",
            result.findSpan { it.fontFamily == FontFamily.Monospace },
        )
        // The opening fence row ("```" at [0, 3)) must be dim.
        assertTrue(
            "expected dim span over the opening fence row",
            result.hasSpanOver(0, 3) { it.color == styles.dimColor },
        )
    }

    @Test
    fun indentedCodeBlock_passesThroughWithMonospaceStyling() {
        val source = "    code\n"
        val result = filter(source)
        assertEquals(source, result.text.text)
        assertIdentity(result)
        assertNotNull(
            "expected a Monospace span over the indented code block",
            result.findSpan { it.fontFamily == FontFamily.Monospace },
        )
    }

    @Test
    fun gfmTable_passesThroughAsRawSource() {
        val source = "| a | b |\n|---|---|\n| 1 | 2 |\n"
        val result = filter(source)
        assertEquals(source, result.text.text)
        assertIdentity(result)
    }

    @Test
    fun horizontalRule_passesThroughAndDimmed() {
        val source = "---"
        val result = filter(source)
        assertEquals(source, result.text.text)
        assertIdentity(result)
        assertTrue(
            "horizontal rule must be dim",
            result.hasSpanOver(0, 3) { it.color == styles.dimColor },
        )
    }

    // ---------------------------------------------------------------------
    // Links - brackets / parens / URL dim, label gets linkColor + Underline
    // ---------------------------------------------------------------------

    @Test
    fun inlineLink_keepsBracketsParensAndUrlVisibleAndDimmed() {
        val source = "[label](https://example.test)"
        val result = filter(source)
        assertEquals(source, result.text.text)
        assertIdentity(result)
        // Label content at [1, 6) gets linkColor + Underline.
        assertTrue(
            "expected Underline over LINK_TEXT",
            result.hasSpanOver(1, 6) { it.textDecoration == TextDecoration.Underline },
        )
        assertTrue(
            "expected linkColor over LINK_TEXT",
            result.hasSpanOver(1, 6) { it.color == styles.linkColor },
        )
        // Brackets and parens dim.
        assertTrue("'[' dim", result.hasSpanOver(0, 1) { it.color == styles.dimColor })
        assertTrue("']' dim", result.hasSpanOver(6, 7) { it.color == styles.dimColor })
        assertTrue("'(' dim", result.hasSpanOver(7, 8) { it.color == styles.dimColor })
        assertTrue(
            "')' dim",
            result.hasSpanOver(source.length - 1, source.length) { it.color == styles.dimColor },
        )
    }

    @Test
    fun linkDestination_rendersDim_notLinkColor() {
        // The URL bytes inside the parens of `[label](url)` are EDITING-ONLY metadata; they must
        // render dim, NOT under linkColor+Underline.
        val source = "[label](https://example.test)"
        val result = filter(source)
        // URL bytes are at [8, 28) - "https://example.test".
        val urlStart = 8
        val urlEnd = source.length - 1
        // Dim spans must cover URL bytes.
        assertTrue(
            "URL bytes must be dim",
            result.hasSpanOver(urlStart, urlEnd) { it.color == styles.dimColor },
        )
        // No linkColor span should cover URL bytes (linkColor is reserved for the label).
        val linkColorSpanOverUrl = result.text.spanStyles.firstOrNull {
            it.start <= urlStart &&
                it.end >= urlEnd &&
                it.item.color == styles.linkColor
        }
        assertEquals(
            "URL must not be colored as a link (linkColor reserved for LINK_TEXT)",
            null,
            linkColorSpanOverUrl,
        )
    }

    @Test
    fun gfmBareAutolink_passesThroughWithLinkStyle() {
        val source = "https://example.test"
        val result = filter(source)
        assertEquals(source, result.text.text)
        assertIdentity(result)
        assertTrue(
            "expected Underline over the bare URL",
            result.hasSpanOver(0, source.length) { it.textDecoration == TextDecoration.Underline },
        )
        assertTrue(
            "expected linkColor over the bare URL",
            result.hasSpanOver(0, source.length) { it.color == styles.linkColor },
        )
    }

    @Test
    fun autolink_angleBracketsAreDim_urlGetsLinkStyle() {
        val source = "<https://example.test>"
        val result = filter(source)
        assertEquals(source, result.text.text)
        assertIdentity(result)
        // Angle brackets at [0, 1) and [source.length-1, source.length) must be dim.
        assertTrue(
            "expected dim '<'",
            result.hasSpanOver(0, 1) { it.color == styles.dimColor },
        )
        assertTrue(
            "expected dim '>'",
            result.hasSpanOver(source.length - 1, source.length) { it.color == styles.dimColor },
        )
        // The URL between the angle brackets gets link styling.
        assertTrue(
            "URL inside <> must have Underline",
            result.hasSpanOver(1, source.length - 1) {
                it.textDecoration == TextDecoration.Underline
            },
        )
    }

    // ---------------------------------------------------------------------
    // Image - rendered verbatim with dim markers, italic alt, dim URL
    // ---------------------------------------------------------------------

    @Test
    fun imageSyntax_keepsSourceVisibleWithDimMarkers() {
        val source = "![alt text](https://example.test/img.png)"
        val result = filter(source)
        assertEquals(source, result.text.text)
        assertIdentity(result)
        // '!' at byte 0 must be dim.
        assertTrue(
            "expected dim '!' marker",
            result.hasSpanOver(0, 1) { it.color == styles.dimColor },
        )
        // '[' at byte 1 dim; ']' at byte 10 dim; '(' at byte 11 dim; ')' at the last byte dim.
        assertTrue(result.hasSpanOver(1, 2) { it.color == styles.dimColor })
        assertTrue(result.hasSpanOver(10, 11) { it.color == styles.dimColor })
        assertTrue(result.hasSpanOver(11, 12) { it.color == styles.dimColor })
        assertTrue(
            result.hasSpanOver(source.length - 1, source.length) { it.color == styles.dimColor },
        )
    }

    @Test
    fun imageAltText_rendersItalic_notUnderlined() {
        // Image alt text must visually distinguish from a real link's underlined LINK_TEXT.
        val source = "![alt text](https://example.test/img.png)"
        val result = filter(source)
        // Alt bytes are at [2, 10) - "alt text".
        assertTrue(
            "expected Italic span over alt text",
            result.hasSpanOver(2, 10) { it.fontStyle == FontStyle.Italic },
        )
        // Alt text must NOT have Underline (Underline is reserved for real link text).
        val underlineOverAlt = result.text.spanStyles.firstOrNull {
            it.start <= 2 &&
                it.end >= 10 &&
                it.item.textDecoration == TextDecoration.Underline
        }
        assertEquals(
            "image alt text must NOT be underlined (would falsely promise a link)",
            null,
            underlineOverAlt,
        )
    }

    @Test
    fun imageWithoutAlt_rendersBareBracketsDimmed() {
        // The `[image: alt]` substitution no longer exists. `![](url)` renders verbatim.
        val source = "![](https://example.test/img.png)"
        val result = filter(source)
        assertEquals(source, result.text.text)
        assertIdentity(result)
        // '!', '[', ']' all dim.
        assertTrue(result.hasSpanOver(0, 1) { it.color == styles.dimColor })
        assertTrue(result.hasSpanOver(1, 2) { it.color == styles.dimColor })
        assertTrue(result.hasSpanOver(2, 3) { it.color == styles.dimColor })
    }

    // ---------------------------------------------------------------------
    // HTML / raw passthrough - bytes pass with no styling
    // ---------------------------------------------------------------------

    @Test
    fun htmlTag_passesThroughVerbatim() {
        val source = "<b>x</b>"
        val result = filter(source)
        assertEquals(source, result.text.text)
        assertIdentity(result)
    }

    // ---------------------------------------------------------------------
    // Identity round-trip - every offset survives in/out under the new model
    // ---------------------------------------------------------------------

    @Test
    fun identityMapping_roundTripsAtEveryOffset() {
        // Pick a source with every flavour of marker so the identity invariant is exercised
        // across the union of code paths.
        val source = "# Hi\n\nThis is **bold**, _ital_, ~~gone~~, `code` and [l](u)."
        val result = filter(source)
        assertEquals(source, result.text.text)
        assertIdentity(result)
        for (i in 0..source.length) {
            assertEquals(
                "originalToTransformed at $i must be identity",
                i,
                result.offsetMapping.originalToTransformed(i),
            )
            assertEquals(
                "transformedToOriginal at $i must be identity",
                i,
                result.offsetMapping.transformedToOriginal(i),
            )
        }
    }

    // ---------------------------------------------------------------------
    // IME crash regression - identity mapping prevents transformedToOriginal bugs
    // ---------------------------------------------------------------------

    /**
     * The old hidden-markers transformation produced an OffsetMapping with non-identity
     * round-trips, which caused IME crashes when the keyboard requested a transformed offset for
     * a source offset INSIDE a hidden marker run. Under the new model the mapping is always
     * Identity, so the IME bug cannot fire regardless of where the cursor sits inside a marker
     * region.
     *
     * This suite picks a representative cursor position INSIDE each marker variant and verifies
     * the transformation produces an Identity mapping at that position. The check is exhaustive
     * across one offset per variant rather than per offset because Identity is uniform across the
     * whole domain - if it holds at one offset for a given source, it holds at every offset.
     */
    @Test
    fun cursorInsideMarker_yieldsIdentityMapping_noImeCrash() {
        data class Case(val name: String, val source: String, val cursor: Int)

        val cases = listOf(
            Case("bold opening marker", "**bold**", 1),
            Case("bold closing marker", "**bold**", 7),
            Case("italic asterisk opening", "*ital*", 0),
            Case("italic underscore opening", "_ital_", 0),
            Case("strike opening", "~~gone~~", 1),
            Case("strike closing", "~~gone~~", 7),
            Case("code span opening backtick", "`code`", 0),
            Case("code span closing backtick", "`code`", 5),
            Case("inline link open bracket", "[label](url)", 0),
            Case("inline link close bracket", "[label](url)", 6),
            Case("inline link open paren", "[label](url)", 7),
            Case("inline link close paren", "[label](url)", 11),
            Case("inline link URL byte", "[label](url)", 9),
            Case("image '!' marker", "![alt](url)", 0),
            Case("image '[' marker", "![alt](url)", 1),
            Case("image ']' marker", "![alt](url)", 5),
            Case("image '(' marker", "![alt](url)", 6),
            Case("image ')' marker", "![alt](url)", 10),
            Case("image URL byte", "![alt](url)", 8),
            Case("atx heading hash", "# Title", 0),
            Case("atx h6 hash run", "###### h", 3),
            Case("blockquote leader", "> quoted", 0),
            Case("unordered list bullet", "- item", 0),
            Case("ordered list marker", "1. item", 0),
            Case("fenced code opening fence", "```\nx\n```", 1),
            Case("fenced code closing fence", "```\nx\n```", 7),
            Case("setext h1 underline", "Title\n=====", 7),
            Case("setext h2 underline", "Title\n-----", 7),
            Case("autolink open angle", "<https://x.test>", 0),
            Case("autolink close angle", "<https://x.test>", 15),
            Case("reference link short label", "[ref]", 1),
            Case("html tag", "<b>x</b>", 0),
            Case("horizontal rule", "---", 1),
        )

        for (case in cases) {
            val tfv = TextFieldValue(
                text = case.source,
                selection = TextRange(case.cursor),
            )
            val result = transformation.filter(AnnotatedString(tfv.text))
            // Identity invariant: every offset round-trips exactly.
            assertSame(
                "${case.name}: offsetMapping must be Identity",
                OffsetMapping.Identity,
                result.offsetMapping,
            )
            // The cursor offset specifically must round-trip cleanly via both directions.
            assertEquals(
                "${case.name}: originalToTransformed at cursor must be identity",
                case.cursor,
                result.offsetMapping.originalToTransformed(case.cursor),
            )
            assertEquals(
                "${case.name}: transformedToOriginal at cursor must be identity",
                case.cursor,
                result.offsetMapping.transformedToOriginal(case.cursor),
            )
            // Endpoint must also be identity (an IME often probes both endpoints).
            assertEquals(
                "${case.name}: originalToTransformed at end must be identity",
                case.source.length,
                result.offsetMapping.originalToTransformed(case.source.length),
            )
            assertEquals(
                "${case.name}: transformedToOriginal at end must be identity",
                case.source.length,
                result.offsetMapping.transformedToOriginal(case.source.length),
            )
        }
    }

    // ---------------------------------------------------------------------
    // Visible AnnotatedString carries the bold span over the right range
    // ---------------------------------------------------------------------

    @Test
    fun visibleAnnotatedString_containsBoldSpanForBalancedSource() {
        val source = "**hello**"
        val result = filter(source)
        assertEquals(source, result.text.text)
        assertIdentity(result)
        // Bold span sits over the visible content range [2, 7) ("hello") in source coordinates.
        val boldSpans = result.text.spanStyles.filter { it.item.fontWeight == FontWeight.Bold }
        assertTrue(
            "expected at least one Bold span covering 'hello' at [2, 7)",
            boldSpans.any { it.start <= 2 && it.end >= 7 },
        )
    }

    // ---------------------------------------------------------------------
    // Equality / hashCode
    // ---------------------------------------------------------------------

    @Test
    fun structuralEquality_holdsForSameStyles() {
        val a = MarkdownEditorTransformation(styles)
        val b = MarkdownEditorTransformation(styles)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun structuralEquality_differsForDifferentStyles() {
        val a = MarkdownEditorTransformation(styles)
        val b = MarkdownEditorTransformation(styles.copy(linkColor = Color.Red))
        assertNotEquals(a, b)
    }

    // ---------------------------------------------------------------------
    // Multi-block source - source preserved verbatim, dim spans cover all markers
    // ---------------------------------------------------------------------

    @Test
    fun mixedMultilineSource_keepsAllMarkersVisible_andPreservesPlainText() {
        val source = "# Hi\n\nThis is **bold** and _ital_.\n"
        val result = filter(source)
        // Source preserved verbatim.
        assertEquals(source, result.text.text)
        assertIdentity(result)
        // Plain content survives at the original offsets.
        assertTrue(result.text.text.contains("Hi"))
        assertTrue(result.text.text.contains("This is **bold** and _ital_."))
        // Dim spans cover the marker runs:
        // '# ' at [0, 2), '**' at [14, 16), '**' at [20, 22), '_' at [27, 28), '_' at [32, 33).
        assertTrue(
            "expected dim '# '",
            result.hasSpanOver(0, 2) { it.color == styles.dimColor },
        )
        assertTrue(
            "expected dim opening '**'",
            result.hasSpanOver(14, 16) { it.color == styles.dimColor },
        )
        assertTrue(
            "expected dim closing '**'",
            result.hasSpanOver(20, 22) { it.color == styles.dimColor },
        )
        assertTrue(
            "expected dim opening '_'",
            result.hasSpanOver(27, 28) { it.color == styles.dimColor },
        )
        assertTrue(
            "expected dim closing '_'",
            result.hasSpanOver(32, 33) { it.color == styles.dimColor },
        )
    }

    // ---------------------------------------------------------------------
    // Single-slot memo (transformation parses once per text)
    // ---------------------------------------------------------------------

    @Test
    fun filterMemo_returnsSameInstance_forRepeatedCalls() {
        val source = "**bold**"
        val a = transformation.filter(AnnotatedString(source))
        val b = transformation.filter(AnnotatedString(source))
        // The memo returns the SAME TransformedText reference for repeated calls with the same
        // source string. That's the primitive Compose's CoreTextField relies on for cache hits.
        assertSame(a, b)
    }
}

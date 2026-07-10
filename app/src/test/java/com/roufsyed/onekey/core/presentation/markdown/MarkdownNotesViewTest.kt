package com.roufsyed.onekey.core.presentation.markdown

import android.app.Application
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Behavioural locks for the markdown renderer surface.
 *
 * Tests exercise the two pure entry points - [parseSource] (the AST gate) and
 * [buildInline] (the inline AnnotatedString assembler) - so they run under
 * Robolectric without needing a Compose tree. The block-level dispatcher is
 * Composable-only; this suite verifies it parses correctly and that inline
 * content reachable from inside each block type round-trips through
 * [buildInline] without throwing.
 *
 * Robolectric is required because the parser indirectly touches stdlib paths
 * that need the Android stub for `android.net.Uri` once a link URL flows
 * through the test helpers below.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class MarkdownNotesViewTest {

    /**
     * Fixed inline styling bundle so tests are independent of any
     * MaterialTheme provisioning. The Color values are arbitrary - only
     * structural identity matters for the span-style assertions below.
     */
    private val styles = MarkdownInlineStyles(
        linkColor = Color(0xFF1976D2),
        dimColor = Color(0xFF666666),
        codeBackground = Color(0xFFEEEEEE),
    )

    private fun parse(source: String): ASTNode =
        MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(source)

    /**
     * Recursively walks [root] looking for a node whose IElementType identity
     * matches [target]. Returns the first such node in pre-order or null if
     * none exists. Used by inline-element tests to pick the right host node
     * for [buildInline].
     */
    private fun findNode(root: ASTNode, target: org.intellij.markdown.IElementType): ASTNode? {
        if (root.type === target) return root
        for (child in root.children) {
            val found = findNode(child, target)
            if (found != null) return found
        }
        return null
    }

    /**
     * Convenience: parse [source] and return the first PARAGRAPH AST node.
     * Most inline tests live inside a paragraph; this trims boilerplate.
     */
    private fun firstParagraph(source: String): ASTNode {
        val root = parse(source)
        return findNode(root, MarkdownElementTypes.PARAGRAPH)
            ?: error("no PARAGRAPH found in source: $source")
    }

    private fun inlineOf(source: String): AnnotatedString {
        val paragraph = firstParagraph(source)
        return buildInline(paragraph, source, styles)
    }

    // ---- parseSource: empty / whitespace ------------------------------------

    @Test
    fun parseSource_empty_returnsPlain() {
        assertTrue(parseSource("") is ParsedNotes.Plain)
    }

    @Test
    fun parseSource_whitespaceOnly_doesNotThrow() {
        // Whitespace is non-empty so it parses; the AST is a MARKDOWN_FILE
        // with a single whitespace token, which the dispatcher handles via
        // the unknown-block fallback. The contract: parseSource never throws
        // on this input and returns either Plain (if the parser rejected it)
        // or Tree (if the parser produced a valid AST).
        val result = runCatching { parseSource("   \t  \n") }
        assertTrue(
            "whitespace-only must not throw, got: ${result.exceptionOrNull()}",
            result.isSuccess,
        )
    }

    @Test
    fun parseSource_overCap_returnsPlain() {
        // 64 KiB cap + 1; the empirical cap from MarkdownNotesView's KDoc.
        val source = "a".repeat(64 * 1024 + 1)
        assertTrue(parseSource(source) is ParsedNotes.Plain)
    }

    @Test
    fun parseSource_smallValidSource_returnsTree() {
        val parsed = parseSource("# hello\n\nworld")
        assertTrue(parsed is ParsedNotes.Tree)
    }

    // ---- onFallback contract: classification of Plain returns ---------------
    //
    // The composable's onFallback callback is wired through `parsed.reason`
    // inside a `LaunchedEffect(reason)` block. The unit-test classpath lacks
    // a Compose harness so we cannot invoke the @Composable directly; instead
    // we pin the upstream contract: parseSource returns a Plain whose reason
    // field matches the fallback branch that produced it. The composable's
    // LaunchedEffect then trivially forwards `parsed.reason` to `onFallback`,
    // emitting once per reason transition.

    @Test
    fun parseSource_empty_plainReasonIsNull_soOnFallbackWouldNotFire() {
        // Empty input is not a fallback condition; the callback contract
        // says empty notes simply render nothing and onFallback stays silent.
        // The Plain.reason = null is the signal the composable checks.
        val recordedReasons = mutableListOf<FallbackReason>()
        val parsed = parseSource("") as ParsedNotes.Plain
        assertNull(
            "empty source must carry reason=null so onFallback is not invoked",
            parsed.reason,
        )
        // Simulate the composable's emission rule: only call when reason != null.
        parsed.reason?.let { recordedReasons += it }
        assertTrue(
            "no fallback callback must fire for empty input; got: $recordedReasons",
            recordedReasons.isEmpty(),
        )
    }

    @Test
    fun parseSource_overCap_plainReasonIsSizeCapExceeded() {
        // The 64 KiB branch must classify as SIZE_CAP_EXCEEDED so the
        // detail screen can drive its "exceeded 64 KiB" banner.
        val recordedReasons = mutableListOf<FallbackReason>()
        val source = "a".repeat(64 * 1024 + 1)
        val parsed = parseSource(source) as ParsedNotes.Plain
        assertEquals(FallbackReason.SIZE_CAP_EXCEEDED, parsed.reason)
        // Simulate the composable's emission rule:
        parsed.reason?.let { recordedReasons += it }
        assertEquals(listOf(FallbackReason.SIZE_CAP_EXCEEDED), recordedReasons)
    }

    @Test
    fun parseSource_smallValid_treeBranch_doesNotFireFallback() {
        // Defensive: a valid parse never produces a Plain so the composable's
        // LaunchedEffect is structurally unreachable. recordedReasons stays
        // empty by construction.
        val recordedReasons = mutableListOf<FallbackReason>()
        val parsed = parseSource("# hello\n\nworld")
        assertTrue(parsed is ParsedNotes.Tree)
        // No reason field exists on Tree; the composable's `when` branch
        // skips the LaunchedEffect path entirely.
        assertTrue(
            "no fallback callback must fire for a successfully parsed source",
            recordedReasons.isEmpty(),
        )
    }

    @Test
    fun fallbackReason_enumValues_pinSurface() {
        // The public enum is consumed by callers outside this package; pin
        // the value set so a future rename / addition triggers a deliberate
        // review. Two values today.
        val values = FallbackReason.values().toList()
        assertEquals(2, values.size)
        assertTrue(values.contains(FallbackReason.SIZE_CAP_EXCEEDED))
        assertTrue(values.contains(FallbackReason.PARSE_FAILED))
    }

    // ---- Inline elements ----------------------------------------------------

    @Test
    fun inline_plainText_carriesNoSpans() {
        val annotated = inlineOf("just a plain paragraph")
        assertEquals("just a plain paragraph", annotated.text)
        assertTrue(
            "plain text must not have decorative spans",
            annotated.spanStyles.isEmpty(),
        )
        assertTrue(
            "plain text must not carry URL annotations",
            annotated.getStringAnnotations(
                URL_ANNOTATION_TAG, 0, annotated.length,
            ).isEmpty(),
        )
    }

    @Test
    fun inline_italic_appliesItalicSpan() {
        val annotated = inlineOf("This is *italic text*.")
        val italicSpans = annotated.spanStyles.filter {
            it.item.fontStyle == FontStyle.Italic
        }
        assertFalse("expected an italic span", italicSpans.isEmpty())
        // Boundary asterisks must NOT appear in the rendered text.
        assertFalse(
            "EMPH delimiter * leaves must be filtered: '${annotated.text}'",
            annotated.text.contains("*"),
        )
        assertTrue(annotated.text.contains("italic text"))
    }

    @Test
    fun inline_bold_appliesBoldSpan() {
        val annotated = inlineOf("This is **bold text**.")
        val boldSpans = annotated.spanStyles.filter {
            it.item.fontWeight == FontWeight.Bold
        }
        assertFalse("expected a bold span", boldSpans.isEmpty())
        assertFalse(
            "STRONG delimiter ** must be filtered: '${annotated.text}'",
            annotated.text.contains("*"),
        )
        assertTrue(annotated.text.contains("bold text"))
    }

    @Test
    fun inline_strikethrough_appliesLineThroughSpan() {
        val annotated = inlineOf("Some ~~struck text~~ here.")
        val struck = annotated.spanStyles.filter {
            it.item.textDecoration == TextDecoration.LineThrough
        }
        assertFalse(
            "expected a LineThrough span for GFM strikethrough",
            struck.isEmpty(),
        )
        assertFalse(
            "tilde delimiters must be filtered: '${annotated.text}'",
            annotated.text.contains("~"),
        )
        assertTrue(annotated.text.contains("struck text"))
    }

    @Test
    fun inline_codeSpan_appliesMonospaceAndBackground() {
        val annotated = inlineOf("Run `format()` now.")
        val code = annotated.spanStyles.filter {
            it.item.fontFamily == FontFamily.Monospace
        }
        assertFalse("expected a monospace span for inline code", code.isEmpty())
        assertTrue(
            "code span must carry the theme code-background color",
            code.any { it.item.background == styles.codeBackground },
        )
        assertFalse(
            "backtick delimiters must be filtered: '${annotated.text}'",
            annotated.text.contains("`"),
        )
        assertTrue(annotated.text.contains("format()"))
    }

    @Test
    fun inline_inlineLink_attachesUrlAnnotationAndLinkStyling() {
        val source = "Visit [our site](https://example.com/path)."
        val annotated = inlineOf(source)

        val annotations = annotated.getStringAnnotations(
            URL_ANNOTATION_TAG, 0, annotated.length,
        )
        assertEquals(1, annotations.size)
        assertEquals("https://example.com/path", annotations[0].item)

        // The visible run uses the linkColor and an underline.
        val linkSpans = annotated.spanStyles.filter {
            it.item.color == styles.linkColor &&
                it.item.textDecoration == TextDecoration.Underline
        }
        assertFalse("expected a styled link span", linkSpans.isEmpty())
        assertTrue("link text must be rendered", annotated.text.contains("our site"))
        assertFalse(
            "raw URL must not leak into visible text: '${annotated.text}'",
            annotated.text.contains("https://example.com/path"),
        )
    }

    @Test
    fun inline_referenceLink_resolvesAgainstDefinitionMap() {
        val source = """
Here is [a ref][site].

[site]: https://example.test/ref
""".trimIndent()
        val root = parse(source)
        val paragraph = findNode(root, MarkdownElementTypes.PARAGRAPH)
            ?: error("paragraph missing")
        val linkDefs = buildLinkDefinitionMap(root, source)
        val annotated = buildInline(paragraph, source, styles, linkDefs)
        val annotations = annotated.getStringAnnotations(
            URL_ANNOTATION_TAG, 0, annotated.length,
        )
        assertEquals(1, annotations.size)
        assertEquals("https://example.test/ref", annotations[0].item)
    }

    @Test
    fun inline_autolink_capturesUrl() {
        val annotated = inlineOf("Visit <https://example.com> now.")
        val annotations = annotated.getStringAnnotations(
            URL_ANNOTATION_TAG, 0, annotated.length,
        )
        assertEquals(1, annotations.size)
        assertEquals("https://example.com", annotations[0].item)
        assertFalse(
            "autolink brackets must be stripped from visible run: '${annotated.text}'",
            annotated.text.contains("<https://"),
        )
    }

    @Test
    fun inline_bareGfmAutolink_capturesUrl() {
        val annotated = inlineOf("see https://example.test/bare for details")
        val annotations = annotated.getStringAnnotations(
            URL_ANNOTATION_TAG, 0, annotated.length,
        )
        assertEquals(1, annotations.size)
        assertEquals("https://example.test/bare", annotations[0].item)
    }

    @Test
    fun inline_image_rendersLiteralPlaceholderWithItalicDim() {
        val annotated = inlineOf("![sunset](https://example.test/p.png) caption")
        assertTrue(
            "image must render as literal placeholder, got: '${annotated.text}'",
            annotated.text.contains("[image: sunset]"),
        )
        // The URL must NEVER appear as a string annotation - no image-loader
        // path is allowed to discover it.
        val anyUrlAnnotation = annotated.getStringAnnotations(
            URL_ANNOTATION_TAG, 0, annotated.length,
        )
        assertTrue(
            "image must not push a URL annotation",
            anyUrlAnnotation.isEmpty(),
        )
        val italicDimSpans = annotated.spanStyles.filter {
            it.item.fontStyle == FontStyle.Italic && it.item.color == styles.dimColor
        }
        assertFalse(
            "image placeholder must be italic + dimColor",
            italicDimSpans.isEmpty(),
        )
    }

    @Test
    fun inline_image_withoutAlt_collapsesPlaceholder() {
        val annotated = inlineOf("![](https://example.test/p.png)")
        assertTrue(
            "blank alt must render as [image] only, got: '${annotated.text}'",
            annotated.text.contains("[image]"),
        )
        assertFalse(annotated.text.contains("[image:"))
    }

    @Test
    fun inline_hardLineBreak_emitsNewline() {
        // Two trailing spaces before the EOL is the CommonMark hard-break form.
        val annotated = inlineOf("line one  \nline two")
        assertTrue(
            "hard break must produce a newline in the visible run: '${annotated.text}'",
            annotated.text.contains("\n"),
        )
    }

    @Test
    fun inline_htmlTag_appendedVerbatim() {
        val annotated = inlineOf("hello <b>world</b> end")
        // Tags flow through as literal text - the angle brackets are part of
        // the visible run.
        assertTrue(
            "inline HTML tag must be literal: '${annotated.text}'",
            annotated.text.contains("<b>"),
        )
        assertTrue(
            "closing tag must also be literal: '${annotated.text}'",
            annotated.text.contains("</b>"),
        )
        // And critically: NO bold SpanStyle covers the visible "world" region
        // - the renderer must not interpret the <b> tag as markdown bold.
        val worldStart = annotated.text.indexOf("world")
        val worldEnd = worldStart + "world".length
        val boldOverlap = annotated.spanStyles.any { range ->
            range.item.fontWeight == FontWeight.Bold &&
                range.end > worldStart && range.start < worldEnd
        }
        assertFalse("HTML tag must not synthesise a bold SpanStyle", boldOverlap)
    }

    // ---- parseSource on each block element -------------------------------

    @Test
    fun block_heading_parsesAsAtx() {
        val parsed = parseSource("# Heading One")
        assertTrue(parsed is ParsedNotes.Tree)
        val tree = parsed as ParsedNotes.Tree
        assertNotNull("ATX_1 must be in the tree", findNode(tree.root, MarkdownElementTypes.ATX_1))
    }

    @Test
    fun block_setextHeading_parses() {
        val source = "Heading\n=======\n"
        val parsed = parseSource(source)
        assertTrue(parsed is ParsedNotes.Tree)
        val tree = parsed as ParsedNotes.Tree
        assertNotNull(findNode(tree.root, MarkdownElementTypes.SETEXT_1))
    }

    @Test
    fun block_unorderedList_parses() {
        val parsed = parseSource("- one\n- two\n- three\n")
        assertTrue(parsed is ParsedNotes.Tree)
        val tree = parsed as ParsedNotes.Tree
        assertNotNull(findNode(tree.root, MarkdownElementTypes.UNORDERED_LIST))
    }

    @Test
    fun block_orderedList_parses() {
        val parsed = parseSource("1. one\n2. two\n3. three\n")
        assertTrue(parsed is ParsedNotes.Tree)
        val tree = parsed as ParsedNotes.Tree
        assertNotNull(findNode(tree.root, MarkdownElementTypes.ORDERED_LIST))
    }

    @Test
    fun block_blockquote_parses() {
        val parsed = parseSource("> quoted text\n")
        assertTrue(parsed is ParsedNotes.Tree)
        val tree = parsed as ParsedNotes.Tree
        assertNotNull(findNode(tree.root, MarkdownElementTypes.BLOCK_QUOTE))
    }

    @Test
    fun block_codeFence_parses() {
        val source = "```kotlin\nval x = 1\n```\n"
        val parsed = parseSource(source)
        assertTrue(parsed is ParsedNotes.Tree)
        val tree = parsed as ParsedNotes.Tree
        val fence = findNode(tree.root, MarkdownElementTypes.CODE_FENCE)
        assertNotNull(fence)
        val content = extractCodeFenceContent(fence!!, source)
        assertTrue(
            "fence content must contain the code: '$content'",
            content.contains("val x = 1"),
        )
    }

    @Test
    fun block_indentedCode_parses() {
        val source = "    line one\n    line two\n"
        val parsed = parseSource(source)
        assertTrue(parsed is ParsedNotes.Tree)
        val tree = parsed as ParsedNotes.Tree
        assertNotNull(findNode(tree.root, MarkdownElementTypes.CODE_BLOCK))
    }

    @Test
    fun block_horizontalRule_parses() {
        val parsed = parseSource("text\n\n---\n\nmore text\n")
        assertTrue(parsed is ParsedNotes.Tree)
        val tree = parsed as ParsedNotes.Tree
        assertNotNull(findNode(tree.root, MarkdownTokenTypes.HORIZONTAL_RULE))
    }

    @Test
    fun block_htmlBlock_parses() {
        // HTML must parse as HTML_BLOCK so the renderer can route it through
        // the literal-text path. NOT as a paragraph that interpolates the
        // tag (that would be a security regression).
        val parsed = parseSource("<div>raw html</div>\n")
        assertTrue(parsed is ParsedNotes.Tree)
        val tree = parsed as ParsedNotes.Tree
        assertNotNull(
            "raw HTML must produce an HTML_BLOCK node",
            findNode(tree.root, MarkdownElementTypes.HTML_BLOCK),
        )
    }

    @Test
    fun block_gfmTable_parses() {
        val source = """
| H1 | H2 |
|----|----|
| a  | b  |
""".trimIndent()
        val parsed = parseSource(source)
        assertTrue(parsed is ParsedNotes.Tree)
        val tree = parsed as ParsedNotes.Tree
        assertNotNull(findNode(tree.root, GFMElementTypes.TABLE))
    }

    // ---- buildLinkDefinitionMap behaviour ----------------------------------

    @Test
    fun linkDefinitionMap_isCaseInsensitive() {
        val source = """
[Visit][SITE]

[site]: https://example.test/ref
""".trimIndent()
        val root = parse(source)
        val defs = buildLinkDefinitionMap(root, source)
        // The lookup key is lowercased + trimmed; the source label is
        // "SITE" (upper) but the map should have "site".
        assertEquals("https://example.test/ref", defs["site"])
    }

    @Test
    fun linkDefinitionMap_missingLabel_yieldsNullLookup() {
        val root = parse("Just a paragraph with no defs.")
        val defs = buildLinkDefinitionMap(root, "Just a paragraph with no defs.")
        assertTrue(defs.isEmpty())
        assertNull(defs["foo"])
    }

    // ---- dispatchLinkTap classification (mirrors SchemeAllowlistUriHandler) -

    @Test
    fun dispatchLinkTap_httpUrl_callsOnTap() {
        val staged = mutableListOf<LinkRequest>()
        val blocked = mutableListOf<String>()
        dispatchLinkTap(
            url = "https://example.com",
            onTap = { staged += it },
            onBlocked = { blocked += it },
        )
        assertEquals(1, staged.size)
        assertEquals(0, blocked.size)
    }

    @Test
    fun dispatchLinkTap_javascriptScheme_callsOnBlocked() {
        val staged = mutableListOf<LinkRequest>()
        val blocked = mutableListOf<String>()
        dispatchLinkTap(
            url = "javascript:alert(1)",
            onTap = { staged += it },
            onBlocked = { blocked += it },
        )
        assertEquals(0, staged.size)
        assertEquals(1, blocked.size)
        assertTrue(blocked[0].contains("javascript"))
    }

    @Test
    fun dispatchLinkTap_emptyUrl_silentNoop() {
        val staged = mutableListOf<LinkRequest>()
        val blocked = mutableListOf<String>()
        dispatchLinkTap(
            url = "   ",
            onTap = { staged += it },
            onBlocked = { blocked += it },
        )
        assertTrue(staged.isEmpty())
        assertTrue(blocked.isEmpty())
    }

    @Test
    fun dispatchLinkTap_bareDomain_normalisesToHttpsAndCallsOnTap() {
        // Hand-typed `[label](google.com)` in the markdown source must open
        // `https://google.com` on tap (matching the dialog's normalize). Without
        // the normalize step in dispatchLinkTap this would route to onBlocked with
        // "unknown scheme" - the symptom the user reported.
        val staged = mutableListOf<LinkRequest>()
        val blocked = mutableListOf<String>()
        dispatchLinkTap(
            url = "google.com",
            onTap = { staged += it },
            onBlocked = { blocked += it },
        )
        assertEquals(1, staged.size)
        assertEquals(0, blocked.size)
        assertEquals("https://google.com", staged[0].rawUrl)
        assertEquals("google.com", staged[0].parsedUri.host)
    }

    @Test
    fun dispatchLinkTap_relativePath_stillBlocked() {
        // Regression guard: the bare-domain normalise must not accidentally
        // accept paths. `/foo` has no host so there's nothing to open.
        val staged = mutableListOf<LinkRequest>()
        val blocked = mutableListOf<String>()
        dispatchLinkTap(
            url = "/foo/bar",
            onTap = { staged += it },
            onBlocked = { blocked += it },
        )
        assertEquals(0, staged.size)
        assertEquals(1, blocked.size)
    }

    @Test
    fun dispatchLinkTap_percentEncodedScheme_stillBlocked() {
        // Smuggle-defence regression guard: `javascript%3Aalert(1)` must stay
        // blocked at click time even after the bare-domain normalise rolled out.
        val staged = mutableListOf<LinkRequest>()
        val blocked = mutableListOf<String>()
        dispatchLinkTap(
            url = "javascript%3Aalert(1)",
            onTap = { staged += it },
            onBlocked = { blocked += it },
        )
        assertEquals(0, staged.size)
        assertEquals(1, blocked.size)
    }
}

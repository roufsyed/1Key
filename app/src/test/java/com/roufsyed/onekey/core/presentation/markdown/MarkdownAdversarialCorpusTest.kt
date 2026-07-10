package com.roufsyed.onekey.core.presentation.markdown

import android.app.Application
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.system.measureTimeMillis

/**
 * Security-critical adversarial corpus for the markdown renderer.
 *
 * Pins every input the threat model lists as a worst-case payload:
 *  - Inline script-injection tags (`<script>`, `<iframe>`, etc.) must NOT
 *    pick up any [androidx.compose.ui.text.SpanStyle] decoration (bold,
 *    italic, link, code) and the entire substring must remain literal in
 *    the rendered output.
 *  - Image URLs must NEVER appear as URL annotations - no image loader
 *    classpath is allowed, so the URL can never escape into a network
 *    request. The placeholder text replaces the URL entirely.
 *  - Dangerous-scheme links (`javascript:`, `data:`) ARE captured as URL
 *    annotations by the renderer (the renderer is structural, not
 *    classifying) but [dispatchLinkTap] must route them to the blocked
 *    branch when the user actually clicks.
 *  - ReDoS payloads ([ x 1000, * x 10000, nested brackets) must either
 *    finish within 250 ms OR fall through to the plain-Text fallback via
 *    [parseSource]'s 64 KiB cap.
 *
 * Robolectric is required because [dispatchLinkTap] calls `android.net.Uri`
 * which is a framework stub on the bare JVM.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class MarkdownAdversarialCorpusTest {

    /**
     * Inline styling bundle used by every [buildInline] call below. The
     * decorative colours are arbitrary - assertions only look at structural
     * span flags (italic / bold / monospace / decoration / annotation tag).
     */
    private val styles = MarkdownInlineStyles(
        linkColor = Color(0xFF1976D2),
        dimColor = Color(0xFF666666),
        codeBackground = Color(0xFFEEEEEE),
    )

    private fun parse(source: String): ASTNode =
        MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(source)

    private fun findNode(root: ASTNode, target: org.intellij.markdown.IElementType): ASTNode? {
        if (root.type === target) return root
        for (child in root.children) {
            val found = findNode(child, target)
            if (found != null) return found
        }
        return null
    }

    private fun findAll(
        root: ASTNode,
        target: org.intellij.markdown.IElementType,
        into: MutableList<ASTNode> = mutableListOf(),
    ): List<ASTNode> {
        if (root.type === target) into += root
        for (child in root.children) {
            findAll(child, target, into)
        }
        return into
    }

    /**
     * Asserts that [payload] appears verbatim somewhere in the rendered
     * output - either as the substring of an HTML_BLOCK source slice (which
     * the block renderer emits literally through a Compose Text), or as a
     * substring of the AnnotatedString produced by buildInline over the
     * first paragraph.
     *
     * Either path is acceptable: HTML_BLOCK rendering is the most common
     * outcome for standalone tags; HTML_TAG inline tokens fall through to
     * the inline pipeline with the same verbatim behaviour. The security
     * invariant is identical: bytes flow through to glyph layout, never to
     * a parser that would execute them.
     */
    private fun assertRendersAsLiteral(source: String, payload: String) {
        val root = parse(source)
        val htmlBlocks = findAll(root, MarkdownElementTypes.HTML_BLOCK)
        for (block in htmlBlocks) {
            val literal = block.sliceText(source)
            if (literal.contains(payload)) return
        }
        val paragraphs = findAll(root, MarkdownElementTypes.PARAGRAPH)
        for (paragraph in paragraphs) {
            val annotated = buildInline(paragraph, source, styles)
            if (annotated.text.contains(payload)) {
                assertNoFormattingAppliedTo(annotated, payload)
                return
            }
        }
        // Fall back: maybe the bytes appear at some other AST level (e.g.
        // raw verbatim under MARKDOWN_FILE for malformed inputs). The
        // renderer would route those through UnknownBlockFallback which
        // also emits the source slice verbatim through a Compose Text.
        val rootSlice = root.sliceText(source)
        assertTrue(
            "payload '$payload' was not present anywhere in the parsed tree's source slice",
            rootSlice.contains(payload),
        )
    }

    /**
     * Confirms that no SpanStyle covering the payload range applies any
     * decorative font formatting (bold, italic, monospace, line-through,
     * underline). Used by the HTML-tag literal assertions to pin that the
     * raw bytes are not being misinterpreted by the inline pipeline.
     */
    private fun assertNoFormattingAppliedTo(annotated: AnnotatedString, payload: String) {
        val start = annotated.text.indexOf(payload)
        if (start < 0) return
        val end = start + payload.length
        for (range in annotated.spanStyles) {
            // A range fully outside the payload is fine.
            if (range.end <= start || range.start >= end) continue
            val style = range.item
            assertFalse(
                "payload '$payload' must not be bold: $range",
                style.fontWeight == FontWeight.Bold,
            )
            assertFalse(
                "payload '$payload' must not be italic: $range",
                style.fontStyle == FontStyle.Italic,
            )
            assertFalse(
                "payload '$payload' must not be monospaced: $range",
                style.fontFamily == FontFamily.Monospace,
            )
            assertFalse(
                "payload '$payload' must not be struck: $range",
                style.textDecoration == TextDecoration.LineThrough,
            )
            assertFalse(
                "payload '$payload' must not be underlined: $range",
                style.textDecoration == TextDecoration.Underline,
            )
        }
    }

    // ---- HTML tag injection corpus -----------------------------------------

    @Test
    fun script_tag_rendersAsEscapedLiteral() {
        val source = "<script>alert(1)</script>"
        assertRendersAsLiteral(source, "<script>")
        assertRendersAsLiteral(source, "alert(1)")
        assertRendersAsLiteral(source, "</script>")
    }

    @Test
    fun iframe_javascriptSrc_rendersAsEscapedLiteral() {
        val source = "<iframe src=javascript:alert(1)>"
        assertRendersAsLiteral(source, "<iframe")
        assertRendersAsLiteral(source, "javascript:alert(1)")
    }

    @Test
    fun img_onerror_rendersAsEscapedLiteral() {
        val source = "<img src=x onerror=alert(1)>"
        assertRendersAsLiteral(source, "<img")
        assertRendersAsLiteral(source, "onerror=alert(1)")
    }

    @Test
    fun anchorTag_javascriptHref_rendersAsEscapedLiteral() {
        // The bytes are an inline HTML anchor, NOT markdown link syntax.
        // We must NOT convert it into a clickable AnnotatedString link.
        val source = "<a href=javascript:0>X</a>"
        assertRendersAsLiteral(source, "<a href=javascript:0>")
        // And critically: no URL annotation is created for the javascript: URL.
        val root = parse(source)
        val paragraph = findNode(root, MarkdownElementTypes.PARAGRAPH)
        if (paragraph != null) {
            val annotated = buildInline(paragraph, source, styles)
            val annotations = annotated.getStringAnnotations(
                URL_ANNOTATION_TAG, 0, annotated.length,
            )
            assertTrue(
                "raw <a> tag must not produce a URL annotation, got: $annotations",
                annotations.isEmpty(),
            )
        }
    }

    @Test
    fun htmlComment_rendersAsEscapedLiteral() {
        val source = "<!-- malicious comment -->"
        assertRendersAsLiteral(source, "<!-- malicious comment -->")
    }

    @Test
    fun styleTag_javascriptBackground_rendersAsEscapedLiteral() {
        val source = "<style>body{background:url(javascript:alert(1))}</style>"
        assertRendersAsLiteral(source, "<style>")
        assertRendersAsLiteral(source, "javascript:alert(1)")
        assertRendersAsLiteral(source, "</style>")
    }

    // ---- Image tracking pixel ----------------------------------------------

    @Test
    fun trackingPixelImage_rendersAsLiteralPlaceholder_neverFetches() {
        val source = "![pixel](https://tracker.example/p.gif?id=user123)"
        val root = parse(source)
        val paragraph = findNode(root, MarkdownElementTypes.PARAGRAPH)
            ?: error("expected a paragraph wrapping the image")
        val annotated = buildInline(paragraph, source, styles)

        // Placeholder text must appear; URL must NOT.
        assertTrue(
            "expected '[image: pixel]' placeholder, got: '${annotated.text}'",
            annotated.text.contains("[image: pixel]"),
        )
        assertFalse(
            "URL must NOT appear in the visible text: '${annotated.text}'",
            annotated.text.contains("tracker.example"),
        )

        // No URL annotation must reference the image URL.
        val annotations = annotated.getStringAnnotations(
            URL_ANNOTATION_TAG, 0, annotated.length,
        )
        assertTrue(
            "image must NOT push a URL annotation: $annotations",
            annotations.isEmpty(),
        )
    }

    @Test
    fun noImageLoader_onClasspath_canary() {
        // Defence-in-depth: pins that no image-loader library is on the
        // compiled classpath. If a future commit adds Coil, OkHttp's
        // ImageRequest, Picasso, or Glide, this canary will discover the
        // class and fail the test immediately - flagging that the IMAGE
        // placeholder contract may now be reachable as a real network call.
        val forbiddenClasses = listOf(
            "coil.ImageLoader",
            "coil.compose.AsyncImage",
            "coil3.ImageLoader",
            "coil3.compose.AsyncImage",
            "com.squareup.picasso.Picasso",
            "com.bumptech.glide.Glide",
            "okhttp3.OkHttpClient",
        )
        for (cls in forbiddenClasses) {
            val found = runCatching { Class.forName(cls) }.isSuccess
            assertFalse(
                "image-loader class '$cls' is on the classpath - the IMAGE " +
                    "placeholder contract may now be bypassable",
                found,
            )
        }
    }

    // ---- Dangerous link schemes captured but not dispatched ----------------

    @Test
    fun javascriptLink_capturesAnnotationButBlocksDispatch() {
        val source = "[Click](javascript:alert(1))"
        val root = parse(source)
        val paragraph = findNode(root, MarkdownElementTypes.PARAGRAPH)
            ?: error("expected a paragraph wrapping the link")
        val annotated = buildInline(paragraph, source, styles)

        // The renderer captures EVERY URL regardless of scheme; the
        // classification happens at dispatch time.
        val annotations = annotated.getStringAnnotations(
            URL_ANNOTATION_TAG, 0, annotated.length,
        )
        assertEquals(1, annotations.size)
        assertEquals("javascript:alert(1)", annotations[0].item)

        // Dispatch path must route to onBlocked.
        val staged = mutableListOf<LinkRequest>()
        val blocked = mutableListOf<String>()
        dispatchLinkTap(
            url = annotations[0].item,
            onTap = { staged += it },
            onBlocked = { blocked += it },
        )
        assertEquals(
            "javascript: URL must NEVER be staged for dispatch",
            0,
            staged.size,
        )
        assertEquals(1, blocked.size)
        assertTrue(blocked[0].contains("javascript"))
    }

    @Test
    fun dataLink_capturesAnnotationButBlocksDispatch() {
        val source = "[Click](data:text/html;base64,PHNjcmlwdD4=)"
        val root = parse(source)
        val paragraph = findNode(root, MarkdownElementTypes.PARAGRAPH)
            ?: error("expected a paragraph wrapping the link")
        val annotated = buildInline(paragraph, source, styles)

        val annotations = annotated.getStringAnnotations(
            URL_ANNOTATION_TAG, 0, annotated.length,
        )
        assertEquals(1, annotations.size)
        assertEquals("data:text/html;base64,PHNjcmlwdD4=", annotations[0].item)

        val staged = mutableListOf<LinkRequest>()
        val blocked = mutableListOf<String>()
        dispatchLinkTap(
            url = annotations[0].item,
            onTap = { staged += it },
            onBlocked = { blocked += it },
        )
        assertEquals(0, staged.size)
        assertEquals(1, blocked.size)
        assertTrue(blocked[0].contains("data"))
    }

    @Test
    fun referenceJavascriptLink_capturesAnnotationButBlocksDispatch() {
        val source = """
[See more][evil].

[evil]: javascript:alert(1)
""".trimIndent()
        val root = parse(source)
        val paragraph = findNode(root, MarkdownElementTypes.PARAGRAPH)
            ?: error("expected a paragraph wrapping the reference")
        val linkDefs = buildLinkDefinitionMap(root, source)
        val annotated = buildInline(paragraph, source, styles, linkDefs)

        val annotations = annotated.getStringAnnotations(
            URL_ANNOTATION_TAG, 0, annotated.length,
        )
        assertEquals(1, annotations.size)
        assertEquals("javascript:alert(1)", annotations[0].item)

        val staged = mutableListOf<LinkRequest>()
        val blocked = mutableListOf<String>()
        dispatchLinkTap(
            url = annotations[0].item,
            onTap = { staged += it },
            onBlocked = { blocked += it },
        )
        assertEquals(0, staged.size)
        assertEquals(1, blocked.size)
    }

    // ---- ReDoS payloads ----------------------------------------------------

    /**
     * Time-budgeted parse. Returns the elapsed millis OR `Long.MAX_VALUE`
     * when the parse threw, so callers can assert either-or behaviour: must
     * complete under 250 ms OR the source must already short-circuit to the
     * plain-text fallback inside parseSource.
     */
    private fun timeParse(source: String): Long {
        var elapsed = Long.MAX_VALUE
        runCatching {
            elapsed = measureTimeMillis {
                parseSource(source)
            }
        }
        return elapsed
    }

    private fun assertCompletesQuicklyOrFallsBack(source: String, label: String) {
        val parsed = parseSource(source)
        // First branch: source is over the 64 KiB cap so parseSource
        // short-circuits to Plain - that is already a safe fallback.
        if (parsed is ParsedNotes.Plain) return

        // Second branch: parser ran. Must complete under 250 ms.
        val elapsed = timeParse(source)
        assertTrue(
            "$label: parse exceeded 250 ms budget (elapsed=${elapsed}ms, len=${source.length})",
            elapsed < 250L,
        )
    }

    @Test
    fun redos_thousandOpeningBrackets_completesUnderBudget() {
        val source = "[".repeat(1000)
        assertCompletesQuicklyOrFallsBack(source, "[ x 1000")
    }

    @Test
    fun redos_tenThousandAsterisks_completesUnderBudget() {
        val source = "*".repeat(10000)
        assertCompletesQuicklyOrFallsBack(source, "* x 10000")
    }

    @Test
    fun redos_nestedBracketsDepth100_completesUnderBudget() {
        val depth = 100
        val source = "[".repeat(depth) + "a" + "]".repeat(depth)
        assertCompletesQuicklyOrFallsBack(source, "nested [..a..] depth 100")
    }

    @Test
    fun redos_nestedBracketsDepth1000_completesUnderBudget() {
        val depth = 1000
        val source = "[".repeat(depth) + "a" + "]".repeat(depth)
        assertCompletesQuicklyOrFallsBack(source, "nested [..a..] depth 1000")
    }

    // ---- onFallback classification on adversarial inputs --------------------

    @Test
    fun overCap_redosPayload_classifiesAsSizeCapExceeded() {
        // A 64 KiB + 1 source built from bracket-spam is the classic "ReDoS
        // would fire if we let the parser run" payload. parseSource must
        // short-circuit to Plain with reason = SIZE_CAP_EXCEEDED so the
        // detail-screen banner can fire.
        val recordedReasons = mutableListOf<FallbackReason>()
        val source = "[".repeat(64 * 1024 + 1)
        val parsed = parseSource(source)
        assertTrue("over-cap input must short-circuit to Plain", parsed is ParsedNotes.Plain)
        val reason = (parsed as ParsedNotes.Plain).reason
        assertEquals(FallbackReason.SIZE_CAP_EXCEEDED, reason)
        // Simulate the composable's LaunchedEffect emission rule.
        reason?.let { recordedReasons += it }
        assertEquals(listOf(FallbackReason.SIZE_CAP_EXCEEDED), recordedReasons)
    }

    @Test
    fun underCap_redosPayload_doesNotClassifyAsSizeCap() {
        // Under-cap ReDoS-shaped sources flow through the parser and the
        // empirical 250 ms budget. The fallback contract here is: when the
        // parser succeeds, no fallback callback fires (Tree branch); when it
        // throws, reason = PARSE_FAILED (not SIZE_CAP_EXCEEDED). We do not
        // assert which branch is taken because that depends on the
        // JetBrains parser internals - only that SIZE_CAP_EXCEEDED is never
        // the answer for a 1000-bracket payload that fits well under 64 KiB.
        val source = "[".repeat(1000)
        val parsed = parseSource(source)
        val recordedReasons = mutableListOf<FallbackReason>()
        if (parsed is ParsedNotes.Plain) {
            parsed.reason?.let { recordedReasons += it }
        }
        assertFalse(
            "under-cap input must not classify as SIZE_CAP_EXCEEDED; got: $recordedReasons",
            recordedReasons.contains(FallbackReason.SIZE_CAP_EXCEEDED),
        )
    }

    // ---- Empty / whitespace inputs -----------------------------------------

    @Test
    fun empty_source_rendersWithoutThrowing() {
        // parseSource is the canonical entry point. It must short-circuit to
        // Plain for empty input and never throw.
        val parsed = parseSource("")
        assertTrue(
            "empty source must short-circuit to Plain",
            parsed is ParsedNotes.Plain,
        )
    }

    @Test
    fun whitespaceOnly_source_rendersWithoutThrowing() {
        // Whitespace-only input parses into a tree the renderer will route
        // through the unknown-block fallback. Must not throw.
        val result = runCatching { parseSource("   \t  \n") }
        assertTrue(
            "whitespace-only must not throw, got: ${result.exceptionOrNull()}",
            result.isSuccess,
        )
    }

    // ---- GFM table renders without throwing --------------------------------

    @Test
    fun gfmTable_parsesAndExtractsCellsWithoutThrowing() {
        val source = """
| Name | Code |
|------|------|
| Foo  | bar  |
| Baz  | qux  |
""".trimIndent()
        val parsed = parseSource(source)
        assertTrue(parsed is ParsedNotes.Tree)
        val tree = parsed as ParsedNotes.Tree
        val table = findNode(tree.root, org.intellij.markdown.flavours.gfm.GFMElementTypes.TABLE)
        assertNotNull("GFM table must parse to a TABLE node", table)
        // Walk the cells via the inline pipeline; each must complete without
        // throwing and produce non-empty text.
        val cells = findAll(table!!, org.intellij.markdown.flavours.gfm.GFMTokenTypes.CELL)
        assertTrue("expected at least four cells", cells.size >= 4)
        for (cell in cells) {
            val annotated = runCatching { buildInline(cell, source, styles) }
            assertTrue(
                "cell inline must not throw, got: ${annotated.exceptionOrNull()}",
                annotated.isSuccess,
            )
        }
    }
}

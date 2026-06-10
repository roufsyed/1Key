package com.onekey.core.presentation.markdown

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import org.intellij.markdown.parser.MarkdownParser

/**
 * Hard cap on the source size (UTF-8 bytes) the live-preview transformation is allowed to parse.
 * Above this size [MarkdownEditorTransformation.filter] short-circuits to a pass-through
 * [OffsetMapping.Identity] so the editor stays responsive on pathological pastes. The host editor
 * composable enforces the cap a second time at the [androidx.compose.foundation.text.BasicTextField]
 * `onValueChange` boundary so over-cap text is rejected before it reaches the transformation, but
 * defending the filter in isolation lets it be used standalone (and unit-tested) without the host.
 */
internal const val MARKDOWN_EDITOR_MAX_BYTES: Int = 64 * 1024

/**
 * Immutable per-render styling bundle used by [MarkdownEditorTransformation].
 *
 * Pulled from [androidx.compose.material3.MaterialTheme] inside the host @Composable and threaded
 * down to the transformation so the transformation itself stays a pure (non-composable) value
 * type. Equality is structural so a stable instance composed via `remember(styles)` keeps the
 * transformation reference itself stable across recompositions, which lets Compose's
 * CoreTextField cache layout when the styles do not change.
 *
 * Fields:
 * - [linkColor]: foreground colour for the visible LINK_TEXT run (matches the view renderer).
 * - [dimColor]: muted foreground used for marker glyphs (asterisks, hashes, brackets, parens,
 *   angle brackets, tilde runs, list-item bullets, blockquote leaders, link destinations, etc.)
 *   so they read as secondary content while staying visible.
 * - [codeBackground]: background painted behind inline code spans and fenced code blocks.
 * - [h1Size]..[h6Size]: typographic scale for ATX_n / SETEXT_n headings. The values are the same
 *   ones the view-mode renderer uses (titleLarge / titleMedium / titleSmall / labelLarge); pinning
 *   them as sp values in this bundle keeps the transformation independent of MaterialTheme.
 */
@Immutable
internal data class MarkdownEditorStyles(
    val linkColor: Color,
    val dimColor: Color,
    val codeBackground: Color,
    val h1Size: TextUnit = 22.sp,
    val h2Size: TextUnit = 19.sp,
    val h3Size: TextUnit = 17.sp,
    val h4Size: TextUnit = 16.sp,
    val h5Size: TextUnit = 15.sp,
    val h6Size: TextUnit = 14.sp,
)

/**
 * Live-preview VisualTransformation for the markdown notes editor.
 *
 * Walks the GFM AST produced by JetBrains markdown 0.7.3 and emits a TransformedText whose visible
 * AnnotatedString is the source string verbatim, decorated with SpanStyles that paint balanced
 * markdown markers in [MarkdownEditorStyles.dimColor] and styled content (bold, italic, strike,
 * code, link, headings) in the appropriate semantic style. Single O(N) pass over the source; no
 * I/O. Offset mapping is the trivial [OffsetMapping.Identity] - every source byte is at the same
 * offset in the transformed string.
 *
 * Marker rendering rules (every marker leaf is visible-but-dimmed; unbalanced or in-progress
 * markers stay as plain source bytes by construction because they appear as TEXT leaves outside
 * any composite):
 * - STRONG `**bold**` -> `**` runs dim, inner content gets `SpanStyle(fontWeight = Bold)`.
 * - EMPH `*ital*` or `_ital_` -> markers dim, inner content gets `SpanStyle(fontStyle = Italic)`.
 * - STRIKETHROUGH `~~strike~~` -> markers dim, inner content gets `LineThrough`.
 * - CODE_SPAN `` `code` `` -> backticks dim + monospace, inner content monospace + codeBackground.
 * - ATX heading `# h` .. `###### h` -> the `#` prefix and trailing space are dimmed; the entire
 *   line (markers included) renders at heading font size + SemiBold inside a [ParagraphStyle], so
 *   the dim hash chars sit on the heading baseline without a line-height jog.
 * - SETEXT heading underline `===` / `---` -> dim underline row inherits the heading paragraph
 *   styling so it renders at heading size + dim colour.
 * - INLINE_LINK `[text](url)` -> brackets, parens, and URL bytes dim; the LINK_TEXT content gets
 *   `linkColor + Underline`.
 * - FULL_REFERENCE_LINK `[text][label]` -> LINK_TEXT brackets dim, label segment dim; LINK_TEXT
 *   inner content gets link styling.
 * - SHORT_REFERENCE_LINK `[label]` -> outer brackets dim; inner label gets link styling.
 * - AUTOLINK `<url>` -> angle brackets dim; URL gets link styling.
 * - GFM bare autolink / EMAIL_AUTOLINK -> no markers; URL gets link styling.
 * - IMAGE `![alt](url)` -> `!`, brackets, parens dim; URL dim; alt text gets `Italic` (no
 *   underline) so it reads as image-alt, not real link text.
 * - Bullet `-`/`+`/`*` and ordered `1.` list markers -> dim, visible.
 * - Blockquote `>` leader -> dim, visible.
 * - Fenced code block -> the entire block (fence rows included) gets monospace + codeBackground;
 *   the fence rows are additionally dimmed so the backticks read as syntax.
 * - Indented code block -> monospace + codeBackground; no markers to dim.
 * - HTML_BLOCK / HTML_TAG -> bytes pass through with no styling. Compose Text never interprets
 *   tag bytes, so XSS is structurally impossible.
 * - GFM table -> bytes pass through with no styling.
 * - Horizontal rule `---` / `***` -> dim, visible.
 * - LINK_DEFINITION -> dim, visible.
 *
 * Memoisation: this class is `@Immutable` with structural equality on the styles bundle, so a host
 * that does `remember(styles) { MarkdownEditorTransformation(styles) }` keeps a stable reference
 * across recompositions. Compose's CoreTextField re-runs `filter()` only when the input text or
 * the transformation reference changes; equality on `styles` keeps the reference stable when only
 * unrelated state recomposes.
 *
 * Thread-safety: `filter()` is pure and re-entrant; safe to call on any thread. In practice Compose
 * always calls it on the main thread.
 *
 * Security posture: same invariants as [MarkdownNotesView]: no image loader, no link-follow at
 * edit time (the editor surface only paints; link dispatch lives on the view-mode renderer), HTML
 * tag bytes are glyphs not DOM, IMAGE source bytes are never opened.
 */
@Immutable
internal class MarkdownEditorTransformation(
    private val styles: MarkdownEditorStyles,
) : VisualTransformation {

    /**
     * Single-slot memoisation of the last (source string -> TransformedText) parse. Compose re-
     * runs [filter] any time the rendered text could differ; in practice the same source string
     * arrives multiple times in a single recomposition (once for the text painter, once for the
     * selection manager). The memo turns those repeats into O(1) lookups.
     *
     * The cache only ever holds ONE entry, so it cannot grow without bound. It is also a
     * transparent computation cache: output is fully determined by input, so the class remains
     * value-typed in the [Immutable] sense even though the field is mutable.
     *
     * The memo is correct as long as [styles] (the other input) is final on this instance, which
     * the `private val` constructor parameter guarantees.
     */
    @Volatile
    private var memo: Memo? = null
    private data class Memo(val source: String, val result: TransformedText)

    override fun filter(text: AnnotatedString): TransformedText {
        val cached = memo
        if (cached != null && cached.source == text.text) return cached.result
        val computed = computeFilter(text)
        memo = Memo(text.text, computed)
        return computed
    }

    private fun computeFilter(text: AnnotatedString): TransformedText {
        val source = text.text
        if (source.isEmpty()) return TransformedText(text, OffsetMapping.Identity)
        if (source.toByteArray(Charsets.UTF_8).size > MARKDOWN_EDITOR_MAX_BYTES) {
            return TransformedText(text, OffsetMapping.Identity)
        }
        val root = runCatching {
            MarkdownParser(GFM_FLAVOUR).buildMarkdownTreeFromString(source)
        }.getOrNull() ?: return TransformedText(text, OffsetMapping.Identity)

        val builder = TransformBuilder(source = source, styles = styles)
        builder.consumeBlockChildren(root)
        builder.finishTail(source.length)
        return builder.toTransformedText()
    }

    override fun equals(other: Any?): Boolean =
        other is MarkdownEditorTransformation && other.styles == styles

    override fun hashCode(): Int = styles.hashCode()

    private companion object {
        /** Shared GFM flavour descriptor; stateless so a singleton is safe. */
        private val GFM_FLAVOUR: GFMFlavourDescriptor = GFMFlavourDescriptor()
    }
}

/**
 * Mutable scratch-pad used during a single [MarkdownEditorTransformation.filter] call.
 *
 * - [out] is the AnnotatedString.Builder accumulating the visible representation. Spans are pushed
 *   with `out.pushStyle` / `out.pop` so nested styling composes via Compose's span stack.
 * - [originalCursor] is the next unconsumed source offset (left-to-right). Every emit step
 *   advances this monotonically by exactly the source byte count it consumed; the visible string
 *   length grows in lock-step with the cursor, so source length == transformed length on exit.
 *
 * The builder is single-use: call [consumeBlockChildren] once with the parsed root, then
 * [finishTail] to flush any trailing un-emitted source range, then [toTransformedText].
 */
private class TransformBuilder(
    private val source: String,
    private val styles: MarkdownEditorStyles,
) {
    private val out: AnnotatedString.Builder = AnnotatedString.Builder()
    private var originalCursor: Int = 0

    // ---------------------------------------------------------------------
    // Public driver
    // ---------------------------------------------------------------------

    /** Walks every direct child of [container], dispatching block-level node types. */
    fun consumeBlockChildren(container: ASTNode) {
        for (child in container.children) {
            renderBlock(child)
        }
    }

    /**
     * If the AST walk left a tail of un-consumed source (e.g. trailing whitespace not contained
     * in any block), emit it verbatim so the original/transformed lengths line up with
     * [source].length.
     */
    fun finishTail(originalLength: Int) {
        if (originalCursor < originalLength) {
            emitVerbatim(originalLength - originalCursor)
        }
    }

    fun toTransformedText(): TransformedText {
        val transformed = out.toAnnotatedString()
        // Source length always equals transformed length by construction (every emit is verbatim).
        // OffsetMapping.Identity is correct for every offset in [0, source.length].
        return TransformedText(transformed, OffsetMapping.Identity)
    }

    // ---------------------------------------------------------------------
    // Block dispatch
    // ---------------------------------------------------------------------

    private fun renderBlock(node: ASTNode) {
        val type = node.type
        when {
            type === MarkdownTokenTypes.EOL -> advanceVerbatimTo(node.endOffset)
            type === MarkdownTokenTypes.WHITE_SPACE -> advanceVerbatimTo(node.endOffset)

            type === MarkdownElementTypes.PARAGRAPH -> renderInlineContainer(node)

            type === MarkdownElementTypes.ATX_1 -> renderAtxHeading(node, styles.h1Size)
            type === MarkdownElementTypes.ATX_2 -> renderAtxHeading(node, styles.h2Size)
            type === MarkdownElementTypes.ATX_3 -> renderAtxHeading(node, styles.h3Size)
            type === MarkdownElementTypes.ATX_4 -> renderAtxHeading(node, styles.h4Size)
            type === MarkdownElementTypes.ATX_5 -> renderAtxHeading(node, styles.h5Size)
            type === MarkdownElementTypes.ATX_6 -> renderAtxHeading(node, styles.h6Size)

            type === MarkdownElementTypes.SETEXT_1 -> renderSetextHeading(node, styles.h1Size)
            type === MarkdownElementTypes.SETEXT_2 -> renderSetextHeading(node, styles.h2Size)

            type === MarkdownElementTypes.UNORDERED_LIST ||
                type === MarkdownElementTypes.ORDERED_LIST -> renderList(node)

            type === MarkdownElementTypes.BLOCK_QUOTE -> renderBlockquote(node)

            type === MarkdownElementTypes.CODE_FENCE -> renderCodeFence(node)
            type === MarkdownElementTypes.CODE_BLOCK -> renderCodeBlock(node)

            type === MarkdownElementTypes.HTML_BLOCK -> renderHtmlBlock(node)

            type === GFMElementTypes.TABLE -> renderTable(node)

            type === MarkdownTokenTypes.HORIZONTAL_RULE -> renderHorizontalRule(node)

            type === MarkdownElementTypes.LINK_DEFINITION -> renderLinkDefinition(node)

            // Block-level IMAGE (rare; usually inline within a paragraph).
            type === MarkdownElementTypes.IMAGE -> renderImage(node)

            else -> renderUnknownBlock(node)
        }
    }

    // ---------------------------------------------------------------------
    // Block renderers
    // ---------------------------------------------------------------------

    /**
     * PARAGRAPH-like container: walk inline children. The inline pipeline preserves intervening
     * EOL / WHITE_SPACE leaves verbatim so the user's line layout survives.
     */
    private fun renderInlineContainer(node: ASTNode) {
        advanceVerbatimTo(node.startOffset)
        for (child in node.children) {
            renderInline(child)
        }
        advanceVerbatimTo(node.endOffset)
    }

    /**
     * ATX heading shape: `[ATX_HEADER `#`, WHITE_SPACE, ATX_CONTENT, EOL?]`. The header leaf and
     * its trailing whitespace are emitted dim (marker styling); the ATX_CONTENT children are
     * emitted under the heading paragraph + font sizing without an additional dim push. The
     * trailing EOL passes through unstyled.
     */
    private fun renderAtxHeading(node: ASTNode, fontSize: TextUnit) {
        advanceVerbatimTo(node.startOffset)
        val paragraphStyle = ParagraphStyle()
        val headingStyle = SpanStyle(fontSize = fontSize, fontWeight = FontWeight.SemiBold)
        out.pushStyle(paragraphStyle)
        out.pushStyle(headingStyle)
        try {
            for (child in node.children) {
                val type = child.type
                when {
                    type === MarkdownTokenTypes.ATX_HEADER -> {
                        // Dim the `#` run; keep heading font size from the outer push.
                        emitDimNode(child)
                    }
                    type === MarkdownTokenTypes.WHITE_SPACE -> {
                        // Whitespace between ATX_HEADER and ATX_CONTENT is part of the marker
                        // syntax; dim it together with the `#` run.
                        emitDimNode(child)
                    }
                    type === MarkdownTokenTypes.ATX_CONTENT -> {
                        renderAtxContent(child)
                    }
                    type === MarkdownTokenTypes.EOL -> {
                        advanceVerbatimTo(child.endOffset)
                    }
                    else -> {
                        // Defensive: anything else inside a heading composite is rare; emit
                        // verbatim (under the heading style).
                        renderInline(child)
                    }
                }
            }
        } finally {
            out.pop() // headingStyle
            out.pop() // paragraphStyle
        }
        advanceVerbatimTo(node.endOffset)
    }

    /**
     * ATX_CONTENT carries the heading text plus optional leading whitespace. The leading space
     * (single space after the `#` marker) is part of the marker syntax; render it dim. The rest
     * of the content recurses into the inline pipeline so e.g. `# **bold**` renders as bold
     * inside the heading style.
     */
    private fun renderAtxContent(content: ASTNode) {
        val children = content.children
        if (children.isEmpty()) {
            // No structured children - emit verbatim. Any leading whitespace inside the slice is
            // dimmed; the rest is emitted under whatever heading style is on the span stack.
            val raw = source.substring(content.startOffset, content.endOffset)
            val leading = raw.indexOfFirst { it != ' ' && it != '\t' }
                .let { if (it < 0) raw.length else it }
            if (leading > 0) {
                out.pushStyle(SpanStyle(color = styles.dimColor))
                try { emitVerbatim(leading) } finally { out.pop() }
            }
            if (leading < raw.length) emitVerbatim(raw.length - leading)
            return
        }
        var seenContent = false
        for (child in children) {
            if (!seenContent && child.type === MarkdownTokenTypes.WHITE_SPACE) {
                emitDimNode(child)
                continue
            }
            seenContent = true
            renderInline(child)
        }
    }

    /**
     * SETEXT shape: `[SETEXT_CONTENT, EOL, SETEXT_underline (`=` or `-`)]`. The content goes
     * through the inline pipeline styled with the heading font; the underline row is dim (and
     * inherits the heading font size from the outer push).
     */
    private fun renderSetextHeading(node: ASTNode, fontSize: TextUnit) {
        advanceVerbatimTo(node.startOffset)
        out.pushStyle(ParagraphStyle())
        out.pushStyle(SpanStyle(fontSize = fontSize, fontWeight = FontWeight.SemiBold))
        try {
            for (child in node.children) {
                val type = child.type
                when {
                    type === MarkdownTokenTypes.SETEXT_CONTENT -> {
                        for (inner in child.children) renderInline(inner)
                        // SETEXT_CONTENT children may not cover every byte; flush the gap.
                        advanceVerbatimTo(child.endOffset)
                    }
                    type === MarkdownTokenTypes.SETEXT_1 ||
                        type === MarkdownTokenTypes.SETEXT_2 -> emitDimNode(child)
                    type === MarkdownTokenTypes.EOL -> advanceVerbatimTo(child.endOffset)
                    else -> renderInline(child)
                }
            }
        } finally {
            out.pop()
            out.pop()
        }
        advanceVerbatimTo(node.endOffset)
    }

    /**
     * Lists: the bullet / number marker is dimmed. The item body recurses through the block
     * dispatcher so nested blocks (paragraphs, code, sublists) keep their styling.
     */
    private fun renderList(node: ASTNode) {
        advanceVerbatimTo(node.startOffset)
        for (child in node.children) {
            renderListChild(child)
        }
        advanceVerbatimTo(node.endOffset)
    }

    private fun renderListChild(child: ASTNode) {
        val type = child.type
        when {
            type === MarkdownElementTypes.LIST_ITEM -> renderListItem(child)
            type === MarkdownTokenTypes.EOL -> advanceVerbatimTo(child.endOffset)
            type === MarkdownTokenTypes.WHITE_SPACE -> advanceVerbatimTo(child.endOffset)
            else -> renderBlock(child)
        }
    }

    private fun renderListItem(item: ASTNode) {
        advanceVerbatimTo(item.startOffset)
        for (child in item.children) {
            val type = child.type
            when {
                type === MarkdownTokenTypes.LIST_BULLET ||
                    type === MarkdownTokenTypes.LIST_NUMBER -> {
                    emitDimNode(child)
                }
                type === MarkdownTokenTypes.EOL -> advanceVerbatimTo(child.endOffset)
                type === MarkdownTokenTypes.WHITE_SPACE -> advanceVerbatimTo(child.endOffset)
                else -> renderBlock(child)
            }
        }
        advanceVerbatimTo(item.endOffset)
    }

    /**
     * BLOCK_QUOTE composite carries `>` leaves intermixed with inline content. The composite and
     * the leaf share the same IElementType; the leaf has zero children. The leader leaves render
     * dim.
     */
    private fun renderBlockquote(node: ASTNode) {
        advanceVerbatimTo(node.startOffset)
        out.pushStyle(ParagraphStyle())
        try {
            for (child in node.children) {
                val type = child.type
                when {
                    type === MarkdownTokenTypes.BLOCK_QUOTE && child.children.isEmpty() -> {
                        emitDimNode(child)
                    }
                    type === MarkdownTokenTypes.EOL -> advanceVerbatimTo(child.endOffset)
                    type === MarkdownTokenTypes.WHITE_SPACE -> advanceVerbatimTo(child.endOffset)
                    else -> renderBlock(child)
                }
            }
        } finally {
            out.pop()
        }
        advanceVerbatimTo(node.endOffset)
    }

    /**
     * Fenced code block: monospace + codeBackground over the whole range; the fence rows (opening
     * and closing ``` lines, plus the optional language tag) are additionally dimmed so the
     * backticks read as syntax. We dim the fence-row source ranges by inspecting the children of
     * the CODE_FENCE node.
     */
    private fun renderCodeFence(node: ASTNode) {
        advanceVerbatimTo(node.startOffset)
        out.pushStyle(ParagraphStyle())
        out.pushStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = styles.codeBackground))
        try {
            for (child in node.children) {
                val type = child.type
                when {
                    type === MarkdownTokenTypes.CODE_FENCE_START ||
                        type === MarkdownTokenTypes.CODE_FENCE_END ||
                        type === MarkdownTokenTypes.FENCE_LANG -> {
                        emitDimNode(child)
                    }
                    type === MarkdownTokenTypes.EOL -> advanceVerbatimTo(child.endOffset)
                    type === MarkdownTokenTypes.WHITE_SPACE -> advanceVerbatimTo(child.endOffset)
                    else -> advanceVerbatimTo(child.endOffset)
                }
            }
            advanceVerbatimTo(node.endOffset)
        } finally {
            out.pop()
            out.pop()
        }
    }

    /**
     * Indented code block: monospace + codeBackground over the entire range. No marker chars to
     * dim - the leading 4-space indent is syntactic but visually indistinguishable.
     */
    private fun renderCodeBlock(node: ASTNode) {
        advanceVerbatimTo(node.startOffset)
        out.pushStyle(ParagraphStyle())
        out.pushStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = styles.codeBackground))
        try {
            emitVerbatim(node.endOffset - node.startOffset)
        } finally {
            out.pop()
            out.pop()
        }
        advanceVerbatimTo(node.endOffset)
    }

    /** HTML block: pass through verbatim. Compose never interprets tag syntax. */
    private fun renderHtmlBlock(node: ASTNode) {
        advanceVerbatimTo(node.startOffset)
        emitVerbatim(node.endOffset - node.startOffset)
    }

    /**
     * GFM table: per locked design, stays as raw source in the editor (no styling, no marker
     * dimming). The view-mode handles the visual table; the editor's job is to keep the source
     * legible while typing.
     */
    private fun renderTable(node: ASTNode) {
        advanceVerbatimTo(node.startOffset)
        emitVerbatim(node.endOffset - node.startOffset)
    }

    /** Horizontal rule: dim styled passthrough. */
    private fun renderHorizontalRule(node: ASTNode) {
        advanceVerbatimTo(node.startOffset)
        out.pushStyle(SpanStyle(color = styles.dimColor))
        try {
            emitVerbatim(node.endOffset - node.startOffset)
        } finally {
            out.pop()
        }
    }

    /**
     * LINK_DEFINITION: dim styled passthrough. The user is editing the source and still needs to
     * see the definition bytes; the view-mode renderer hides them from the painted output but the
     * editor keeps them visible (dim).
     */
    private fun renderLinkDefinition(node: ASTNode) {
        advanceVerbatimTo(node.startOffset)
        out.pushStyle(SpanStyle(color = styles.dimColor))
        try {
            emitVerbatim(node.endOffset - node.startOffset)
        } finally {
            out.pop()
        }
    }

    /** Unknown block: pass through verbatim. */
    private fun renderUnknownBlock(node: ASTNode) {
        advanceVerbatimTo(node.startOffset)
        emitVerbatim(node.endOffset - node.startOffset)
    }

    // ---------------------------------------------------------------------
    // Inline dispatch
    // ---------------------------------------------------------------------

    private fun renderInline(node: ASTNode) {
        val type = node.type
        when {
            type === MarkdownTokenTypes.TEXT -> advanceVerbatimTo(node.endOffset)
            type === MarkdownTokenTypes.WHITE_SPACE -> advanceVerbatimTo(node.endOffset)
            type === MarkdownTokenTypes.EOL -> advanceVerbatimTo(node.endOffset)
            type === MarkdownTokenTypes.HARD_LINE_BREAK -> advanceVerbatimTo(node.endOffset)
            type === MarkdownTokenTypes.HTML_TAG -> advanceVerbatimTo(node.endOffset)

            type === MarkdownElementTypes.STRONG ->
                renderStyledComposite(
                    node = node,
                    isDelimiter = ::isEmphLeaf,
                    contentStyle = SpanStyle(fontWeight = FontWeight.Bold),
                    markerExtra = null,
                )
            type === MarkdownElementTypes.EMPH ->
                renderStyledComposite(
                    node = node,
                    isDelimiter = ::isEmphLeaf,
                    contentStyle = SpanStyle(fontStyle = FontStyle.Italic),
                    markerExtra = null,
                )
            type === GFMElementTypes.STRIKETHROUGH ->
                renderStyledComposite(
                    node = node,
                    isDelimiter = ::isTildeLeaf,
                    contentStyle = SpanStyle(textDecoration = TextDecoration.LineThrough),
                    markerExtra = null,
                )
            type === MarkdownElementTypes.CODE_SPAN ->
                renderStyledComposite(
                    node = node,
                    isDelimiter = ::isBacktickLeaf,
                    contentStyle = SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        background = styles.codeBackground,
                    ),
                    markerExtra = SpanStyle(fontFamily = FontFamily.Monospace),
                )

            type === MarkdownElementTypes.INLINE_LINK -> renderInlineLink(node)
            type === MarkdownElementTypes.FULL_REFERENCE_LINK ||
                type === MarkdownElementTypes.SHORT_REFERENCE_LINK -> renderReferenceLink(node)
            type === MarkdownElementTypes.AUTOLINK -> renderAutolinkComposite(node)
            type === GFMTokenTypes.GFM_AUTOLINK -> renderBareAutolink(node)
            type === MarkdownTokenTypes.EMAIL_AUTOLINK -> renderEmailAutolink(node)

            type === MarkdownElementTypes.IMAGE -> renderImage(node)

            else -> {
                // Recurse into composite types whose children carry the visible bytes; fall back
                // to verbatim for anything else.
                if (node.children.isNotEmpty()) {
                    for (child in node.children) renderInline(child)
                } else {
                    advanceVerbatimTo(node.endOffset)
                }
            }
        }
    }

    /**
     * Shared path for STRONG / EMPH / STRIKETHROUGH / CODE_SPAN: walk the composite's children,
     * render the boundary delimiter leaves dim (plus any [markerExtra] styling), and emit the
     * inner content under [contentStyle]. Unbalanced markers don't reach this path - the parser
     * keeps them as standalone TEXT leaves outside any composite, so they render verbatim with no
     * dim styling.
     *
     * Span ordering: the [contentStyle] is pushed BEFORE iterating children, then the marker push
     * is pushed INSIDE for marker leaves only. Because the marker push only covers the marker
     * bytes (not the inner content) and the dim span has just `color = dimColor`, the markers
     * render dim at their natural weight while the inner content keeps the full [contentStyle].
     */
    private fun renderStyledComposite(
        node: ASTNode,
        isDelimiter: (ASTNode) -> Boolean,
        contentStyle: SpanStyle,
        markerExtra: SpanStyle?,
    ) {
        advanceVerbatimTo(node.startOffset)
        out.pushStyle(contentStyle)
        try {
            val markerStyle = if (markerExtra == null) {
                SpanStyle(color = styles.dimColor)
            } else {
                SpanStyle(color = styles.dimColor).merge(markerExtra)
            }
            for (child in node.children) {
                if (isDelimiter(child)) {
                    advanceVerbatimTo(child.startOffset)
                    out.pushStyle(markerStyle)
                    try {
                        emitVerbatim(child.endOffset - child.startOffset)
                    } finally {
                        out.pop()
                    }
                } else {
                    renderInline(child)
                }
            }
        } finally {
            out.pop()
        }
        advanceVerbatimTo(node.endOffset)
    }

    /**
     * INLINE_LINK shape: `[LINK_TEXT { '[', children, ']' }, '(', LINK_DESTINATION, ')']`.
     * The LINK_TEXT brackets and the surrounding `()` / LINK_DESTINATION / LINK_TITLE all render
     * dim; the LINK_TEXT inner content renders with `linkColor + Underline`. The dim push on the
     * markers covers ONLY the marker bytes, so the underline does not extend onto the brackets.
     */
    private fun renderInlineLink(node: ASTNode) {
        advanceVerbatimTo(node.startOffset)
        for (child in node.children) {
            val type = child.type
            when {
                type === MarkdownElementTypes.LINK_TEXT -> renderLinkText(child)
                type === MarkdownTokenTypes.LPAREN ||
                    type === MarkdownTokenTypes.RPAREN -> emitDimNode(child)
                type === MarkdownElementTypes.LINK_DESTINATION ||
                    type === MarkdownElementTypes.LINK_TITLE -> emitDimNode(child)
                type === MarkdownTokenTypes.EOL -> advanceVerbatimTo(child.endOffset)
                type === MarkdownTokenTypes.WHITE_SPACE -> emitDimNode(child)
                else -> renderInline(child)
            }
        }
        advanceVerbatimTo(node.endOffset)
    }

    /**
     * FULL/SHORT_REFERENCE_LINK shape:
     *  - FULL `[text][label]`: LINK_TEXT renders with link styling; the trailing `[label]`
     *    LINK_LABEL renders dim (it is metadata, not part of the visible label).
     *  - SHORT `[label]`: outer brackets dim; the inner label content renders with link styling.
     */
    private fun renderReferenceLink(node: ASTNode) {
        advanceVerbatimTo(node.startOffset)
        val isFull = node.type === MarkdownElementTypes.FULL_REFERENCE_LINK
        for (child in node.children) {
            val type = child.type
            when {
                type === MarkdownElementTypes.LINK_TEXT -> renderLinkText(child)
                type === MarkdownElementTypes.LINK_LABEL -> {
                    if (isFull) {
                        emitDimNode(child)
                    } else {
                        renderLinkLabel(child)
                    }
                }
                type === MarkdownTokenTypes.EOL -> advanceVerbatimTo(child.endOffset)
                type === MarkdownTokenTypes.WHITE_SPACE -> emitDimNode(child)
                else -> renderInline(child)
            }
        }
        advanceVerbatimTo(node.endOffset)
    }

    /**
     * LINK_TEXT: the bracket leaves render dim; the inner content renders with `linkColor +
     * Underline`. The inner push is scoped to the children so the underline doesn't reach the
     * brackets themselves.
     */
    private fun renderLinkText(node: ASTNode) {
        advanceVerbatimTo(node.startOffset)
        for (child in node.children) {
            val type = child.type
            when {
                type === MarkdownTokenTypes.LBRACKET ||
                    type === MarkdownTokenTypes.RBRACKET -> emitDimNode(child)
                else -> {
                    advanceVerbatimTo(child.startOffset)
                    out.pushStyle(
                        SpanStyle(color = styles.linkColor, textDecoration = TextDecoration.Underline),
                    )
                    try {
                        renderInline(child)
                    } finally {
                        out.pop()
                    }
                }
            }
        }
        advanceVerbatimTo(node.endOffset)
    }

    /**
     * SHORT_REFERENCE_LINK label: brackets dim, inner content with link styling. Mirrors
     * [renderLinkText] but applies to a [MarkdownElementTypes.LINK_LABEL] node.
     */
    private fun renderLinkLabel(node: ASTNode) {
        advanceVerbatimTo(node.startOffset)
        if (node.children.isEmpty()) {
            // Leaf form: slice the bracket chars off the source range and render the middle
            // styled with the link colour.
            val raw = source.substring(node.startOffset, node.endOffset)
            val openCount = if (raw.startsWith('[')) 1 else 0
            val closeCount = if (raw.endsWith(']')) 1 else 0
            if (openCount > 0) {
                out.pushStyle(SpanStyle(color = styles.dimColor))
                try { emitVerbatim(openCount) } finally { out.pop() }
            }
            val middle = raw.length - openCount - closeCount
            if (middle > 0) {
                out.pushStyle(
                    SpanStyle(color = styles.linkColor, textDecoration = TextDecoration.Underline),
                )
                try { emitVerbatim(middle) } finally { out.pop() }
            }
            if (closeCount > 0) {
                out.pushStyle(SpanStyle(color = styles.dimColor))
                try { emitVerbatim(closeCount) } finally { out.pop() }
            }
            return
        }
        for (child in node.children) {
            val type = child.type
            when {
                type === MarkdownTokenTypes.LBRACKET ||
                    type === MarkdownTokenTypes.RBRACKET -> emitDimNode(child)
                else -> {
                    advanceVerbatimTo(child.startOffset)
                    out.pushStyle(
                        SpanStyle(color = styles.linkColor, textDecoration = TextDecoration.Underline),
                    )
                    try {
                        renderInline(child)
                    } finally {
                        out.pop()
                    }
                }
            }
        }
        advanceVerbatimTo(node.endOffset)
    }

    /**
     * AUTOLINK composite: `<url>`. Angle brackets dim; URL with link styling.
     */
    private fun renderAutolinkComposite(node: ASTNode) {
        advanceVerbatimTo(node.startOffset)
        for (child in node.children) {
            val type = child.type
            when {
                type === MarkdownTokenTypes.LT ||
                    type === MarkdownTokenTypes.GT -> emitDimNode(child)
                type === MarkdownTokenTypes.AUTOLINK ||
                    type === MarkdownElementTypes.AUTOLINK -> {
                    advanceVerbatimTo(child.startOffset)
                    out.pushStyle(
                        SpanStyle(color = styles.linkColor, textDecoration = TextDecoration.Underline),
                    )
                    try {
                        emitVerbatim(child.endOffset - child.startOffset)
                    } finally {
                        out.pop()
                    }
                }
                else -> renderInline(child)
            }
        }
        advanceVerbatimTo(node.endOffset)
    }

    /** GFM bare URL: link styling over the bytes. No markers. */
    private fun renderBareAutolink(node: ASTNode) {
        advanceVerbatimTo(node.startOffset)
        out.pushStyle(SpanStyle(color = styles.linkColor, textDecoration = TextDecoration.Underline))
        try {
            emitVerbatim(node.endOffset - node.startOffset)
        } finally {
            out.pop()
        }
    }

    /** EMAIL_AUTOLINK: same as GFM bare. */
    private fun renderEmailAutolink(node: ASTNode) {
        advanceVerbatimTo(node.startOffset)
        out.pushStyle(SpanStyle(color = styles.linkColor, textDecoration = TextDecoration.Underline))
        try {
            emitVerbatim(node.endOffset - node.startOffset)
        } finally {
            out.pop()
        }
    }

    /**
     * IMAGE `![alt](url)`: rendered verbatim with dim markers (`!`, `[`, `]`, `(`, `)`), the alt
     * text in italic (so it visually distinguishes from a real link's underlined text), and the
     * URL dim. Every source byte stays in place so the offset mapping remains Identity and the
     * user can edit alt/url chars individually.
     *
     * The image AST is `![ INLINE_LINK [ LINK_TEXT [ '[', ..., ']' ], '(', LINK_DESTINATION, ')' ] ]`
     * - we render the leading `!` dim then recurse into the inner INLINE_LINK with image-specific
     * styling for the LINK_TEXT (italic instead of link colour).
     */
    private fun renderImage(node: ASTNode) {
        advanceVerbatimTo(node.startOffset)
        for (child in node.children) {
            val type = child.type
            when {
                type === MarkdownTokenTypes.EXCLAMATION_MARK -> emitDimNode(child)
                type === MarkdownElementTypes.INLINE_LINK -> renderImageInner(child)
                type === MarkdownTokenTypes.EOL -> advanceVerbatimTo(child.endOffset)
                type === MarkdownTokenTypes.WHITE_SPACE -> advanceVerbatimTo(child.endOffset)
                else -> renderInline(child)
            }
        }
        advanceVerbatimTo(node.endOffset)
    }

    /**
     * Inner INLINE_LINK of an IMAGE: same shape as [renderInlineLink] but the LINK_TEXT renders
     * italic+dim (image alt convention) instead of linkColor+underline.
     */
    private fun renderImageInner(node: ASTNode) {
        advanceVerbatimTo(node.startOffset)
        for (child in node.children) {
            val type = child.type
            when {
                type === MarkdownElementTypes.LINK_TEXT -> renderImageAlt(child)
                type === MarkdownTokenTypes.LPAREN ||
                    type === MarkdownTokenTypes.RPAREN -> emitDimNode(child)
                type === MarkdownElementTypes.LINK_DESTINATION ||
                    type === MarkdownElementTypes.LINK_TITLE -> emitDimNode(child)
                type === MarkdownTokenTypes.EOL -> advanceVerbatimTo(child.endOffset)
                type === MarkdownTokenTypes.WHITE_SPACE -> emitDimNode(child)
                else -> renderInline(child)
            }
        }
        advanceVerbatimTo(node.endOffset)
    }

    /** Image alt text inside its LINK_TEXT: brackets dim, inner content italic + dim. */
    private fun renderImageAlt(node: ASTNode) {
        advanceVerbatimTo(node.startOffset)
        for (child in node.children) {
            val type = child.type
            when {
                type === MarkdownTokenTypes.LBRACKET ||
                    type === MarkdownTokenTypes.RBRACKET -> emitDimNode(child)
                else -> {
                    advanceVerbatimTo(child.startOffset)
                    out.pushStyle(
                        SpanStyle(fontStyle = FontStyle.Italic, color = styles.dimColor),
                    )
                    try {
                        renderInline(child)
                    } finally {
                        out.pop()
                    }
                }
            }
        }
        advanceVerbatimTo(node.endOffset)
    }

    // ---------------------------------------------------------------------
    // Emit primitives
    // ---------------------------------------------------------------------

    /**
     * Emit any source range that has not yet been consumed, up to (but not including) [endOffset].
     * Used to fast-forward across whitespace, EOL leaves, and AST gaps without enumerating each
     * child explicitly. No styling is applied; the bytes pass through under whatever spans are
     * already on the builder's stack.
     */
    private fun advanceVerbatimTo(endOffset: Int) {
        if (endOffset <= originalCursor) return
        emitVerbatim(endOffset - originalCursor)
    }

    /**
     * Append [len] chars starting at [originalCursor] to the builder, then advance the cursor.
     * Any spans currently on the builder's stack apply to the appended range. Source length and
     * transformed length grow in lock-step so the final [OffsetMapping.Identity] is correct.
     */
    private fun emitVerbatim(len: Int) {
        if (len <= 0) return
        val slice = source.substring(originalCursor, originalCursor + len)
        out.append(slice)
        originalCursor += len
    }

    /**
     * Emit the entire byte range of [node] verbatim under a dim SpanStyle. Used for marker leaves
     * (asterisks, hashes, brackets, parens, angle brackets, tildes, list bullets, blockquote
     * leaders, link destinations, etc.).
     */
    private fun emitDimNode(node: ASTNode) {
        advanceVerbatimTo(node.startOffset)
        val len = node.endOffset - node.startOffset
        if (len <= 0) return
        out.pushStyle(SpanStyle(color = styles.dimColor))
        try {
            emitVerbatim(len)
        } finally {
            out.pop()
        }
    }
}

// ---------------------------------------------------------------------------
// Inline marker leaf predicates - shared with the view-mode inline renderer but
// duplicated here to keep the transformation file self-contained.
// ---------------------------------------------------------------------------

/** True for the boundary EMPH leaf tokens that wrap an EMPH or STRONG composite. */
private fun isEmphLeaf(child: ASTNode): Boolean =
    child.type === MarkdownTokenTypes.EMPH && child.children.isEmpty()

/** True for the boundary TILDE leaves that wrap a STRIKETHROUGH composite. */
private fun isTildeLeaf(child: ASTNode): Boolean =
    child.type === GFMTokenTypes.TILDE && child.children.isEmpty()

/** True for the boundary BACKTICK leaves that wrap a CODE_SPAN composite. */
private fun isBacktickLeaf(child: ASTNode): Boolean =
    child.type === MarkdownTokenTypes.BACKTICK && child.children.isEmpty()

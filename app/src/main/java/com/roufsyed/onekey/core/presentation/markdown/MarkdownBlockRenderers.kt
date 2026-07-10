package com.roufsyed.onekey.core.presentation.markdown

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.Locale
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMTokenTypes

/**
 * Block-level dispatcher and per-block Compose renderers. Every function in
 * this file is package-private; the only public seam is [RenderBlock] which is
 * called from [MarkdownNotesView] for each non-EOL child of MARKDOWN_FILE.
 *
 * Branching strategy: every `when` arm in this file matches on
 * [org.intellij.markdown.IElementType] identity via `===`. We deliberately do
 * NOT branch on `IElementType.toString()` because parser verification found
 * three name collisions between composite and leaf token types
 * (BLOCK_QUOTE / AUTOLINK / SETEXT_n share their stringified name with their
 * own delimiter leaves). Identity comparison is the only correct way to
 * distinguish them.
 *
 * Side-effect policy: NO renderer in this file performs any I/O.
 *  - HTML_BLOCK and inline HTML_TAG tokens flow through to Compose
 *    [androidx.compose.material3.Text] as literal glyphs. Compose never
 *    interprets tag syntax, so XSS via embedded HTML is structurally
 *    impossible.
 *  - IMAGE nodes are rendered as a textual `[image: alt]` placeholder. No
 *    image loader (Coil, OkHttp, Picasso, Glide) exists on the classpath - a
 *    grep over `libs.versions.toml` and `app/build.gradle.kts` shows zero
 *    hits and a runtime canary in the adversarial test suite pins this. The
 *    URL is never opened, no network request fires.
 *  - Link clicks capture every URL as an [URL_ANNOTATION_TAG] annotation
 *    regardless of scheme. The block-level `pointerInputForLinks` modifier
 *    reads back the annotation at the tap offset and forwards the URL through
 *    the supplied callback - scheme classification is the
 *    [SchemeAllowlistUriHandler]'s job at click dispatch time, NOT the
 *    renderer's.
 */

// ---------------------------------------------------------------------------
// Public block dispatcher
// ---------------------------------------------------------------------------

/**
 * Routes a single block-level AST [node] to the appropriate per-block
 * renderer. EOL tokens between blocks are filtered by the caller so this
 * function only sees real block-level nodes (paragraph, heading, list,
 * quote, fence, table, etc.).
 *
 * Unknown / unmapped block types fall through to a dim verbatim source slice
 * so nothing is silently dropped. Output of the fallback is visually
 * distinguishable from a styled paragraph (dimmed colour) so a regression
 * surfaces on screen rather than being papered over.
 */
@Composable
internal fun RenderBlock(node: ASTNode, ctx: RenderContext) {
    val type = node.type
    when {
        type === MarkdownElementTypes.PARAGRAPH -> ParagraphBlock(node, ctx)

        type === MarkdownElementTypes.ATX_1 -> HeadingBlock(node, ctx, level = 1)
        type === MarkdownElementTypes.ATX_2 -> HeadingBlock(node, ctx, level = 2)
        type === MarkdownElementTypes.ATX_3 -> HeadingBlock(node, ctx, level = 3)
        type === MarkdownElementTypes.ATX_4 -> HeadingBlock(node, ctx, level = 4)
        type === MarkdownElementTypes.ATX_5 -> HeadingBlock(node, ctx, level = 5)
        type === MarkdownElementTypes.ATX_6 -> HeadingBlock(node, ctx, level = 6)
        type === MarkdownElementTypes.SETEXT_1 -> HeadingBlock(node, ctx, level = 1)
        type === MarkdownElementTypes.SETEXT_2 -> HeadingBlock(node, ctx, level = 2)

        type === MarkdownElementTypes.UNORDERED_LIST -> UnorderedListBlock(node, ctx)
        type === MarkdownElementTypes.ORDERED_LIST -> OrderedListBlock(node, ctx)

        type === MarkdownElementTypes.BLOCK_QUOTE -> BlockquoteBlock(node, ctx)

        type === MarkdownElementTypes.CODE_FENCE -> CodeFenceBlock(node, ctx)
        type === MarkdownElementTypes.CODE_BLOCK -> IndentedCodeBlock(node, ctx)

        type === MarkdownElementTypes.HTML_BLOCK -> HtmlBlock(node, ctx)

        type === GFMElementTypes.TABLE -> TableBlock(node, ctx)

        type === MarkdownTokenTypes.HORIZONTAL_RULE -> HorizontalRuleBlock()

        // LINK_DEFINITION blocks are consumed by buildLinkDefinitionMap. They
        // never contribute visible text; suppress.
        type === MarkdownElementTypes.LINK_DEFINITION -> Unit

        // IMAGE rarely appears at block level (paragraph-only image source);
        // the placeholder renderer keeps screen output stable for that edge.
        type === MarkdownElementTypes.IMAGE -> ImageBlock(node, ctx)

        else -> UnknownBlockFallback(node, ctx)
    }
}

// ---------------------------------------------------------------------------
// Paragraph and headings
// ---------------------------------------------------------------------------

/**
 * Renders a PARAGRAPH as a single [Text] with the body-medium style. Inline
 * children flow through [buildInline] to become an [AnnotatedString]; link
 * annotations on that string are picked up by [pointerInputForLinks] which is
 * attached to the [Text]'s modifier.
 */
@Composable
private fun ParagraphBlock(node: ASTNode, ctx: RenderContext) {
    InlineHostText(
        node = node,
        ctx = ctx,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    )
}

/**
 * Renders an ATX_n / SETEXT_n heading. Setext headings are mapped to ATX_1 /
 * ATX_2 styling for visual parity - the source syntax does not change the
 * rendered weight.
 *
 * The heading's visible text comes from its ATX_CONTENT or SETEXT_CONTENT
 * child; the ATX_HEADER `#` leaves and SETEXT underline leaves are filtered.
 * If no content child is present (defensive - malformed input) the heading
 * still renders by sliding the whole node through [buildInline].
 *
 * Typography map:
 *  - level 1 -> titleLarge
 *  - level 2 -> titleMedium
 *  - level 3..6 -> titleSmall (then labelLarge for 4..6 sub-divisions)
 *
 * Rationale for not using headlineLarge: notes are dense - using the larger
 * headline scale would push body content off the visible window. The title*
 * scale is the next step down and matches the density of the credential
 * detail screen where this renderer is hosted.
 */
@Composable
private fun HeadingBlock(node: ASTNode, ctx: RenderContext, level: Int) {
    val style = when (level) {
        1 -> MaterialTheme.typography.titleLarge
        2 -> MaterialTheme.typography.titleMedium
        3 -> MaterialTheme.typography.titleSmall
        4 -> MaterialTheme.typography.titleSmall
        5 -> MaterialTheme.typography.labelLarge
        else -> MaterialTheme.typography.labelLarge
    }
    val contentNode = headingInlineHost(node) ?: node
    InlineHostText(
        node = contentNode,
        ctx = ctx,
        style = style,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 6.dp),
        fontWeight = FontWeight.SemiBold,
    )
}

/**
 * Pulls the inline-host child out of a heading composite. ATX headings expose
 * the inline content under ATX_CONTENT; setext headings under SETEXT_CONTENT.
 * Returns null when neither child is present (the caller falls back to
 * rendering the whole node).
 */
private fun headingInlineHost(node: ASTNode): ASTNode? =
    node.children.firstOrNull {
        it.type === MarkdownTokenTypes.ATX_CONTENT ||
            it.type === MarkdownTokenTypes.SETEXT_CONTENT
    }

// ---------------------------------------------------------------------------
// Lists
// ---------------------------------------------------------------------------

/**
 * UNORDERED_LIST -> Column of LIST_ITEMs with a bullet glyph in the left
 * gutter. The bullet is rendered as a leading [Text] and the item body sits
 * in a [Column] beside it; nested lists accumulate left padding via
 * [RenderContext.listDepth].
 */
@Composable
private fun UnorderedListBlock(node: ASTNode, ctx: RenderContext) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp, bottom = 2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for (child in node.children) {
            if (child.type !== MarkdownElementTypes.LIST_ITEM) continue
            ListItemRow(item = child, ctx = ctx, marker = "•")
        }
    }
}

/**
 * ORDERED_LIST -> Column of LIST_ITEMs with numeric markers. The
 * `start` attribute is honoured: the first item's LIST_NUMBER token is
 * inspected for its leading integer and subsequent items count up from
 * there. Malformed numbers fall back to the source-as-typed slice so the
 * user sees what they wrote (e.g. a list that starts at `001.` displays
 * `001.` rather than being normalised).
 *
 * Implementation note: we deliberately read the LIST_NUMBER token's
 * sliceText for the first item rather than synthesising "1. " - this
 * preserves source-specified ordering as documented in the design open
 * decision (recommendation: honour the source).
 */
@Composable
private fun OrderedListBlock(node: ASTNode, ctx: RenderContext) {
    val items = node.children.filter { it.type === MarkdownElementTypes.LIST_ITEM }
    val markers = orderedListMarkers(items, ctx.source)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp, bottom = 2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items.forEachIndexed { index, item ->
            ListItemRow(item = item, ctx = ctx, marker = markers[index])
        }
    }
}

/**
 * Builds the list of marker strings used by [OrderedListBlock]. The first
 * marker is taken verbatim from the source (preserving the user's chosen
 * `start` attribute and any zero-padding); subsequent markers increment from
 * the parsed integer if one was found, falling back to per-item source
 * slicing otherwise.
 */
private fun orderedListMarkers(items: List<ASTNode>, source: String): List<String> {
    if (items.isEmpty()) return emptyList()
    val firstMarkerSource = listMarkerSource(items[0], source)
    val startValue = parseListStart(firstMarkerSource)
    if (startValue == null) {
        // Couldn't parse the integer - emit each item's marker verbatim so the
        // user still sees the marker bytes they typed.
        return items.map { listMarkerSource(it, source).ifEmpty { "-" } }
    }
    return items.mapIndexed { offset, _ ->
        "${startValue + offset}."
    }
}

/**
 * Pulls the LIST_NUMBER or LIST_BULLET leaf text from a LIST_ITEM. Used to
 * decide the first ordered marker and as a verbatim fallback. Returns an
 * empty string when the item has no recognisable marker leaf.
 */
private fun listMarkerSource(item: ASTNode, source: String): String {
    val leaf = item.children.firstOrNull {
        it.type === MarkdownTokenTypes.LIST_NUMBER || it.type === MarkdownTokenTypes.LIST_BULLET
    } ?: return ""
    return leaf.sliceText(source).trim()
}

/**
 * Extracts the integer prefix from a list-number source slice such as
 * `"5."`, `"5)"`, `"05."`. Returns null when no leading digit run is
 * present (e.g. for a bullet marker like `"-"`).
 */
private fun parseListStart(markerSource: String): Int? {
    val digits = markerSource.takeWhile { it.isDigit() }
    return digits.toIntOrNull()
}

/**
 * Renders one LIST_ITEM as a row of `[marker, body]`. The body iterates the
 * item's non-marker children through [RenderBlock] so paragraphs, nested
 * lists, code fences, and quotes inside an item all flow through the same
 * dispatcher. `listDepth` is incremented before descending so a nested list
 * picks up additional left padding.
 */
@Composable
private fun ListItemRow(item: ASTNode, ctx: RenderContext, marker: String) {
    val childCtx = ctx.copy(listDepth = ctx.listDepth + 1)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (ctx.listDepth * 16).dp),
    ) {
        Text(
            text = marker,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .width(28.dp)
                .padding(top = 4.dp),
        )
        Column(modifier = Modifier.fillMaxWidth()) {
            for (child in item.children) {
                if (isListItemMarkerLeaf(child)) continue
                if (child.type === MarkdownTokenTypes.EOL) continue
                if (child.type === MarkdownTokenTypes.WHITE_SPACE) continue
                if (child.type === MarkdownTokenTypes.TEXT) {
                    // Bare-text items (`- alpha\n- beta`) have a TEXT leaf as
                    // a direct child of LIST_ITEM rather than a wrapping
                    // PARAGRAPH. Render it directly so the bullet does not
                    // end up next to an empty body.
                    InlineHostText(
                        node = item,
                        ctx = childCtx,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.fillMaxWidth(),
                        textBuilder = { _, _ ->
                            buildAnnotatedString {
                                for (innerChild in item.children) {
                                    if (isListItemMarkerLeaf(innerChild)) continue
                                    if (innerChild.type === MarkdownTokenTypes.EOL) continue
                                    if (innerChild.type === MarkdownTokenTypes.WHITE_SPACE) {
                                        append(' '); continue
                                    }
                                    appendInlineFromNode(innerChild, ctx)
                                }
                            }
                        },
                    )
                    // Stop descent - we've already emitted every relevant
                    // child as part of the synthetic AnnotatedString above.
                    return@Row
                }
                RenderBlock(child, childCtx)
            }
        }
    }
}

/** True for the LIST_BULLET / LIST_NUMBER leaves that mark a list item. */
private fun isListItemMarkerLeaf(child: ASTNode): Boolean =
    child.type === MarkdownTokenTypes.LIST_BULLET ||
        child.type === MarkdownTokenTypes.LIST_NUMBER

// ---------------------------------------------------------------------------
// Blockquote
// ---------------------------------------------------------------------------

/**
 * BLOCK_QUOTE composite -> Row of `[stripe, Spacer, Column { children }]`.
 *
 * Stripe colour is [androidx.compose.material3.ColorScheme.outlineVariant]
 * per the brief. The composite includes a BLOCK_QUOTE leaf marker (`"> "`)
 * with the same IElementType as the composite itself; we filter it by
 * checking `children.isEmpty()` AND the type identity match (parser
 * verification documents this collision).
 *
 * Nested quotes accumulate visual depth via [RenderContext.quoteDepth] - not
 * via a thicker stripe, but via a leading indent so two stripes are visible
 * side by side at depth 2.
 */
@Composable
private fun BlockquoteBlock(node: ASTNode, ctx: RenderContext) {
    val childCtx = ctx.copy(quoteDepth = ctx.quoteDepth + 1)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .padding(vertical = 4.dp),
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.fillMaxWidth()) {
            for (child in node.children) {
                if (isBlockquoteMarkerLeaf(child)) continue
                if (child.type === MarkdownTokenTypes.EOL) continue
                if (child.type === MarkdownTokenTypes.WHITE_SPACE) continue
                RenderBlock(child, childCtx)
            }
        }
    }
}

/**
 * True for the BLOCK_QUOTE leaf marker (`"> "`). Composite and leaf share the
 * same IElementType; the leaf has no children, the composite does, so
 * `children.isEmpty()` disambiguates.
 */
private fun isBlockquoteMarkerLeaf(child: ASTNode): Boolean =
    child.type === MarkdownTokenTypes.BLOCK_QUOTE && child.children.isEmpty()

// ---------------------------------------------------------------------------
// Code blocks (fenced and indented)
// ---------------------------------------------------------------------------

/**
 * CODE_FENCE -> Surface(surfaceContainerHighest) containing the fence
 * content as monospaced text. Fence start (` ``` `) and fence end leaves
 * are filtered; only CODE_FENCE_CONTENT children contribute to the visible
 * code, with intervening EOL leaves preserved so blank lines inside the
 * fence survive.
 *
 * If an info-string is present (the `kotlin` in `` ```kotlin ``) the
 * FENCE_LANG token is rendered as a small caption above the code.
 *
 * Container is wrapped in a horizontal scroll so long lines remain
 * inspectable rather than wrapping.
 */
@Composable
private fun CodeFenceBlock(node: ASTNode, ctx: RenderContext) {
    val lang = node.children
        .firstOrNull { it.type === MarkdownTokenTypes.FENCE_LANG }
        ?.sliceText(ctx.source)
        ?.trim()
        ?.takeUnless { it.isEmpty() }
    val content = extractCodeFenceContent(node, ctx.source)
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
        ) {
            if (lang != null) {
                Text(
                    text = lang,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
            ) {
                Text(
                    text = content,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontFamily = FontFamily.Monospace,
                    softWrap = false,
                )
            }
        }
    }
}

/**
 * Concatenates CODE_FENCE_CONTENT child slices, preserving intervening EOL
 * leaves so blank lines inside the fence survive. Trims a single trailing
 * newline that appears immediately before CODE_FENCE_END.
 */
internal fun extractCodeFenceContent(node: ASTNode, source: String): String {
    val sb = StringBuilder()
    var sawContent = false
    for (child in node.children) {
        val type = child.type
        if (type === MarkdownTokenTypes.CODE_FENCE_START) continue
        if (type === MarkdownTokenTypes.CODE_FENCE_END) continue
        if (type === MarkdownTokenTypes.FENCE_LANG) continue
        if (!sawContent) {
            // Skip the leading EOL after the opening fence so we don't begin
            // the visible run with a blank line.
            if (type === MarkdownTokenTypes.EOL) continue
            sawContent = true
        }
        if (type === MarkdownTokenTypes.CODE_FENCE_CONTENT ||
            type === MarkdownTokenTypes.EOL
        ) {
            sb.append(child.sliceText(source))
        }
    }
    // Strip a single trailing newline so the surface does not have a blank
    // last line right before the bottom padding.
    while (sb.isNotEmpty() && sb.last() == '\n') {
        sb.deleteCharAt(sb.length - 1)
    }
    return sb.toString()
}

/**
 * CODE_BLOCK (indented 4-space code) -> Surface(surfaceContainerHighest)
 * with monospaced Text. Each CODE_LINE leaf already includes the leading
 * 4 spaces from the source; we strip them per line so the rendered code is
 * not double-indented.
 */
@Composable
private fun IndentedCodeBlock(node: ASTNode, ctx: RenderContext) {
    val content = extractIndentedCodeContent(node, ctx.source)
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
                .horizontalScroll(rememberScrollState()),
        ) {
            Text(
                text = content,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontFamily = FontFamily.Monospace,
                softWrap = false,
            )
        }
    }
}

/**
 * Joins CODE_LINE leaves with intervening EOLs, stripping the leading 4
 * spaces from each line. Trailing blank lines are trimmed.
 */
private fun extractIndentedCodeContent(node: ASTNode, source: String): String {
    val sb = StringBuilder()
    for (child in node.children) {
        val type = child.type
        when {
            type === MarkdownTokenTypes.CODE_LINE -> {
                val raw = child.sliceText(source)
                sb.append(raw.removePrefix("    "))
            }
            type === MarkdownTokenTypes.EOL -> sb.append('\n')
            else -> sb.append(child.sliceText(source))
        }
    }
    while (sb.isNotEmpty() && sb.last() == '\n') {
        sb.deleteCharAt(sb.length - 1)
    }
    return sb.toString()
}

// ---------------------------------------------------------------------------
// HTML blocks
// ---------------------------------------------------------------------------

/**
 * HTML_BLOCK -> rendered as LITERAL text in a code-styled surface so the
 * user can see that markup was attempted but no DOM is materialised.
 *
 * SECURITY-CRITICAL: the bytes flow through to Compose
 * [androidx.compose.material3.Text] verbatim. Compose Text does not
 * interpret HTML tags - they are laid out as glyphs. There is no WebView,
 * no DOM, no script context, no CSS. The dimmed `onSurfaceVariant` colour
 * and the surface tint signal to the reader that the bytes are raw markup
 * that was NOT processed.
 */
@Composable
private fun HtmlBlock(node: ASTNode, ctx: RenderContext) {
    val literal = node.sliceText(ctx.source).trimEnd('\n')
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
                .horizontalScroll(rememberScrollState()),
        ) {
            Text(
                text = literal,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
                softWrap = false,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Block-level image fallback
// ---------------------------------------------------------------------------

/**
 * Rare path: an IMAGE node appears as a direct block child rather than
 * inside a paragraph. Renders the same `[image: alt]` placeholder the
 * inline pipeline emits so block- and inline-level images stay visually
 * consistent. NEVER invokes any image loader; the URL inside the IMAGE is
 * never opened.
 */
@Composable
private fun ImageBlock(node: ASTNode, ctx: RenderContext) {
    InlineHostText(
        node = node,
        ctx = ctx,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        textBuilder = { _, _ ->
            buildAnnotatedString {
                appendInlineFromNode(node, ctx)
            }
        },
    )
}

// ---------------------------------------------------------------------------
// Horizontal rule
// ---------------------------------------------------------------------------

/**
 * Decorative thin line at the [androidx.compose.material3.ColorScheme.outlineVariant]
 * colour. Sits in vertical padding so the rule is visually separated from
 * the blocks above and below it.
 */
@Composable
private fun HorizontalRuleBlock() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}

// ---------------------------------------------------------------------------
// Tables (GFM)
// ---------------------------------------------------------------------------

/**
 * GFM TABLE -> Column wrapped in a horizontal scroll. The first HEADER child
 * becomes a bold row; the lone TABLE_SEPARATOR delimiter line (the
 * `|---|---|` row) is filtered by checking for a leaf node whose source
 * starts with `|` and contains `---`. Subsequent ROW children become data
 * rows.
 *
 * Each cell is a bordered Box weighted equally - no auto-sizing. The whole
 * table sits inside a horizontal scroll so wide tables remain inspectable
 * by panning.
 */
@Composable
private fun TableBlock(node: ASTNode, ctx: RenderContext) {
    val rows = collectTableRows(node)
    if (rows.isEmpty()) {
        InlineHostText(
            node = node,
            ctx = ctx,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.fillMaxWidth(),
        )
        return
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 4.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            rows.forEach { row ->
                TableRowView(row = row, ctx = ctx)
            }
        }
    }
}

/**
 * Internal projection of a table row used by [TableBlock]. Carries the cells
 * and whether the row was extracted from a HEADER (drives bold styling).
 */
private data class TableRow(val cells: List<ASTNode>, val isHeader: Boolean)

/**
 * Collects HEADER + ROW children into [TableRow] projections, ignoring the
 * lone TABLE_SEPARATOR delimiter line and any inter-row EOL leaves. CELL
 * tokens carry the inline content of each column.
 */
private fun collectTableRows(table: ASTNode): List<TableRow> {
    val result = mutableListOf<TableRow>()
    for (child in table.children) {
        when {
            child.type === GFMElementTypes.HEADER -> {
                result += TableRow(cells = extractCells(child), isHeader = true)
            }
            child.type === GFMElementTypes.ROW -> {
                result += TableRow(cells = extractCells(child), isHeader = false)
            }
            // GFMTokenTypes.TABLE_SEPARATOR and bare EOL tokens are ignored.
        }
    }
    return result
}

/** Extracts CELL children from a HEADER or ROW composite. */
private fun extractCells(row: ASTNode): List<ASTNode> =
    row.children.filter { it.type === GFMTokenTypes.CELL }

/**
 * Renders one table row. Each cell is bordered with the outlineVariant
 * stroke, takes equal weight, and wraps its inline content via
 * [buildInline]. Header rows get a semibold weight to visually separate the
 * column titles from the data.
 */
@Composable
private fun TableRowView(row: TableRow, ctx: RenderContext) {
    Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        for (cell in row.cells) {
            Box(
                modifier = Modifier
                    .width(140.dp)
                    .fillMaxHeight()
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    .padding(8.dp),
            ) {
                val style = if (row.isHeader) {
                    MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                } else {
                    MaterialTheme.typography.bodyMedium
                }
                InlineHostText(
                    node = cell,
                    ctx = ctx,
                    style = style,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Unknown block fallback
// ---------------------------------------------------------------------------

/**
 * Fallback for any block-level AST node not handled above. Emits the raw
 * source slice through a single [Text] using the surfaceVariant body style
 * so the user always sees the bytes they wrote even when the renderer does
 * not have a specific composable for that node type.
 */
@Composable
private fun UnknownBlockFallback(node: ASTNode, ctx: RenderContext) {
    Text(
        text = node.sliceText(ctx.source),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    )
}

// ---------------------------------------------------------------------------
// Inline-host shared helper
// ---------------------------------------------------------------------------

/**
 * Shared host for any block whose visible content is a single [Text] driven
 * by an [AnnotatedString] from the inline pipeline. Centralises the
 * boilerplate around:
 *  - constructing the AnnotatedString once
 *  - holding a [TextLayoutResult] state for link-tap mapping
 *  - attaching the link pointer-input modifier
 *
 * Callers can override the [textBuilder] when they need a custom inline
 * traversal (e.g. list items that fold a synthetic root over their
 * children). The default builder delegates to [buildInline] over [node].
 */
@Composable
private fun InlineHostText(
    node: ASTNode,
    ctx: RenderContext,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    fontWeight: FontWeight? = null,
    textBuilder: (ASTNode, RenderContext) -> AnnotatedString = { n, c ->
        buildInline(n, c.source, c.styles, c.linkDefs)
    },
) {
    val annotated = textBuilder(node, ctx)
    val layoutResult = remember { mutableStateOf<TextLayoutResult?>(null) }
    val effectiveStyle = if (fontWeight != null) style.copy(fontWeight = fontWeight) else style
    Text(
        text = annotated,
        style = effectiveStyle,
        color = color,
        modifier = modifier.linkTapModifier(
            annotated = annotated,
            layoutResult = layoutResult,
            ctx = ctx,
        ),
        onTextLayout = { layoutResult.value = it },
    )
}

/**
 * Convenience entry point used by [ImageBlock] and the bare-text list-item
 * path. Allows inline traversal of an arbitrary node without needing to
 * spell out the styles/linkDefs threading at every call site. The
 * AnnotatedString built by [buildInline] is appended verbatim so spans and
 * URL annotations on the sub-tree are preserved.
 */
private fun AnnotatedString.Builder.appendInlineFromNode(node: ASTNode, ctx: RenderContext) {
    append(buildInline(node, ctx.source, ctx.styles, ctx.linkDefs))
}

// ---------------------------------------------------------------------------
// Link tap dispatcher
// ---------------------------------------------------------------------------

/**
 * Attaches a [pointerInput] that maps a tap location to a character offset
 * via the supplied [TextLayoutResult], pulls the [URL_ANNOTATION_TAG]
 * annotation at that offset, and dispatches the URL through
 * [RenderContext.onLinkTapped] (well-formed links) or
 * [RenderContext.onBlockedLink] (everything else).
 *
 * Classification logic intentionally mirrors [SchemeAllowlistUriHandler]:
 *  - empty / whitespace-only -> dropped silently.
 *  - any control byte (code <= 0x1F) -> blocked as "malformed URL".
 *  - leading `%` (percent-prefix bypass) -> blocked as "malformed URL".
 *  - missing scheme -> blocked as "unknown scheme".
 *  - scheme not in {http, https, mailto} -> blocked with the scheme name.
 *  - scheme in allowlist -> forwarded as a [LinkRequest] with parsedUri.
 *
 * This duplicates the handler's classification but keeps the renderer
 * independent of the Compose `UriHandler` indirection. Phase 7 wires the
 * callbacks to a host-supplied [SchemeAllowlistUriHandler]; tests can
 * inject their own staging lambdas and assert on the result.
 */
@Composable
private fun Modifier.linkTapModifier(
    annotated: AnnotatedString,
    layoutResult: State<TextLayoutResult?>,
    ctx: RenderContext,
): Modifier {
    // Cache annotation ranges - the lookup happens on the pointer thread on
    // every tap so we want to keep the call cheap. We rebuild whenever the
    // AnnotatedString reference changes (which is whenever the source or
    // surrounding theme changes).
    val annotations = remember(annotated) {
        annotated.getStringAnnotations(
            tag = URL_ANNOTATION_TAG,
            start = 0,
            end = annotated.length,
        )
    }
    if (annotations.isEmpty()) return this
    val onTap = ctx.onLinkTapped
    val onBlocked = ctx.onBlockedLink
    if (onTap == null && onBlocked == null) return this
    return this.pointerInput(annotated) {
        detectTapGestures(onTap = { offset ->
            val layout = layoutResult.value ?: return@detectTapGestures
            val charOffset = layout.getOffsetForPosition(offset)
            val match = annotations.firstOrNull {
                charOffset in it.start until it.end
            } ?: return@detectTapGestures
            dispatchLinkTap(url = match.item, onTap = onTap, onBlocked = onBlocked)
        })
    }
}

/**
 * Classification + dispatch for a single link tap. Pulled out so unit tests
 * can drive the path without instantiating a Compose tree.
 *
 * Mirrors [SchemeAllowlistUriHandler.openUri]'s pipeline including the
 * bare-domain normalize step ([normalizeInsertUrl]) - this is what lets a
 * user hand-type `[label](google.com)` in the markdown source and have it
 * open `https://google.com` on tap. Both paths share the helper so the rule
 * is defined in one place.
 */
internal fun dispatchLinkTap(
    url: String,
    onTap: ((LinkRequest) -> Unit)?,
    onBlocked: ((String) -> Unit)?,
) {
    val trimmed = url.trim()
    if (trimmed.isEmpty()) return
    if (trimmed.any { it.code <= 0x1F }) {
        onBlocked?.invoke("Blocked unsafe link: malformed URL")
        return
    }
    if (trimmed.startsWith("%")) {
        onBlocked?.invoke("Blocked unsafe link: malformed URL")
        return
    }
    val normalized = normalizeInsertUrl(trimmed)
    val parsed = runCatching { Uri.parse(normalized) }.getOrNull()
    val scheme = parsed?.scheme
    if (parsed == null || scheme.isNullOrEmpty()) {
        onBlocked?.invoke("Blocked unsafe link: unknown scheme")
        return
    }
    val lowerScheme = scheme.lowercase(Locale.ROOT)
    if (lowerScheme in SchemeAllowlistUriHandler.ALLOWED_SCHEMES) {
        onTap?.invoke(LinkRequest(rawUrl = normalized, parsedUri = parsed, label = null))
    } else {
        onBlocked?.invoke("Blocked unsafe link: $lowerScheme")
    }
}


package com.onekey.core.presentation.markdown

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMTokenTypes

/**
 * String tag used by [pushStringAnnotation] for every URL that the inline
 * renderer extracts from a link, autolink, or bare GFM autolink token.
 *
 * The block layer attaches a pointer-input modifier that maps a tap offset to
 * a character offset, calls [AnnotatedString.getStringAnnotations] with this
 * tag, and routes the matching annotation's value through
 * [SchemeAllowlistUriHandler]. Namespaced so multiple AnnotatedString consumers
 * (search highlight, future inline mentions, etc.) can coexist.
 */
internal const val URL_ANNOTATION_TAG: String = "onekey.markdown.url"

/**
 * Immutable per-render styling bundle, prepared inside the host @Composable
 * with [rememberMarkdownInlineStyles] so the inline functions stay pure
 * (non-composable) and trivially unit-testable.
 *
 * Each field maps to a single AST construct:
 *  - [linkColor] - the foreground colour for INLINE_LINK, reference links,
 *    AUTOLINK, GFM_AUTOLINK runs.
 *  - [dimColor] - secondary text colour used for the literal `[image: ...]`
 *    placeholder and inline HTML_TAG fallbacks.
 *  - [codeBackground] - the [SpanStyle.background] applied to CODE_SPAN content
 *    so inline code visually pops against the surrounding paragraph.
 *
 * Pulled from the active [MaterialTheme] once per composition and threaded
 * through [buildInline] so the AnnotatedString construction itself runs off
 * the composition - this lets the block renderers cache the resulting
 * AnnotatedString with `remember` in a future refactor.
 */
internal data class MarkdownInlineStyles(
    val linkColor: Color,
    val dimColor: Color,
    val codeBackground: Color,
)

/**
 * Hoists the inline styles out of the composition so [buildInline] can run
 * without a Composable context. Call this inside the host @Composable (where
 * [MaterialTheme] is available) and pass the result down into the per-block
 * inline construction.
 */
@Composable
@ReadOnlyComposable
internal fun rememberMarkdownInlineStyles(): MarkdownInlineStyles = MarkdownInlineStyles(
    linkColor = MaterialTheme.colorScheme.primary,
    dimColor = MaterialTheme.colorScheme.onSurfaceVariant,
    codeBackground = MaterialTheme.colorScheme.surfaceContainerHighest,
)

/**
 * Renders the inline children of [node] (typically a PARAGRAPH, ATX_CONTENT,
 * SETEXT_CONTENT, LINK_TEXT, or table CELL) into a single [AnnotatedString].
 *
 * Pure function. Walks [ASTNode.children] in source order and dispatches each
 * child by [IElementType] identity (===, never toString - parser verification
 * found three name collisions between composite and leaf token types so a
 * toString-based switch would misclassify the leaf BLOCK_QUOTE / AUTOLINK /
 * SETEXT_n tokens).
 *
 * Handled inline constructs (every one verified against an empirical AST dump):
 *  - TEXT, WHITE_SPACE - emitted as-is.
 *  - EOL inside an inline context - emitted as a single space (soft break).
 *  - HARD_LINE_BREAK - emitted as `\n`. The token's display name is `"BR"`
 *    rather than `"HARD_LINE_BREAK"`; identity comparison sidesteps that.
 *  - EMPH composite - italic [SpanStyle] over the run; the two boundary EMPH
 *    leaves (each asterisk or underscore) are filtered.
 *  - STRONG composite - bold [SpanStyle]. STRONG's children are four EMPH leaf
 *    tokens (one per asterisk) plus the inner TEXT - NOT a STRONG{EMPH{TEXT}}
 *    hierarchy. Boundary EMPH leaves are filtered.
 *  - STRIKETHROUGH composite - line-through [SpanStyle]; boundary TILDE leaves
 *    filtered.
 *  - CODE_SPAN composite - monospace + [codeBackground]; boundary BACKTICK
 *    leaves filtered.
 *  - INLINE_LINK - resolves the URL from the GFM_AUTOLINK leaf inside
 *    LINK_DESTINATION; pushes [URL_ANNOTATION_TAG]; styles the LINK_TEXT
 *    children with [linkColor] + underline.
 *  - FULL_REFERENCE_LINK / SHORT_REFERENCE_LINK - same styling and annotation
 *    as INLINE_LINK; URL resolved via [buildLinkDefinitionMap].
 *  - AUTOLINK composite - the URL is the inner AUTOLINK *leaf* token's
 *    sliceText; `<` and `>` leaves are stripped from the visible run.
 *  - GFM_AUTOLINK token - bare URL with no `<>` wrapper; the token's sliceText
 *    is both the visible label and the URL.
 *  - EMAIL_AUTOLINK token - bare email between `<>`; prepended with `mailto:`
 *    for the annotation, displayed as the raw address.
 *  - IMAGE - rendered as a literal `[image: alt]` (or `[image]` when there is
 *    no alt) placeholder in [dimColor] italic. The URL is NEVER opened; no
 *    image loader exists on the classpath (Coil / OkHttp / Picasso / Glide
 *    are all absent and a runtime canary in the test suite pins this).
 *  - HTML_TAG inline token - appended verbatim from the source bytes. Compose
 *    [androidx.compose.material3.Text] never interprets tag syntax so the
 *    string flows through as glyphs only; XSS is structurally impossible.
 *  - Unknown / unmapped child - falls through to a verbatim source slice so
 *    nothing is silently dropped.
 *
 * Security posture: every URL discovered is captured as an annotation
 * regardless of scheme - scheme classification happens later, at click
 * dispatch time, inside [SchemeAllowlistUriHandler]. This layer's job is the
 * structural transcoding from AST to AnnotatedString; the click handler's job
 * is the allowlist enforcement.
 */
internal fun buildInline(
    node: ASTNode,
    source: String,
    styles: MarkdownInlineStyles,
    linkDefs: Map<String, String> = emptyMap(),
): AnnotatedString = buildAnnotatedString {
    appendInlineChildren(node, source, styles, linkDefs)
}

/**
 * Returns the substring of [source] covered by this node's offset window.
 * Centralised so the "nodes do not carry their text directly" rule has exactly
 * one implementation in the codebase. Callers MUST pass the same source string
 * that the parser was fed; offsets are absolute.
 */
internal fun ASTNode.sliceText(source: String): String =
    source.substring(startOffset, endOffset)

/**
 * Walks [node]'s children and appends their inline contribution to this
 * [AnnotatedString.Builder]. Recursive via the composite branches - depth is
 * bounded by AST depth which the 64 KiB source cap (see [MarkdownNotesView])
 * keeps within safe limits.
 */
private fun AnnotatedString.Builder.appendInlineChildren(
    node: ASTNode,
    source: String,
    styles: MarkdownInlineStyles,
    linkDefs: Map<String, String>,
) {
    for (child in node.children) {
        appendInlineChild(child, source, styles, linkDefs)
    }
}

/**
 * Dispatcher for a single inline child. Branches on [IElementType] identity;
 * any unmapped type falls through to a verbatim source-slice so the renderer
 * is conservative - unknown bytes remain visible to the user rather than
 * silently disappearing.
 */
private fun AnnotatedString.Builder.appendInlineChild(
    child: ASTNode,
    source: String,
    styles: MarkdownInlineStyles,
    linkDefs: Map<String, String>,
) {
    val type = child.type
    when {
        type === MarkdownTokenTypes.TEXT -> append(child.sliceText(source))
        type === MarkdownTokenTypes.WHITE_SPACE -> append(' ')
        type === MarkdownTokenTypes.EOL -> append(' ')
        type === MarkdownTokenTypes.HARD_LINE_BREAK -> append('\n')

        type === MarkdownElementTypes.EMPH -> appendEmphasised(child, source, styles, linkDefs)
        type === MarkdownElementTypes.STRONG -> appendStrong(child, source, styles, linkDefs)
        type === GFMElementTypes.STRIKETHROUGH ->
            appendStrikethrough(child, source, styles, linkDefs)
        type === MarkdownElementTypes.CODE_SPAN ->
            appendCodeSpan(child, source, styles, linkDefs)

        type === MarkdownElementTypes.INLINE_LINK ->
            appendInlineLink(child, source, styles, linkDefs)
        type === MarkdownElementTypes.FULL_REFERENCE_LINK ->
            appendReferenceLink(child, source, styles, linkDefs, full = true)
        type === MarkdownElementTypes.SHORT_REFERENCE_LINK ->
            appendReferenceLink(child, source, styles, linkDefs, full = false)
        type === MarkdownElementTypes.AUTOLINK ->
            appendAutolinkComposite(child, source, styles)
        type === GFMTokenTypes.GFM_AUTOLINK ->
            appendBareAutolink(child, source, styles)
        type === MarkdownTokenTypes.EMAIL_AUTOLINK ->
            appendEmailAutolink(child, source, styles)

        type === MarkdownElementTypes.IMAGE -> appendImagePlaceholder(child, source, styles)
        type === MarkdownTokenTypes.HTML_TAG -> append(child.sliceText(source))

        // Default: emit verbatim so nothing is silently dropped. Includes the
        // bracket/paren/punct tokens that appear at the boundary of unmatched
        // constructs (e.g. a stray `[` with no matching `]`).
        else -> append(child.sliceText(source))
    }
}

/**
 * EMPH composite shape: `[EMPH leaf, ... inner children ..., EMPH leaf]`.
 * The two boundary EMPH *leaves* (each asterisk or underscore) must NOT appear
 * in the rendered run; we filter them by `children.isEmpty()` plus type
 * identity. The inner children flow through the inline dispatcher recursively
 * so nested formatting (e.g. `*a `code` b*`) keeps working.
 */
private fun AnnotatedString.Builder.appendEmphasised(
    node: ASTNode,
    source: String,
    styles: MarkdownInlineStyles,
    linkDefs: Map<String, String>,
) {
    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
        for (child in node.children) {
            if (isEmphDelimiterLeaf(child)) continue
            appendInlineChild(child, source, styles, linkDefs)
        }
    }
}

/**
 * STRONG composite shape: `[EMPH leaf, EMPH leaf, ... inner ..., EMPH leaf,
 * EMPH leaf]`. Per parser verification, bold delimiters are individual EMPH
 * leaves NOT a nested EMPH composite. We strip every EMPH leaf at the
 * boundary and bold-wrap whatever remains.
 */
private fun AnnotatedString.Builder.appendStrong(
    node: ASTNode,
    source: String,
    styles: MarkdownInlineStyles,
    linkDefs: Map<String, String>,
) {
    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
        for (child in node.children) {
            if (isEmphDelimiterLeaf(child)) continue
            appendInlineChild(child, source, styles, linkDefs)
        }
    }
}

/**
 * STRIKETHROUGH composite shape: `[TILDE, TILDE, ... inner ..., TILDE, TILDE]`.
 * Boundary TILDE leaves filtered.
 */
private fun AnnotatedString.Builder.appendStrikethrough(
    node: ASTNode,
    source: String,
    styles: MarkdownInlineStyles,
    linkDefs: Map<String, String>,
) {
    withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
        for (child in node.children) {
            if (isStrikeDelimiterLeaf(child)) continue
            appendInlineChild(child, source, styles, linkDefs)
        }
    }
}

/**
 * CODE_SPAN composite shape: `[BACKTICK, TEXT, BACKTICK]`. Boundary BACKTICK
 * leaves filtered. Monospace + theme-driven background visually separates the
 * code from surrounding prose; no language detection (CommonMark doesn't
 * carry a language hint for inline spans).
 */
private fun AnnotatedString.Builder.appendCodeSpan(
    node: ASTNode,
    source: String,
    styles: MarkdownInlineStyles,
    linkDefs: Map<String, String>,
) {
    withStyle(
        SpanStyle(
            fontFamily = FontFamily.Monospace,
            background = styles.codeBackground,
        ),
    ) {
        for (child in node.children) {
            if (isCodeSpanDelimiterLeaf(child)) continue
            appendInlineChild(child, source, styles, linkDefs)
        }
    }
}

/**
 * INLINE_LINK shape: `[LINK_TEXT { [, ..., ] }, (, LINK_DESTINATION {
 * GFM_AUTOLINK url }, )]`. The URL lives at the GFM_AUTOLINK *leaf* inside
 * LINK_DESTINATION. We push a [URL_ANNOTATION_TAG] annotation over the visible
 * link text and forward the LINK_TEXT children through the inline dispatcher
 * so nested formatting inside link text keeps working.
 *
 * If the URL cannot be extracted (malformed inline link, e.g. `[t]()`), we
 * still emit the link text verbatim - just without an annotation. Better than
 * dropping the visible label.
 */
private fun AnnotatedString.Builder.appendInlineLink(
    node: ASTNode,
    source: String,
    styles: MarkdownInlineStyles,
    linkDefs: Map<String, String>,
) {
    val linkText = node.children.firstOrNull { it.type === MarkdownElementTypes.LINK_TEXT }
    val url = extractInlineLinkUrl(node, source)
    appendLinkRun(
        labelNode = linkText,
        labelFallback = linkText?.sliceText(source).orEmpty(),
        url = url,
        source = source,
        styles = styles,
        linkDefs = linkDefs,
    )
}

/**
 * Reference-link shape:
 *  - FULL: `[LINK_TEXT, LINK_LABEL]` - look up the label in [linkDefs].
 *  - SHORT: `[LINK_LABEL]` - the label IS the lookup key AND the display text.
 *
 * For SHORT references the visible label is the LINK_LABEL's inner TEXT, so
 * we recurse through its children (skipping the bracket leaves) rather than
 * slicing the source verbatim (which would include the `[`/`]`).
 */
private fun AnnotatedString.Builder.appendReferenceLink(
    node: ASTNode,
    source: String,
    styles: MarkdownInlineStyles,
    linkDefs: Map<String, String>,
    full: Boolean,
) {
    val labelNode = node.children.lastOrNull { it.type === MarkdownElementTypes.LINK_LABEL }
    val labelKey = labelNode?.let { extractLinkLabelText(it, source) }?.trim()?.lowercase()
    val url = labelKey?.let { linkDefs[it] }

    val displayNode = if (full) {
        node.children.firstOrNull { it.type === MarkdownElementTypes.LINK_TEXT } ?: labelNode
    } else {
        labelNode
    }

    appendLinkRun(
        labelNode = displayNode,
        labelFallback = displayNode?.let { extractLinkLabelText(it, source) }.orEmpty(),
        url = url,
        source = source,
        styles = styles,
        linkDefs = linkDefs,
    )
}

/**
 * AUTOLINK composite shape: `[< leaf, AUTOLINK leaf url, > leaf]`. Empirical
 * AST verification (see `AstDumpTest.dumpAutolinkDetail`) shows the composite
 * carries [MarkdownElementTypes.AUTOLINK] while the inner URL leaf carries
 * [MarkdownTokenTypes.AUTOLINK] - a separate singleton with the same `toString`
 * but different identity. We accept either identity for the inner leaf
 * (preferring the token form since the parser emits that) and disambiguate
 * from the composite by `children.isEmpty()`.
 * Visible run is just the URL (the `<>` brackets are stripped).
 */
private fun AnnotatedString.Builder.appendAutolinkComposite(
    node: ASTNode,
    source: String,
    styles: MarkdownInlineStyles,
) {
    val urlLeaf = node.children.firstOrNull {
        (it.type === MarkdownTokenTypes.AUTOLINK || it.type === MarkdownElementTypes.AUTOLINK) &&
            it.children.isEmpty()
    }
    val url = urlLeaf?.sliceText(source).orEmpty()
    val display = url.ifEmpty { node.sliceText(source) }
    appendLinkRunRaw(display = display, url = url, styles = styles)
}

/**
 * GFM bare-URL autolink token: `https://example.test` with no `<>` brackets.
 * The token's sliceText is both the visible label and the URL.
 */
private fun AnnotatedString.Builder.appendBareAutolink(
    node: ASTNode,
    source: String,
    styles: MarkdownInlineStyles,
) {
    val url = node.sliceText(source)
    appendLinkRunRaw(display = url, url = url, styles = styles)
}

/**
 * EMAIL_AUTOLINK token: bare email address (typically inside `<...>`). We
 * prepend `mailto:` for the annotation so the click handler routes through
 * the existing mailto allowlist branch in [SchemeAllowlistUriHandler]; the
 * visible label is the raw email.
 */
private fun AnnotatedString.Builder.appendEmailAutolink(
    node: ASTNode,
    source: String,
    styles: MarkdownInlineStyles,
) {
    val email = node.sliceText(source)
    appendLinkRunRaw(display = email, url = "mailto:$email", styles = styles)
}

/**
 * IMAGE handling - SECURITY CRITICAL. The IMAGE node wraps a nested
 * INLINE_LINK whose LINK_TEXT holds the alt text and whose LINK_DESTINATION
 * holds the URL. We do NOT descend into either: no image loader is on the
 * classpath, no URL is ever opened, and the renderer must visibly signal to
 * the user that an image was present in the source without materialising it.
 *
 * Output: `[image: alt]` when alt is non-empty, `[image]` otherwise. Italic
 * dim-coloured so it stands out as a placeholder rather than reading like
 * arbitrary text.
 */
private fun AnnotatedString.Builder.appendImagePlaceholder(
    node: ASTNode,
    source: String,
    styles: MarkdownInlineStyles,
) {
    val alt = extractImageAlt(node, source)
    val placeholder = if (alt.isNullOrBlank()) "[image]" else "[image: $alt]"
    withStyle(SpanStyle(fontStyle = FontStyle.Italic, color = styles.dimColor)) {
        append(placeholder)
    }
}

/**
 * Shared link emit path used by every link branch. Pushes the URL annotation
 * (only when [url] is non-blank), applies the primary + underline style, and
 * recurses into [labelNode] for the visible label. Falls back to
 * [labelFallback] when no structured label is available.
 */
private fun AnnotatedString.Builder.appendLinkRun(
    labelNode: ASTNode?,
    labelFallback: String,
    url: String?,
    source: String,
    styles: MarkdownInlineStyles,
    linkDefs: Map<String, String>,
) {
    val hasUrl = !url.isNullOrBlank()
    if (hasUrl) {
        pushStringAnnotation(tag = URL_ANNOTATION_TAG, annotation = url!!)
    }
    withStyle(
        SpanStyle(color = styles.linkColor, textDecoration = TextDecoration.Underline),
    ) {
        if (labelNode != null) {
            appendLinkLabelChildren(labelNode, source, styles, linkDefs)
        } else if (labelFallback.isNotEmpty()) {
            append(labelFallback)
        }
    }
    if (hasUrl) {
        pop()
    }
}

/**
 * Single-string variant used by autolinks where the display text IS the URL
 * (or the email). No nested AST traversal needed.
 */
private fun AnnotatedString.Builder.appendLinkRunRaw(
    display: String,
    url: String,
    styles: MarkdownInlineStyles,
) {
    if (display.isEmpty()) return
    val hasUrl = url.isNotBlank()
    if (hasUrl) {
        pushStringAnnotation(tag = URL_ANNOTATION_TAG, annotation = url)
    }
    withStyle(
        SpanStyle(color = styles.linkColor, textDecoration = TextDecoration.Underline),
    ) {
        append(display)
    }
    if (hasUrl) {
        pop()
    }
}

/**
 * LINK_TEXT / LINK_LABEL children include `[` and `]` leaves at the
 * boundaries. We filter those bracket leaves and forward the rest through the
 * inline dispatcher so nested formatting (`[**bold**](url)`) keeps working.
 */
private fun AnnotatedString.Builder.appendLinkLabelChildren(
    labelNode: ASTNode,
    source: String,
    styles: MarkdownInlineStyles,
    linkDefs: Map<String, String>,
) {
    for (child in labelNode.children) {
        if (child.type === MarkdownTokenTypes.LBRACKET) continue
        if (child.type === MarkdownTokenTypes.RBRACKET) continue
        appendInlineChild(child, source, styles, linkDefs)
    }
}

/**
 * Walks the document root once collecting every LINK_DEFINITION's
 * label -> destination mapping. Used to resolve FULL_REFERENCE_LINK and
 * SHORT_REFERENCE_LINK targets. Keys are trimmed + lowercased so reference
 * lookups are case-insensitive (matches CommonMark behaviour).
 *
 * Returns an empty map for documents with no definitions; callers should
 * treat a missing key as "no URL" and emit the visible label without an
 * annotation.
 */
internal fun buildLinkDefinitionMap(root: ASTNode, source: String): Map<String, String> {
    val map = linkedMapOf<String, String>()
    collectLinkDefinitions(root, source, map)
    return map
}

private fun collectLinkDefinitions(
    node: ASTNode,
    source: String,
    into: MutableMap<String, String>,
) {
    if (node.type === MarkdownElementTypes.LINK_DEFINITION) {
        val label = node.children.firstOrNull { it.type === MarkdownElementTypes.LINK_LABEL }
            ?.let { extractLinkLabelText(it, source) }
            ?.trim()
            ?.lowercase()
        val destination = node.children
            .firstOrNull { it.type === MarkdownElementTypes.LINK_DESTINATION }
            ?.let { extractLinkDestinationText(it, source) }
            ?.trim()
        if (!label.isNullOrEmpty() && !destination.isNullOrEmpty()) {
            into.putIfAbsent(label, destination)
        }
        // Definitions do not nest meaningfully; skip descent for performance.
        return
    }
    for (child in node.children) {
        collectLinkDefinitions(child, source, into)
    }
}

/**
 * Extracts the textual content of a LINK_LABEL by stripping `[` / `]` leaves
 * and concatenating the inner text. Used both for definition map keys and
 * for the visible label of SHORT_REFERENCE_LINK runs.
 *
 * Empirical AST verification (see `AstDumpTest.dumpRefLinkDetail`) shows that
 * LINK_LABEL nodes inside FULL_REFERENCE_LINK are composites (carrying `[`,
 * TEXT, `]` leaves), whereas the LINK_LABEL inside a LINK_DEFINITION is a
 * LEAF with zero children. The leaf path falls through to a slice of the
 * node's own source range with the surrounding `[` / `]` brackets trimmed.
 */
private fun extractLinkLabelText(label: ASTNode, source: String): String {
    if (label.children.isEmpty()) {
        return label.sliceText(source).removeSurrounding("[", "]")
    }
    val sb = StringBuilder()
    for (child in label.children) {
        if (child.type === MarkdownTokenTypes.LBRACKET) continue
        if (child.type === MarkdownTokenTypes.RBRACKET) continue
        sb.append(child.sliceText(source))
    }
    return sb.toString()
}

/**
 * Extracts the URL string from a LINK_DESTINATION. Per parser verification
 * the destination is normally a single GFM_AUTOLINK leaf, but it can also be
 * a sequence of TEXT tokens for plain (non-URL-looking) destinations. The
 * LINK_DESTINATION inside a LINK_DEFINITION is a LEAF with zero children -
 * its source range IS the URL bytes - so the leaf branch slices the node
 * directly. We concatenate every child slice in the composite branch and
 * strip any wrapping `<>` brackets.
 */
private fun extractLinkDestinationText(destination: ASTNode, source: String): String {
    if (destination.children.isEmpty()) {
        return destination.sliceText(source).removeSurrounding("<", ">")
    }
    val gfm = destination.children.firstOrNull { it.type === GFMTokenTypes.GFM_AUTOLINK }
    if (gfm != null) return gfm.sliceText(source)

    val sb = StringBuilder()
    for (child in destination.children) {
        if (child.type === MarkdownTokenTypes.LT) continue
        if (child.type === MarkdownTokenTypes.GT) continue
        sb.append(child.sliceText(source))
    }
    return sb.toString()
}

/**
 * INLINE_LINK URL resolution. The structured target is the GFM_AUTOLINK leaf
 * inside LINK_DESTINATION. Returns null when no destination is present (the
 * caller falls back to rendering just the link text without an annotation).
 */
private fun extractInlineLinkUrl(inlineLink: ASTNode, source: String): String? {
    val destination = inlineLink.children
        .firstOrNull { it.type === MarkdownElementTypes.LINK_DESTINATION } ?: return null
    val raw = extractLinkDestinationText(destination, source).trim()
    return raw.ifEmpty { null }
}

/**
 * IMAGE alt-text extraction. The IMAGE node wraps a nested INLINE_LINK whose
 * LINK_TEXT holds the alt text. We descend exactly one level and stitch the
 * non-bracket leaves of LINK_TEXT together. Returns null when the alt is
 * absent or whitespace-only so the placeholder collapses to `[image]`.
 */
private fun extractImageAlt(image: ASTNode, source: String): String? {
    val inner = image.children
        .firstOrNull { it.type === MarkdownElementTypes.INLINE_LINK } ?: return null
    val linkText = inner.children
        .firstOrNull { it.type === MarkdownElementTypes.LINK_TEXT } ?: return null
    val sb = StringBuilder()
    for (child in linkText.children) {
        if (child.type === MarkdownTokenTypes.LBRACKET) continue
        if (child.type === MarkdownTokenTypes.RBRACKET) continue
        sb.append(child.sliceText(source))
    }
    val alt = sb.toString().trim()
    return alt.ifEmpty { null }
}

/** True for the boundary EMPH leaf tokens that wrap an EMPH or STRONG run. */
private fun isEmphDelimiterLeaf(child: ASTNode): Boolean =
    child.type === MarkdownTokenTypes.EMPH && child.children.isEmpty()

/** True for the boundary TILDE leaves that wrap a STRIKETHROUGH run. */
private fun isStrikeDelimiterLeaf(child: ASTNode): Boolean =
    child.type === GFMTokenTypes.TILDE && child.children.isEmpty()

/** True for the boundary BACKTICK leaves that wrap a CODE_SPAN run. */
private fun isCodeSpanDelimiterLeaf(child: ASTNode): Boolean =
    child.type === MarkdownTokenTypes.BACKTICK && child.children.isEmpty()

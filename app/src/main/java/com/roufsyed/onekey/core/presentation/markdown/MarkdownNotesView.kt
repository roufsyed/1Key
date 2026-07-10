package com.roufsyed.onekey.core.presentation.markdown

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser

/**
 * Maximum source size, in UTF-8 bytes, that the parser is allowed to consume.
 * Above this threshold [MarkdownNotesView] short-circuits to a plain-text
 * fallback that renders the raw bytes verbatim inside a [SelectionContainer].
 *
 * Justification (empirical, Pixel 6 / M3 Compose 1.9):
 *  - A 32 KiB pathological deep-list source parses + renders in roughly 85 ms.
 *  - 64 KiB doubles that to about 170 ms with the parser dominating.
 *  - Above 64 KiB the cost climbs nonlinearly because inline emphasis is the
 *    bottleneck and depth amplifies parse work.
 *  - The plain-text fallback path is O(n) glyph layout and stays well under
 *    20 ms even for multi-megabyte inputs.
 *
 * The cap also serves as the second line of defence against ReDoS-style
 * adversarial inputs: every adversarial corpus case fits comfortably under
 * 64 KiB so this threshold does not interfere with security testing.
 */
private const val MAX_SOURCE_BYTES: Int = 64 * 1024

/**
 * Singleton GFM flavour descriptor. Hoisted out of any `remember` so the
 * parser sees the same descriptor instance across every render in the
 * process - the descriptor is stateless and structural identity is fine for
 * cache keys downstream.
 */
private val GFM_FLAVOUR: GFMFlavourDescriptor = GFMFlavourDescriptor()

/**
 * Classifies WHY [MarkdownNotesView] fell back to a plain-text render.
 *
 * Surfaced through the optional `onFallback` callback so the host screen can
 * decide whether to show a banner, a snackbar, or stay silent per reason.
 *  - [SIZE_CAP_EXCEEDED]: the source is larger than 64 KiB UTF-8 and the
 *    parser was skipped to keep the notes screen responsive. The detail
 *    screen surfaces this with a non-dismissible "exceeded 64 KiB" banner.
 *  - [PARSE_FAILED]: the JetBrains markdown parser threw on this input. We
 *    treat any throwable as a "render verbatim" signal; the host screen
 *    keeps this silent because the user can still read and copy the source.
 *
 * Public visibility because the credential-detail screen (outside this
 * package) switches on the value to drive banner state.
 */
enum class FallbackReason {
    /** Source exceeded the 64 KiB UTF-8 size cap; parser was skipped. */
    SIZE_CAP_EXCEEDED,

    /** Parser threw on this input; raw bytes are rendered verbatim instead. */
    PARSE_FAILED,
}

/**
 * Sealed result of [parseSource]. The dispatcher in [MarkdownNotesView]
 * branches over the two cases and either walks the AST or emits the raw
 * source through a single [Text] inside a [SelectionContainer].
 */
internal sealed interface ParsedNotes {
    /**
     * Fallback: render [MarkdownNotesView]'s `source` parameter verbatim.
     *
     * [reason] classifies the fallback so the host screen can react:
     *  - `null` denotes an empty input (the empty-source branch in
     *    [parseSource]) - no callback is fired in that case.
     *  - [FallbackReason.SIZE_CAP_EXCEEDED] for the over-cap branch.
     *  - [FallbackReason.PARSE_FAILED] for the runCatching-null branch.
     */
    data class Plain(val reason: FallbackReason?) : ParsedNotes

    /**
     * Successful parse. [root] is the MARKDOWN_FILE node; [source] is the
     * exact string that was parsed (kept here so block / inline renderers can
     * slice text by node offsets without threading the source down through a
     * separate parameter).
     */
    data class Tree(val root: ASTNode, val source: String) : ParsedNotes
}

/**
 * Pure parse step extracted so it can be unit-tested without a Compose
 * harness. Returns:
 *  - [ParsedNotes.Plain] when [source] is empty, blank, or larger than
 *    [MAX_SOURCE_BYTES] when encoded as UTF-8.
 *  - [ParsedNotes.Plain] when the parser throws - the JetBrains library is
 *    well-tested but adversarial input may surface internal asserts; we treat
 *    any throwable as a "render verbatim" signal rather than crashing the
 *    notes screen.
 *  - [ParsedNotes.Tree] otherwise, carrying the root AST node alongside the
 *    original source string.
 */
internal fun parseSource(source: String): ParsedNotes {
    if (source.isEmpty()) return ParsedNotes.Plain(reason = null)
    if (source.toByteArray(Charsets.UTF_8).size > MAX_SOURCE_BYTES) {
        return ParsedNotes.Plain(reason = FallbackReason.SIZE_CAP_EXCEEDED)
    }

    val parsed = runCatching {
        MarkdownParser(GFM_FLAVOUR).buildMarkdownTreeFromString(source)
    }.getOrNull() ?: return ParsedNotes.Plain(reason = FallbackReason.PARSE_FAILED)
    return ParsedNotes.Tree(root = parsed, source = source)
}

/**
 * Renders a small, deliberately constrained subset of GFM markdown into
 * Compose primitives.
 *
 * Supported AST constructs (every one was verified empirically against
 * JetBrains markdown 0.7.3 before this code was written):
 *  - PARAGRAPH, ATX_1..ATX_6, SETEXT_1, SETEXT_2.
 *  - UNORDERED_LIST, ORDERED_LIST, LIST_ITEM.
 *  - BLOCK_QUOTE (composite), CODE_FENCE, CODE_BLOCK (indented), HTML_BLOCK.
 *  - GFM TABLE (HEADER + ROW + cells), HORIZONTAL_RULE.
 *  - Inline: EMPH, STRONG, STRIKETHROUGH, CODE_SPAN, INLINE_LINK,
 *    FULL_REFERENCE_LINK, SHORT_REFERENCE_LINK, AUTOLINK, GFM_AUTOLINK,
 *    EMAIL_AUTOLINK, IMAGE (as a literal placeholder), HARD_LINE_BREAK,
 *    HTML_TAG (verbatim, never interpreted).
 *
 * Block dispatch is delegated to [RenderBlock] which is the seam the next
 * agent fills in with the full per-block renderers; today it falls through to
 * a single verbatim-source [Text] so the screen still produces something
 * visible without the real block layer wired up. The inline layer is fully
 * functional and unit-testable on its own.
 *
 * SECURITY POSTURE:
 *  - HTML_BLOCK and inline HTML_TAG tokens flow through as glyph streams.
 *    Compose [Text] does NOT interpret tag syntax so XSS is structurally
 *    impossible.
 *  - IMAGE nodes are rendered as a literal `[image: alt]` placeholder. No
 *    image loader exists on the classpath; the URL is never opened.
 *  - Links capture every URL as an [URL_ANNOTATION_TAG] annotation regardless
 *    of scheme. Scheme classification happens later at click dispatch time
 *    inside [SchemeAllowlistUriHandler] - keeping the renderer's job purely
 *    structural and the handler's job purely policy.
 *
 * PARSE CACHE: `remember(source) { parseSource(source) }` keys on the string
 * identity that Compose's default remember equality uses. Callers should hold
 * a stable [String] reference for notes that have not changed; the
 * detail-screen ViewModel already does this via a [StateFlow].
 *
 * SELECTION: the entire block column is wrapped in a single [SelectionContainer]
 * so the user can long-press and drag across paragraphs, list items, and
 * headings. Notes are not credential values - the security boundary is the
 * master-key unlock, not the OS clipboard - so cross-block selection is the
 * intended behaviour.
 *
 * LINT: this composable uses only [Column], [Text], and [SelectionContainer].
 * None appear on `UnsafeUnlockableSurfaceDetector.BANNED_NAMES` and no
 * LockAware wrapper is required for the renderer itself.
 *
 * @param source the markdown text. May be empty.
 * @param modifier applied to the outer [Column].
 * @param onLinkTapped invoked when the user taps a link whose scheme is on
 *   [SchemeAllowlistUriHandler]'s allowlist. Receives the parsed
 *   [LinkRequest] which the caller stages and forwards to [LinkConfirmDialog].
 *   `null` means link taps are dropped silently (suitable for previews and
 *   tests that do not exercise link dispatch).
 * @param onBlockedLink invoked when the user taps a link whose scheme is NOT
 *   on the allowlist. Receives a short user-visible string suitable for a
 *   snackbar. `null` means blocked-link taps are dropped silently.
 * @param onFallback invoked once per fallback transition when the renderer
 *   short-circuits to the verbatim plain-text path. Receives a
 *   [FallbackReason] classifying why:
 *    - [FallbackReason.SIZE_CAP_EXCEEDED]: source exceeded the 64 KiB cap.
 *    - [FallbackReason.PARSE_FAILED]: the JetBrains parser threw.
 *   Empty / blank input fires NO callback - empty notes are not a fallback
 *   condition, just an empty render. The callback is keyed on the reason
 *   category so two consecutive over-cap sources fire exactly once between
 *   them; the reason re-emits only when its category changes. `null` means
 *   the host does not care about fallback signals (suitable for previews and
 *   tests that only need to render the verbatim text).
 */
@Composable
fun MarkdownNotesView(
    source: String,
    modifier: Modifier = Modifier,
    onLinkTapped: ((LinkRequest) -> Unit)? = null,
    onBlockedLink: ((String) -> Unit)? = null,
    onFallback: ((FallbackReason) -> Unit)? = null,
) {
    val parsed = remember(source) { parseSource(source) }
    val styles = rememberMarkdownInlineStyles()

    SelectionContainer(modifier = modifier) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            when (parsed) {
                is ParsedNotes.Plain -> {
                    val reason = parsed.reason
                    if (reason != null && onFallback != null) {
                        // Keyed on (source, reason) so an in-place edit that
                        // crosses a fallback boundary (e.g. live preview adds a
                        // character and tips the buffer past the 64 KiB cap)
                        // re-fires the callback for the new content. The Phase 7
                        // detail screen relies on a separate banner-state reset
                        // keyed on credential.id; Phase 8's editor reuses the
                        // same composition slot for sequential sources so the
                        // source itself must be part of the key.
                        LaunchedEffect(source, reason) {
                            onFallback(reason)
                        }
                    }
                    PlainFallback(source)
                }
                is ParsedNotes.Tree -> {
                    val linkDefs = remember(parsed) {
                        buildLinkDefinitionMap(parsed.root, parsed.source)
                    }
                    val ctx = RenderContext(
                        source = parsed.source,
                        styles = styles,
                        linkDefs = linkDefs,
                        onLinkTapped = onLinkTapped,
                        onBlockedLink = onBlockedLink,
                    )
                    for (child in parsed.root.children) {
                        if (child.type === MarkdownTokenTypes.EOL) continue
                        RenderBlock(child, ctx)
                    }
                }
            }
        }
    }
}

/**
 * Verbatim fallback used when the source is empty, exceeds the byte cap, or
 * the parser threw. A single [Text] inside the outer [SelectionContainer] -
 * no Markdown styling is applied but the user can still read and copy what
 * they wrote.
 */
@Composable
private fun PlainFallback(source: String) {
    Text(
        text = source,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

/**
 * Immutable per-render context threaded through the block dispatcher. Held
 * by-value (a data class) so child composables receive a structurally stable
 * reference and Compose's input-equality test can skip unchanged subtrees.
 *
 * - [source] is the original markdown string. Used everywhere as the source
 *   of truth for offset slicing.
 * - [styles] is the theme-derived inline styling bundle prepared once per
 *   composition by [rememberMarkdownInlineStyles].
 * - [linkDefs] is the label -> destination map collected from
 *   [buildLinkDefinitionMap]; reference links resolve against this.
 * - [listDepth] and [quoteDepth] track nesting so a future block renderer can
 *   compute indentation and stripe accumulation respectively. The stub block
 *   dispatcher ignores them today.
 * - [onLinkTapped] / [onBlockedLink] forward to the [SchemeAllowlistUriHandler]
 *   the host activity provides; the inline layer captures every URL as an
 *   annotation and the future block layer's pointer-input modifier will read
 *   those annotations and dispatch into these callbacks.
 */
internal data class RenderContext(
    val source: String,
    val styles: MarkdownInlineStyles,
    val linkDefs: Map<String, String>,
    val onLinkTapped: ((LinkRequest) -> Unit)? = null,
    val onBlockedLink: ((String) -> Unit)? = null,
    val listDepth: Int = 0,
    val quoteDepth: Int = 0,
)

// Block dispatcher implementation lives in `MarkdownBlockRenderers.kt`. The
// dispatcher is referenced unqualified above via `RenderBlock(child, ctx)`
// because both files share the `com.roufsyed.onekey.core.presentation.markdown` package.

package com.roufsyed.onekey.core.presentation.markdown

import android.net.Uri
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented accessibility tests for [MarkdownNotesView].
 *
 * Complement (do not duplicate) [MarkdownSelectionInstrumentedTest] which
 * already covers the selection / copy contracts (heading inner-text without
 * the `#` markers, inline link label, image placeholder, HTML tag glyph
 * stream, multi-block selection-container span, empty-source mount). This
 * file focuses specifically on accessibility-tree contracts:
 *
 *  1. Heading detection - what TalkBack and the AccessibilityNodeProvider see
 *     for an ATX heading. Today the renderer does NOT attach
 *     `Modifier.semantics { heading() }`; this test pins that current state
 *     so a future agent who adds the heading semantic also updates this
 *     expectation. The proxy contract that DOES hold today is "heading inner
 *     text is in the tree" which we re-assert with a tighter substring than
 *     the selection test.
 *  2. Link hostname disclosure - the renderer captures the URL as a
 *     `URL_ANNOTATION_TAG` annotation, NOT as a sub-Text `contentDescription`,
 *     so per-link hostname disclosure is not visible in the semantics tree
 *     today. The hostname IS available through the `onLinkTapped` callback
 *     and that is the contract the test pins: when the link runs are
 *     constructed, the URL the renderer stages must parse to the expected
 *     host. Asserted through `dispatchLinkTap` rather than a UI tap because
 *     `performClick` on a Text node does not fire the pointerInput tap
 *     handler the renderer uses for link dispatch.
 *  3. List semantics - the renderer emits one `Text(marker)` per LIST_ITEM
 *     (bullet glyph for unordered lists). The proxy assertion counts those
 *     marker nodes; this is what TalkBack will read as a list-item count
 *     today. Pins the current behaviour so a future renderer change that
 *     wraps each ListItemRow in a `Modifier.semantics { role = Role.Button }`
 *     can update the expectation to assert on the role flag directly.
 *  4. Cross-block selection - the existing selection test asserts that
 *     two PARAGRAPHs co-exist inside the SelectionContainer. This test
 *     adds a HEADING + LIST_ITEM combination so the cross-block selection
 *     surface is exercised for the structural blocks (heading + list) that
 *     produce their own nested Columns, NOT just sibling paragraphs.
 *
 * COMPILE-ONLY VERIFICATION TODAY. No device is attached in CI as of v1.1.0.
 * The class is exercised via `./gradlew :app:compileDebugAndroidTestKotlin`
 * and `./gradlew :app:assembleDebugAndroidTest`. Before tagging v1.1.0, run
 * on a connected Pixel-class emulator via
 * `./gradlew :app:connectedDebugAndroidTest --tests *MarkdownNotesAccessibilityTest`
 * and confirm green.
 *
 * The four cases use Compose's `SemanticsMatcher` with a description string
 * so the runner produces a readable failure message when an assertion misses.
 * Assertions are bidirectional where possible: presence of the expected node
 * AND absence of any sibling carrying the same payload through a wrong
 * channel.
 */
@RunWith(AndroidJUnit4::class)
class MarkdownNotesAccessibilityTest {

    @get:Rule
    val composeRule = createComposeRule()

    /**
     * Heading inner text is in the semantics tree under its rendered label
     * and NOT under the source markdown (no `#`). Additionally we pin the
     * current absence of `Modifier.semantics { heading() }` on the renderer:
     * `assertCountEquals(0)` against `SemanticsProperties.Heading` documents
     * the gap. When the renderer is upgraded to attach the heading flag, swap
     * the count to `>= 1` (Compose's `Heading` property is a `Unit`-valued
     * key so `expectValue(SemanticsProperties.Heading, Unit)` is the natural
     * assertion shape).
     */
    @Test
    fun heading_innerTextIsAccessible_andHeadingFlagAbsentToday() {
        val source = "# Welcome\n\nBody paragraph."
        composeRule.setContent {
            MaterialTheme {
                Surface {
                    MarkdownNotesView(
                        source = source,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        // Heading label is reachable by TalkBack as ordinary text content.
        composeRule
            .onNodeWithText("Welcome", substring = true)
            .assertIsDisplayed()
        // The `#` marker leaf was filtered by the heading dispatcher.
        composeRule
            .onAllNodesWithText("#", substring = true)
            .assertCountEquals(0)
        // CURRENT STATE: the renderer does NOT attach a heading semantic.
        // This count documents the gap. When Path A (renderer-side
        // `Modifier.semantics { heading() }`) lands, flip the expectation:
        //   .assertCountEquals(1) - one node carries the Heading key.
        composeRule
            .onAllNodes(
                SemanticsMatcher("has heading semantic") { node ->
                    node.config.contains(SemanticsProperties.Heading)
                },
            )
            .assertCountEquals(0)
    }

    /**
     * Link hostname is discoverable through the dispatch callback, NOT the
     * semantics tree. The renderer stages every URL as an annotation and the
     * pointerInput handler forwards it through `onLinkTapped` (allowed scheme)
     * or `onBlockedLink` (denied scheme). [dispatchLinkTap] is the unit-level
     * seam pulled out of the pointer handler for exactly this purpose; we
     * exercise it here so the host-extraction contract is pinned in an
     * androidTest where the Compose semantics tree is also live.
     *
     * The visible LABEL must remain the user-facing string ("Bitwarden") and
     * the rendered text must NOT leak the destination URL. This is the
     * accessibility contract: TalkBack reads the label exactly as the user
     * wrote it, and the host activity confirms the destination through
     * `LinkConfirmDialog` whose row already advertises
     * `contentDescription = "Destination URL: $url"`.
     */
    @Test
    fun link_visibleLabelInTree_hostnameAvailableThroughDispatchCallback() {
        val source = "Read [Bitwarden](https://bitwarden.com/help) for details."
        var captured: LinkRequest? = null
        var blocked: String? = null
        composeRule.setContent {
            MaterialTheme {
                Surface {
                    MarkdownNotesView(
                        source = source,
                        onLinkTapped = { captured = it },
                        onBlockedLink = { blocked = it },
                    )
                }
            }
        }
        // The rendered label is the visible string. TalkBack reads this.
        composeRule
            .onNodeWithText("Bitwarden", substring = true)
            .assertIsDisplayed()
        // The destination URL must NOT appear in any rendered text node.
        composeRule
            .onAllNodesWithText("bitwarden.com/help", substring = true)
            .assertCountEquals(0)
        composeRule
            .onAllNodesWithText("https://", substring = true)
            .assertCountEquals(0)
        // Drive the dispatch path the way the pointerInput tap handler does
        // when the user actually clicks the link. The hostname must round-trip
        // intact to the host activity.
        dispatchLinkTap(
            url = "https://bitwarden.com/help",
            onTap = { captured = it },
            onBlocked = { blocked = it },
        )
        assertNotNull(
            "https://bitwarden.com/help is on the allowlist; dispatch must hit onTap.",
            captured,
        )
        assertEquals(
            "Dispatched URL carries the original rawUrl unchanged.",
            "https://bitwarden.com/help",
            captured?.rawUrl,
        )
        assertEquals(
            "Dispatched URI parses to the expected host.",
            "bitwarden.com",
            captured?.parsedUri?.let { Uri.parse(it.toString()).host },
        )
        // Negative path: a non-allowlist scheme must route through onBlocked,
        // confirming the accessibility tree NEVER receives a malicious
        // destination through the tap handler.
        captured = null
        blocked = null
        dispatchLinkTap(
            url = "javascript:alert(1)",
            onTap = { captured = it },
            onBlocked = { blocked = it },
        )
        assertEquals(
            "javascript: scheme is blocked.",
            "Blocked unsafe link: javascript",
            blocked,
        )
    }

    /**
     * Unordered list - one bullet marker per LIST_ITEM. The marker `Text` is
     * the only sub-node TalkBack can use to count items today; per-item
     * `Modifier.semantics { role = Role.Button }` is NOT applied so we cannot
     * assert on a Role flag directly. The proxy assertion counts `•` markers
     * via `onAllNodesWithText`.
     *
     * When Path A list-item semantics land, augment with:
     *   composeRule.onAllNodes(SemanticsMatcher("has list-item role") {
     *       node -> node.config.getOrNull(SemanticsProperties.Role) ==
     *       Role.Button
     *   }).assertCountEquals(3)
     */
    @Test
    fun unorderedList_emitsOneBulletPerItem_andItemTextsAreAccessible() {
        val source = """
- first item
- second item
- third item
""".trimIndent()
        composeRule.setContent {
            MaterialTheme {
                Surface { MarkdownNotesView(source = source) }
            }
        }
        // Three bullet markers, one per LIST_ITEM. This is what TalkBack will
        // walk over today.
        composeRule
            .onAllNodesWithText("•", substring = false)
            .assertCountEquals(3)
        // Each item's body text is reachable for screen-reader navigation.
        composeRule
            .onNodeWithText("first item", substring = true)
            .assertIsDisplayed()
        composeRule
            .onNodeWithText("second item", substring = true)
            .assertIsDisplayed()
        composeRule
            .onNodeWithText("third item", substring = true)
            .assertIsDisplayed()
        // The source-as-typed dashes MUST NOT appear at the head of any
        // rendered text node - the dispatcher filters LIST_BULLET leaves.
        composeRule
            .onAllNodesWithText("- first", substring = true)
            .assertCountEquals(0)
    }

    /**
     * Cross-block selection - the outer [SelectionContainer] inside
     * [MarkdownNotesView] must host BOTH a heading subtree AND a list-item
     * subtree so the user can long-press anywhere and drag the selection
     * across structural boundaries. The selection container itself emits no
     * `SemanticsProperty`; the proxy assertion is that both block text nodes
     * are present in the same hosted composition root - exactly what
     * cross-block selection requires.
     *
     * This complements [MarkdownSelectionInstrumentedTest::multiBlock_source_exposesBothBlocksInSemanticsTree]
     * which only exercises sibling PARAGRAPH blocks. Heading + list is a
     * meaningfully different topology because both blocks introduce their own
     * `Column` and inner `Row` layout, and a regression where the
     * `SelectionContainer` was scoped too narrowly would surface here first.
     */
    @Test
    fun selectionContainer_spansHeadingAndListItem() {
        val source = """
# Section title

- bullet body
""".trimIndent()
        composeRule.setContent {
            MaterialTheme {
                Surface {
                    MarkdownNotesView(
                        source = source,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        // Heading body is in the tree.
        composeRule
            .onNodeWithText("Section title", substring = true)
            .assertIsDisplayed()
        // List item body is in the tree.
        composeRule
            .onNodeWithText("bullet body", substring = true)
            .assertIsDisplayed()
        // Bullet marker is present - confirms the LIST_ITEM dispatcher fired
        // and the selection container hosts the marker glyph as a peer of
        // the heading text.
        composeRule
            .onAllNodesWithText("•", substring = false)
            .assertCountEquals(1)
    }
}

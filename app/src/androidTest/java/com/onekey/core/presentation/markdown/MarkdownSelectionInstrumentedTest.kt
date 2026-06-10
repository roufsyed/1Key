package com.onekey.core.presentation.markdown

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented Compose UI tests for [MarkdownNotesView]'s selection surface.
 *
 * Verifies (when run on a device):
 *  - A multi-block source mounts inside a [androidx.compose.foundation.text.selection.SelectionContainer]
 *    so cross-block long-press + drag selection is possible at the platform
 *    level. We assert the rendered text nodes exist for both blocks so the
 *    selection container has something to span.
 *  - Inline link text appears as the rendered LABEL (e.g. "OneKey site"),
 *    NOT the raw markdown source (`[OneKey site](https://example.test)`).
 *    This pins that selection / copy yields the user-visible string, not the
 *    underlying markdown bytes.
 *  - GFM strikethrough renders with the inner text visible, not the source.
 *  - An image renders as the `[image: alt]` placeholder and never reveals
 *    the underlying URL through any node in the semantics tree.
 *  - HTML tags appear in the tree as literal characters - no synthetic span
 *    has converted `<b>...</b>` into a Compose Bold style.
 *
 * Cannot be executed in the current environment (no device). The script
 * verifies compile-only via `./gradlew :app:assembleDebugAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class MarkdownSelectionInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    /**
     * Two-paragraph source mounted into a Surface so the SelectionContainer
     * inside [MarkdownNotesView] has a valid layout host. Both paragraph
     * text nodes must be present in the semantics tree - the selection
     * container itself is invisible to the semantics walker, but its
     * children's text content is what selection / copy will collect.
     */
    @Test
    fun multiBlock_source_exposesBothBlocksInSemanticsTree() {
        val source = """
First paragraph with regular text.

Second paragraph below.
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
        composeRule
            .onNodeWithText("First paragraph", substring = true)
            .assertIsDisplayed()
        composeRule
            .onNodeWithText("Second paragraph", substring = true)
            .assertIsDisplayed()
    }

    /**
     * Inline markdown link - the visible LABEL must appear in the semantics
     * tree; the raw source (`[OneKey site](...)`) must NOT. This is what a
     * copy operation will yield - the user-visible rendered text, not the
     * underlying markdown bytes.
     */
    @Test
    fun inlineLink_copyYieldsRenderedLabelNotSource() {
        val source = "Visit [OneKey site](https://example.test) for details."
        composeRule.setContent {
            MaterialTheme {
                Surface {
                    MarkdownNotesView(source = source)
                }
            }
        }
        // The rendered label is in the tree.
        composeRule
            .onNodeWithText("OneKey site", substring = true)
            .assertIsDisplayed()
        // The raw markdown link syntax is NOT in the tree.
        composeRule
            .onAllNodesWithText("[OneKey site](https://example.test)", substring = true)
            .assertCountEquals(0)
    }

    /**
     * GFM strikethrough - the inner text is what the user copies. The
     * boundary `~~` delimiters must be filtered from the rendered run.
     */
    @Test
    fun strikethrough_copyYieldsInnerTextNotDelimiters() {
        val source = "This is ~~struck text~~ here."
        composeRule.setContent {
            MaterialTheme {
                Surface { MarkdownNotesView(source = source) }
            }
        }
        composeRule
            .onNodeWithText("struck text", substring = true)
            .assertIsDisplayed()
        // The tilde delimiters must NOT appear in the tree.
        composeRule
            .onAllNodesWithText("~~", substring = true)
            .assertCountEquals(0)
    }

    /**
     * Image - placeholder text appears; URL never does. Selection / copy can
     * only see the literal `[image: caption]` glyphs, NOT the underlying URL.
     */
    @Test
    fun image_rendersPlaceholder_neverRevealsUrl() {
        val source = "![caption](https://tracker.example/pixel.gif)"
        composeRule.setContent {
            MaterialTheme {
                Surface { MarkdownNotesView(source = source) }
            }
        }
        composeRule
            .onNodeWithText("[image: caption]", substring = true)
            .assertIsDisplayed()
        composeRule
            .onAllNodesWithText("tracker.example", substring = true)
            .assertCountEquals(0)
        composeRule
            .onAllNodesWithText("pixel.gif", substring = true)
            .assertCountEquals(0)
    }

    /**
     * Inline HTML - tags flow through as literal glyphs and remain
     * selectable. The renderer must NOT silently strip them; the user should
     * see the bytes they wrote and the copy operation should yield the same
     * bytes back.
     */
    @Test
    fun htmlTag_rendersAsLiteralCharacters() {
        val source = "hello <b>world</b> end"
        composeRule.setContent {
            MaterialTheme {
                Surface { MarkdownNotesView(source = source) }
            }
        }
        // The whole literal payload remains in the tree.
        composeRule
            .onNodeWithText("<b>world</b>", substring = true)
            .assertIsDisplayed()
    }

    /**
     * Headings render their inner content. The `#` markers MUST NOT appear
     * in the semantics tree - they are filtered by the heading dispatcher.
     */
    @Test
    fun heading_rendersInnerTextWithoutHashMarkers() {
        val source = "# Welcome\n\nBody paragraph."
        composeRule.setContent {
            MaterialTheme {
                Surface { MarkdownNotesView(source = source) }
            }
        }
        composeRule
            .onNodeWithText("Welcome", substring = true)
            .assertIsDisplayed()
        composeRule
            .onAllNodesWithText("# Welcome", substring = true)
            .assertCountEquals(0)
    }

    /**
     * Empty source must mount and the SelectionContainer must still be
     * present (no exception). We cannot assert positive content for an
     * empty notes view, but we CAN ensure the composition does not crash by
     * just setting the content.
     */
    @Test
    fun emptySource_mountsWithoutCrashing() {
        composeRule.setContent {
            MaterialTheme {
                Surface { MarkdownNotesView(source = "") }
            }
        }
        composeRule.runOnIdle {
            // No assertion required - reaching idle without throwing is the
            // contract. Compose's setContent + runOnIdle would surface any
            // composition-time crash.
        }
    }
}

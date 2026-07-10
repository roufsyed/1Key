package com.roufsyed.onekey.core.presentation.markdown

import android.net.Uri
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented Compose UI tests for [LinkConfirmDialog].
 *
 * Verifies:
 *  - A 200-char URL composes without crashing AND the destination text node
 *    is reachable via Compose's semantics tree (the test framework sees the
 *    full text regardless of horizontal-scroll clipping; the assertion pins
 *    that nothing replaced the URL with an ellipsis like "...").
 *  - A punycode host (xn--80aaxitdbjk.com) renders the warning row.
 *  - A digit-letter confusable host ("paypa1.com") renders the warning row.
 *  - A clean host ("paypal.com") does NOT render the warning row.
 *
 * Cannot be executed in this environment (no device). The script must verify
 * compilation only via `./gradlew :app:assembleDebugAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class LinkConfirmDialogInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    /**
     * 200-char URL renders without crashing and the full string is present in
     * the semantics tree (no ellipsis). The Compose UI test framework reads
     * the underlying Text content, so a `softWrap = false` + `horizontalScroll`
     * row still exposes the entire string for assertion.
     */
    @Test
    fun longUrl_rendersWithoutTruncation() {
        val longUrl = "https://example.com/" + "a".repeat(200)
        val request = LinkRequest(
            rawUrl = longUrl,
            parsedUri = Uri.parse(longUrl),
            label = null,
        )

        composeRule.setContent {
            MaterialTheme {
                LinkConfirmDialog(
                    request = request,
                    onConfirm = {},
                    onDismiss = {},
                )
            }
        }

        composeRule.onNodeWithText("Open link?").assertIsDisplayed()
        // The full URL must be present in the semantics tree - no ellipsis.
        composeRule.onNodeWithText(longUrl, substring = false).assertIsDisplayed()
    }

    /**
     * Punycode-form host - HomographDetector.detect returns
     * [HomographWarning.Punycode] and the warning row's reason-specific copy
     * is rendered.
     */
    @Test
    fun punycodeHost_showsWarningRow() {
        val url = "https://xn--80aaxitdbjk.com/path"
        val request = LinkRequest(
            rawUrl = url,
            parsedUri = Uri.parse(url),
            label = "Click here",
        )

        composeRule.setContent {
            MaterialTheme {
                LinkConfirmDialog(
                    request = request,
                    onConfirm = {},
                    onDismiss = {},
                )
            }
        }

        composeRule
            .onNodeWithText("This domain looks similar", substring = true)
            .assertIsDisplayed()
    }

    /**
     * Digit-letter confusable host - "paypa1.com" trips the digit-letter
     * layer (the '1' is a confusable for 'l'). Warning row must render.
     */
    @Test
    fun digitLetterHost_showsWarningRow() {
        val url = "https://paypa1.com/account"
        val request = LinkRequest(
            rawUrl = url,
            parsedUri = Uri.parse(url),
            label = null,
        )

        composeRule.setContent {
            MaterialTheme {
                LinkConfirmDialog(
                    request = request,
                    onConfirm = {},
                    onDismiss = {},
                )
            }
        }

        composeRule
            .onNodeWithText("This domain looks similar", substring = true)
            .assertIsDisplayed()
    }

    /**
     * Clean ASCII host - no warning row in the tree. We assert ZERO matches
     * for the warning-copy prefix; the surrounding "Link text" and
     * "Destination" captions are unaffected.
     */
    @Test
    fun cleanHost_omitsWarningRow() {
        val url = "https://paypal.com/account"
        val request = LinkRequest(
            rawUrl = url,
            parsedUri = Uri.parse(url),
            label = "PayPal",
        )

        composeRule.setContent {
            MaterialTheme {
                LinkConfirmDialog(
                    request = request,
                    onConfirm = {},
                    onDismiss = {},
                )
            }
        }

        // The dialog itself rendered.
        composeRule.onNodeWithText("Open link?").assertIsDisplayed()
        // But the warning copy is not in the tree.
        composeRule
            .onAllNodesWithText("This domain looks similar", substring = true)
            .assertCountEquals(0)
    }

    /**
     * Confirm button click fires the onConfirm lambda exactly once. Pins the
     * dispatch contract: the dialog itself does not launch an intent; the
     * caller is responsible for handling the confirm side effect.
     */
    @Test
    fun confirmButton_invokesOnConfirm() {
        val url = "https://example.com"
        val request = LinkRequest(
            rawUrl = url,
            parsedUri = Uri.parse(url),
            label = null,
        )
        val confirmCount = mutableStateOf(0)

        composeRule.setContent {
            MaterialTheme {
                LinkConfirmDialog(
                    request = request,
                    onConfirm = { confirmCount.value = confirmCount.value + 1 },
                    onDismiss = {},
                )
            }
        }

        composeRule.onNodeWithText("Open in browser").performClick()
        composeRule.runOnIdle {
            assert(confirmCount.value == 1) {
                "expected onConfirm to be invoked once, was ${confirmCount.value}"
            }
        }
    }
}

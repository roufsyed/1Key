package com.roufsyed.onekey.core.presentation.markdown

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compile-only verification scaffolding for [LinkInsertDialog].
 *
 * The environment used to author this test cannot execute an instrumented run (no device or
 * emulator is attached), so this file is verified against compilation only via
 * `./gradlew :app:assembleDebugAndroidTest`. The three test methods below exist so the
 * generated APK exercises the dialog's public composable signature AND the
 * [validateInsertUrl] internal helper - if either changes shape, compilation here will
 * break and any future agent will see a clear failure pointing at this file.
 *
 *  - [dialog_rendersWithTwoTextFields_andTitle] mounts the dialog and asserts the two field
 *    labels plus the title text are reachable in the semantics tree. The dialog uses
 *    `LockAwareDialog`; the surface is composed inside a `MaterialTheme` so the M3
 *    typography resolves at composition time.
 *
 *  - [validHttpUrl_passesValidation] calls the internal validator directly to pin that an
 *    `http://` URL returns `null` (no validation error). This is the insert-time check
 *    that mirrors `SchemeAllowlistUriHandler.openUri`'s click-time check; if the two ever
 *    diverge a user could insert a link that would later be blocked.
 *
 *  - [javascriptUrl_failsValidation] pins the converse: a `javascript:` URL must be
 *    rejected at insert time with a `DisallowedScheme` error carrying the offending
 *    scheme literal. Defence-in-depth - the dialog AND the click-time handler both
 *    refuse to open.
 */
@RunWith(AndroidJUnit4::class)
class LinkInsertDialogTest {

    @get:Rule
    val composeRule = createComposeRule()

    /**
     * Mount the dialog with an empty initial label and assert that:
     *  - the dialog title "Insert link" is displayed,
     *  - the "Link text (optional)" field label is displayed,
     *  - the "URL" field label is displayed,
     *  - both confirm and dismiss buttons are present.
     *
     * Compile contract: the dialog's parameter shape (initialLabel: String, onConfirm: (label,
     * url) -> Unit, onDismiss: () -> Unit) is exercised here.
     */
    @Test
    fun dialog_rendersWithTwoTextFields_andTitle() {
        composeRule.setContent {
            MaterialTheme {
                LinkInsertDialog(
                    initialLabel = "",
                    onConfirm = { _, _ -> },
                    onDismiss = {},
                )
            }
        }

        composeRule.onNodeWithText("Insert link").assertIsDisplayed()
        composeRule.onNodeWithText("Link text (optional)").assertIsDisplayed()
        composeRule.onNodeWithText("URL").assertIsDisplayed()
        composeRule.onNodeWithText("Insert").assertIsDisplayed()
        composeRule.onNodeWithText("Cancel").assertIsDisplayed()
    }

    /**
     * Insert-time validator: a well-formed `http://example.com` URL must return null (no
     * error). The validator is reused by the confirm button - if this returns null, the
     * `onConfirm` callback fires with the trimmed URL.
     */
    @Test
    fun validHttpUrl_passesValidation() {
        val result = validateInsertUrl("http://example.com")
        assertNull("http URL must validate successfully, got: $result", result)
    }

    /**
     * Insert-time validator: a `javascript:` URL must be rejected with a
     * [LinkValidationError.DisallowedScheme] error whose `scheme` field equals
     * `"javascript"`. The lowercase normalisation matches the click-time handler so the
     * error message and the blocked-scheme telemetry stay consistent.
     */
    @Test
    fun javascriptUrl_failsValidation() {
        val result = validateInsertUrl("javascript:alert(1)")
        assertNotNull("javascript: URL must be rejected", result)
        assertTrue(
            "expected DisallowedScheme, got: $result",
            result is LinkValidationError.DisallowedScheme,
        )
        assertEquals(
            "javascript",
            (result as LinkValidationError.DisallowedScheme).scheme,
        )
    }
}

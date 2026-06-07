package com.onekey.feature.autofill.domain

import android.app.Application
import android.text.InputType
import android.view.View
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
 * Covers the classifier passes documented in [FieldParser]:
 *
 *  1. explicit hints / HTML attrs / inputType win,
 *  2. heuristic keyword rescue fills missing slots,
 *  3. immediate-predecessor rescue picks the username when only a password is
 *     hinted,
 *
 * plus the disqualifiers (URI / phone / long-message / password variants) and
 * the webDomain inheritance contract.
 *
 * Robolectric provides the runtime AutofillId class even though its public
 * constructor is `@TestApi`; see [AutofillIdFactory].
 */
// `application = Application::class` bypasses our HiltAndroidApp; the real
// app pulls in EncryptedSharedPreferences (AndroidKeyStore) at construction,
// which Robolectric's shadow KeyStore cannot resolve.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class FieldParserTest {

    private val parser = FieldParser()

    @Test fun returns_null_on_empty_node_list() {
        assertNull(parser.parse(emptyList(), packageName = "com.example"))
    }

    @Test fun returns_null_when_nothing_classifies() {
        val nodes = listOf(textNode(id = 1, idEntry = "first_name", inputType = InputType.TYPE_CLASS_TEXT))
        assertNull(parser.parse(nodes, packageName = "com.example"))
    }

    @Test fun explicit_username_and_password_hints_win() {
        val nodes = listOf(
            textNode(id = 1, hints = listOf(View.AUTOFILL_HINT_USERNAME)),
            textNode(id = 2, hints = listOf(View.AUTOFILL_HINT_PASSWORD)),
        )
        val parsed = assertNotNull(parser.parse(nodes, packageName = "com.example"))
        assertEquals(1, parsed.username?.autofillId?.let { extractViewId(it) })
        assertEquals(2, parsed.password?.autofillId?.let { extractViewId(it) })
        assertEquals(AutofillScenario.LOGIN, parsed.scenario)
    }

    @Test fun explicit_email_hint_routes_into_email_slot() {
        val nodes = listOf(
            textNode(id = 1, hints = listOf(View.AUTOFILL_HINT_EMAIL_ADDRESS)),
            textNode(id = 2, hints = listOf(View.AUTOFILL_HINT_PASSWORD)),
        )
        val parsed = assertNotNull(parser.parse(nodes, packageName = "com.example"))
        assertEquals(1, parsed.email?.autofillId?.let { extractViewId(it) })
        assertNull("Username slot must remain empty when only email is hinted", parsed.username)
        assertEquals(2, parsed.password?.autofillId?.let { extractViewId(it) })
    }

    @Test fun newPassword_hint_classifies_as_password() {
        val nodes = listOf(
            textNode(id = 1, hints = listOf("newPassword")),
            textNode(id = 2, hints = listOf(View.AUTOFILL_HINT_USERNAME)),
        )
        val parsed = assertNotNull(parser.parse(nodes, packageName = "com.example"))
        assertEquals(1, parsed.password?.autofillId?.let { extractViewId(it) })
    }

    @Test fun password_input_type_without_hint_is_picked_up() {
        val nodes = listOf(
            textNode(
                id = 1,
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            ),
        )
        val parsed = assertNotNull(parser.parse(nodes, packageName = "com.example"))
        assertEquals(1, parsed.password?.autofillId?.let { extractViewId(it) })
    }

    @Test fun numeric_password_input_type_is_picked_up() {
        val nodes = listOf(
            textNode(
                id = 1,
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD,
            ),
        )
        val parsed = assertNotNull(parser.parse(nodes, packageName = "com.example"))
        assertEquals(1, parsed.password?.autofillId?.let { extractViewId(it) })
    }

    @Test fun keyword_heuristic_resolves_un_hinted_username() {
        val nodes = listOf(
            textNode(id = 1, idEntry = "login_field"),
            textNode(id = 2, hints = listOf(View.AUTOFILL_HINT_PASSWORD)),
        )
        val parsed = assertNotNull(parser.parse(nodes, packageName = "com.example"))
        assertEquals(1, parsed.username?.autofillId?.let { extractViewId(it) })
        assertEquals(2, parsed.password?.autofillId?.let { extractViewId(it) })
    }

    @Test fun heuristic_does_not_overwrite_explicit_classification() {
        val nodes = listOf(
            textNode(id = 1, hints = listOf(View.AUTOFILL_HINT_USERNAME)),
            textNode(id = 2, idEntry = "another_login_field"),
            textNode(id = 3, hints = listOf(View.AUTOFILL_HINT_PASSWORD)),
        )
        val parsed = assertNotNull(parser.parse(nodes, packageName = "com.example"))
        assertEquals(
            "Explicit hint at id=1 must outrank later heuristic match at id=2",
            1,
            parsed.username?.autofillId?.let { extractViewId(it) },
        )
    }

    @Test fun immediate_predecessor_rescue_picks_username_before_password() {
        val nodes = listOf(
            textNode(id = 1, inputType = InputType.TYPE_CLASS_TEXT),
            textNode(id = 2, hints = listOf(View.AUTOFILL_HINT_PASSWORD)),
        )
        val parsed = assertNotNull(parser.parse(nodes, packageName = "com.example"))
        assertEquals(1, parsed.username?.autofillId?.let { extractViewId(it) })
    }

    @Test fun rescue_does_not_run_when_email_already_present() {
        val nodes = listOf(
            textNode(id = 1, inputType = InputType.TYPE_CLASS_TEXT),
            textNode(id = 2, hints = listOf(View.AUTOFILL_HINT_EMAIL_ADDRESS)),
            textNode(id = 3, hints = listOf(View.AUTOFILL_HINT_PASSWORD)),
        )
        val parsed = assertNotNull(parser.parse(nodes, packageName = "com.example"))
        assertNull("Rescue is skipped when email already filled identity slot", parsed.username)
        assertEquals(2, parsed.email?.autofillId?.let { extractViewId(it) })
    }

    @Test fun rescue_rejects_uri_input_predecessor() {
        val nodes = listOf(
            textNode(
                id = 1,
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
            ),
            textNode(id = 2, hints = listOf(View.AUTOFILL_HINT_PASSWORD)),
        )
        val parsed = assertNotNull(parser.parse(nodes, packageName = "com.example"))
        assertNull("URI fields must never become the rescued username", parsed.username)
    }

    @Test fun rescue_rejects_long_message_predecessor() {
        val nodes = listOf(
            textNode(
                id = 1,
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_LONG_MESSAGE,
            ),
            textNode(id = 2, hints = listOf(View.AUTOFILL_HINT_PASSWORD)),
        )
        val parsed = assertNotNull(parser.parse(nodes, packageName = "com.example"))
        assertNull(parsed.username)
    }

    @Test fun rescue_rejects_non_text_class_predecessor() {
        val nodes = listOf(
            // Non-EditText class - even though the input type is plain text,
            // looksLikePlausibleIdentifier should reject it.
            textNode(
                id = 1,
                inputType = InputType.TYPE_CLASS_TEXT,
                className = "android.widget.TextView",
            ),
            textNode(id = 2, hints = listOf(View.AUTOFILL_HINT_PASSWORD)),
        )
        val parsed = assertNotNull(parser.parse(nodes, packageName = "com.example"))
        assertNull(parsed.username)
    }

    @Test fun html_password_type_classifies_as_password() {
        val nodes = listOf(
            textNode(
                id = 1,
                htmlAttributes = listOf("type" to "text", "name" to "username"),
            ),
            textNode(
                id = 2,
                htmlAttributes = listOf("type" to "password"),
            ),
        )
        val parsed = assertNotNull(parser.parse(nodes, packageName = "com.example"))
        assertEquals(1, parsed.username?.autofillId?.let { extractViewId(it) })
        assertEquals(2, parsed.password?.autofillId?.let { extractViewId(it) })
    }

    @Test fun html_autocomplete_new_password_classifies_as_password() {
        val nodes = listOf(
            textNode(id = 1, htmlAttributes = listOf("autocomplete" to "new-password")),
        )
        val parsed = assertNotNull(parser.parse(nodes, packageName = "com.example"))
        assertEquals(1, parsed.password?.autofillId?.let { extractViewId(it) })
    }

    @Test fun html_autocomplete_username_classifies_as_username() {
        val nodes = listOf(
            textNode(id = 1, htmlAttributes = listOf("autocomplete" to "username")),
        )
        val parsed = assertNotNull(parser.parse(nodes, packageName = "com.example"))
        assertEquals(1, parsed.username?.autofillId?.let { extractViewId(it) })
    }

    @Test fun web_domain_normalisation_strips_www_and_lowercases() {
        val nodes = listOf(
            textNode(id = 1, hints = listOf(View.AUTOFILL_HINT_USERNAME), webDomain = "WWW.Example.COM"),
            textNode(id = 2, hints = listOf(View.AUTOFILL_HINT_PASSWORD), webDomain = "www.example.com"),
        )
        val parsed = assertNotNull(parser.parse(nodes, packageName = "com.example"))
        assertEquals("example.com", parsed.webDomain)
    }

    @Test fun isFillable_requires_either_password_or_username_or_email() {
        // Direct shape contract - defensive, mirrors how the service uses parsed.isFillable().
        val withPassword = ParsedFields(
            username = null, password = AutofillField(newAutofillId(1), AutofillField.Type.PASSWORD),
            email = null, scenario = AutofillScenario.LOGIN, packageName = "x", webDomain = null,
        )
        assertTrue(withPassword.isFillable())

        val onlyUsername = ParsedFields(
            username = AutofillField(newAutofillId(1), AutofillField.Type.USERNAME),
            password = null, email = null, scenario = AutofillScenario.LOGIN,
            packageName = "x", webDomain = null,
        )
        assertTrue(onlyUsername.isFillable())

        val empty = ParsedFields(
            username = null, password = null, email = null,
            scenario = AutofillScenario.LOGIN, packageName = "x", webDomain = null,
        )
        assertFalse(empty.isFillable())
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun <T : Any> assertNotNull(value: T?): T {
        assertNotNull("value was null", value)
        return value!!
    }

    private fun textNode(
        id: Int,
        hints: List<String> = emptyList(),
        idEntry: String? = null,
        hint: String? = null,
        inputType: Int = InputType.TYPE_CLASS_TEXT,
        htmlAttributes: List<Pair<String, String>> = emptyList(),
        webDomain: String? = null,
        className: String? = "android.widget.EditText",
    ): RawNode = RawNode(
        autofillId = newAutofillId(id),
        autofillHints = hints,
        idEntry = idEntry,
        hint = hint,
        inputType = inputType,
        htmlAttributes = htmlAttributes,
        webDomain = webDomain,
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_YES,
        className = className,
    )

    /**
     * Reads the viewId out of an [android.view.autofill.AutofillId] via
     * `getViewId()`. The accessor is `@TestApi`, hence reflection.
     */
    private fun extractViewId(autofillId: android.view.autofill.AutofillId): Int {
        val m = autofillId::class.java.getDeclaredMethod("getViewId")
        m.isAccessible = true
        return m.invoke(autofillId) as Int
    }
}

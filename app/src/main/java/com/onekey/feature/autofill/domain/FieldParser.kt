package com.onekey.feature.autofill.domain

import android.text.InputType
import android.view.View
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pure-logic classifier. Takes the flattened node list produced by
 * [StructureWalker] and produces a [ParsedFields] describing the username /
 * email / password fields in the form (if any).
 *
 * Strategy is intentionally conservative for v1:
 *  1. `View.AUTOFILL_HINT_*` constants take precedence - they're what the
 *     host app explicitly told us.
 *  2. WebView nodes use HTML `name` / `type` / `autocomplete` attributes
 *     against a small, well-known vocabulary.
 *  3. `inputType` flags catch native password fields that omit hints.
 *  4. As a last resort, substring matching on lower-cased `idEntry` + `hint`.
 *  5. Username-rescue: if a password is found but no username, only the
 *     immediately preceding text-shaped node qualifies, and only when its
 *     [InputType] looks like a plausible identifier (no long-message, URI,
 *     filter, or numeric-only fields).
 *
 * Multi-language keyword corpora and sign-up detection are out of scope for
 * v1 - see `project_autofill.md` for the deferred list.
 */
@Singleton
class FieldParser @Inject constructor() {

    /**
     * @return `null` when no recognisable credential fields were found in
     *   [nodes]. The service responds to a `null` parse with `onSuccess(null)`,
     *   which tells the OS we have no contribution to make for this request.
     */
    fun parse(nodes: List<RawNode>, packageName: String): ParsedFields? {
        if (nodes.isEmpty()) return null

        var username: AutofillField? = null
        var password: AutofillField? = null
        var email: AutofillField? = null
        var webDomain: String? = null

        // Pass 1: classify by explicit hints, HTML attributes, input type.
        nodes.forEachIndexed { _, node ->
            // Some browsers / WebViews report `webDomain` as the empty string on
            // non-form nodes instead of null. The old `!= null` check captured
            // the empty value and then refused to look at later nodes that
            // carried the real host. Skip blank values so the first NON-blank
            // node wins.
            if (webDomain.isNullOrBlank() && !node.webDomain.isNullOrBlank()) {
                webDomain = normaliseHost(node.webDomain!!)
            }
            val type = classifyDirect(node) ?: return@forEachIndexed
            val field = AutofillField(node.autofillId, type)
            when (type) {
                AutofillField.Type.PASSWORD -> if (password == null) password = field
                AutofillField.Type.EMAIL -> if (email == null) email = field
                AutofillField.Type.USERNAME -> if (username == null) username = field
            }
        }

        // Pass 2: heuristic substring rescue for un-hinted text inputs. Only
        // applied when nothing explicit matched a slot - never overrides a
        // confident pass-1 classification.
        nodes.forEach { node ->
            val type = classifyHeuristic(node) ?: return@forEach
            val field = AutofillField(node.autofillId, type)
            when (type) {
                AutofillField.Type.PASSWORD -> if (password == null) password = field
                AutofillField.Type.EMAIL -> if (email == null) email = field
                AutofillField.Type.USERNAME -> if (username == null) username = field
            }
        }

        // Pass 3: username rescue. If we have a password but no username and
        // no email, scan for the immediately preceding text-shaped node.
        if (password != null && username == null && email == null) {
            val passwordIndex = nodes.indexOfFirst { it.autofillId == password!!.autofillId }
            if (passwordIndex > 0) {
                val candidate = nodes[passwordIndex - 1]
                if (looksLikePlausibleIdentifier(candidate)) {
                    username = AutofillField(candidate.autofillId, AutofillField.Type.USERNAME)
                }
            }
        }

        if (username == null && password == null && email == null) return null
        return ParsedFields(
            username = username,
            password = password,
            email = email,
            scenario = AutofillScenario.LOGIN,
            packageName = packageName,
            // Coerce blank to null so every downstream check (`isHostMatch`,
            // `saveUrlSurfaceVisible`, the cross-host pane's "Form: $target"
            // line) sees a consistent null-or-real-host invariant. Defence
            // against an empty-string slipping through from upstream sources
            // we have not yet diagnosed.
            webDomain = webDomain?.takeIf { it.isNotBlank() },
        )
    }

    private fun classifyDirect(node: RawNode): AutofillField.Type? {
        // Hints first. Compare case-insensitively - the framework constants are
        // camelCase ("emailAddress", "postalAddress", "newPassword"), and we
        // can't assume host apps preserve the casing when echoing them back.
        node.autofillHints.forEach { hint ->
            when {
                hint.equals(View.AUTOFILL_HINT_PASSWORD, ignoreCase = true) ||
                    hint.equals(HINT_NEW_PASSWORD, ignoreCase = true) ->
                    return AutofillField.Type.PASSWORD
                hint.equals(View.AUTOFILL_HINT_USERNAME, ignoreCase = true) ->
                    return AutofillField.Type.USERNAME
                hint.equals(View.AUTOFILL_HINT_EMAIL_ADDRESS, ignoreCase = true) ->
                    return AutofillField.Type.EMAIL
            }
        }
        // HTML attributes for WebView fields.
        if (node.htmlAttributes.isNotEmpty()) {
            val attrType = node.htmlAttributes.findValue("type")?.lowercase()
            val attrName = node.htmlAttributes.findValue("name")?.lowercase()
            val attrAutocomplete = node.htmlAttributes.findValue("autocomplete")?.lowercase()
            when {
                attrType == "password" -> return AutofillField.Type.PASSWORD
                attrType == "email" -> return AutofillField.Type.EMAIL
                attrAutocomplete?.contains("current-password") == true -> return AutofillField.Type.PASSWORD
                attrAutocomplete?.contains("new-password") == true -> return AutofillField.Type.PASSWORD
                attrAutocomplete?.contains("username") == true -> return AutofillField.Type.USERNAME
                attrAutocomplete?.contains("email") == true -> return AutofillField.Type.EMAIL
                attrName?.matchesAny(PASSWORD_KEYWORDS) == true -> return AutofillField.Type.PASSWORD
                attrName?.matchesAny(EMAIL_KEYWORDS) == true -> return AutofillField.Type.EMAIL
                attrName?.matchesAny(USERNAME_KEYWORDS) == true -> return AutofillField.Type.USERNAME
            }
        }
        // InputType flags for native password fields without hints.
        if (isPasswordInputType(node.inputType)) return AutofillField.Type.PASSWORD
        return null
    }

    private fun classifyHeuristic(node: RawNode): AutofillField.Type? {
        // Already classified directly - handled by caller, so just check the
        // substring corpora here.
        val haystack = buildString {
            node.idEntry?.let { append(it.lowercase()); append(' ') }
            node.hint?.let { append(it.lowercase()) }
        }
        if (haystack.isBlank()) return null
        return when {
            haystack.matchesAny(PASSWORD_KEYWORDS) -> AutofillField.Type.PASSWORD
            haystack.matchesAny(EMAIL_KEYWORDS) -> AutofillField.Type.EMAIL
            haystack.matchesAny(USERNAME_KEYWORDS) -> AutofillField.Type.USERNAME
            else -> null
        }
    }

    private fun looksLikePlausibleIdentifier(node: RawNode): Boolean {
        if (!isTextClass(node.className)) return false
        // Disallow types we'd never see hold a username: numeric-only, URI,
        // long-message bodies, filter UIs.
        val variation = node.inputType and InputType.TYPE_MASK_VARIATION
        val classMask = node.inputType and InputType.TYPE_MASK_CLASS
        if (classMask != InputType.TYPE_CLASS_TEXT) return false
        val forbidden = setOf(
            InputType.TYPE_TEXT_VARIATION_LONG_MESSAGE,
            InputType.TYPE_TEXT_VARIATION_URI,
            InputType.TYPE_TEXT_VARIATION_FILTER,
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_PHONETIC,
            InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS,
        )
        return variation !in forbidden
    }

    private fun isPasswordInputType(inputType: Int): Boolean {
        val classMask = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        if (classMask == InputType.TYPE_CLASS_TEXT) {
            return variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
        }
        if (classMask == InputType.TYPE_CLASS_NUMBER &&
            variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
        ) {
            return true
        }
        return false
    }

    private fun isTextClass(className: String?): Boolean {
        if (className == null) return false
        // Cover both native widgets and WebView wrappers.
        return className.contains("EditText", ignoreCase = true) ||
            className.contains("TextField", ignoreCase = true) ||
            className.contains("html.input", ignoreCase = true)
    }

    private fun normaliseHost(raw: String): String =
        raw.lowercase().removePrefix("www.")

    private fun List<Pair<String, String>>.findValue(key: String): String? =
        firstOrNull { it.first.equals(key, ignoreCase = true) }?.second

    private fun String.matchesAny(needles: Set<String>): Boolean =
        needles.any { this.contains(it) }

    private companion object {
        // `View.AUTOFILL_HINT_NEW_PASSWORD` was introduced in API 26 alongside
        // AutofillService. We list it as a literal string so the codebase doesn't
        // accidentally trip a future deprecation that splits hint constants.
        const val HINT_NEW_PASSWORD = "newPassword"

        val PASSWORD_KEYWORDS: Set<String> = setOf(
            "password", "passwd", "pswd", "pass_word", "pwd",
        )
        val EMAIL_KEYWORDS: Set<String> = setOf(
            "email", "e-mail", "e_mail", "emailaddress", "emailaddr",
        )
        val USERNAME_KEYWORDS: Set<String> = setOf(
            "username", "user_name", "userid", "user_id", "login", "account",
            "signin", "memberid", "loginid",
        )
    }
}

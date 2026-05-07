package com.onekey.feature.importexport.domain

/**
 * Converts a raw custom-field key from a foreign export into a human-readable
 * label. Run once at import time so the value stored in the DB matches what
 * the UI shows (no display-time transform; matches the convention for keys
 * the user types by hand into the editor).
 *
 * Handled shapes:
 *  - snake_case             →  "Snake Case"
 *  - kebab-case             →  "Kebab Case"
 *  - camelCase / PascalCase →  "Camel Case" / "Pascal Case"
 *  - already spaced         →  preserved (idempotent)
 *  - acronym + word         →  "HTTPRealm" → "HTTP Realm"
 *
 * A small list of acronyms is preserved in uppercase rather than title-cased
 * (so `id` becomes `ID`, not `Id`). Words outside the list keep whatever
 * casing they came in with after the first character is title-cased.
 *
 * Idempotent: feeding the result back in returns the same string.
 */
object CustomFieldKeyFormatter {

    private val ACRONYMS = setOf(
        "ID", "URL", "URI", "UUID",
        "HTTP", "HTTPS", "IP",
        "OTP", "TOTP", "HOTP", "2FA",
        "IBAN",
    )

    private val LOWER_THEN_UPPER = Regex("([a-z0-9])([A-Z])")
    private val ACRONYM_THEN_TITLECASE = Regex("([A-Z]+)([A-Z][a-z])")
    private val SEPARATORS = Regex("[_-]+")
    private val WHITESPACE = Regex("\\s+")

    fun prettify(key: String): String {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) return ""

        val withSpaces = trimmed
            .replace(LOWER_THEN_UPPER, "$1 $2")          // camelCase boundary
            .replace(ACRONYM_THEN_TITLECASE, "$1 $2")    // HTTPRealm → HTTP Realm
            .replace(SEPARATORS, " ")                    // snake/kebab → space

        val words = withSpaces.split(WHITESPACE).filter { it.isNotBlank() }
        if (words.isEmpty()) return ""

        return words.joinToString(" ") { word ->
            val upper = word.uppercase()
            if (upper in ACRONYMS) upper
            else word.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }
    }
}

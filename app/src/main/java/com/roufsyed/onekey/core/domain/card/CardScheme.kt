package com.roufsyed.onekey.core.domain.card

/**
 * The card network ("scheme") drawn on the card. The editor offers these as a
 * dropdown plus a free-text "Custom" option; the chosen [displayName] (or the custom
 * text) is stored as the value of the "Card Scheme" custom field, so the data model
 * stays free text and older or hand-typed values ("Master", "Amex") still resolve.
 */
enum class CardScheme(val displayName: String, private val aliases: List<String> = emptyList()) {
    VISA("Visa"),
    MASTERCARD("Mastercard", listOf("Master", "Master Card", "MC")),
    AMERICAN_EXPRESS("American Express", listOf("Amex")),
    RUPAY("RuPay"),
    DINERS_CLUB("Diners Club", listOf("Diners", "Dinners", "Diners Club International")),
    ;

    companion object {
        /** Dropdown options in display order. "Custom" is a UI-only option on top of these. */
        val options: List<CardScheme> = entries

        /** The scheme a stored value names (case and spacing insensitive, aliases included), or null for custom text. */
        fun fromValue(raw: String): CardScheme? {
            val key = CardFieldBinder.normaliseKey(raw)
            if (key.isEmpty()) return null
            return entries.firstOrNull { scheme ->
                (listOf(scheme.displayName) + scheme.aliases).any { CardFieldBinder.normaliseKey(it) == key }
            }
        }
    }
}

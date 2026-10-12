package com.roufsyed.onekey.core.domain.card

/**
 * Display and copy formatting for card slot values. Pure functions over strings;
 * nothing here touches storage, so the persisted value is never rewritten.
 *
 * Masks are synthetic and fixed-shape on purpose: they must leak neither the
 * length of the real value (a 15-digit Amex versus 16, a 4-digit CVV versus 3)
 * nor any character of a value that is not a plain digit string. Only ASCII
 * digits count as digits, so Unicode format and control characters (for example
 * a bidi override) make a value "not numeric" and fully masked.
 */
object CardValueFormatter {

    const val MASKED_NUMBER = "•••• •••• •••• ••••"
    const val MASKED_CVV = "•••"
    const val MASKED_SHORT = "••••••••"

    /** Card numbers show their last four digits only when at least this many digits are present. */
    const val MIN_CARD_DIGITS_FOR_LAST_FOUR = 12

    /** The name on the card is abbreviated after this many characters (code points). */
    const val MAX_NAME_ON_CARD_CHARS = 24

    private val separators = Regex("[ \\-]")

    /** [value] trimmed with spaces and hyphens removed. */
    fun stripSeparators(value: String): String = value.trim().replace(separators, "")

    /** True for a non-empty string of ASCII digits only. */
    fun isAsciiDigits(value: String): Boolean = value.isNotEmpty() && value.all { it in '0'..'9' }

    /** The digit string of [value] when it is digits plus separators and nothing else, otherwise null. */
    fun digits(value: String): String? = stripSeparators(value).takeIf(::isAsciiDigits)

    /**
     * Digit groups for display: 13 digits as 4-3-3-3, 14 as 4-6-4, 15 (Amex) as
     * 4-6-5, anything else in groups of four left to right (16 as 4-4-4-4, 19 as
     * 4-4-4-4-3). Null when [value] is not a digit string.
     */
    fun digitGroups(value: String): List<String>? {
        val d = digits(value) ?: return null
        return when (d.length) {
            13 -> listOf(d.substring(0, 4), d.substring(4, 7), d.substring(7, 10), d.substring(10))
            14 -> listOf(d.substring(0, 4), d.substring(4, 10), d.substring(10))
            15 -> listOf(d.substring(0, 4), d.substring(4, 10), d.substring(10))
            else -> d.chunked(4)
        }
    }

    /** Grouped digits, or the trimmed value verbatim when it is not a digit string. */
    fun groupedNumber(value: String): String = digitGroups(value)?.joinToString(" ") ?: value.trim()

    /** Last four digits when [value] is a digit string of at least [minDigits] digits, otherwise null. */
    fun lastFour(value: String, minDigits: Int): String? {
        val d = digits(value) ?: return null
        return if (d.length >= minDigits) d.takeLast(4) else null
    }

    /**
     * Fixed-shape mask for a number: `•••• •••• •••• 1234` when the last-four rule
     * passes, otherwise `•••• •••• •••• ••••`. The shape never follows the real
     * grouping, so it reveals neither length nor brand.
     */
    fun maskedNumber(value: String, minDigits: Int): String =
        lastFour(value, minDigits)?.let { "•••• •••• •••• $it" } ?: MASKED_NUMBER

    /** Separator-free digits when [value] is a digit string, otherwise the trimmed value verbatim. */
    fun digitsForCopy(value: String): String = digits(value) ?: value.trim()

    /**
     * The name as printed on the card: the trimmed value up to [MAX_NAME_ON_CARD_CHARS]
     * characters, then an ellipsis. Counts code points so a surrogate pair (an emoji,
     * a supplementary-plane letter) is never split. Display only: accessibility text
     * and the field's own row keep the full name.
     */
    fun abbreviatedName(value: String): String {
        val name = value.trim()
        if (name.codePointCount(0, name.length) <= MAX_NAME_ON_CARD_CHARS) return name
        val cut = name.offsetByCodePoints(0, MAX_NAME_ON_CARD_CHARS)
        return name.substring(0, cut).trimEnd() + "…"
    }

    /** Clipboard text for [slot]: numeric slots are stripped to digits, everything else is trimmed. */
    fun copyText(slot: CardSlot, value: String): String =
        if (slot.numeric) digitsForCopy(value) else value.trim()

    /** The at-rest (masked) rendering for a sensitive [slot]. */
    fun maskedText(slot: CardSlot, value: String): String = when (slot) {
        CardSlot.CARD_NUMBER -> maskedNumber(value, MIN_CARD_DIGITS_FOR_LAST_FOUR)
        CardSlot.CVV -> MASKED_CVV
        CardSlot.BANK_NAME, CardSlot.CARDHOLDER, CardSlot.EXPIRY, CardSlot.SCHEME -> MASKED_SHORT
    }

    /** The revealed (or never-masked) rendering for [slot]. */
    fun revealedText(slot: CardSlot, value: String): String = when (slot) {
        CardSlot.CARD_NUMBER -> groupedNumber(value)
        CardSlot.EXPIRY -> ExpiryParser.displayOrRaw(value)
        CardSlot.CARDHOLDER -> abbreviatedName(value)
        CardSlot.CVV, CardSlot.BANK_NAME, CardSlot.SCHEME -> value.trim()
    }

    /** Text for accessibility services: like [revealedText] but never abbreviated. */
    fun accessibleText(slot: CardSlot, value: String): String =
        if (slot == CardSlot.CARDHOLDER) value.trim() else revealedText(slot, value)

    /** Last four digits for accessibility text ("ends in 1234"), honouring the minimum; null otherwise. */
    fun accessibleLastFour(slot: CardSlot, value: String): String? =
        if (slot == CardSlot.CARD_NUMBER) lastFour(value, MIN_CARD_DIGITS_FOR_LAST_FOUR) else null
}

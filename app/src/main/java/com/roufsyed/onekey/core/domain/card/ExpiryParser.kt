package com.roufsyed.onekey.core.domain.card

import java.time.YearMonth
import java.util.Locale

/** A parsed card expiry. [month] is 1 to 12, [year] is a four-digit year in 2000 to 2099. */
data class Expiry(val month: Int, val year: Int) {
    /** Canonical "MM/YY" rendering, locale independent digits. */
    fun display(): String = String.format(Locale.ROOT, "%02d/%02d", month, year % 100)
}

/**
 * Lenient, display-only expiry parsing. Accepts M/YY, MM/YY, MM/YYYY, M-YY,
 * MM-YY, MM-YYYY and the compact MMYY and MMYYYY forms. Anything else, a month
 * outside 1 to 12, or a year outside 2000 to 2099 returns null and the card shows
 * the raw text instead. Never throws, and never rewrites the stored value.
 */
object ExpiryParser {

    private val separated = Regex("^(\\d{1,2})\\s*[/\\-]\\s*(\\d{2}|\\d{4})$")
    private val compact = Regex("^(\\d{2})(\\d{2}|\\d{4})$")

    fun parse(raw: String): Expiry? {
        val text = raw.trim()
        val match = separated.matchEntire(text) ?: compact.matchEntire(text) ?: return null
        val month = match.groupValues[1].toIntOrNull() ?: return null
        if (month !in 1..12) return null
        val yearText = match.groupValues[2]
        val year = when (yearText.length) {
            2 -> 2000 + (yearText.toIntOrNull() ?: return null)
            4 -> yearText.toIntOrNull() ?: return null
            else -> return null
        }
        if (year !in 2000..2099) return null
        return Expiry(month, year)
    }

    /** "MM/YY" when [raw] parses, otherwise the trimmed raw text. */
    fun displayOrRaw(raw: String): String = parse(raw)?.display() ?: raw.trim()

    /**
     * A card is valid through the last day of its expiry month, so it is expired
     * only once [now] is in a later month. [month] is validated by [parse], so
     * [YearMonth.of] cannot throw here.
     */
    fun isExpired(expiry: Expiry, now: YearMonth = YearMonth.now()): Boolean =
        YearMonth.of(expiry.year, expiry.month).isBefore(now)
}

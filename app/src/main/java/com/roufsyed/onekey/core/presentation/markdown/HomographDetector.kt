package com.roufsyed.onekey.core.presentation.markdown

/**
 * Classification of a host that *might* be a homograph attack vector.
 *
 * Sealed (rather than a data class with a string reason) so callers can branch
 * over subtypes with compile-checked exhaustiveness and render reason-specific
 * copy. Subtypes are deliberately coarse - we do not need to enumerate every
 * Unicode block, only the user-visible category of the warning.
 */
sealed class HomographWarning {

    /**
     * Host is already in ACE / punycode form (any dot-separated label begins
     * with `xn--`). Such a destination decodes to a non-ASCII Unicode form
     * which can imitate a different site.
     */
    data object Punycode : HomographWarning()

    /**
     * Host contains non-ASCII letters (Cyrillic, Greek, etc.) mixed with or
     * resembling Latin characters. We do not try to identify the script - we
     * report the *fact* of non-ASCII content.
     */
    data object MixedScript : HomographWarning()

    /**
     * Host is pure ASCII but contains at least one of the visual confusables
     * {`0`<->`o`, `1`<->`l`, `5`<->`s`} alongside ASCII letters. The set of
     * offending digits is surfaced so the dialog can show which characters to
     * scrutinise.
     */
    data class DigitLetter(val confusables: Set<Char>) : HomographWarning()
}

/**
 * Pure utility classifier that decides whether a hostname *looks like* a
 * homograph or IDN attack vector. Returns a non-null [HomographWarning] when
 * something is suspicious, `null` otherwise.
 *
 * Crucial invariant: this function NEVER mutates the host. It only reports.
 * The autofill exact-host matcher and the markdown-link confirm dialog can
 * both call it without ever observing a different host string downstream,
 * eliminating the silent-mismatch hazard that would arise if detection and
 * normalisation shared a single mutator.
 *
 * No state, no I/O, no Android dependency. Safe to call from any thread and
 * from Compose composition.
 */
object HomographDetector {

    /** Digits the dialog treats as visual confusables for ASCII letters. */
    private val CONFUSABLE_DIGITS: Set<Char> = setOf('0', '1', '5')

    /**
     * Returns a non-null warning when [host] is suspicious, `null` when the
     * host looks clean or is unparseable.
     *
     * Contract:
     *  - Input is a *host* (e.g. `example.com`), NOT a full URL. The caller is
     *    expected to have already extracted the host via [android.net.Uri.parse]
     *    or [java.net.URI]; passing a URL here is a programmer error and yields
     *    undefined classification.
     *  - Case-insensitive: input is lowercased internally before any check.
     *  - Never throws. Malformed input (control chars, ACE decode failure) is
     *    treated as 'unknown' and returns null - the calling dialog still
     *    shows the destination, the warning row simply is not rendered.
     *  - Never mutates the host. The return value is purely informational.
     *
     * Detection layers, in order:
     *  1. Already-ACE punycode - any label literally begins with `xn--`. The
     *     destination decodes to Unicode and may imitate a different site.
     *  2. Mixed-script - host contains any non-ASCII character. Covers
     *     Cyrillic/Greek lookalikes such as `paypаl.com` (the `а` is U+0430).
     *     Distinct from the punycode check above so a user who pasted the
     *     Unicode form receives a "Mixed Script" warning, while inputs that
     *     were already in ACE form when handed to us are reported as
     *     "Punycode".
     *  3. Digit-letter substitution - host is pure ASCII letters / digits /
     *     hyphens / dots AND contains at least one letter alongside at least
     *     one confusable digit from [CONFUSABLE_DIGITS].
     *
     * Hosts containing only digits and dots (raw IPv4) return null because
     * they are not letter-substitution candidates.
     */
    fun detect(host: String?): HomographWarning? {
        if (host.isNullOrBlank()) return null
        val normalised = host.trim().lowercase()
        if (normalised.isEmpty()) return null

        // Layer 1: already in ACE / punycode form.
        if (normalised.split('.').any { it.startsWith("xn--") }) {
            return HomographWarning.Punycode
        }

        // Layer 2: mixed-script - any non-ASCII char in the raw input.
        if (normalised.any { it.code > 0x7F }) return HomographWarning.MixedScript

        // Layer 3: digit-letter substitution on pure-ASCII hosts.
        return digitLetterWarning(normalised)
    }

    /**
     * Returns [HomographWarning.DigitLetter] iff the host is pure ASCII (no
     * non-ASCII chars - that case is handled by the mixed-script layer), is
     * composed only of letters / digits / hyphens / dots, contains at least
     * one ASCII letter, and contains at least one confusable digit.
     */
    private fun digitLetterWarning(host: String): HomographWarning.DigitLetter? {
        var hasLetter = false
        val found = mutableSetOf<Char>()
        for (ch in host) {
            when {
                ch in 'a'..'z' -> hasLetter = true
                ch in '0'..'9' -> if (ch in CONFUSABLE_DIGITS) found += ch
                ch == '-' || ch == '.' -> Unit
                else -> return null // unexpected ASCII char; bail out
            }
        }
        return if (hasLetter && found.isNotEmpty()) HomographWarning.DigitLetter(found) else null
    }
}

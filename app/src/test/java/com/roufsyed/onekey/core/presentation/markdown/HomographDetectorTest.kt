package com.roufsyed.onekey.core.presentation.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behavioural locks for [HomographDetector.detect].
 *
 * Pure JVM - [HomographDetector] depends only on [java.net.IDN] (stdlib), so
 * no Robolectric is required. Test corpus comes from the Phase 5 spec plus a
 * handful of edge cases (raw IPv4, pure-digit labels, mixed case).
 */
class HomographDetectorTest {

    @Test fun latinOnlyDomain_returnsNull() {
        assertNull(HomographDetector.detect("example.com"))
        assertNull(HomographDetector.detect("github.com"))
        assertNull(HomographDetector.detect("accounts.google.com"))
    }

    @Test fun punycodeAcePrefix_isFlagged() {
        assertEquals(
            HomographWarning.Punycode,
            HomographDetector.detect("xn--80aaxitdbjk.com"),
        )
    }

    @Test fun cyrillicLookalike_isFlaggedAsMixedScript() {
        // The 'а' (U+0430, Cyrillic small a) replaces the Latin 'a'.
        val warning = HomographDetector.detect("paypаl.com")
        assertEquals(HomographWarning.MixedScript, warning)
    }

    @Test fun digitLetter_g00gle_isFlagged() {
        val warning = HomographDetector.detect("g00gle.com")
        assertTrue("expected DigitLetter, got $warning", warning is HomographWarning.DigitLetter)
        assertTrue((warning as HomographWarning.DigitLetter).confusables.contains('0'))
    }

    @Test fun digitLetter_paypa1_isFlagged() {
        val warning = HomographDetector.detect("paypa1.com")
        assertTrue("expected DigitLetter, got $warning", warning is HomographWarning.DigitLetter)
        assertTrue((warning as HomographWarning.DigitLetter).confusables.contains('1'))
    }

    @Test fun digitLetter_micro5oft_isFlagged() {
        val warning = HomographDetector.detect("micro5oft.com")
        assertTrue("expected DigitLetter, got $warning", warning is HomographWarning.DigitLetter)
        assertTrue((warning as HomographWarning.DigitLetter).confusables.contains('5'))
    }

    @Test fun emptyHost_returnsNull() {
        assertNull(HomographDetector.detect(""))
    }

    @Test fun nullHost_returnsNull() {
        assertNull(HomographDetector.detect(null))
    }

    @Test fun blankHost_returnsNull() {
        assertNull(HomographDetector.detect("   "))
    }

    @Test fun mixedCase_isNormalised() {
        val warning = HomographDetector.detect("G00GLE.com")
        assertTrue("expected DigitLetter, got $warning", warning is HomographWarning.DigitLetter)
        assertTrue((warning as HomographWarning.DigitLetter).confusables.contains('0'))
    }

    @Test fun rawIpv4_returnsNull() {
        // No letters present, so digit-letter classifier short-circuits.
        assertNull(HomographDetector.detect("192.168.1.1"))
    }

    @Test fun pureDigitsLabel_returnsNull() {
        assertNull(HomographDetector.detect("123456789"))
    }
}

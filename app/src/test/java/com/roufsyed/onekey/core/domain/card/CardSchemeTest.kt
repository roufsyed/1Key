package com.roufsyed.onekey.core.domain.card

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CardSchemeTest {

    @Test fun options_are_the_five_schemes_in_display_order() {
        assertEquals(
            listOf("Visa", "Mastercard", "American Express", "RuPay", "Diners Club"),
            CardScheme.options.map { it.displayName },
        )
    }

    @Test fun fromValue_matches_display_names_case_and_spacing_insensitively() {
        assertEquals(CardScheme.VISA, CardScheme.fromValue("Visa"))
        assertEquals(CardScheme.VISA, CardScheme.fromValue("  VISA "))
        assertEquals(CardScheme.MASTERCARD, CardScheme.fromValue("mastercard"))
        assertEquals(CardScheme.AMERICAN_EXPRESS, CardScheme.fromValue("American   Express"))
        assertEquals(CardScheme.RUPAY, CardScheme.fromValue("rupay"))
        assertEquals(CardScheme.DINERS_CLUB, CardScheme.fromValue("Diners Club"))
    }

    @Test fun fromValue_accepts_the_common_short_forms() {
        assertEquals(CardScheme.MASTERCARD, CardScheme.fromValue("Master"))
        assertEquals(CardScheme.MASTERCARD, CardScheme.fromValue("Master Card"))
        assertEquals(CardScheme.MASTERCARD, CardScheme.fromValue("MC"))
        assertEquals(CardScheme.AMERICAN_EXPRESS, CardScheme.fromValue("Amex"))
        assertEquals(CardScheme.DINERS_CLUB, CardScheme.fromValue("Diners"))
        assertEquals(CardScheme.DINERS_CLUB, CardScheme.fromValue("Dinners"))
    }

    @Test fun unknown_and_blank_values_are_custom() {
        assertNull(CardScheme.fromValue(""))
        assertNull(CardScheme.fromValue("   "))
        assertNull(CardScheme.fromValue("Maestro"))
        assertNull(CardScheme.fromValue("JCB"))
        assertNull(CardScheme.fromValue("Visa Platinum"))
    }
}

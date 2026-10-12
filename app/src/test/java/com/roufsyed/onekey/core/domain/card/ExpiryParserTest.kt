package com.roufsyed.onekey.core.domain.card

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.YearMonth

class ExpiryParserTest {

    @Test fun accepts_every_documented_format() {
        assertEquals(Expiry(12, 2027), ExpiryParser.parse("12/27"))
        assertEquals(Expiry(1, 2027), ExpiryParser.parse("1/27"))
        assertEquals(Expiry(12, 2027), ExpiryParser.parse("12/2027"))
        assertEquals(Expiry(12, 2027), ExpiryParser.parse("12-27"))
        assertEquals(Expiry(12, 2027), ExpiryParser.parse("12-2027"))
        assertEquals(Expiry(12, 2027), ExpiryParser.parse("1227"))
        assertEquals(Expiry(12, 2027), ExpiryParser.parse("122027"))
        assertEquals(Expiry(3, 2030), ExpiryParser.parse(" 03 / 30 "))
    }

    @Test fun rejects_out_of_range_months_without_throwing() {
        assertNull(ExpiryParser.parse("13/25"))
        assertNull(ExpiryParser.parse("00/00"))
        assertNull(ExpiryParser.parse("0/27"))
        assertNull(ExpiryParser.parse("1325"))
    }

    @Test fun rejects_years_outside_2000_to_2099_and_garbage() {
        assertNull(ExpiryParser.parse("12/2100"))
        assertNull(ExpiryParser.parse("12/1999"))
        assertNull(ExpiryParser.parse("12/270"))
        assertNull(ExpiryParser.parse("December 2027"))
        assertNull(ExpiryParser.parse(""))
        assertNull(ExpiryParser.parse("12/27/2027"))
        assertNull(ExpiryParser.parse("١٢/٢٧"))
    }

    @Test fun display_is_two_digit_month_slash_two_digit_year() {
        assertEquals("01/27", Expiry(1, 2027).display())
        assertEquals("12/05", Expiry(12, 2005).display())
        assertEquals("12/27", ExpiryParser.displayOrRaw("12-2027"))
        assertEquals("13/25", ExpiryParser.displayOrRaw(" 13/25 "))
    }

    @Test fun a_card_is_valid_through_the_end_of_its_expiry_month() {
        val expiry = Expiry(6, 2027)
        assertFalse(ExpiryParser.isExpired(expiry, YearMonth.of(2027, 6)))
        assertFalse(ExpiryParser.isExpired(expiry, YearMonth.of(2027, 5)))
        assertTrue(ExpiryParser.isExpired(expiry, YearMonth.of(2027, 7)))
        assertTrue(ExpiryParser.isExpired(expiry, YearMonth.of(2028, 1)))
    }
}

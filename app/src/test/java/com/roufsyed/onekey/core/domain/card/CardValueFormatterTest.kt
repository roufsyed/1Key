package com.roufsyed.onekey.core.domain.card

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CardValueFormatterTest {

    // ── grouping ─────────────────────────────────────────────────────────

    @Test fun groups_by_digit_count() {
        assertEquals(listOf("4111", "1111", "1111", "1111"), CardValueFormatter.digitGroups("4111 1111 1111 1111"))
        assertEquals(listOf("3782", "822463", "10005"), CardValueFormatter.digitGroups("378282246310005"))
        assertEquals(listOf("3056", "930902", "5904"), CardValueFormatter.digitGroups("30569309025904"))
        assertEquals(listOf("4222", "222", "222", "222"), CardValueFormatter.digitGroups("4222222222222"))
        assertEquals(listOf("6304", "0000", "0000", "0000", "000"), CardValueFormatter.digitGroups("6304000000000000000"))
        assertEquals("4111 1111 1111 1111", CardValueFormatter.groupedNumber("4111-1111-1111-1111"))
    }

    @Test fun non_digit_values_render_verbatim_and_ungrouped() {
        assertNull(CardValueFormatter.digitGroups("4111 XXXX XXXX 1111"))
        assertEquals("4111 XXXX XXXX 1111", CardValueFormatter.groupedNumber(" 4111 XXXX XXXX 1111 "))
        assertEquals("----", CardValueFormatter.groupedNumber("----"))
    }

    @Test fun unicode_format_and_control_characters_are_not_digits() {
        val withBidiOverride = "4111‮1111 1111 1111"
        assertNull(CardValueFormatter.digits(withBidiOverride))
        assertEquals(CardValueFormatter.MASKED_NUMBER, CardValueFormatter.maskedText(CardSlot.CARD_NUMBER, withBidiOverride))
        val withZeroWidthSpace = "4111​1111 1111 1111"
        assertNull(CardValueFormatter.digits(withZeroWidthSpace))
        val withArabicIndicDigits = "٤١١١١١١١١١١١١١١١"
        assertNull(CardValueFormatter.digits(withArabicIndicDigits))
    }

    // ── masks ────────────────────────────────────────────────────────────

    @Test fun card_number_mask_shows_last_four_only_for_long_digit_strings() {
        assertEquals("•••• •••• •••• 1111", CardValueFormatter.maskedText(CardSlot.CARD_NUMBER, "4111 1111 1111 1111"))
        // Amex: 15 digits still get the synthetic 4-4-4-4 shape, never the real grouping.
        assertEquals("•••• •••• •••• 0005", CardValueFormatter.maskedText(CardSlot.CARD_NUMBER, "378282246310005"))
        // Fewer than 12 digits: fully masked.
        assertEquals(CardValueFormatter.MASKED_NUMBER, CardValueFormatter.maskedText(CardSlot.CARD_NUMBER, "12345678901"))
        // Not a digit string: fully masked, never the trailing characters.
        assertEquals(CardValueFormatter.MASKED_NUMBER, CardValueFormatter.maskedText(CardSlot.CARD_NUMBER, "4111 XXXX XXXX 1111"))
        assertEquals(CardValueFormatter.MASKED_NUMBER, CardValueFormatter.maskedText(CardSlot.CARD_NUMBER, "----"))
    }

    @Test fun cvv_mask_is_fixed_shape_regardless_of_length() {
        assertEquals("•••", CardValueFormatter.maskedText(CardSlot.CVV, "123"))
        assertEquals("•••", CardValueFormatter.maskedText(CardSlot.CVV, "1234"))
        assertEquals("•••", CardValueFormatter.maskedText(CardSlot.CVV, ""))
    }

    @Test fun user_flagged_text_slots_mask_as_eight_bullets() {
        assertEquals("••••••••", CardValueFormatter.maskedText(CardSlot.CARDHOLDER, "Alice Example"))
        assertEquals("••••••••", CardValueFormatter.maskedText(CardSlot.EXPIRY, "12/27"))
        assertEquals("••••••••", CardValueFormatter.maskedText(CardSlot.SCHEME, "Visa"))
    }

    // ── revealed ─────────────────────────────────────────────────────────

    @Test fun revealed_text_groups_numbers_formats_expiry_and_trims_the_rest() {
        assertEquals("4111 1111 1111 1111", CardValueFormatter.revealedText(CardSlot.CARD_NUMBER, "4111111111111111"))
        assertEquals("12/27", CardValueFormatter.revealedText(CardSlot.EXPIRY, "12-2027"))
        assertEquals("13/25", CardValueFormatter.revealedText(CardSlot.EXPIRY, "13/25"))
        assertEquals("Alice Example", CardValueFormatter.revealedText(CardSlot.CARDHOLDER, "  Alice Example "))
        assertEquals("123", CardValueFormatter.revealedText(CardSlot.CVV, " 123 "))
        assertEquals("Visa", CardValueFormatter.revealedText(CardSlot.SCHEME, " Visa"))
    }

    // ── copy ─────────────────────────────────────────────────────────────

    @Test fun copy_text_strips_separators_only_for_digit_strings_on_numeric_slots() {
        assertEquals("4111111111111111", CardValueFormatter.copyText(CardSlot.CARD_NUMBER, "4111 1111-1111 1111"))
        assertEquals("123", CardValueFormatter.copyText(CardSlot.CVV, "1 2 3"))
        assertEquals("4111 XXXX XXXX 1111", CardValueFormatter.copyText(CardSlot.CARD_NUMBER, " 4111 XXXX XXXX 1111 "))
        assertEquals("----", CardValueFormatter.copyText(CardSlot.CARD_NUMBER, "----"))
        assertEquals("Alice Example", CardValueFormatter.copyText(CardSlot.CARDHOLDER, " Alice Example "))
    }

    @Test fun name_on_card_is_abbreviated_after_twenty_four_characters() {
        assertEquals("Alice Example", CardValueFormatter.revealedText(CardSlot.CARDHOLDER, " Alice Example "))
        assertEquals("ABCDEFGHIJKLMNOPQRSTUVWX", CardValueFormatter.abbreviatedName("ABCDEFGHIJKLMNOPQRSTUVWX"))
        assertEquals("ABCDEFGHIJKLMNOPQRSTUVWX…", CardValueFormatter.abbreviatedName("ABCDEFGHIJKLMNOPQRSTUVWXY"))
        assertEquals("Maximilian Alexander Mon…", CardValueFormatter.abbreviatedName("Maximilian Alexander Montgomery"))
        // A trailing space before the cut is dropped so the ellipsis hugs the last word.
        assertEquals("Alexandra Marie Winterbo…", CardValueFormatter.abbreviatedName("Alexandra Marie Winterbottom"))
        assertEquals("Anne Marie Elizabeth van…", CardValueFormatter.abbreviatedName("Anne Marie Elizabeth van Dyke"))
        // Supplementary-plane characters count as one and are never split.
        val emojiName = "😀".repeat(25)
        assertEquals("😀".repeat(24) + "…", CardValueFormatter.abbreviatedName(emojiName))
        // Accessibility text keeps the full name.
        assertEquals("Maximilian Alexander Montgomery", CardValueFormatter.accessibleText(CardSlot.CARDHOLDER, "Maximilian Alexander Montgomery"))
    }

    @Test fun empty_stripped_string_is_not_all_digits() {
        assertFalse(CardValueFormatter.isAsciiDigits(""))
        assertNull(CardValueFormatter.digits(" - - "))
        assertTrue(CardValueFormatter.isAsciiDigits("0"))
    }

    @Test fun accessible_last_four_applies_to_the_card_number_only() {
        assertEquals("1111", CardValueFormatter.accessibleLastFour(CardSlot.CARD_NUMBER, "4111 1111 1111 1111"))
        assertNull(CardValueFormatter.accessibleLastFour(CardSlot.CARD_NUMBER, "1234"))
        assertNull(CardValueFormatter.accessibleLastFour(CardSlot.CVV, "123"))
        assertNull(CardValueFormatter.accessibleLastFour(CardSlot.EXPIRY, "12/27"))
    }
}

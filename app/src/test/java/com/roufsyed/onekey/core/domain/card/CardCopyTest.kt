package com.roufsyed.onekey.core.domain.card

import com.roufsyed.onekey.core.domain.model.CredentialType
import com.roufsyed.onekey.core.domain.model.CustomField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CardCopyTest {

    private val fields = listOf(
        CustomField("Cardholder", "Alice Example", false),
        CustomField("Card Number", "4111 1111-1111 1111", true),
        CustomField("Expiry", "12/27", false),
        CustomField("CVV", "1 2 3", true),
    )

    @Test fun card_number_copies_stripped_digits_with_the_fixed_label() {
        assertEquals(
            CardCopyPayload("Card number", "4111111111111111"),
            CardCopy.payload(CredentialType.CREDIT_CARD, fields, CardSlot.CARD_NUMBER),
        )
    }

    @Test fun exact_payload_copies_the_stored_value_untouched() {
        assertEquals(
            CardCopyPayload("Card number", "4111 1111-1111 1111"),
            CardCopy.payload(CredentialType.CREDIT_CARD, fields, CardSlot.CARD_NUMBER, exact = true),
        )
        assertEquals(CardCopyPayload("CVV", "1 2 3"), CardCopy.payload(CredentialType.CREDIT_CARD, fields, CardSlot.CVV, exact = true))
        assertEquals(CardCopyPayload("Expiry", "12/27"), CardCopy.payload(CredentialType.CREDIT_CARD, fields, CardSlot.EXPIRY, exact = true))
        assertEquals(CardCopyPayload("Name on card", "Alice Example"), CardCopy.payload(CredentialType.CREDIT_CARD, fields, CardSlot.CARDHOLDER, exact = true))
        val blank = listOf(CustomField("CVV", "   ", true))
        assertNull(CardCopy.payload(CredentialType.CREDIT_CARD, blank, CardSlot.CVV, exact = true))
    }

    @Test fun cvv_copies_stripped_digits() {
        assertEquals(CardCopyPayload("CVV", "123"), CardCopy.payload(CredentialType.CREDIT_CARD, fields, CardSlot.CVV))
    }

    @Test fun bank_account_copies_its_debit_card_slots_the_same_way() {
        val bank = fields + CustomField("Account Number", "000123456789", true)
        assertEquals(
            CardCopyPayload("Card number", "4111111111111111"),
            CardCopy.payload(CredentialType.BANK_ACCOUNT, bank, CardSlot.CARD_NUMBER),
        )
    }

    @Test fun label_is_the_slot_constant_never_the_user_key() {
        val renamed = listOf(CustomField("my HDFC visa card number", "4111111111111111", true))
        assertNull(CardCopy.payload(CredentialType.CREDIT_CARD, renamed, CardSlot.CARD_NUMBER))
        val aliased = listOf(CustomField("PAN", "4111111111111111", true))
        assertEquals("Card number", CardCopy.payload(CredentialType.CREDIT_CARD, aliased, CardSlot.CARD_NUMBER)?.label)
    }

    @Test fun non_digit_values_copy_verbatim_trimmed() {
        val odd = listOf(CustomField("Card Number", " 4111 XXXX XXXX 1111 ", true))
        assertEquals("4111 XXXX XXXX 1111", CardCopy.payload(CredentialType.CREDIT_CARD, odd, CardSlot.CARD_NUMBER)?.text)
    }

    @Test fun unbound_blank_and_wrong_type_slots_return_null() {
        assertNull(CardCopy.payload(CredentialType.CREDIT_CARD, fields, CardSlot.SCHEME))
        assertNull(CardCopy.payload(CredentialType.LOGIN, fields, CardSlot.CARD_NUMBER))
        val blank = listOf(CustomField("CVV", "   ", true))
        assertNull(CardCopy.payload(CredentialType.CREDIT_CARD, blank, CardSlot.CVV))
    }

    @Test fun duplicate_keys_copy_the_first_bound_field() {
        val dup = listOf(
            CustomField("Card Number", "4111111111111111", true),
            CustomField("card number", "5555555555554444", true),
        )
        assertEquals("4111111111111111", CardCopy.payload(CredentialType.CREDIT_CARD, dup, CardSlot.CARD_NUMBER)?.text)
    }
}

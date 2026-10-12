package com.roufsyed.onekey.core.domain.card

import com.roufsyed.onekey.core.domain.model.CredentialType
import com.roufsyed.onekey.core.domain.model.CustomField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CardFieldBinderTest {

    @Test fun normaliseKey_trims_collapses_and_lowercases() {
        assertEquals("card number", CardFieldBinder.normaliseKey("  Card   Number "))
        assertEquals("ifsc/routing", CardFieldBinder.normaliseKey("IFSC / Routing"))
        assertEquals("ifsc/routing", CardFieldBinder.normaliseKey("ifsc/routing"))
        assertEquals("", CardFieldBinder.normaliseKey("   "))
    }

    @Test fun binds_canonical_labels_in_list_order() {
        val fields = listOf(
            CustomField("Cardholder", "Alice", false),
            CustomField("Card Number", "4111 1111 1111 1111", true),
            CustomField("Expiry", "12/27", false),
            CustomField("CVV", "123", true),
        )
        assertEquals(
            mapOf(CardSlot.CARDHOLDER to 0, CardSlot.CARD_NUMBER to 1, CardSlot.EXPIRY to 2, CardSlot.CVV to 3),
            CardFieldBinder.resolve(CredentialType.CREDIT_CARD, fields),
        )
    }

    @Test fun binds_recased_padded_and_alias_keys() {
        val fields = listOf(
            CustomField("  card number ", "4111111111111111", true),
            CustomField("Name on Card", "Alice", false),
            CustomField("CVC", "123", true),
            CustomField("Valid Thru", "12/27", false),
        )
        val bound = CardFieldBinder.resolve(CredentialType.CREDIT_CARD, fields)
        assertEquals(0, bound[CardSlot.CARD_NUMBER])
        assertEquals(1, bound[CardSlot.CARDHOLDER])
        assertEquals(2, bound[CardSlot.CVV])
        assertEquals(3, bound[CardSlot.EXPIRY])
    }

    @Test fun first_duplicate_wins_and_later_ones_stay_unbound() {
        val fields = listOf(
            CustomField("Card Number", "4111111111111111", true),
            CustomField("card number", "5555555555554444", true),
        )
        assertEquals(mapOf(CardSlot.CARD_NUMBER to 0), CardFieldBinder.resolve(CredentialType.CREDIT_CARD, fields))
    }

    @Test fun blank_values_never_bind() {
        val fields = listOf(
            CustomField("Card Number", "   ", true),
            CustomField("CVV", "", true),
            CustomField("Cardholder", "Alice", false),
        )
        assertEquals(mapOf(CardSlot.CARDHOLDER to 2), CardFieldBinder.resolve(CredentialType.CREDIT_CARD, fields))
    }

    @Test fun unrelated_keys_and_pin_never_bind() {
        val fields = listOf(
            CustomField("PIN", "1234", true),
            CustomField("Billing Zip", "90210", false),
            CustomField("Notes", "x", false),
        )
        assertTrue(CardFieldBinder.resolve(CredentialType.CREDIT_CARD, fields).isEmpty())
    }

    @Test fun bank_account_binds_the_same_card_slots_and_ignores_account_fields() {
        // A bank account shows the debit card attached to it. Its account fields are
        // never drawn on the card; they stay ordinary rows.
        val fields = listOf(
            CustomField("Account Holder", "John Doe", false),
            CustomField("Account Number", "000123456789", true),
            CustomField("IFSC / Routing", "021000021", true),
            CustomField("Card Number", "4000 1234 5678 9010", true),
            CustomField("CVV", "321", true),
        )
        assertEquals(
            mapOf(CardSlot.CARD_NUMBER to 3, CardSlot.CVV to 4),
            CardFieldBinder.resolve(CredentialType.BANK_ACCOUNT, fields),
        )
        // Bank Name is a card slot on both types: it heads the card.
        val withBank = fields + CustomField("Bank Name", "Chase", false)
        assertEquals(5, CardFieldBinder.resolve(CredentialType.BANK_ACCOUNT, withBank)[CardSlot.BANK_NAME])
    }

    @Test fun non_card_types_never_bind() {
        val fields = listOf(CustomField("Card Number", "4111111111111111", true))
        assertTrue(CardFieldBinder.resolve(CredentialType.LOGIN, fields).isEmpty())
        assertTrue(CardFieldBinder.resolve(CredentialType.OTHER, fields).isEmpty())
    }

    @Test fun slotFor_ignores_value_and_duplicates() {
        assertEquals(CardSlot.CARD_NUMBER, CardFieldBinder.slotFor(CredentialType.CREDIT_CARD, "card number"))
        assertEquals(CardSlot.CVV, CardFieldBinder.slotFor(CredentialType.CREDIT_CARD, "Security Code"))
        assertEquals(CardSlot.CARD_NUMBER, CardFieldBinder.slotFor(CredentialType.BANK_ACCOUNT, "Card Number"))
        assertNull(CardFieldBinder.slotFor(CredentialType.BANK_ACCOUNT, "Account Number"))
        assertNull(CardFieldBinder.slotFor(CredentialType.CREDIT_CARD, "PIN"))
        assertNull(CardFieldBinder.slotFor(CredentialType.LOGIN, "Card Number"))
        assertNull(CardFieldBinder.slotFor(CredentialType.CREDIT_CARD, ""))
    }
}

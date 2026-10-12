package com.roufsyed.onekey.core.domain.card

import com.roufsyed.onekey.core.domain.model.CredentialType
import com.roufsyed.onekey.core.domain.model.CustomField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CardFieldsTest {

    @Test fun new_card_credentials_start_with_the_six_card_fields_empty() {
        val expected = listOf(
            CustomField("Bank Name", "", false),
            CustomField("Name on Card", "", false),
            CustomField("Card Number", "", true),
            CustomField("Expiry", "", false),
            CustomField("CVV", "", true),
            CustomField("Card Scheme", "", false),
        )
        assertEquals(expected, CardFields.defaultsFor(CredentialType.CREDIT_CARD))
        assertEquals(expected, CardFields.defaultsFor(CredentialType.BANK_ACCOUNT))
    }

    @Test fun other_types_get_no_default_fields() {
        CredentialType.entries
            .filterNot { CardSlot.hasCard(it) }
            .forEach { assertTrue("$it", CardFields.defaultsFor(it).isEmpty()) }
    }

    @Test fun defaults_bind_to_their_slots_once_filled() {
        val filled = CardFields.defaultsFor(CredentialType.CREDIT_CARD).map { it.copy(value = "x") }
        val bound = CardFieldBinder.resolve(CredentialType.CREDIT_CARD, filled)
        assertEquals(CardFields.defaultSlots.toSet(), bound.keys)
    }

    @Test fun pruneBlank_drops_only_blank_card_slot_fields() {
        val fields = listOf(
            CustomField("Name on Card", "Alice", false),
            CustomField("Card Number", "   ", true),
            CustomField("Expiry", "", false),
            CustomField("Security Code", "", true),
            CustomField("PIN", "", true),
            CustomField("", "", false),
            CustomField("CVV", "123", true),
        )
        assertEquals(
            listOf(
                CustomField("Name on Card", "Alice", false),
                CustomField("PIN", "", true),
                CustomField("", "", false),
                CustomField("CVV", "123", true),
            ),
            CardFields.pruneBlank(CredentialType.CREDIT_CARD, fields),
        )
    }

    @Test fun pruneBlank_leaves_non_card_types_untouched() {
        val fields = listOf(CustomField("Card Number", "", true), CustomField("Host", "", false))
        assertEquals(fields, CardFields.pruneBlank(CredentialType.LOGIN, fields))
        assertEquals(fields, CardFields.pruneBlank(CredentialType.SERVER, fields))
    }
}

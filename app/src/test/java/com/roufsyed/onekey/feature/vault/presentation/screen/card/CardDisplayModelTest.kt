package com.roufsyed.onekey.feature.vault.presentation.screen.card

import com.roufsyed.onekey.core.domain.card.CardFace
import com.roufsyed.onekey.core.domain.card.CardSlot
import com.roufsyed.onekey.core.domain.model.Credential
import com.roufsyed.onekey.core.domain.model.CredentialType
import com.roufsyed.onekey.core.domain.model.CustomField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CardDisplayModelTest {

    private val fields = listOf(
        CustomField("Network", "Visa", false),
        CustomField("Cardholder", "Alice Example", false),
        CustomField("Card Number", "4111 1111 1111 1111", true),
        CustomField("Expiry", "12/27", false),
        CustomField("CVV", "123", true),
        CustomField("PIN", "1234", true),
    )

    private fun credential(type: CredentialType = CredentialType.CREDIT_CARD, customFields: List<CustomField> = fields) = Credential(
        id = "c1", title = "Visa", username = "", password = "", url = "", notes = "",
        otpParams = null, tags = emptyList(), customFields = customFields,
        createdAt = 1L, updatedAt = 2L, type = type,
    )

    @Test fun binds_slots_and_reports_bound_indices() {
        val model = CardDisplayModel.from(credential())
        assertFalse(model.isEmpty)
        assertEquals(5, model.slots.size)
        assertEquals(setOf(0, 1, 2, 3, 4), model.boundIndices)
        assertEquals("Visa", model.title)
        assertEquals(2, model[CardSlot.CARD_NUMBER]?.fieldIndex)
    }

    @Test fun pin_is_never_bound() {
        val model = CardDisplayModel.from(credential())
        assertFalse(5 in model.boundIndices)
    }

    @Test fun bank_account_shows_its_debit_card_and_ignores_account_fields() {
        val bank = listOf(
            CustomField("Account Number", "000123456789", true),
            CustomField("Card Number", "4000 1234 5678 9010", true),
            CustomField("CVV", "321", true),
        )
        val model = CardDisplayModel.from(credential(type = CredentialType.BANK_ACCOUNT, customFields = bank))
        assertEquals(setOf(1, 2), model.boundIndices)
        assertEquals("•••• •••• •••• 9010", model.getValue(CardSlot.CARD_NUMBER).text(revealed = false))
    }

    @Test fun empty_when_no_slot_has_a_value_or_the_type_has_no_card() {
        assertTrue(CardDisplayModel.from(credential(customFields = listOf(CustomField("Card Number", "", true)))).isEmpty)
        assertTrue(CardDisplayModel.from(credential(type = CredentialType.LOGIN)).isEmpty)
    }

    @Test fun text_masks_sensitive_slots_until_revealed_and_never_masks_plain_ones() {
        val model = CardDisplayModel.from(credential())
        val number = model.getValue(CardSlot.CARD_NUMBER)
        assertEquals("•••• •••• •••• 1111", number.text(revealed = false))
        assertEquals("4111 1111 1111 1111", number.text(revealed = true))
        assertEquals("Alice Example", model.getValue(CardSlot.CARDHOLDER).text(revealed = false))
        assertEquals("12/27", model.getValue(CardSlot.EXPIRY).text(revealed = false))
    }

    @Test fun accessibility_text_never_exposes_a_sensitive_value() {
        val model = CardDisplayModel.from(credential())
        assertEquals("Name on Card, Alice Example", model.getValue(CardSlot.CARDHOLDER).accessibilityText(revealed = false))
        assertEquals("Card Number, hidden, ends in 1111", model.getValue(CardSlot.CARD_NUMBER).accessibilityText(revealed = false))
        assertEquals("CVV, hidden", model.getValue(CardSlot.CVV).accessibilityText(revealed = false))
        assertEquals(
            "Card Number revealed, hidden from screen readers. Use Copy.",
            model.getValue(CardSlot.CARD_NUMBER).accessibilityText(revealed = true),
        )
        assertFalse(model.getValue(CardSlot.CVV).accessibilityText(revealed = true).contains("123"))
    }

    @Test fun masked_and_copyable_slots_follow_the_face_and_the_flags() {
        val model = CardDisplayModel.from(credential())
        assertEquals(listOf(CardSlot.CARD_NUMBER), model.maskedOn(CardFace.FRONT).map { it.slot })
        assertEquals(listOf(CardSlot.CVV), model.maskedOn(CardFace.BACK).map { it.slot })
        assertEquals(listOf(CardSlot.CARD_NUMBER), model.copyableOn(CardFace.FRONT).map { it.slot })
        assertEquals(listOf(CardSlot.CVV), model.copyableOn(CardFace.BACK).map { it.slot })
    }

    @Test fun user_unflagged_number_is_plain_but_still_copyable_and_flagged_name_is_masked() {
        val unflagged = fields.map {
            when (it.key) {
                "Card Number" -> it.copy(isSensitive = false)
                "Cardholder" -> it.copy(isSensitive = true)
                else -> it
            }
        }
        val model = CardDisplayModel.from(credential(customFields = unflagged))
        assertEquals("4111 1111 1111 1111", model.getValue(CardSlot.CARD_NUMBER).text(revealed = false))
        assertEquals(listOf(CardSlot.CARDHOLDER), model.maskedOn(CardFace.FRONT).map { it.slot })
        assertEquals("••••••••", model.getValue(CardSlot.CARDHOLDER).text(revealed = false))
        assertEquals("Name on Card, hidden", model.getValue(CardSlot.CARDHOLDER).accessibilityText(revealed = false))
        assertEquals(listOf(CardSlot.CARD_NUMBER), model.copyableOn(CardFace.FRONT).map { it.slot })
    }

    @Test fun long_name_is_abbreviated_on_the_card_but_read_in_full() {
        val long = fields.map { if (it.key == "Cardholder") it.copy(value = "Maximilian Alexander Montgomery") else it }
        val holder = CardDisplayModel.from(credential(customFields = long)).getValue(CardSlot.CARDHOLDER)
        assertEquals("Maximilian Alexander Mon…", holder.text(revealed = false))
        assertEquals("Name on Card, Maximilian Alexander Montgomery", holder.accessibilityText(revealed = false))
    }

    @Test fun placeholders_exist_for_every_drawn_slot_except_scheme() {
        CardSlot.entries.forEach { slot ->
            val placeholder = CardDisplayModel.placeholderFor(slot)
            if (slot == CardSlot.SCHEME) assertNull(placeholder) else assertTrue(!placeholder.isNullOrBlank())
        }
    }

    private fun CardDisplayModel.getValue(slot: CardSlot): CardSlotDisplay =
        this[slot] ?: throw AssertionError("$slot not bound")
}

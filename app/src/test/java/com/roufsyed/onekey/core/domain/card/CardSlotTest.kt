package com.roufsyed.onekey.core.domain.card

import com.roufsyed.onekey.core.domain.model.CredentialType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CardSlotTest {

    @Test fun both_card_types_share_the_same_six_slots() {
        val expected = listOf(CardSlot.BANK_NAME, CardSlot.CARDHOLDER, CardSlot.CARD_NUMBER, CardSlot.EXPIRY, CardSlot.CVV, CardSlot.SCHEME)
        assertEquals(expected, CardSlot.entries)
        assertEquals(expected, CardSlot.forType(CredentialType.CREDIT_CARD))
        assertEquals(expected, CardSlot.forType(CredentialType.BANK_ACCOUNT))
    }

    @Test fun only_the_two_card_types_have_a_card() {
        CredentialType.entries.forEach { type ->
            val expected = type == CredentialType.CREDIT_CARD || type == CredentialType.BANK_ACCOUNT
            assertEquals("$type", expected, CardSlot.hasCard(type))
            if (!expected) assertTrue(CardSlot.forType(type).isEmpty())
        }
    }

    @Test fun faces_follow_the_physical_card_layout() {
        assertEquals(CardFace.FRONT, CardSlot.BANK_NAME.face)
        assertEquals(CardFace.FRONT, CardSlot.CARDHOLDER.face)
        assertEquals(CardFace.FRONT, CardSlot.CARD_NUMBER.face)
        assertEquals(CardFace.FRONT, CardSlot.EXPIRY.face)
        assertEquals(CardFace.FRONT, CardSlot.SCHEME.face)
        assertEquals(CardFace.BACK, CardSlot.CVV.face)
        assertEquals(CardFace.BACK, CardFace.FRONT.other())
        assertEquals(CardFace.FRONT, CardFace.BACK.other())
    }

    @Test fun copyable_slots_are_exactly_the_value_slots() {
        assertEquals(setOf(CardSlot.CARD_NUMBER, CardSlot.CVV), CardSlot.entries.filter { it.copyable }.toSet())
    }

    @Test fun numeric_slots_are_exactly_the_digit_slots() {
        assertEquals(
            setOf(CardSlot.CARD_NUMBER, CardSlot.EXPIRY, CardSlot.CVV),
            CardSlot.entries.filter { it.numeric }.toSet(),
        )
    }

    @Test fun labels_and_aliases_never_collide_between_slots() {
        val seen = HashMap<String, CardSlot>()
        CardSlot.entries.forEach { slot ->
            slot.labels.forEach { label ->
                val key = CardFieldBinder.normaliseKey(label)
                val previous = seen.put(key, slot)
                assertTrue("'$label' is claimed by both $previous and $slot", previous == null || previous == slot)
            }
        }
    }

    @Test fun clip_labels_are_fixed_text_never_derived_from_user_input() {
        CardSlot.entries.forEach { slot ->
            assertFalse(slot.clipLabel.isBlank())
            assertEquals(slot.clipLabel, slot.clipLabel.trim())
        }
    }
}

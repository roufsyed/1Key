package com.roufsyed.onekey.core.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomFieldTest {

    @Test fun pruneBlank_drops_every_field_without_a_value_and_keeps_order() {
        val fields = listOf(
            CustomField("Name on Card", "Alice", false),
            CustomField("Card Number", "   ", true),
            CustomField("Expiry", "", false),
            CustomField("PIN", "", true),
            CustomField("", "", false),
            CustomField("Host", "", false),
            CustomField("CVV", "123", true),
            CustomField("", "unlabelled but typed", false),
        )
        assertEquals(
            listOf(
                CustomField("Name on Card", "Alice", false),
                CustomField("CVV", "123", true),
                CustomField("", "unlabelled but typed", false),
            ),
            CustomField.pruneBlank(fields),
        )
    }

    @Test fun pruneBlank_keeps_a_list_with_values_intact() {
        val fields = listOf(CustomField("Host", "db.example", false), CustomField("Port", "5432", false))
        assertEquals(fields, CustomField.pruneBlank(fields))
    }

    @Test fun pruneBlank_of_nothing_is_nothing() {
        assertTrue(CustomField.pruneBlank(emptyList()).isEmpty())
    }
}

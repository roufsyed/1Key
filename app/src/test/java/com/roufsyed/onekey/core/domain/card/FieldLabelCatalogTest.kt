package com.roufsyed.onekey.core.domain.card

import com.roufsyed.onekey.core.domain.model.CredentialType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Golden lock for [FieldLabelCatalog]: the exact labels, order and default
 * sensitivity of the editor's suggestion chips and the OCR targets. The lists for
 * SERVER, DATABASE and EMAIL are unchanged from before the unification; the two
 * card types both offer the card fields, because a bank account shows the debit
 * card attached to it. Any change here is a visible behaviour change for every
 * user and must be deliberate.
 */
class FieldLabelCatalogTest {

    @Test fun bank_account_suggestions_match_golden() {
        assertEquals(
            listOf(
                FieldLabel("Account Holder", false),
                FieldLabel("Account Number", true),
                FieldLabel("Bank Name", false),
                FieldLabel("IFSC / Routing", true),
                FieldLabel("IBAN", true),
                FieldLabel("Branch", false),
                FieldLabel("PIN", true),
                FieldLabel("Name on Card", false),
                FieldLabel("Card Number", true),
                FieldLabel("Expiry", false),
                FieldLabel("CVV", true),
                FieldLabel("Card Scheme", false),
            ),
            FieldLabelCatalog.suggestionsFor(CredentialType.BANK_ACCOUNT),
        )
    }

    @Test fun credit_card_suggestions_match_golden() {
        assertEquals(
            listOf(
                FieldLabel("Bank Name", false),
                FieldLabel("Name on Card", false),
                FieldLabel("Card Number", true),
                FieldLabel("Expiry", false),
                FieldLabel("CVV", true),
                FieldLabel("PIN", true),
                FieldLabel("Billing Zip", false),
                FieldLabel("Card Scheme", false),
            ),
            FieldLabelCatalog.suggestionsFor(CredentialType.CREDIT_CARD),
        )
    }

    @Test fun server_database_and_email_suggestions_match_golden() {
        assertEquals(
            listOf(
                FieldLabel("Host", false),
                FieldLabel("Port", false),
                FieldLabel("SSH Key Path", false),
                FieldLabel("API Token", true),
            ),
            FieldLabelCatalog.suggestionsFor(CredentialType.SERVER),
        )
        assertEquals(
            listOf(
                FieldLabel("Host", false),
                FieldLabel("Port", false),
                FieldLabel("Database", false),
                FieldLabel("Connection String", true),
            ),
            FieldLabelCatalog.suggestionsFor(CredentialType.DATABASE),
        )
        assertEquals(
            listOf(
                FieldLabel("IMAP Host", false),
                FieldLabel("SMTP Host", false),
                FieldLabel("App Password", true),
            ),
            FieldLabelCatalog.suggestionsFor(CredentialType.EMAIL),
        )
    }

    @Test fun types_without_suggestions_return_empty() {
        listOf(CredentialType.LOGIN, CredentialType.PASSWORD, CredentialType.SECURE_NOTE, CredentialType.OTHER)
            .forEach { assertTrue("$it must have no suggestions", FieldLabelCatalog.suggestionsFor(it).isEmpty()) }
    }

    @Test fun both_card_types_offer_every_card_slot_with_the_slot_sensitivity() {
        listOf(CredentialType.CREDIT_CARD, CredentialType.BANK_ACCOUNT).forEach { type ->
            val suggestions = FieldLabelCatalog.suggestionsFor(type)
            CardSlot.entries.forEach { slot ->
                assertTrue(
                    "$type must offer ${slot.canonicalLabel}",
                    suggestions.contains(FieldLabel(slot.canonicalLabel, slot.sensitiveByDefault)),
                )
            }
        }
    }

    @Test fun sensitiveByDefault_matches_labels_and_aliases_after_normalisation() {
        assertTrue(FieldLabelCatalog.sensitiveByDefault("Card Number"))
        assertTrue(FieldLabelCatalog.sensitiveByDefault("  card   number "))
        assertTrue(FieldLabelCatalog.sensitiveByDefault("cvv"))
        assertTrue(FieldLabelCatalog.sensitiveByDefault("CVC"))
        assertTrue(FieldLabelCatalog.sensitiveByDefault("Security Code"))
        assertTrue(FieldLabelCatalog.sensitiveByDefault("IFSC/Routing"))
        assertTrue(FieldLabelCatalog.sensitiveByDefault("Account Number"))
        assertTrue(FieldLabelCatalog.sensitiveByDefault("PIN"))
        assertTrue(FieldLabelCatalog.sensitiveByDefault("API Token"))
        assertTrue(FieldLabelCatalog.sensitiveByDefault("Connection String"))
        assertTrue(FieldLabelCatalog.sensitiveByDefault("App Password"))

        assertFalse(FieldLabelCatalog.sensitiveByDefault("Cardholder"))
        assertFalse(FieldLabelCatalog.sensitiveByDefault("Expiry"))
        assertFalse(FieldLabelCatalog.sensitiveByDefault("Host"))
        assertFalse(FieldLabelCatalog.sensitiveByDefault("Billing Zip"))
        assertFalse(FieldLabelCatalog.sensitiveByDefault(""))
        assertFalse(FieldLabelCatalog.sensitiveByDefault("   "))
    }
}

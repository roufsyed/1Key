package com.roufsyed.onekey.feature.vault.presentation

import com.roufsyed.onekey.core.domain.model.CredentialType
import com.roufsyed.onekey.feature.vault.presentation.screen.OcrTarget
import com.roufsyed.onekey.feature.vault.presentation.screen.ocrTargetsFor
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Golden lock for [ocrTargetsFor]. The custom-field targets come from the shared
 * FieldLabelCatalog. Every list except BANK_ACCOUNT is exactly what the OCR sheet
 * offered before the refactor; BANK_ACCOUNT additionally offers the debit-card
 * fields it now renders as a flip card.
 */
class OcrTargetsGoldenTest {

    private fun cf(label: String, sensitive: Boolean) = OcrTarget.CustomField(label, sensitive)

    @Test fun login_and_password_offer_auth_fields_url_and_notes() {
        val expected = listOf(OcrTarget.Username, OcrTarget.Password, OcrTarget.Url, OcrTarget.Notes)
        assertEquals(expected, ocrTargetsFor(CredentialType.LOGIN))
        assertEquals(expected, ocrTargetsFor(CredentialType.PASSWORD))
    }

    @Test fun secure_note_and_other_offer_notes_only() {
        assertEquals(listOf(OcrTarget.Notes), ocrTargetsFor(CredentialType.SECURE_NOTE))
        assertEquals(listOf(OcrTarget.Notes), ocrTargetsFor(CredentialType.OTHER))
    }

    @Test fun credit_card_targets_match_golden() {
        assertEquals(
            listOf(
                OcrTarget.Username,
                OcrTarget.Password,
                cf("Bank Name", false),
                cf("Name on Card", false),
                cf("Card Number", true),
                cf("Expiry", false),
                cf("CVV", true),
                cf("PIN", true),
                cf("Billing Zip", false),
                cf("Card Scheme", false),
                OcrTarget.Notes,
            ),
            ocrTargetsFor(CredentialType.CREDIT_CARD),
        )
    }

    @Test fun bank_account_targets_match_golden() {
        assertEquals(
            listOf(
                OcrTarget.Username,
                OcrTarget.Password,
                cf("Account Holder", false),
                cf("Account Number", true),
                cf("Bank Name", false),
                cf("IFSC / Routing", true),
                cf("IBAN", true),
                cf("Branch", false),
                cf("PIN", true),
                cf("Name on Card", false),
                cf("Card Number", true),
                cf("Expiry", false),
                cf("CVV", true),
                cf("Card Scheme", false),
                OcrTarget.Notes,
            ),
            ocrTargetsFor(CredentialType.BANK_ACCOUNT),
        )
    }

    @Test fun server_database_and_email_targets_match_golden() {
        assertEquals(
            listOf(
                OcrTarget.Username, OcrTarget.Password,
                cf("Host", false), cf("Port", false), cf("SSH Key Path", false), cf("API Token", true),
                OcrTarget.Notes,
            ),
            ocrTargetsFor(CredentialType.SERVER),
        )
        assertEquals(
            listOf(
                OcrTarget.Username, OcrTarget.Password,
                cf("Host", false), cf("Port", false), cf("Database", false), cf("Connection String", true),
                OcrTarget.Notes,
            ),
            ocrTargetsFor(CredentialType.DATABASE),
        )
        assertEquals(
            listOf(
                OcrTarget.Username, OcrTarget.Password,
                cf("IMAP Host", false), cf("SMTP Host", false), cf("App Password", true),
                OcrTarget.Notes,
            ),
            ocrTargetsFor(CredentialType.EMAIL),
        )
    }
}

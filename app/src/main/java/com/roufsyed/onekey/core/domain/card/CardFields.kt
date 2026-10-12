package com.roufsyed.onekey.core.domain.card

import com.roufsyed.onekey.core.domain.model.CredentialType
import com.roufsyed.onekey.core.domain.model.CustomField

/**
 * The card fields a NEW card credential starts with. New CREDIT_CARD and
 * BANK_ACCOUNT credentials are pre-filled with empty Bank Name, Name on Card,
 * Card Number, Expiry, CVV and Card Scheme fields so the card can be completed
 * without hunting for suggestion chips; whatever the user leaves blank is dropped
 * on save by [CustomField.pruneBlank], the rule every custom field follows (the
 * chips offer the label again at any time).
 */
object CardFields {

    /** Pre-added to every new card credential, in display order. */
    val defaultSlots: List<CardSlot> = listOf(
        CardSlot.BANK_NAME,
        CardSlot.CARDHOLDER,
        CardSlot.CARD_NUMBER,
        CardSlot.EXPIRY,
        CardSlot.CVV,
        CardSlot.SCHEME,
    )

    /** Empty default fields for [type]; empty for types without a card. */
    fun defaultsFor(type: CredentialType): List<CustomField> =
        if (CardSlot.hasCard(type)) {
            defaultSlots.map { CustomField(key = it.canonicalLabel, value = "", isSensitive = it.sensitiveByDefault) }
        } else {
            emptyList()
        }
}

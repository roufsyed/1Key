package com.roufsyed.onekey.core.domain.card

import com.roufsyed.onekey.core.domain.model.CredentialType

/** Which side of the flip card is showing or drawn. */
enum class CardFace {
    FRONT,
    BACK,
    ;

    fun other(): CardFace = if (this == FRONT) BACK else FRONT
}

/**
 * A position on the flip card that a custom field can bind to. The card stores
 * nothing of its own: every value comes from the credential's free-text custom
 * fields, resolved by label through [CardFieldBinder].
 *
 * One layout serves both card types. A CREDIT_CARD credential is the card itself;
 * a BANK_ACCOUNT credential shows the debit card attached to the account, while
 * its account-specific fields (account number, IBAN, IFSC and so on) stay ordinary
 * rows below the card.
 *
 * @property canonicalLabel the editor suggestion and OCR target label, and the key
 *   the binder matches first.
 * @property clipLabel the fixed clipboard label used by copy actions. Never user
 *   text: the clip label is readable by any clipboard reader on every API level.
 * @property sensitiveByDefault the sensitivity the editor and OCR assign on creation.
 * @property face the side of the card the slot is drawn on.
 * @property numeric true for slots whose values are digit strings (numeric keyboard,
 *   digit grouping, separator stripping on copy).
 * @property copyable true for value slots that get a Copy button on the action row,
 *   regardless of the field's sensitive flag.
 * @property aliases alternative labels seen in imports and hand-typed keys, compared
 *   after [CardFieldBinder.normaliseKey]. Kept deliberately short and unambiguous.
 */
enum class CardSlot(
    val canonicalLabel: String,
    val clipLabel: String,
    val sensitiveByDefault: Boolean,
    val face: CardFace,
    val numeric: Boolean,
    val copyable: Boolean,
    val aliases: List<String> = emptyList(),
) {
    /** Issuing bank, drawn at the top of the card in place of the credential title. */
    BANK_NAME(
        "Bank Name", "Bank name", sensitiveByDefault = false, CardFace.FRONT,
        numeric = false, copyable = false, aliases = listOf("Bank", "Issuer"),
    ),
    CARDHOLDER(
        "Name on Card", "Name on card", sensitiveByDefault = false, CardFace.FRONT,
        numeric = false, copyable = false, aliases = listOf("Cardholder", "Card Holder"),
    ),
    CARD_NUMBER(
        "Card Number", "Card number", sensitiveByDefault = true, CardFace.FRONT,
        numeric = true, copyable = true, aliases = listOf("Card No", "Number", "PAN"),
    ),
    EXPIRY(
        "Expiry", "Expiry", sensitiveByDefault = false, CardFace.FRONT,
        numeric = true, copyable = false, aliases = listOf("Expiry Date", "Exp", "Valid Thru"),
    ),
    CVV(
        "CVV", "CVV", sensitiveByDefault = true, CardFace.BACK,
        numeric = true, copyable = true, aliases = listOf("CVC", "CVV2", "Security Code"),
    ),
    /** Card network; drawn as a mark in place of the chip glyph. Edited through a dropdown. */
    SCHEME(
        "Card Scheme", "Card scheme", sensitiveByDefault = false, CardFace.FRONT,
        numeric = false, copyable = false, aliases = listOf("Network", "Card Network", "Scheme", "Brand"),
    ),
    ;

    /** Every label this slot answers to, canonical first. */
    val labels: List<String> get() = listOf(canonicalLabel) + aliases

    companion object {
        /** True for the two credential types that render as a flip card. */
        fun hasCard(type: CredentialType): Boolean =
            type == CredentialType.CREDIT_CARD || type == CredentialType.BANK_ACCOUNT

        /** The slots that make up the card for [type]; empty for types without a card. */
        fun forType(type: CredentialType): List<CardSlot> = if (hasCard(type)) entries else emptyList()
    }
}

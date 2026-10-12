package com.roufsyed.onekey.core.domain.card

import com.roufsyed.onekey.core.domain.model.CredentialType

/** A suggested custom-field label for a credential type and the sensitivity it is created with. */
data class FieldLabel(val label: String, val sensitive: Boolean)

/**
 * Single source of truth for the per-type "suggested fields". The editor's
 * suggestion chips and the OCR target list both map from here, so the two can
 * never drift again (they used to be duplicated literals). The card slots come
 * from [CardSlot] and are offered on both card types; the remaining labels (PIN,
 * Billing Zip, the account, server, database and email fields) are plain
 * suggestions with no card position.
 *
 * Order matters: it is the order the chips and OCR targets are shown in, and it
 * is locked by FieldLabelCatalogTest.
 */
object FieldLabelCatalog {

    private val pin = FieldLabel("PIN", sensitive = true)

    private val cardSlots: List<FieldLabel> = listOf(
        slot(CardSlot.CARDHOLDER),
        slot(CardSlot.CARD_NUMBER),
        slot(CardSlot.EXPIRY),
        slot(CardSlot.CVV),
    )

    private val byType: Map<CredentialType, List<FieldLabel>> = mapOf(
        CredentialType.BANK_ACCOUNT to listOf(
            FieldLabel("Account Holder", sensitive = false),
            FieldLabel("Account Number", sensitive = true),
            slot(CardSlot.BANK_NAME),
            FieldLabel("IFSC / Routing", sensitive = true),
            FieldLabel("IBAN", sensitive = true),
            FieldLabel("Branch", sensitive = false),
            pin,
        ) + cardSlots + slot(CardSlot.SCHEME),
        CredentialType.CREDIT_CARD to listOf(slot(CardSlot.BANK_NAME)) + cardSlots + listOf(
            pin,
            FieldLabel("Billing Zip", sensitive = false),
            slot(CardSlot.SCHEME),
        ),
        CredentialType.SERVER to listOf(
            FieldLabel("Host", sensitive = false),
            FieldLabel("Port", sensitive = false),
            FieldLabel("SSH Key Path", sensitive = false),
            FieldLabel("API Token", sensitive = true),
        ),
        CredentialType.DATABASE to listOf(
            FieldLabel("Host", sensitive = false),
            FieldLabel("Port", sensitive = false),
            FieldLabel("Database", sensitive = false),
            FieldLabel("Connection String", sensitive = true),
        ),
        CredentialType.EMAIL to listOf(
            FieldLabel("IMAP Host", sensitive = false),
            FieldLabel("SMTP Host", sensitive = false),
            FieldLabel("App Password", sensitive = true),
        ),
    )

    /** Every normalised label (catalogue labels plus card-slot aliases) that is sensitive by default. */
    private val sensitiveKeys: Set<String> by lazy {
        val fromCatalog = byType.values.flatten()
            .filter { it.sensitive }
            .map { CardFieldBinder.normaliseKey(it.label) }
        val fromSlots = CardSlot.entries
            .filter { it.sensitiveByDefault }
            .flatMap { slot -> slot.labels.map(CardFieldBinder::normaliseKey) }
        (fromCatalog + fromSlots).toSet()
    }

    /** Suggested fields for [type], in display order. Empty for types without suggestions. */
    fun suggestionsFor(type: CredentialType): List<FieldLabel> = byType[type] ?: emptyList()

    /**
     * True when [key] names a value this catalogue treats as sensitive for ANY type,
     * by canonical label or card-slot alias, after [CardFieldBinder.normaliseKey].
     * Intended for imports that carry no explicit sensitivity flag.
     */
    fun sensitiveByDefault(key: String): Boolean {
        val normalised = CardFieldBinder.normaliseKey(key)
        return normalised.isNotEmpty() && normalised in sensitiveKeys
    }

    private fun slot(slot: CardSlot) = FieldLabel(slot.canonicalLabel, slot.sensitiveByDefault)
}

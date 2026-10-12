package com.roufsyed.onekey.core.domain.card

import com.roufsyed.onekey.core.domain.model.Credential
import com.roufsyed.onekey.core.domain.model.CredentialType
import com.roufsyed.onekey.core.domain.model.CustomField

/** What a card copy action puts on the clipboard: a fixed label and the prepared text. */
data class CardCopyPayload(val label: String, val text: String)

/**
 * Resolves the clipboard payload for a card slot. Uses the same [CardFieldBinder]
 * the card draws with, so view and copy can never pick different duplicate fields;
 * the label is the slot's fixed [CardSlot.clipLabel] (never user text) and numeric
 * slots are stripped to digits by [CardValueFormatter.copyText].
 */
object CardCopy {

    /**
     * @param exact true copies the stored value byte for byte (long press on the card);
     *   false prepares it for payment forms (copy buttons): numeric slots are stripped
     *   to digits, everything else is trimmed.
     */
    fun payload(type: CredentialType, customFields: List<CustomField>, slot: CardSlot, exact: Boolean = false): CardCopyPayload? {
        val index = CardFieldBinder.resolve(type, customFields)[slot] ?: return null
        val raw = customFields[index].value
        val text = if (exact) raw else CardValueFormatter.copyText(slot, raw)
        if (text.isBlank()) return null
        return CardCopyPayload(slot.clipLabel, text)
    }

    fun payload(credential: Credential, slot: CardSlot, exact: Boolean = false): CardCopyPayload? =
        payload(credential.type, credential.customFields, slot, exact)
}

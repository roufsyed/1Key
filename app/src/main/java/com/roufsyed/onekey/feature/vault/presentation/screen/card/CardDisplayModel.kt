package com.roufsyed.onekey.feature.vault.presentation.screen.card

import androidx.compose.runtime.Immutable
import com.roufsyed.onekey.core.domain.card.CardFace
import com.roufsyed.onekey.core.domain.card.CardFieldBinder
import com.roufsyed.onekey.core.domain.card.CardSlot
import com.roufsyed.onekey.core.domain.card.CardValueFormatter
import com.roufsyed.onekey.core.domain.model.Credential
import com.roufsyed.onekey.core.domain.model.CredentialType
import com.roufsyed.onekey.core.domain.model.CustomField

/**
 * One bound slot: which custom field feeds it and how it renders. Pure Kotlin so
 * the masking and accessibility rules are unit tested without Compose.
 */
@Immutable
data class CardSlotDisplay(
    val slot: CardSlot,
    val fieldIndex: Int,
    val rawValue: String,
    val isSensitive: Boolean,
) {
    /** Text drawn on the card. Non-sensitive slots are always shown in full. */
    fun text(revealed: Boolean): String =
        if (isSensitive && !revealed) {
            CardValueFormatter.maskedText(slot, rawValue)
        } else {
            CardValueFormatter.revealedText(slot, rawValue)
        }

    /**
     * Accessibility description. A sensitive value is never exposed to accessibility
     * services, not even while revealed on screen - the same rule the password row
     * applies - so screen-reader users copy instead. The masked form may name the
     * last four digits when the formatter's last-four rule passes.
     */
    fun accessibilityText(revealed: Boolean): String {
        val label = slot.canonicalLabel
        if (!isSensitive) return "$label, ${CardValueFormatter.accessibleText(slot, rawValue)}"
        if (revealed) return "$label revealed, hidden from screen readers. Use Copy."
        val tail = CardValueFormatter.accessibleLastFour(slot, rawValue)
        return if (tail != null) "$label, hidden, ends in $tail" else "$label, hidden"
    }
}

/**
 * Everything the flip card needs, resolved once per credential (or per editor
 * keystroke in the preview). Slots that are not bound are simply absent; the
 * composable decides whether to omit them or draw a placeholder.
 */
@Immutable
data class CardDisplayModel(
    val type: CredentialType,
    val title: String,
    val slots: Map<CardSlot, CardSlotDisplay>,
) {
    val isEmpty: Boolean get() = slots.isEmpty()

    /** Indices of the custom fields the card renders. The detail screen still shows their rows. */
    val boundIndices: Set<Int> get() = slots.values.mapTo(HashSet()) { it.fieldIndex }

    operator fun get(slot: CardSlot): CardSlotDisplay? = slots[slot]

    /** Bound slots on [face] that are currently masked at rest (sensitive). */
    fun maskedOn(face: CardFace): List<CardSlotDisplay> =
        slots.values.filter { it.slot.face == face && it.isSensitive }

    /** Bound value slots on [face] that get a Copy button, regardless of the sensitive flag. */
    fun copyableOn(face: CardFace): List<CardSlotDisplay> =
        slots.values.filter { it.slot.face == face && it.slot.copyable }

    companion object {
        fun from(credential: Credential): CardDisplayModel =
            build(credential.type, credential.title, credential.customFields)

        fun build(type: CredentialType, title: String, customFields: List<CustomField>): CardDisplayModel {
            val bound = CardFieldBinder.resolve(type, customFields)
            val slots = LinkedHashMap<CardSlot, CardSlotDisplay>()
            bound.forEach { (slot, index) ->
                val field = customFields[index]
                slots[slot] = CardSlotDisplay(slot, index, field.value, field.isSensitive)
            }
            return CardDisplayModel(type, title, slots)
        }

        /** Editor-preview placeholder for an unbound slot; null for slots that are simply omitted. */
        fun placeholderFor(slot: CardSlot): String? = when (slot) {
            CardSlot.BANK_NAME -> "BANK NAME"
            CardSlot.CARD_NUMBER -> CardValueFormatter.MASKED_NUMBER
            CardSlot.CARDHOLDER -> "NAME ON CARD"
            CardSlot.EXPIRY -> "MM/YY"
            CardSlot.CVV -> CardValueFormatter.MASKED_CVV
            CardSlot.SCHEME -> null
        }
    }
}

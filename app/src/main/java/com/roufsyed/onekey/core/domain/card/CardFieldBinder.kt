package com.roufsyed.onekey.core.domain.card

import com.roufsyed.onekey.core.domain.model.Credential
import com.roufsyed.onekey.core.domain.model.CredentialType
import com.roufsyed.onekey.core.domain.model.CustomField

/**
 * Resolves a credential's free-text custom fields onto [CardSlot]s by label.
 *
 * Matching is deliberately more tolerant than the editor's chip de-duplication
 * (which is only case-insensitive) and the OCR upsert (which is exact): keys are
 * trimmed, internal whitespace is collapsed, spacing around '/' is normalised and
 * the comparison is case-insensitive, then canonical labels and the short alias
 * lists on [CardSlot] are consulted. The FIRST field in list order wins a slot;
 * later duplicates stay ordinary rows. Pure Kotlin, no Android imports.
 */
object CardFieldBinder {

    private val whitespace = Regex("\\s+")
    private val slashSpacing = Regex("\\s*/\\s*")

    /** Lower-cased (locale independent), trimmed, single-spaced, "a/b" spacing normalised. */
    fun normaliseKey(key: String): String =
        key.trim().replace(whitespace, " ").replace(slashSpacing, "/").lowercase()

    private val keysBySlot: Map<CardSlot, Set<String>> =
        CardSlot.entries.associateWith { slot -> slot.labels.map(::normaliseKey).toSet() }

    /**
     * The slot a field key names for [type], ignoring the field's value and any
     * duplicates. Used by the editor for per-row keyboard options and preview
     * placeholders. Null when the key names no slot or [type] has no card.
     */
    fun slotFor(type: CredentialType, key: String): CardSlot? {
        val slots = CardSlot.forType(type)
        if (slots.isEmpty()) return null
        val normalised = normaliseKey(key)
        if (normalised.isEmpty()) return null
        return slots.firstOrNull { normalised in keysBySlot.getValue(it) }
    }

    /**
     * Index of the first custom field bound to each slot of [type]. Fields with a
     * blank value never bind (an empty slot is omitted from the card rather than
     * drawn empty, and the field keeps its ordinary row). Empty for types without
     * a card.
     */
    fun resolve(type: CredentialType, customFields: List<CustomField>): Map<CardSlot, Int> {
        val slots = CardSlot.forType(type)
        if (slots.isEmpty() || customFields.isEmpty()) return emptyMap()
        val bound = LinkedHashMap<CardSlot, Int>()
        customFields.forEachIndexed { index, field ->
            if (field.value.isBlank()) return@forEachIndexed
            val normalised = normaliseKey(field.key)
            if (normalised.isEmpty()) return@forEachIndexed
            val slot = slots.firstOrNull { it !in bound && normalised in keysBySlot.getValue(it) }
                ?: return@forEachIndexed
            bound[slot] = index
        }
        return bound
    }

    fun resolve(credential: Credential): Map<CardSlot, Int> = resolve(credential.type, credential.customFields)
}

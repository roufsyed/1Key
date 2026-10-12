package com.roufsyed.onekey.feature.vault.presentation.screen

import com.roufsyed.onekey.core.domain.card.FieldLabelCatalog
import com.roufsyed.onekey.core.domain.model.CredentialType

// ---------------------------------------------------------------------------
// OCR public types + per-type target mapping.
//
// These live in the shared (src/main) source set - deliberately NOT alongside
// the OcrScannerSheet composable, which is flavor-specific:
//   - full   : the real ML Kit "Scan from photo" sheet (src/full).
//   - fdroid : a LockAwareDialog explaining the feature is stripped (src/fdroid).
// Both flavors expose the SAME `OcrScannerSheet(targets, onResult, onDismiss)`
// signature, and CredentialDetailScreen (shared) builds `targets` via
// [ocrTargetsFor] and consumes an [OcrAssignments] result regardless of flavor.
//
// They can be shared precisely because they are pure Kotlin - no ML Kit, no
// Compose - so the fdroid build compiles against them without pulling in any
// Google proprietary dependency.
// ---------------------------------------------------------------------------

/**
 * Where an OCR-extracted text block can be routed. The launching screen passes a list
 * of targets sized to its credential type - SECURE_NOTE only offers [Notes], BANK_ACCOUNT
 * offers [Username] + [Password] + several [CustomField]s + [Notes], etc.
 */
sealed class OcrTarget(val label: String) {
    data object Username : OcrTarget("Username")
    data object Password : OcrTarget("Password")
    data object Url : OcrTarget("URL")
    /** Multi-block target. Tapping the same block again removes it from the notes set. */
    data object Notes : OcrTarget("Notes")
    /** Maps to a custom field on the credential. The launching screen upserts by key. */
    data class CustomField(val key: String, val sensitive: Boolean) : OcrTarget(key)
}

data class OcrCustomFieldAssignment(
    val key: String,
    val value: String,
    val sensitive: Boolean,
)

data class OcrAssignments(
    val username: String? = null,
    val password: String? = null,
    val url: String? = null,
    val notes: String? = null,
    val customFields: List<OcrCustomFieldAssignment> = emptyList(),
)

/**
 * Default OCR target list per credential type. The custom-field targets come from
 * [FieldLabelCatalog], the same table that feeds the editor's suggestion chips, so
 * the two can never disagree on labels or default sensitivity. Username/Password/URL
 * are dropped for `SECURE_NOTE` and `OTHER` since those types have no auth fields
 * rendered. They stay for `CREDIT_CARD` because users may also store the
 * online-card-portal login on the same record. URL is offered only for the
 * login-shaped `LOGIN` and `PASSWORD` types.
 */
fun ocrTargetsFor(type: CredentialType): List<OcrTarget> {
    if (type == CredentialType.SECURE_NOTE || type == CredentialType.OTHER) {
        return listOf(OcrTarget.Notes)
    }
    val customFieldTargets = FieldLabelCatalog.suggestionsFor(type)
        .map { OcrTarget.CustomField(it.label, it.sensitive) }
    val urlTarget = if (type == CredentialType.LOGIN || type == CredentialType.PASSWORD) {
        listOf(OcrTarget.Url)
    } else {
        emptyList()
    }
    return listOf(OcrTarget.Username, OcrTarget.Password) + urlTarget + customFieldTargets + OcrTarget.Notes
}

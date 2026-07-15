package com.roufsyed.onekey.feature.vault.presentation.screen

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
 * Default OCR target list per credential type. Mirrors the editor's `fieldSuggestionsFor`
 * + the universal Notes fallback. Username/Password/URL are dropped for `SECURE_NOTE` and
 * `OTHER` since those types have no auth fields rendered. They stay for `CREDIT_CARD`
 * because users may also store the online-card-portal login on the same record.
 */
fun ocrTargetsFor(type: CredentialType): List<OcrTarget> = when (type) {
    CredentialType.LOGIN, CredentialType.PASSWORD -> listOf(
        OcrTarget.Username,
        OcrTarget.Password,
        OcrTarget.Url,
        OcrTarget.Notes,
    )
    CredentialType.SECURE_NOTE, CredentialType.OTHER -> listOf(
        OcrTarget.Notes,
    )
    CredentialType.CREDIT_CARD -> listOf(
        OcrTarget.Username,
        OcrTarget.Password,
        OcrTarget.CustomField("Cardholder", sensitive = false),
        OcrTarget.CustomField("Card Number", sensitive = true),
        OcrTarget.CustomField("Expiry", sensitive = false),
        OcrTarget.CustomField("CVV", sensitive = true),
        OcrTarget.CustomField("PIN", sensitive = true),
        OcrTarget.CustomField("Billing Zip", sensitive = false),
        OcrTarget.CustomField("Network", sensitive = false),
        OcrTarget.Notes,
    )
    CredentialType.BANK_ACCOUNT -> listOf(
        OcrTarget.Username,
        OcrTarget.Password,
        OcrTarget.CustomField("Account Holder", sensitive = false),
        OcrTarget.CustomField("Account Number", sensitive = true),
        OcrTarget.CustomField("Bank Name", sensitive = false),
        OcrTarget.CustomField("IFSC / Routing", sensitive = true),
        OcrTarget.CustomField("IBAN", sensitive = true),
        OcrTarget.CustomField("Branch", sensitive = false),
        OcrTarget.CustomField("PIN", sensitive = true),
        OcrTarget.Notes,
    )
    CredentialType.SERVER -> listOf(
        OcrTarget.Username,
        OcrTarget.Password,
        OcrTarget.CustomField("Host", sensitive = false),
        OcrTarget.CustomField("Port", sensitive = false),
        OcrTarget.CustomField("SSH Key Path", sensitive = false),
        OcrTarget.CustomField("API Token", sensitive = true),
        OcrTarget.Notes,
    )
    CredentialType.DATABASE -> listOf(
        OcrTarget.Username,
        OcrTarget.Password,
        OcrTarget.CustomField("Host", sensitive = false),
        OcrTarget.CustomField("Port", sensitive = false),
        OcrTarget.CustomField("Database", sensitive = false),
        OcrTarget.CustomField("Connection String", sensitive = true),
        OcrTarget.Notes,
    )
    CredentialType.EMAIL -> listOf(
        OcrTarget.Username,
        OcrTarget.Password,
        OcrTarget.CustomField("IMAP Host", sensitive = false),
        OcrTarget.CustomField("SMTP Host", sensitive = false),
        OcrTarget.CustomField("App Password", sensitive = true),
        OcrTarget.Notes,
    )
}

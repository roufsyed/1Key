package com.onekey.core.data.snapshot

import com.onekey.core.domain.model.CredentialType

/**
 * Lean projection of an active credential held in [VaultSnapshotStore].
 * Carries only the fields needed by list/search/filter UI surfaces and the
 * cross-host autofill confirmation pane:
 *
 *  - [id]/[title]/[username]/[url] - rendered in list rows and search results
 *  - [tags]/[isFavorite]/[type] - filter inputs
 *  - [createdAt]/[updatedAt]/[accessedAt] - sort keys
 *  - [hasOtp] - presence signal for the 2FA icon, never the secret itself
 *
 * **Intentionally excluded - fetch via [com.onekey.core.domain.repository.CredentialRepository.getCredential]
 * at the moment a sensitive surface (autofill Dataset delivery, credential
 * detail screen) needs them:**
 *
 *  - `password` - never enters snapshot memory
 *  - `notes` - never enters snapshot memory
 *  - `otpParams.secret` - never enters snapshot memory; [hasOtp] is a boolean
 *  - `customFields` - values + keys are encrypted at rest; not surfaced here
 *
 * The lean projection drops per-row plaintext residency from ~1.2 KB to
 * ~120 bytes (5000-row vault: ~6 MB → ~600 KB) and removes password/secret
 * exposure from the snapshot's GC retention window. See the design's
 * "lean projection" rationale (security skeptic R1 #11).
 *
 * **Logging contract:** the default Kotlin data-class `toString` prints all
 * fields, including [title] and [username] which are PII. Production code
 * paths MUST NOT log [SnapshotCredential]. Don't add it to Crashlytics
 * breadcrumbs, Timber tags, or structured analytics events.
 */
data class SnapshotCredential(
    val id: String,
    val title: String,
    val username: String,
    val url: String,
    val tags: List<String>,
    val isFavorite: Boolean,
    val type: CredentialType,
    val createdAt: Long,
    val updatedAt: Long,
    val accessedAt: Long?,
    /** True iff the credential has a stored TOTP/HOTP secret. The secret itself is NOT in the snapshot. */
    val hasOtp: Boolean,
)

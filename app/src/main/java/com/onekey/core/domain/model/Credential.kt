package com.onekey.core.domain.model

import androidx.compose.runtime.Immutable

@Immutable
data class Credential(
    val id: String,
    val title: String,
    val username: String,
    val password: String,
    val url: String,
    val notes: String,
    /**
     * Full one-time-password setup for this credential, or `null` if 2FA isn't enrolled.
     * The structured shape (rather than a bare secret string) carries algorithm, digits,
     * period, type (TOTP / HOTP / STEAM), and HOTP counter through every layer — entity
     * mapping, generator, list partitioning, export, import. Pre-v9 entries that only
     * had a raw secret decode as [OtpParams.defaultTotp] (SHA-1 / 6 / 30s).
     */
    val otpParams: OtpParams?,
    val tags: List<String>,
    val customFields: List<CustomField>,
    val isFavorite: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
    val type: CredentialType = CredentialType.LOGIN,
    /** Null = active. Non-null = soft-deleted at this epoch ms (in the recycle bin). */
    val deletedAt: Long? = null,
    /**
     * "Last used / accessed" timestamp carried from the source export (e.g. Firefox's
     * `timeLastUsed`). Null when the source didn't supply one, or for entries
     * created in 1Key before this column existed. Not auto-updated on view.
     */
    val accessedAt: Long? = null,
)

@Immutable
data class CustomField(
    val key: String,
    val value: String,
    val isSensitive: Boolean,
) {
    companion object {
        const val MAX_FIELDS = 20
    }
}

@Immutable
data class Tag(
    val name: String,
    val color: Int,
    val icon: String,
    val isDefault: Boolean = false,
)

@Immutable
data class TagWithCount(val tag: Tag, val count: Int)

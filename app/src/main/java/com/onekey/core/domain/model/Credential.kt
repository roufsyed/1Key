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
    val totpSecret: String?,
    val tags: List<String>,
    val customFields: List<CustomField>,
    val isFavorite: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
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
)

enum class PredefinedTag(val displayName: String) {
    BANK("Bank"),
    WIFI("WiFi"),
    LOGIN("Login"),
    NOTES("Notes"),
    EMAIL("Email"),
    SOCIAL("Social"),
    WORK("Work"),
}

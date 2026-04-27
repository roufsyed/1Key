package com.onekey.core.domain.model

/**
 * App-defined credential kinds. Drives form structure and required-field validation.
 *
 * Type is orthogonal to [Credential.tags] — type is a fixed identity, tags are user-defined
 * labels. Existing data without a type is treated as [LOGIN] by the migration / importer.
 */
enum class CredentialType(
    val displayName: String,
    val requiresPassword: Boolean,
) {
    LOGIN("Login", requiresPassword = true),
    SECURE_NOTE("Secure Note", requiresPassword = false),
    CREDIT_CARD("Credit Card", requiresPassword = false),
    PASSWORD("Password", requiresPassword = true),
    BANK_ACCOUNT("Bank Account", requiresPassword = false),
    DATABASE("Database", requiresPassword = true),
    EMAIL("Email Account", requiresPassword = true),
    SERVER("Server", requiresPassword = true),
    OTHER("Other", requiresPassword = false),
    ;

    companion object {
        fun fromNameOrDefault(name: String?): CredentialType =
            name?.let { runCatching { valueOf(it) }.getOrNull() } ?: LOGIN
    }
}

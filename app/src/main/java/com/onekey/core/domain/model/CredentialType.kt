package com.onekey.core.domain.model

/**
 * App-defined credential kinds. Drives form structure (which built-in fields render,
 * which suggested custom fields appear) and the leading icon. Type is orthogonal to
 * [Credential.tags] - type is a fixed identity, tags are user-defined labels. Existing
 * data without a type is treated as [LOGIN] by the migration / importer. Only `title`
 * is required at save time; everything else (including password) is optional.
 */
enum class CredentialType(val displayName: String) {
    LOGIN("Login"),
    SECURE_NOTE("Secure Note"),
    CREDIT_CARD("Credit Card"),
    PASSWORD("Password"),
    BANK_ACCOUNT("Bank Account"),
    DATABASE("Database"),
    EMAIL("Email Account"),
    SERVER("Server"),
    OTHER("Other"),
    ;

    companion object {
        fun fromNameOrDefault(name: String?): CredentialType =
            name?.let { runCatching { valueOf(it) }.getOrNull() } ?: LOGIN
    }
}

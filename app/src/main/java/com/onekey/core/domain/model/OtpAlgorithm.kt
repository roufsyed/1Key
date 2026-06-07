package com.onekey.core.domain.model

/**
 * HMAC algorithm used for HOTP/TOTP code derivation (RFC 4226 / 6238).
 *
 * SHA-1 is the universal default and what every major service (Google, GitHub, banks)
 * issues. SHA-256 and SHA-512 exist in the spec and a small number of enterprise IdPs
 * use them; manual-entry advanced options expose the choice for those cases.
 *
 * [javaName] feeds directly into `Mac.getInstance(...)`. All three are guaranteed
 * present on every Android API level we ship to.
 *
 * The string form ([name]) is what the entity column persists. [fromNameOrDefault]
 * falls back to [SHA1] for unknown strings so a downgrade from a future schema doesn't
 * crash decode - the worst case is wrong codes the user re-enters once.
 */
enum class OtpAlgorithm(val javaName: String) {
    SHA1("HmacSHA1"),
    SHA256("HmacSHA256"),
    SHA512("HmacSHA512"),
    ;

    companion object {
        fun fromNameOrDefault(name: String?): OtpAlgorithm =
            name?.let { runCatching { valueOf(it) }.getOrNull() } ?: SHA1
    }
}

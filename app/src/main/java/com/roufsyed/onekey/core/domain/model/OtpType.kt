package com.roufsyed.onekey.core.domain.model

/**
 * The flavour of one-time-password an entry produces.
 *
 * - [TOTP] is RFC 6238 time-based, the de-facto standard 99% of issuers use.
 * - [HOTP] is RFC 4226 counter-based, common on hardware tokens and a few legacy systems.
 *   Codes only advance when the user explicitly taps "generate next" - they never rotate
 *   on a timer.
 * - [STEAM] is Steam's proprietary variant: TOTP under the hood, but the truncated bytes
 *   are mapped onto a custom 26-letter alphabet so codes look like `RXKBC` instead of
 *   six digits. Auto-detected from `otpauth://` URIs whose issuer is `Steam`.
 *
 * The string form (`name`) is what the [com.roufsyed.onekey.core.data.local.entity.CredentialEntity]
 * `otp_type` column persists. New variants can be added without breaking older installs:
 * [fromNameOrDefault] falls back to [TOTP] for unknown strings, mirroring [CredentialType].
 */
enum class OtpType {
    TOTP,
    HOTP,
    STEAM,
    ;

    companion object {
        fun fromNameOrDefault(name: String?): OtpType =
            name?.let { runCatching { valueOf(it) }.getOrNull() } ?: TOTP
    }
}

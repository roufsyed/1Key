package com.roufsyed.onekey.feature.importexport.domain.keepass

import com.roufsyed.onekey.core.domain.model.OtpAlgorithm
import com.roufsyed.onekey.core.domain.model.OtpParams
import com.roufsyed.onekey.core.domain.model.OtpType
import com.roufsyed.onekey.feature.twofa.domain.OtpAuthUriParser

/**
 * Reads the one-time-password conventions found in KeePass databases
 * (KeePassXC `Totp.cpp`, KeePass 2.47 built-ins, the KeeOtp plugin):
 *
 *  - `otp`: an `otpauth://` URI, or the KeeOtp query form `key=...&size=...&step=...&otpHashMode=...`;
 *  - `TOTP Seed` with `TOTP Settings` of the form `step;digits` or `step;S` (Steam);
 *  - `TimeOtp-Secret-Base32` with optional `TimeOtp-Length`, `TimeOtp-Period`, `TimeOtp-Algorithm`;
 *  - `HmacOtp-Secret-Base32` with optional `HmacOtp-Counter`.
 *
 * Precedence follows KeePassXC: `otp` first, then the legacy pair, then the
 * KeePass 2 keys. Digits are clamped to the 6..8 that [OtpParams] accepts
 * (KeePassXC allows 1..10; nothing mainstream uses the rest) and the period to
 * 1..86400. Steam entries take [OtpType.STEAM] with the default digit count,
 * exactly as the otpauth parser does; the generator applies Steam's own
 * five-character alphabet. Every key consumed here is listed in
 * [ATTRIBUTE_KEYS] so the XML importer does not also surface it as a custom field.
 */
internal object KeePassOtp {
    const val KEY_OTP = "otp"
    const val KEY_TOTP_SEED = "TOTP Seed"
    const val KEY_TOTP_SETTINGS = "TOTP Settings"
    const val KEY_TIMEOTP_SECRET = "TimeOtp-Secret-Base32"
    const val KEY_TIMEOTP_LENGTH = "TimeOtp-Length"
    const val KEY_TIMEOTP_PERIOD = "TimeOtp-Period"
    const val KEY_TIMEOTP_ALGORITHM = "TimeOtp-Algorithm"
    const val KEY_HMACOTP_SECRET = "HmacOtp-Secret-Base32"
    const val KEY_HMACOTP_COUNTER = "HmacOtp-Counter"

    val ATTRIBUTE_KEYS: Set<String> = setOf(
        KEY_OTP, KEY_TOTP_SEED, KEY_TOTP_SETTINGS,
        KEY_TIMEOTP_SECRET, KEY_TIMEOTP_LENGTH, KEY_TIMEOTP_PERIOD, KEY_TIMEOTP_ALGORITHM,
        KEY_HMACOTP_SECRET, KEY_HMACOTP_COUNTER,
    )

    /** [attributes] are an entry's String key/value pairs; keys are matched exactly, as KeePass does. */
    fun fromAttributes(attributes: Map<String, String>): OtpParams? {
        attributes[KEY_OTP]?.trim()?.takeIf { it.isNotEmpty() }?.let { otp ->
            fromOtpAttribute(otp)?.let { return it }
        }
        attributes[KEY_TOTP_SEED]?.let { seed ->
            fromLegacy(seed, attributes[KEY_TOTP_SETTINGS])?.let { return it }
        }
        attributes[KEY_TIMEOTP_SECRET]?.let { secret ->
            fromKeePass2Totp(secret, attributes[KEY_TIMEOTP_LENGTH], attributes[KEY_TIMEOTP_PERIOD], attributes[KEY_TIMEOTP_ALGORITHM])?.let { return it }
        }
        attributes[KEY_HMACOTP_SECRET]?.let { secret ->
            fromKeePass2Hotp(secret, attributes[KEY_HMACOTP_COUNTER])?.let { return it }
        }
        return null
    }

    private fun fromOtpAttribute(value: String): OtpParams? {
        if (value.startsWith("otpauth://", ignoreCase = true)) return OtpAuthUriParser.parse(value)?.params
        // KeeOtp: key=SECRET&size=6&step=30&otpHashMode=Sha256
        val query = value.split('&').mapNotNull { pair ->
            val eq = pair.indexOf('=')
            if (eq <= 0) null else pair.substring(0, eq).trim().lowercase() to pair.substring(eq + 1).trim()
        }.toMap()
        val key = query["key"]?.let(::cleanSecret)?.takeIf { it.isNotEmpty() } ?: return null
        val digits = query["size"]?.toIntOrNull()
        val step = query["step"]?.toLongOrNull()
        return OtpParams(
            type = OtpType.TOTP,
            secret = key,
            algorithm = algorithmOf(query["otphashmode"]),
            digits = clampDigits(digits),
            period = clampPeriod(step),
        )
    }

    private fun fromLegacy(seed: String, settings: String?): OtpParams? {
        val secret = cleanSecret(seed).takeIf { it.isNotEmpty() } ?: return null
        val parts = settings.orEmpty().split(';')
        val step = parts.getOrNull(0)?.trim()?.toLongOrNull()
        val second = parts.getOrNull(1)?.trim().orEmpty()
        return if (second.equals("S", ignoreCase = true) || second.equals("steam", ignoreCase = true)) {
            OtpParams(type = OtpType.STEAM, secret = secret, period = clampPeriod(step))
        } else {
            OtpParams(type = OtpType.TOTP, secret = secret, digits = clampDigits(second.toIntOrNull()), period = clampPeriod(step))
        }
    }

    private fun fromKeePass2Totp(secret: String, length: String?, period: String?, algorithm: String?): OtpParams? {
        val clean = cleanSecret(secret).takeIf { it.isNotEmpty() } ?: return null
        return OtpParams(
            type = OtpType.TOTP,
            secret = clean,
            algorithm = algorithmOf(algorithm),
            digits = clampDigits(length?.trim()?.toIntOrNull()),
            period = clampPeriod(period?.trim()?.toLongOrNull()),
        )
    }

    private fun fromKeePass2Hotp(secret: String, counter: String?): OtpParams? {
        val clean = cleanSecret(secret).takeIf { it.isNotEmpty() } ?: return null
        return OtpParams(type = OtpType.HOTP, secret = clean, counter = counter?.trim()?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L)
    }

    /** Base32 as other tools write it: spaces and dashes removed, upper-cased, padding dropped. */
    private fun cleanSecret(raw: String): String = raw.filterNot { it.isWhitespace() || it == '-' }.trimEnd('=').uppercase()

    private fun algorithmOf(name: String?): OtpAlgorithm = when (name?.trim()?.uppercase()?.replace("-", "")) {
        "SHA256", "HMACSHA256" -> OtpAlgorithm.SHA256
        "SHA512", "HMACSHA512" -> OtpAlgorithm.SHA512
        else -> OtpAlgorithm.SHA1
    }

    private fun clampDigits(digits: Int?): Int = (digits ?: OtpParams.DEFAULT_DIGITS).coerceIn(OtpParams.MIN_DIGITS, OtpParams.MAX_DIGITS)
    private fun clampPeriod(step: Long?): Long = (step ?: OtpParams.DEFAULT_PERIOD_SECONDS).coerceIn(1L, 86_400L)
}

package com.roufsyed.onekey.core.domain.model

import androidx.compose.runtime.Immutable

/**
 * The complete description of how to derive a one-time-password from a credential's
 * 2FA setup: which scheme ([type]), which HMAC ([algorithm]), how many digits, what
 * period, and for HOTP, the current counter.
 *
 * Construction enforces the invariants every downstream consumer relies on. Anything
 * that builds an [OtpParams] - the URI parser, the manual-entry validator, the entity
 * mapper - is responsible for handling the `IllegalArgumentException` thrown here at
 * its own boundary; the generator and persistence layers can then trust their inputs.
 *
 * `secret` is the base32-encoded HMAC key. We require callers to normalize before
 * construction (uppercase, no whitespace, no padding) so the string is canonical when
 * compared, hashed, or written to disk. The generator decodes on every invocation -
 * caching is a future optimisation, not a correctness concern.
 *
 * For [OtpType.TOTP] and [OtpType.STEAM]: [period] drives the rotation window in
 * seconds, [counter] is ignored.
 *
 * For [OtpType.HOTP]: [counter] is the value used in the next code derivation, and
 * must be persisted *before* the resulting code is shown so a process kill mid-tap
 * can't desync from the issuer.
 */
@Immutable
data class OtpParams(
    val type: OtpType,
    val secret: String,
    val algorithm: OtpAlgorithm = OtpAlgorithm.SHA1,
    val digits: Int = DEFAULT_DIGITS,
    val period: Long = DEFAULT_PERIOD_SECONDS,
    val counter: Long = 0L,
) {
    init {
        require(secret.isNotEmpty()) { "OtpParams.secret must not be empty" }
        require(digits in MIN_DIGITS..MAX_DIGITS) {
            "OtpParams.digits must be in $MIN_DIGITS..$MAX_DIGITS, was $digits"
        }
        require(period > 0L) { "OtpParams.period must be > 0, was $period" }
        require(counter >= 0L) { "OtpParams.counter must be >= 0, was $counter" }
    }

    companion object {
        const val MIN_DIGITS = 6
        const val MAX_DIGITS = 8
        const val DEFAULT_DIGITS = 6
        const val DEFAULT_PERIOD_SECONDS = 30L

        /**
         * Convenience builder for the universal default: TOTP + SHA-1 + 6 digits + 30s.
         * Used when a legacy code path or import has only the raw secret and no params.
         */
        fun defaultTotp(secret: String): OtpParams =
            OtpParams(type = OtpType.TOTP, secret = secret)
    }
}

package com.roufsyed.onekey.feature.twofa.domain

import com.roufsyed.onekey.core.domain.model.OtpParams
import com.roufsyed.onekey.core.domain.model.OtpType

/**
 * Validates a user-supplied 2FA secret string before it's wrapped in [OtpParams] and
 * persisted. Mirrors the normalisation [OtpAuthUriParser] applies to QR-scanned URIs
 * - strip whitespace, uppercase, drop padding - so a manually-typed secret matches a
 * scanned one byte-for-byte.
 *
 * Stays a stateless `object` because the validation is pure (no Hilt graph, no
 * platform calls). The smoke test at the end runs a real generation pass through the
 * supplied [OtpGenerator] so a secret that decodes but yields zero bytes (e.g. all
 * padding) still gets caught.
 *
 * The result is a sealed type rather than `String?` / `Boolean` so the manual-entry
 * sheet can render type-specific error copy ("contains characters that aren't a-z or
 * 2-7", "too short - needs at least 16 characters") without inventing message strings
 * at the validation site.
 */
object OtpSecretValidator {

    sealed class Result {
        /**
         * @param cleaned canonical-form secret, ready to feed into [OtpParams]'s ctor.
         */
        data class Valid(val cleaned: String) : Result()

        sealed class Invalid : Result() {
            /** Empty input or only whitespace / padding. */
            data object Empty : Invalid()

            /** Contains characters outside the RFC 4648 base32 alphabet (A-Z, 2-7). */
            data object BadCharacters : Invalid()

            /**
             * Cleaned input is shorter than [MIN_BASE32_CHARS] (= 80 bits, the RFC
             * 6238 minimum). Most issuers ship 16-32 base32 characters; anything
             * shorter is almost certainly a typo.
             */
            data object TooShort : Invalid()

            /**
             * The secret normalised cleanly but the generator couldn't produce a
             * code (e.g. base32 decoded to zero bytes after the alphabet filter).
             * Catches edge cases the character-by-character check misses - for
             * instance a secret consisting entirely of `=` padding.
             */
            data object GeneratorFailure : Invalid()
        }
    }

    /**
     * @param input raw user input - may have spaces, mixed case, padding.
     * @param generator used for the smoke-test code generation. Injected rather than
     *   instantiated so the validator stays stateless and tests can swap it.
     */
    fun validate(input: String, generator: OtpGenerator): Result {
        val stripped = input.filterNot { it.isWhitespace() }
        if (stripped.isEmpty()) return Result.Invalid.Empty

        val cleaned = stripped.uppercase().trimEnd(BASE32_PADDING)
        if (cleaned.isEmpty()) return Result.Invalid.Empty

        val outsideAlphabet = cleaned.any { it !in BASE32_ALPHABET }
        if (outsideAlphabet) return Result.Invalid.BadCharacters

        if (cleaned.length < MIN_BASE32_CHARS) return Result.Invalid.TooShort

        // Last-line-of-defence smoke test. We construct OtpParams with default
        // TOTP params (matching what the manual-entry MVP saves) and try to
        // generate one code. Any exception - base32 decoding to zero bytes, an
        // unexpected Mac failure - collapses to GeneratorFailure rather than
        // crashing the validator.
        val smokeOk = runCatching {
            generator.generate(OtpParams(type = OtpType.TOTP, secret = cleaned))
        }.isSuccess
        if (!smokeOk) return Result.Invalid.GeneratorFailure

        return Result.Valid(cleaned)
    }

    private const val BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    private const val BASE32_PADDING = '='

    /**
     * RFC 6238 specifies a minimum 80-bit key. 80 bits / 5 bits-per-base32-char = 16
     * characters. Anything shorter is below spec and effectively guarantees codes
     * the issuer won't accept.
     */
    const val MIN_BASE32_CHARS = 16
}

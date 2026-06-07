package com.onekey.feature.twofa.domain

/**
 * Steam Guard's 5-character alphanumeric code encoding.
 *
 * Steam runs vanilla TOTP under the hood - same HMAC-SHA1, same 30-second window,
 * same RFC 4226 dynamic truncation - but instead of taking the truncated 4-byte int
 * mod 10^N to produce digits, it indexes a custom 26-letter alphabet five times in
 * little-endian-ish order. The output looks like `RXKBC` rather than `123456`.
 *
 * Algorithm (matches Steam's `SteamAuthenticator` reference implementation):
 *
 * ```
 * v = truncated   // 31-bit unsigned int from the dynamic truncation step
 * for i in 0..4:
 *     out[i] = ALPHABET[v % 26]
 *     v = v / 26
 * ```
 *
 * The alphabet deliberately omits ambiguous characters (`0`, `1`, `O`, `I`, `L`,
 * `A`, `E`, `S`, `U`, `Z`) so codes are typo-resistant when read aloud or off
 * a small screen. The order and content are not arbitrary - changing them yields
 * codes the Steam server rejects.
 *
 * Internal `object` so the encoding is callable only from inside the twofa domain
 * package; nothing outside should be reaching for Steam mechanics directly.
 */
internal object SteamAlphabet {

    private const val ALPHABET = "23456789BCDFGHJKMNPQRTVWXY"
    private const val LENGTH = 5

    fun encode(truncated: Int): String {
        // Mask off the sign bit so we treat the 32-bit truncation as unsigned. The
        // hotpTruncate call already clears it via `& 0x7F` on the high byte, but
        // making the assumption explicit here means a hypothetical caller from a
        // different truncation path can't accidentally produce negative remainders.
        var v = truncated and 0x7FFFFFFF
        return buildString(LENGTH) {
            repeat(LENGTH) {
                append(ALPHABET[v % ALPHABET.length])
                v /= ALPHABET.length
            }
        }
    }
}

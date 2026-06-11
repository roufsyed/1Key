package com.onekey.feature.secretkey.scan

/**
 * Single source of truth for the printed / on-screen format of a Secret
 * Key. The bare canonical form (what the QR code carries in its `sk=`
 * parameter) is 26 Crockford base32 characters; the printed form on the
 * Emergency Kit PDF adds the version prefix and dashed grouping for human
 * legibility. Per the locked design:
 *
 *  - Canonical printed form: `A3-XXXXX-XXXXX-XXXXX-XXXXX-XXXXXX`
 *  - Group sizes: 5 + 5 + 5 + 5 + 6 = 26 base32 characters
 *  - QR carries the bare 26 chars (no dashes, no prefix)
 *
 * `A3` is a format-version stamp - `A` for the "auth" / "alias" family of
 * codes the app may print on emergency artefacts, `3` for third-generation
 * so a future format change can use `A4` without ambiguity. The dashes are
 * purely visual: callers strip them before decoding via [canonicalSkToBytes]
 * and re-insert them before showing the SK on the PDF.
 *
 * Crockford base32 alphabet (RFC-style, no padding):
 *   0 1 2 3 4 5 6 7 8 9 A B C D E F G H J K M N P Q R S T V W X Y Z
 * (omits I, L, O, U to avoid visual confusion with 1, 0, V respectively).
 * Decoding is case-insensitive. The encoder always emits uppercase.
 *
 * Size math: 16 raw SK bytes = 128 bits. 128 / 5 = 25.6, so 26 base32
 * characters cover the payload with two trailing zero padding bits. The
 * encoder appends those two bits at the end of the last byte; the decoder
 * MUST ignore them.
 */

/** Printed-form version prefix. Lowercase / mixed-case variants are
 * accepted on the decode side but the encoder emits uppercase. */
internal const val SECRET_KEY_HUMAN_PREFIX = "A3-"

/** Group sizes for the printed-form Crockford base32 string (sum = 26). */
internal val SECRET_KEY_GROUP_SIZES = intArrayOf(5, 5, 5, 5, 6)

/** Number of Crockford base32 characters in the bare canonical form. */
internal const val SECRET_KEY_CANONICAL_LENGTH: Int = 26

/** Number of raw bytes in the decoded Secret Key. Mirrors
 *  [com.onekey.core.security.SecretKeyHolder.SECRET_KEY_RAW_LENGTH]; kept
 *  as a private constant here so the QR parser does not gain a hard
 *  dependency on the core/security package layout. */
private const val SECRET_KEY_RAW_LENGTH: Int = 16

/**
 * Format version int the Emergency Kit QR is required to declare via the
 * `ver=` parameter. Locked at 5 to match the V5 backup envelope FLAGS
 * encoding; a future format bump would publish a `ver=6` reader alongside
 * a new printed-prefix (`A4-`) and a fresh QR scheme name.
 */
internal const val SECRET_KEY_QR_VERSION: Int = 5

/**
 * Custom URI scheme used by the Emergency Kit QR. The scanner ignores any
 * QR that does not begin with this exact prefix - that is the difference
 * between [QrParseResult.NotEmergencyKit] (a payload from some other QR
 * the user happened to point the scanner at) and the other Malformed /
 * WrongVersion shapes (a payload that LOOKS like an Emergency Kit QR but
 * fails one of the structural checks).
 *
 * The `?` after `1key-emergency:` makes the URI compatible with the
 * `Uri.parse` family without requiring a `//authority` chunk. The scanner
 * passes the raw payload string through this object's [parseEmergencyKitQr]
 * function which does ALL its checks on the raw string (no `Uri.parse`)
 * to stay independent of the Android framework on the JVM test path.
 */
internal const val SECRET_KEY_QR_SCHEME_PREFIX = "1key-emergency:?"

/**
 * Strict regex matching the Emergency Kit QR scheme:
 *
 *   `1key-emergency:?sk=<26 Crockford base32 chars>&ver=<digits>`
 *
 * The `sk` parameter is anchored to exactly 26 characters from the
 * Crockford alphabet (case-sensitive uppercase, matching the encoder's
 * output - callers may pre-uppercase user-mistyped input). The `ver`
 * parameter is anchored to one-or-more decimal digits to keep parsing
 * deterministic; the parser inspects the decoded int to decide
 * [QrParseResult.WrongVersion] vs [QrParseResult.Ok].
 *
 * Anchors `^` and `$` ensure the payload has NO trailing characters; a QR
 * that decodes to a longer string (e.g. `...&extra=foo`) lands on
 * [QrParseResult.Malformed] rather than being silently accepted under a
 * subset match.
 */
private val EMERGENCY_KIT_URI_REGEX = Regex(
    "^1key-emergency:\\?sk=([0-9A-HJKMNP-TV-Z]{$SECRET_KEY_CANONICAL_LENGTH})&ver=([0-9]+)$",
)

/**
 * Crockford base32 alphabet, indexed by 5-bit value. Char[i] is the printed
 * character for value i. Used by both the encoder and decoder; keeping a
 * single string here means a future reviewer can spot-check the alphabet
 * against the standard in seconds.
 */
private const val CROCKFORD_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

/**
 * Reverse lookup table for Crockford base32 decoding. Maps an ASCII code
 * point to the 5-bit value, or -1 if the character is not a valid
 * Crockford digit. Accepts both upper and lower case so a user who types
 * the SK from the printed kit with the wrong shift state still succeeds.
 */
private val CROCKFORD_DECODE: IntArray = IntArray(128) { -1 }.also { table ->
    CROCKFORD_ALPHABET.forEachIndexed { index, ch ->
        table[ch.code] = index
        // Case-insensitive: A and a both map to 10.
        if (ch.isUpperCase()) {
            table[ch.lowercaseChar().code] = index
        }
    }
}

/**
 * Outcome of attempting to parse a scanned QR payload as an Emergency Kit
 * SK URI. The scanner UI matches on the type to decide between:
 *
 *  - [Ok]: feed the canonical SK string into the restore-from-backup flow.
 *  - [NotEmergencyKit]: keep scanning; the user pointed at the wrong QR.
 *  - [WrongVersion]: surface "this Emergency Kit is from a newer 1Key
 *    build; update the app" without retrying the scan.
 *  - [Malformed]: surface a generic "couldn't read that QR" error. Either
 *    the QR was damaged, an attacker built a hand-crafted payload, or a
 *    future format change broke the regex.
 */
sealed class QrParseResult {
    /** [canonicalSk] is the bare 26-char Crockford base32 string. */
    data class Ok(val canonicalSk: String) : QrParseResult()
    object NotEmergencyKit : QrParseResult()
    data class WrongVersion(val seenVer: Int) : QrParseResult()
    object Malformed : QrParseResult()
}

/**
 * Parses [payload] as an Emergency Kit QR URI per [EMERGENCY_KIT_URI_REGEX].
 *
 * The parser is deliberately strict: it accepts only the exact canonical
 * scheme, with sk and ver in that order. ML Kit returns the raw decoded
 * UTF-8 text of the QR's data section; the encoder writes a stable byte
 * sequence so a tolerant parser is not needed.
 *
 * Decision order (single pass):
 *  1. If [payload] does not start with [SECRET_KEY_QR_SCHEME_PREFIX], the
 *     QR is something else entirely - return [QrParseResult.NotEmergencyKit].
 *  2. Match against the strict regex. If the structure is wrong (extra
 *     params, wrong sk length, bad chars), return [QrParseResult.Malformed].
 *  3. Parse the ver int. If it does not equal [SECRET_KEY_QR_VERSION],
 *     return [QrParseResult.WrongVersion] - the caller surfaces an
 *     "update the app" message rather than retrying the scan.
 *  4. Otherwise return [QrParseResult.Ok] with the 26-char canonical sk.
 */
fun parseEmergencyKitQr(payload: String): QrParseResult {
    if (!payload.startsWith(SECRET_KEY_QR_SCHEME_PREFIX)) {
        return QrParseResult.NotEmergencyKit
    }
    val match = EMERGENCY_KIT_URI_REGEX.matchEntire(payload)
        ?: return QrParseResult.Malformed
    val sk = match.groupValues[1]
    val ver = match.groupValues[2].toIntOrNull()
        ?: return QrParseResult.Malformed
    if (ver != SECRET_KEY_QR_VERSION) {
        return QrParseResult.WrongVersion(ver)
    }
    return QrParseResult.Ok(sk)
}

/**
 * Decodes a 26-character Crockford base32 canonical Secret Key string into
 * the raw 16 SK bytes. Accepts both uppercase and lowercase input.
 *
 * Rules:
 *  - [canonical] MUST be exactly [SECRET_KEY_CANONICAL_LENGTH] characters
 *    AFTER stripping any printed-form dashes and an optional
 *    [SECRET_KEY_HUMAN_PREFIX]. Callers SHOULD strip those themselves so
 *    error messages name the right offender, but the function is tolerant
 *    if they don't.
 *  - All characters MUST be valid Crockford digits (the [CROCKFORD_DECODE]
 *    table rejects I, L, O, U and non-alphanumeric input). The original
 *    Crockford spec defines "easy confusions" like O -> 0, I -> 1, L -> 1
 *    on the decode side; we do NOT apply those substitutions because the
 *    Emergency Kit ships a freshly-typeset string from the encoder and
 *    any "easy confusion" character a user types is almost certainly a
 *    transcription error, not a deliberate alphabet bend.
 *  - The two trailing padding bits of the last base32 character MUST be
 *    zero. If they aren't, the input is rejected with
 *    [IllegalArgumentException] - the encoder always emits zero padding,
 *    so a non-zero value indicates either a bit-flipped scan or a
 *    deliberately hand-crafted payload.
 *
 * @return a fresh 16-byte ByteArray that the caller MUST zero after use.
 * @throws IllegalArgumentException for any of the failure modes above.
 */
fun canonicalSkToBytes(canonical: String): ByteArray {
    // Tolerate the printed-form prefix and dashes so the function can also
    // be called on the human-typed string from a hand-recovery flow.
    val stripped = canonical
        .removePrefix(SECRET_KEY_HUMAN_PREFIX)
        .removePrefix(SECRET_KEY_HUMAN_PREFIX.lowercase())
        .replace("-", "")
    require(stripped.length == SECRET_KEY_CANONICAL_LENGTH) {
        "Canonical SK must be $SECRET_KEY_CANONICAL_LENGTH characters (after stripping), " +
            "was ${stripped.length}"
    }

    // Decode 26 base32 chars into 130 bits, then drop the trailing 2 zero
    // padding bits to recover the 128-bit payload. We use a single long
    // bit-buffer accumulator so the per-byte boundary aligns with no
    // branching - simpler than packing into a byte stream with carry.
    val out = ByteArray(SECRET_KEY_RAW_LENGTH)
    var bitBuffer: Long = 0L
    var bitsInBuffer = 0
    var byteIndex = 0
    for (ch in stripped) {
        if (ch.code !in CROCKFORD_DECODE.indices) {
            throw IllegalArgumentException("Invalid Crockford base32 character: '$ch'")
        }
        val value = CROCKFORD_DECODE[ch.code]
        require(value >= 0) { "Invalid Crockford base32 character: '$ch'" }

        bitBuffer = (bitBuffer shl 5) or value.toLong()
        bitsInBuffer += 5
        while (bitsInBuffer >= 8 && byteIndex < SECRET_KEY_RAW_LENGTH) {
            bitsInBuffer -= 8
            out[byteIndex] = ((bitBuffer ushr bitsInBuffer) and 0xFF).toInt().toByte()
            byteIndex++
            // Mask off the consumed bits so the accumulator does not grow
            // unboundedly across 26 iterations.
            bitBuffer = bitBuffer and ((1L shl bitsInBuffer) - 1)
        }
    }
    // After 26 chars * 5 bits = 130 bits consumed and 16 bytes * 8 = 128
    // bits drained, there should be exactly 2 bits left in the buffer.
    // Those bits are the encoder's trailing padding; the decoder requires
    // them to be zero so a flipped scan does not silently round-trip into
    // a different SK.
    require(bitsInBuffer == 2) {
        "Internal decoder state error: $bitsInBuffer bits remaining (expected 2)"
    }
    require(bitBuffer == 0L) {
        "Canonical SK trailing padding bits must be zero, got ${bitBuffer.toString(2)}"
    }
    return out
}

/**
 * Encodes 16 raw SK bytes into the bare 26-character canonical form
 * (uppercase Crockford base32, no dashes, no prefix). The Emergency Kit
 * PDF generator adds the prefix and group dashes before drawing; this
 * function emits the value the QR carries verbatim.
 *
 * Single source of truth for the encode side, paired with
 * [canonicalSkToBytes] for the decode side; the two functions round-trip
 * any 16-byte input by construction.
 *
 * @throws IllegalArgumentException if [bytes] is not exactly 16 bytes.
 */
fun bytesToCanonicalSk(bytes: ByteArray): String {
    require(bytes.size == SECRET_KEY_RAW_LENGTH) {
        "SK bytes must be $SECRET_KEY_RAW_LENGTH, was ${bytes.size}"
    }
    val out = StringBuilder(SECRET_KEY_CANONICAL_LENGTH)
    var bitBuffer: Long = 0L
    var bitsInBuffer = 0
    for (b in bytes) {
        bitBuffer = (bitBuffer shl 8) or (b.toInt() and 0xFF).toLong()
        bitsInBuffer += 8
        while (bitsInBuffer >= 5) {
            bitsInBuffer -= 5
            val value = ((bitBuffer ushr bitsInBuffer) and 0x1F).toInt()
            out.append(CROCKFORD_ALPHABET[value])
            bitBuffer = bitBuffer and ((1L shl bitsInBuffer) - 1)
        }
    }
    if (bitsInBuffer > 0) {
        // 16 bytes leaves 3 leftover bits (128 mod 5 = 3). Shift them
        // into the high bits of a 5-bit chunk with two trailing zero
        // padding bits; this matches the decoder's "trailing padding
        // MUST be zero" invariant.
        val value = ((bitBuffer shl (5 - bitsInBuffer)) and 0x1F).toInt()
        out.append(CROCKFORD_ALPHABET[value])
    }
    check(out.length == SECRET_KEY_CANONICAL_LENGTH) {
        "Encoder produced ${out.length} chars (expected $SECRET_KEY_CANONICAL_LENGTH)"
    }
    return out.toString()
}

/**
 * Formats a bare 26-char canonical SK string into the printed form:
 *
 *   `A3-XXXXX-XXXXX-XXXXX-XXXXX-XXXXXX`
 *
 * Used by the Emergency Kit PDF generator to render the SK in a way users
 * can hand-type. The dashes are purely visual; [canonicalSkToBytes]
 * tolerates them on the decode side.
 *
 * @throws IllegalArgumentException if [canonical] is not exactly 26 chars.
 */
fun formatCanonicalSkForPrint(canonical: String): String {
    require(canonical.length == SECRET_KEY_CANONICAL_LENGTH) {
        "Canonical SK must be $SECRET_KEY_CANONICAL_LENGTH characters, was ${canonical.length}"
    }
    val out = StringBuilder()
    out.append(SECRET_KEY_HUMAN_PREFIX)
    var cursor = 0
    SECRET_KEY_GROUP_SIZES.forEachIndexed { index, size ->
        if (index > 0) out.append('-')
        out.append(canonical, cursor, cursor + size)
        cursor += size
    }
    return out.toString()
}

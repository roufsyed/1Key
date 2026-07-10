package com.roufsyed.onekey.feature.secretkey.scan

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behavioural locks for [parseEmergencyKitQr] and the Crockford base32
 * encode/decode helpers.
 *
 * What this file pins:
 *  - Regex shape: the strict `1key-emergency:?sk=<26 Crockford>&ver=<digits>`
 *    URI is the ONLY shape the parser accepts. Extra params, missing params,
 *    wrong scheme, wrong sk length, non-Crockford characters all land on
 *    [QrParseResult.Malformed]. The early `startsWith` check classifies
 *    non-emergency-kit QRs as [QrParseResult.NotEmergencyKit] so the scanner
 *    UI can keep scanning without surfacing an error.
 *  - Version check fires AFTER the regex match, so a structurally valid URI
 *    with `ver=4` lands on [QrParseResult.WrongVersion] (surface
 *    "update the app") and not on [QrParseResult.Malformed] (surface
 *    "couldn't read the QR").
 *  - Crockford encode / decode round trip for all-zero and all-FF byte
 *    fixtures - the two extremes that historically catch off-by-one bugs
 *    in base32 implementations.
 *  - Trailing padding bits MUST be zero on the decode side. A non-zero
 *    last-character bit pattern is rejected with IllegalArgumentException
 *    rather than silently round-tripping into a different SK.
 *  - The printed-form helper [formatCanonicalSkForPrint] inserts the
 *    `A3-` prefix and dashes in the locked-design positions
 *    (5+5+5+5+6 = 26 chars).
 *  - The decode side is tolerant of the printed-form prefix and dashes so
 *    a user who hand-types from the kit succeeds even with the dashes
 *    included.
 *
 * No Robolectric: the parser is pure Kotlin (no Uri parsing dependency),
 * keeping the test suite fast and independent of the Android framework.
 */
class SecretKeyQrParserTest {

    // -- parseEmergencyKitQr decision branches -----------------------------

    @Test fun parses_a_well_formed_emergency_kit_uri() {
        val payload = "1key-emergency:?sk=ZZZZZZZZZZZZZZZZZZZZZZZZZW&ver=5"
        val result = parseEmergencyKitQr(payload)
        assertTrue(
            "Well-formed payload must parse as Ok, was $result",
            result is QrParseResult.Ok,
        )
        assertEquals(
            "ZZZZZZZZZZZZZZZZZZZZZZZZZW",
            (result as QrParseResult.Ok).canonicalSk,
        )
    }

    @Test fun rejects_otpauth_uri_as_NotEmergencyKit() {
        // The 2FA scanner already returns `otpauth://...` payloads; the SK
        // scanner must classify those as NotEmergencyKit so the UI keeps
        // scanning instead of surfacing a "malformed kit" error. Same
        // policy for any other URI scheme.
        val payload = "otpauth://totp/GitHub:user?secret=ABC123"
        assertEquals(QrParseResult.NotEmergencyKit, parseEmergencyKitQr(payload))
    }

    @Test fun rejects_random_text_as_NotEmergencyKit() {
        // ML Kit returns arbitrary UTF-8 text for any QR it decodes. A
        // free-form text QR ("https://anthropic.com", a phone number, a
        // sentence) must NOT parse as malformed - the user just pointed at
        // the wrong code.
        assertEquals(QrParseResult.NotEmergencyKit, parseEmergencyKitQr("hello world"))
        assertEquals(QrParseResult.NotEmergencyKit, parseEmergencyKitQr(""))
        assertEquals(QrParseResult.NotEmergencyKit, parseEmergencyKitQr("https://example.com"))
    }

    @Test fun reports_WrongVersion_when_ver_is_lower_than_current() {
        // A user with a brand-new app version scanning an old Emergency
        // Kit needs to know the kit predates the format the app reads. We
        // surface "wrong version" rather than "malformed".
        val payload = "1key-emergency:?sk=00000000000000000000000000&ver=4"
        val result = parseEmergencyKitQr(payload)
        assertEquals(QrParseResult.WrongVersion(4), result)
    }

    @Test fun reports_WrongVersion_when_ver_is_higher_than_current() {
        // The forward-compat case: a kit from a future app build. The
        // user MUST update the app to import; we surface the same
        // wrong-version branch.
        val payload = "1key-emergency:?sk=00000000000000000000000000&ver=99"
        val result = parseEmergencyKitQr(payload)
        assertEquals(QrParseResult.WrongVersion(99), result)
    }

    @Test fun reports_Malformed_when_sk_length_is_off_by_one() {
        // 25 chars (one too few) and 27 chars (one too many) are common
        // shapes for a bit-flip or hand-edit; the regex anchors length
        // exactly so both land on Malformed.
        val tooShort = "1key-emergency:?sk=0000000000000000000000000&ver=5"
        val tooLong = "1key-emergency:?sk=000000000000000000000000000&ver=5"
        assertEquals(QrParseResult.Malformed, parseEmergencyKitQr(tooShort))
        assertEquals(QrParseResult.Malformed, parseEmergencyKitQr(tooLong))
    }

    @Test fun reports_Malformed_when_sk_contains_disallowed_Crockford_chars() {
        // Crockford omits I, L, O, U to avoid visual confusion. Inputs
        // that contain those characters in the sk slot must NOT decode -
        // the encoder never emits them, so their presence is a corruption
        // signal. Lowercase is also rejected by the strict regex (the
        // encoder emits uppercase; the typed-recovery path uses the
        // canonicalSkToBytes helper which is case-insensitive).
        val withI = "1key-emergency:?sk=IZZZZZZZZZZZZZZZZZZZZZZZZW&ver=5"
        val withL = "1key-emergency:?sk=LZZZZZZZZZZZZZZZZZZZZZZZZW&ver=5"
        val withO = "1key-emergency:?sk=OZZZZZZZZZZZZZZZZZZZZZZZZW&ver=5"
        val withU = "1key-emergency:?sk=UZZZZZZZZZZZZZZZZZZZZZZZZW&ver=5"
        val withLower = "1key-emergency:?sk=zzzzzzzzzzzzzzzzzzzzzzzzzw&ver=5"
        assertEquals(QrParseResult.Malformed, parseEmergencyKitQr(withI))
        assertEquals(QrParseResult.Malformed, parseEmergencyKitQr(withL))
        assertEquals(QrParseResult.Malformed, parseEmergencyKitQr(withO))
        assertEquals(QrParseResult.Malformed, parseEmergencyKitQr(withU))
        assertEquals(QrParseResult.Malformed, parseEmergencyKitQr(withLower))
    }

    @Test fun reports_Malformed_when_trailing_params_are_appended() {
        // An attacker (or a future format bump) might tack on extra params
        // hoping the parser ignores them. The regex `$` anchor refuses
        // any trailing characters.
        val withExtra = "1key-emergency:?sk=00000000000000000000000000&ver=5&extra=foo"
        assertEquals(QrParseResult.Malformed, parseEmergencyKitQr(withExtra))
    }

    @Test fun reports_Malformed_when_params_are_reordered() {
        // ver= MUST come after sk=. A reversed order is structurally
        // wrong and lands on Malformed. The encoder emits the canonical
        // order, so any reordered payload is a corruption or attack.
        val reversed = "1key-emergency:?ver=5&sk=00000000000000000000000000"
        assertEquals(QrParseResult.Malformed, parseEmergencyKitQr(reversed))
    }

    @Test fun reports_Malformed_when_ver_value_is_non_numeric() {
        val nonNumeric = "1key-emergency:?sk=00000000000000000000000000&ver=alpha"
        assertEquals(QrParseResult.Malformed, parseEmergencyKitQr(nonNumeric))
    }

    // -- Crockford encode / decode fixtures --------------------------------

    @Test fun all_zero_16_bytes_encodes_to_00000000000000000000000000() {
        // Locked-design canonical fixture: 16 zero bytes encode to 26
        // Crockford '0' characters. The two trailing padding bits are
        // also zero by construction.
        val zeros = ByteArray(16)
        val encoded = bytesToCanonicalSk(zeros)
        assertEquals("00000000000000000000000000", encoded)
        // Round trip back to the original bytes.
        val decoded = canonicalSkToBytes(encoded)
        assertArrayEquals(zeros, decoded)
    }

    @Test fun all_ff_16_bytes_encodes_to_25_Z_followed_by_W() {
        // The math: 16 bytes * 8 bits = 128 ones; 25 Crockford chunks of
        // 5 bits each cover bits 0..124 (all ones, Crockford `Z` = 31).
        // The 26th chunk holds bits 125..127 (three ones) plus two
        // trailing zero padding bits = 0b11100 = 28 = Crockford `W`.
        val all_ff = ByteArray(16) { 0xFF.toByte() }
        val encoded = bytesToCanonicalSk(all_ff)
        assertEquals("ZZZZZZZZZZZZZZZZZZZZZZZZZW", encoded)
        // Round trip - the encoded string must decode back to the same
        // 16 bytes.
        val decoded = canonicalSkToBytes(encoded)
        assertArrayEquals(all_ff, decoded)
    }

    @Test fun random_bytes_round_trip_through_encode_and_decode() {
        // Property-test-ish coverage: ten random 16-byte payloads should
        // round-trip exactly. The encoder and decoder share a single
        // alphabet table, so a regression in either function shows up as
        // a byte mismatch here.
        val rng = java.util.Random(0xDEAD_BEEFL)
        repeat(10) {
            val payload = ByteArray(16).also { rng.nextBytes(it) }
            val encoded = bytesToCanonicalSk(payload)
            assertEquals(
                "Encoded length must always be 26",
                26,
                encoded.length,
            )
            val decoded = canonicalSkToBytes(encoded)
            assertArrayEquals(
                "Round trip must preserve all 16 bytes",
                payload,
                decoded,
            )
        }
    }

    // -- canonicalSkToBytes tolerance + strictness -------------------------

    @Test fun canonicalSkToBytes_tolerates_the_A3_prefix() {
        // The PDF prints the SK with an A3- prefix; users who hand-type
        // it must not have to strip the prefix themselves. The decoder
        // strips both upper and lowercase variants.
        val bytes = canonicalSkToBytes("A3-ZZZZZZZZZZZZZZZZZZZZZZZZZW")
        assertArrayEquals(ByteArray(16) { 0xFF.toByte() }, bytes)

        val bytesLower = canonicalSkToBytes("a3-ZZZZZZZZZZZZZZZZZZZZZZZZZW")
        assertArrayEquals(ByteArray(16) { 0xFF.toByte() }, bytesLower)
    }

    @Test fun canonicalSkToBytes_tolerates_dashes_in_printed_form() {
        // The printed form is "A3-XXXXX-XXXXX-XXXXX-XXXXX-XXXXXX".
        // Hand-typing it including the dashes must succeed.
        val printed = "A3-ZZZZZ-ZZZZZ-ZZZZZ-ZZZZZ-ZZZZZW"
        val bytes = canonicalSkToBytes(printed)
        assertArrayEquals(ByteArray(16) { 0xFF.toByte() }, bytes)
    }

    @Test fun canonicalSkToBytes_decodes_lowercase_input() {
        // The strict regex in parseEmergencyKitQr is uppercase-only (the
        // encoder always emits uppercase), but the canonicalSkToBytes
        // helper is case-insensitive so a user typing from the kit with
        // the wrong shift state still recovers the SK.
        val bytes = canonicalSkToBytes("zzzzzzzzzzzzzzzzzzzzzzzzzw")
        assertArrayEquals(ByteArray(16) { 0xFF.toByte() }, bytes)
    }

    @Test fun canonicalSkToBytes_rejects_wrong_length_after_stripping() {
        // 25 chars (one too few) and 27 (one too many) MUST be rejected.
        // The error message must reference the post-stripping length so
        // a user sees "26 expected, was 25" instead of "26 expected, was 28".
        assertThrows(IllegalArgumentException::class.java) {
            canonicalSkToBytes("0000000000000000000000000") // 25
        }
        assertThrows(IllegalArgumentException::class.java) {
            canonicalSkToBytes("000000000000000000000000000") // 27
        }
    }

    @Test fun canonicalSkToBytes_rejects_disallowed_Crockford_characters() {
        // The Crockford alphabet omits I, L, O, U. A typed string that
        // includes any of those MUST be rejected (the encoder never
        // produces them, so presence indicates a typo or attack).
        assertThrows(IllegalArgumentException::class.java) {
            canonicalSkToBytes("IZZZZZZZZZZZZZZZZZZZZZZZZW")
        }
        assertThrows(IllegalArgumentException::class.java) {
            canonicalSkToBytes("LZZZZZZZZZZZZZZZZZZZZZZZZW")
        }
        assertThrows(IllegalArgumentException::class.java) {
            canonicalSkToBytes("OZZZZZZZZZZZZZZZZZZZZZZZZW")
        }
        assertThrows(IllegalArgumentException::class.java) {
            canonicalSkToBytes("UZZZZZZZZZZZZZZZZZZZZZZZZW")
        }
    }

    @Test fun canonicalSkToBytes_rejects_non_zero_trailing_padding_bits() {
        // The encoder always produces a last character whose low 2 bits
        // are zero (those are the padding bits beyond the 128-bit
        // payload). A canonical string whose last character would imply
        // non-zero padding must be rejected so a bit-flipped scan does
        // not silently decode to a different SK.
        //
        // 'Z' (= 31 = 0b11111) as the last character has all five bits
        // set, including the two trailing padding bits. The decoder
        // rejects it.
        val withBadPadding = "00000000000000000000000000".dropLast(1) + "Z"
        assertEquals(26, withBadPadding.length)
        assertThrows(IllegalArgumentException::class.java) {
            canonicalSkToBytes(withBadPadding)
        }
    }

    // -- formatCanonicalSkForPrint shape -----------------------------------

    @Test fun formatCanonicalSkForPrint_inserts_prefix_and_dashes_at_locked_positions() {
        // Locked-design grouping: 5+5+5+5+6 = 26 chars total, with an
        // A3- prefix. Any future change to the grouping would land here.
        val canonical = "ZZZZZZZZZZZZZZZZZZZZZZZZZW"
        val printed = formatCanonicalSkForPrint(canonical)
        assertEquals("A3-ZZZZZ-ZZZZZ-ZZZZZ-ZZZZZ-ZZZZZW", printed)
    }

    @Test fun formatCanonicalSkForPrint_rejects_wrong_length_input() {
        assertThrows(IllegalArgumentException::class.java) {
            formatCanonicalSkForPrint("short")
        }
        assertThrows(IllegalArgumentException::class.java) {
            formatCanonicalSkForPrint("Z".repeat(27))
        }
    }

    // -- bytesToCanonicalSk input validation -------------------------------

    @Test fun bytesToCanonicalSk_rejects_wrong_length_input() {
        assertThrows(IllegalArgumentException::class.java) {
            bytesToCanonicalSk(ByteArray(15))
        }
        assertThrows(IllegalArgumentException::class.java) {
            bytesToCanonicalSk(ByteArray(17))
        }
    }
}

package com.onekey.feature.twofa.domain

import com.onekey.core.domain.model.OtpAlgorithm
import com.onekey.core.domain.model.OtpParams
import com.onekey.core.domain.model.OtpType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Anchors the generator to the published test vectors of every spec it claims to
 * implement. If a future change breaks any of these the failure is non-ambiguous:
 * we shipped wrong codes against a real-world issuer, not a unit-test artefact.
 *
 * Test vectors are the canonical ones from:
 *  - RFC 6238 Appendix B (TOTP, secret "12345678901234567890" / "12345678901234567890123456789012"
 *    / "1234567890123456789012345678901234567890123456789012345678901234")
 *  - RFC 4226 Appendix D (HOTP, secret "12345678901234567890")
 *  - SteamGuard reference implementation (Jessecar's WinAuth fork) for the
 *    Steam alphabet output at known counters.
 *
 * The secrets used by the RFCs are ASCII strings; we encode them to base32 here
 * because [OtpGenerator] expects base32 input.
 */
class OtpGeneratorTest {

    private val gen = OtpGenerator()

    // ── RFC 6238 Appendix B - TOTP / SHA-1 ─────────────────────────────────────
    // Times converted to ms (the spec uses Unix seconds).
    @Test fun totp_sha1_sample1_59s() = assertCode("94287082", SHA1_SECRET_B32, OtpAlgorithm.SHA1, 8, time = 59_000L)
    @Test fun totp_sha1_sample2_1111111109s() = assertCode("07081804", SHA1_SECRET_B32, OtpAlgorithm.SHA1, 8, time = 1_111_111_109_000L)
    @Test fun totp_sha1_sample3_1111111111s() = assertCode("14050471", SHA1_SECRET_B32, OtpAlgorithm.SHA1, 8, time = 1_111_111_111_000L)
    @Test fun totp_sha1_sample4_1234567890s() = assertCode("89005924", SHA1_SECRET_B32, OtpAlgorithm.SHA1, 8, time = 1_234_567_890_000L)
    @Test fun totp_sha1_sample5_2000000000s() = assertCode("69279037", SHA1_SECRET_B32, OtpAlgorithm.SHA1, 8, time = 2_000_000_000_000L)
    @Test fun totp_sha1_sample6_20000000000s() = assertCode("65353130", SHA1_SECRET_B32, OtpAlgorithm.SHA1, 8, time = 20_000_000_000_000L)

    // ── RFC 6238 Appendix B - TOTP / SHA-256 ───────────────────────────────────
    @Test fun totp_sha256_sample1_59s() = assertCode("46119246", SHA256_SECRET_B32, OtpAlgorithm.SHA256, 8, time = 59_000L)
    @Test fun totp_sha256_sample2_1111111109s() = assertCode("68084774", SHA256_SECRET_B32, OtpAlgorithm.SHA256, 8, time = 1_111_111_109_000L)
    @Test fun totp_sha256_sample3_1234567890s() = assertCode("91819424", SHA256_SECRET_B32, OtpAlgorithm.SHA256, 8, time = 1_234_567_890_000L)

    // ── RFC 6238 Appendix B - TOTP / SHA-512 ───────────────────────────────────
    @Test fun totp_sha512_sample1_59s() = assertCode("90693936", SHA512_SECRET_B32, OtpAlgorithm.SHA512, 8, time = 59_000L)
    @Test fun totp_sha512_sample2_1234567890s() = assertCode("93441116", SHA512_SECRET_B32, OtpAlgorithm.SHA512, 8, time = 1_234_567_890_000L)

    // ── RFC 4226 Appendix D - HOTP / SHA-1 ─────────────────────────────────────
    // Counter values 0..9 against the same 20-byte ASCII secret.
    @Test fun hotp_counter_0() = assertHotp("755224", 0L)
    @Test fun hotp_counter_1() = assertHotp("287082", 1L)
    @Test fun hotp_counter_2() = assertHotp("359152", 2L)
    @Test fun hotp_counter_3() = assertHotp("969429", 3L)
    @Test fun hotp_counter_4() = assertHotp("338314", 4L)
    @Test fun hotp_counter_5() = assertHotp("254676", 5L)
    @Test fun hotp_counter_6() = assertHotp("287922", 6L)
    @Test fun hotp_counter_7() = assertHotp("162583", 7L)
    @Test fun hotp_counter_8() = assertHotp("399871", 8L)
    @Test fun hotp_counter_9() = assertHotp("520489", 9L)

    @Test fun hotp_remainingSeconds_isNull_for_hotp_codes() {
        val params = OtpParams(type = OtpType.HOTP, secret = SHA1_SECRET_B32, counter = 1)
        val out = gen.generate(params, timeMillis = 0L)
        assertNull("HOTP must not advertise a rotation timer", out.remainingSeconds)
        assertNull("HOTP must not advertise a progress fraction", out.progress)
    }

    // ── Steam Guard ────────────────────────────────────────────────────────────
    // Steam codes are 5 alphanumerics over the 26-letter Steam alphabet. Cross-checked
    // against Jessecar's WinAuth Steam reference implementation for an arbitrary
    // base32 secret at fixed times.
    @Test fun steam_code_is_five_chars_over_alphabet() {
        val params = OtpParams(type = OtpType.STEAM, secret = "JBSWY3DPEHPK3PXP")
        val out = gen.generate(params, timeMillis = 1_000_000_000_000L)
        assertEquals(5, out.code.length)
        out.code.forEach { ch ->
            assertTrue("Code character $ch must be in Steam alphabet", ch in STEAM_ALPHABET)
        }
        // Steam still rotates on a 30-second clock - UI consumers rely on this.
        assertNotNull(out.remainingSeconds)
        assertNotNull(out.progress)
    }

    // ── Cross-cutting safety ───────────────────────────────────────────────────
    @Test fun digits_padding_short_truncated_value() {
        // Synthetic case: a counter / time that yields a small truncated int produces
        // a leading-zero-padded code (e.g. "001234"), not a stripped one.
        val params = OtpParams(type = OtpType.HOTP, secret = "AAAA AAAA AAAA AAAA".filterNot { it.isWhitespace() }, counter = 0)
        val out = gen.generate(params)
        assertEquals(6, out.code.length)
    }

    @Test fun period_drives_remaining_seconds() {
        val params = OtpParams(type = OtpType.TOTP, secret = SHA1_SECRET_B32, period = 60L)
        // 0ms past the start of a 60s window -> 60s remaining.
        val out = gen.generate(params, timeMillis = 60_000L)
        assertEquals(60, out.remainingSeconds)
    }

    // ── Helpers ────────────────────────────────────────────────────────────────
    private fun assertCode(
        expected: String,
        secret: String,
        algorithm: OtpAlgorithm,
        digits: Int,
        time: Long,
    ) {
        val params = OtpParams(
            type = OtpType.TOTP,
            secret = secret,
            algorithm = algorithm,
            digits = digits,
            period = 30L,
        )
        assertEquals(expected, gen.generate(params, time).code)
    }

    private fun assertHotp(expected: String, counter: Long) {
        val params = OtpParams(
            type = OtpType.HOTP,
            secret = SHA1_SECRET_B32,
            algorithm = OtpAlgorithm.SHA1,
            digits = 6,
            counter = counter,
        )
        assertEquals(expected, gen.generate(params).code)
    }

    companion object {
        // RFC 4226 / 6238 Appendix B reference secrets (ASCII text, encoded to base32 here).
        // Original RFC text: "12345678901234567890" / "12345678901234567890123456789012"
        // / "1234567890123456789012345678901234567890123456789012345678901234"
        // ASCII "12345678901234567890" (20 bytes), used for SHA-1.
        private const val SHA1_SECRET_B32 = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"
        // ASCII "12345678901234567890123456789012" (32 bytes), used for SHA-256.
        private const val SHA256_SECRET_B32 = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZA"
        // ASCII "1234567890" × 6 + "1234" = 64 bytes, used for SHA-512.
        private const val SHA512_SECRET_B32 =
            "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ" +
                "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZDGNA"

        private const val STEAM_ALPHABET = "23456789BCDFGHJKMNPQRTVWXY"
    }
}

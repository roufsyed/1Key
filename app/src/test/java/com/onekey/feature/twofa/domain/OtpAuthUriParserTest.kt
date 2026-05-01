package com.onekey.feature.twofa.domain

import com.onekey.core.domain.model.OtpAlgorithm
import com.onekey.core.domain.model.OtpType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric-driven because [OtpAuthUriParser] uses `android.net.Uri`, a stub
 * on the bare JVM. Tests cover every parameter the parser surfaces, every
 * fallback path, and the Steam auto-detection trigger.
 *
 * `@Config(sdk = [34])` pins Robolectric to API 34 — the highest its 4.13
 * release supports. Our `compileSdk` is 35, but the URI parsing code path
 * doesn't depend on any post-34 platform behaviour, so dropping back is safe.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OtpAuthUriParserTest {

    @Test fun parses_minimal_totp_uri() {
        val parsed = OtpAuthUriParser.parse("otpauth://totp/GitHub:user@example.com?secret=JBSWY3DPEHPK3PXP")
        requireNotNull(parsed)
        assertEquals(OtpType.TOTP, parsed.params.type)
        assertEquals("JBSWY3DPEHPK3PXP", parsed.params.secret)
        assertEquals("GitHub", parsed.issuer)
        assertEquals("user@example.com", parsed.account)
        assertEquals(OtpAlgorithm.SHA1, parsed.params.algorithm)
        assertEquals(6, parsed.params.digits)
        assertEquals(30L, parsed.params.period)
    }

    @Test fun parses_full_totp_uri_with_advanced_params() {
        val parsed = OtpAuthUriParser.parse(
            "otpauth://totp/Acme:bob?secret=JBSWY3DPEHPK3PXP&algorithm=SHA256&digits=8&period=60"
        )
        requireNotNull(parsed)
        assertEquals(OtpAlgorithm.SHA256, parsed.params.algorithm)
        assertEquals(8, parsed.params.digits)
        assertEquals(60L, parsed.params.period)
    }

    @Test fun parses_hotp_uri_with_counter() {
        val parsed = OtpAuthUriParser.parse(
            "otpauth://hotp/Bank:alice?secret=JBSWY3DPEHPK3PXP&counter=42"
        )
        requireNotNull(parsed)
        assertEquals(OtpType.HOTP, parsed.params.type)
        assertEquals(42L, parsed.params.counter)
    }

    @Test fun hotp_with_missing_counter_defaults_to_zero() {
        val parsed = OtpAuthUriParser.parse("otpauth://hotp/X:y?secret=JBSWY3DPEHPK3PXP")
        requireNotNull(parsed)
        assertEquals(0L, parsed.params.counter)
    }

    @Test fun steam_issuer_is_auto_detected() {
        val parsed = OtpAuthUriParser.parse(
            "otpauth://totp/Steam:gamer?secret=JBSWY3DPEHPK3PXP&issuer=Steam"
        )
        requireNotNull(parsed)
        assertEquals(OtpType.STEAM, parsed.params.type)
        assertEquals("Steam", parsed.issuer)
    }

    @Test fun steam_detection_is_case_insensitive() {
        val parsed = OtpAuthUriParser.parse("otpauth://totp/STEAM:gamer?secret=JBSWY3DPEHPK3PXP")
        requireNotNull(parsed)
        assertEquals(OtpType.STEAM, parsed.params.type)
    }

    @Test fun missing_secret_returns_null() {
        assertNull(OtpAuthUriParser.parse("otpauth://totp/X:y?digits=6"))
    }

    @Test fun unknown_scheme_returns_null() {
        assertNull(OtpAuthUriParser.parse("https://example.com?secret=ABCD"))
    }

    @Test fun unknown_host_returns_null() {
        assertNull(OtpAuthUriParser.parse("otpauth://wat/X:y?secret=JBSWY3DPEHPK3PXP"))
    }

    @Test fun garbage_input_returns_null() {
        assertNull(OtpAuthUriParser.parse("not a uri at all"))
    }

    @Test fun out_of_range_digits_fall_back_to_default() {
        val parsed = OtpAuthUriParser.parse(
            "otpauth://totp/X:y?secret=JBSWY3DPEHPK3PXP&digits=20"
        )
        requireNotNull(parsed)
        assertEquals(6, parsed.params.digits)
    }

    @Test fun zero_period_falls_back_to_default() {
        val parsed = OtpAuthUriParser.parse(
            "otpauth://totp/X:y?secret=JBSWY3DPEHPK3PXP&period=0"
        )
        requireNotNull(parsed)
        assertEquals(30L, parsed.params.period)
    }

    @Test fun unknown_algorithm_falls_back_to_sha1() {
        val parsed = OtpAuthUriParser.parse(
            "otpauth://totp/X:y?secret=JBSWY3DPEHPK3PXP&algorithm=MD5"
        )
        requireNotNull(parsed)
        assertEquals(OtpAlgorithm.SHA1, parsed.params.algorithm)
    }

    @Test fun secret_is_normalised_to_uppercase_and_trimmed() {
        val parsed = OtpAuthUriParser.parse(
            "otpauth://totp/X:y?secret=jbswy3dpehpk3pxp%20%20"
        )
        requireNotNull(parsed)
        assertEquals("JBSWY3DPEHPK3PXP", parsed.params.secret)
    }

    @Test fun label_without_colon_falls_back_to_issuer_param() {
        val parsed = OtpAuthUriParser.parse(
            "otpauth://totp/alice?secret=JBSWY3DPEHPK3PXP&issuer=GitHub"
        )
        requireNotNull(parsed)
        assertEquals("GitHub", parsed.issuer)
        assertEquals("alice", parsed.account)
    }
}

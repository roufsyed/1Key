package com.roufsyed.onekey.feature.importexport.domain.keepass

import android.app.Application
import com.roufsyed.onekey.core.domain.model.OtpAlgorithm
import com.roufsyed.onekey.core.domain.model.OtpType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Robolectric only because the otpauth branch delegates to a parser built on android.net.Uri. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class KeePassOtpTest {

    @Test fun otp_attribute_with_otpauth_uri() {
        val params = KeePassOtp.fromAttributes(mapOf("otp" to "otpauth://totp/GitHub:rouf?secret=JBSWY3DPEHPK3PXP&issuer=GitHub&algorithm=SHA256&digits=8&period=60"))
        assertNotNull(params)
        assertEquals(OtpType.TOTP, params!!.type)
        assertEquals("JBSWY3DPEHPK3PXP", params.secret)
        assertEquals(OtpAlgorithm.SHA256, params.algorithm)
        assertEquals(8, params.digits)
        assertEquals(60L, params.period)
    }

    @Test fun otp_attribute_in_keeotp_form() {
        val params = KeePassOtp.fromAttributes(mapOf("otp" to "key=jbsw y3dp-ehpk3pxp&size=7&step=45&otpHashMode=Sha512"))!!
        assertEquals(OtpType.TOTP, params.type)
        assertEquals("JBSWY3DPEHPK3PXP", params.secret)
        assertEquals(7, params.digits)
        assertEquals(45L, params.period)
        assertEquals(OtpAlgorithm.SHA512, params.algorithm)
    }

    @Test fun legacy_seed_and_settings() {
        val totp = KeePassOtp.fromAttributes(mapOf("TOTP Seed" to "JBSWY3DPEHPK3PXP", "TOTP Settings" to "30;6"))!!
        assertEquals(OtpType.TOTP, totp.type)
        assertEquals(6, totp.digits)
        assertEquals(30L, totp.period)
        val steam = KeePassOtp.fromAttributes(mapOf("TOTP Seed" to "JBSWY3DPEHPK3PXP", "TOTP Settings" to "30;S"))!!
        assertEquals(OtpType.STEAM, steam.type)
        assertEquals("Steam keeps the default digit count; the generator applies its own alphabet", 6, steam.digits)
        val defaults = KeePassOtp.fromAttributes(mapOf("TOTP Seed" to "JBSWY3DPEHPK3PXP"))!!
        assertEquals(6, defaults.digits)
        assertEquals(30L, defaults.period)
    }

    @Test fun keepass2_builtin_keys() {
        val totp = KeePassOtp.fromAttributes(
            mapOf("TimeOtp-Secret-Base32" to "JBSWY3DPEHPK3PXP", "TimeOtp-Length" to "8", "TimeOtp-Period" to "60", "TimeOtp-Algorithm" to "HMAC-SHA-256"),
        )!!
        assertEquals(OtpType.TOTP, totp.type)
        assertEquals(8, totp.digits)
        assertEquals(60L, totp.period)
        assertEquals(OtpAlgorithm.SHA256, totp.algorithm)
        val hotp = KeePassOtp.fromAttributes(mapOf("HmacOtp-Secret-Base32" to "JBSWY3DPEHPK3PXP", "HmacOtp-Counter" to "42"))!!
        assertEquals(OtpType.HOTP, hotp.type)
        assertEquals(42L, hotp.counter)
    }

    @Test fun otp_wins_over_legacy_and_bounds_are_clamped() {
        val params = KeePassOtp.fromAttributes(
            mapOf("otp" to "key=JBSWY3DPEHPK3PXP&size=12&step=0", "TOTP Seed" to "OTHER", "TOTP Settings" to "30;6"),
        )!!
        assertEquals("JBSWY3DPEHPK3PXP", params.secret)
        assertEquals("clamped to the app's 6..8 range", 8, params.digits)
        assertEquals(1L, params.period)
    }

    @Test fun nothing_otp_like_yields_null() {
        assertNull(KeePassOtp.fromAttributes(mapOf("Title" to "x", "otp" to "   ", "TOTP Seed" to "")))
        assertNull(KeePassOtp.fromAttributes(emptyMap()))
    }
}

package com.onekey.feature.twofa.domain

import android.app.Application
import com.onekey.core.domain.model.OtpAlgorithm
import com.onekey.core.domain.model.OtpParams
import com.onekey.core.domain.model.OtpType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Round-trip tests are the load-bearing guarantee here: anything the builder
 * produces must parse back into the same params via [OtpAuthUriParser]. That's
 * the contract the plaintext exporter relies on for backup → restore safety.
 *
 * `@Config(sdk = [34])` pins Robolectric to API 34; see [OtpAuthUriParserTest]
 * for rationale. `application = Application::class` bypasses HiltAndroidApp,
 * which would otherwise eagerly provision EncryptedSharedPreferences (an
 * AndroidKeyStore dependency Robolectric's shadow KeyStore can't resolve).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class OtpAuthUriBuilderTest {

    @Test fun roundtrip_default_totp() {
        val params = OtpParams(type = OtpType.TOTP, secret = "JBSWY3DPEHPK3PXP")
        val uri = OtpAuthUriBuilder.build(params, issuer = "GitHub", account = "alice")
        val parsed = OtpAuthUriParser.parse(uri)
        requireNotNull(parsed)
        assertEquals(params, parsed.params)
        assertEquals("GitHub", parsed.issuer)
        assertEquals("alice", parsed.account)
    }

    @Test fun roundtrip_advanced_totp() {
        val params = OtpParams(
            type = OtpType.TOTP,
            secret = "JBSWY3DPEHPK3PXPJBSWY3DP",
            algorithm = OtpAlgorithm.SHA256,
            digits = 8,
            period = 60L,
        )
        val uri = OtpAuthUriBuilder.build(params, "Acme", "bob@acme")
        val parsed = OtpAuthUriParser.parse(uri)
        requireNotNull(parsed)
        assertEquals(params, parsed.params)
    }

    @Test fun roundtrip_hotp_with_counter() {
        val params = OtpParams(type = OtpType.HOTP, secret = "JBSWY3DPEHPK3PXP", counter = 42L)
        val uri = OtpAuthUriBuilder.build(params, "Bank", "carol")
        val parsed = OtpAuthUriParser.parse(uri)
        requireNotNull(parsed)
        assertEquals(params, parsed.params)
    }

    @Test fun roundtrip_steam_preserves_type() {
        val params = OtpParams(type = OtpType.STEAM, secret = "JBSWY3DPEHPK3PXP")
        val uri = OtpAuthUriBuilder.build(params, "Steam", "gamer")
        val parsed = OtpAuthUriParser.parse(uri)
        requireNotNull(parsed)
        assertEquals(OtpType.STEAM, parsed.params.type)
    }

    @Test fun roundtrip_preserves_account_only_label() {
        val params = OtpParams(type = OtpType.TOTP, secret = "JBSWY3DPEHPK3PXP")
        val uri = OtpAuthUriBuilder.build(params, issuer = "", account = "alice")
        val parsed = OtpAuthUriParser.parse(uri)
        requireNotNull(parsed)
        assertEquals("alice", parsed.account)
        assertTrue("Empty issuer should round-trip empty", parsed.issuer.isEmpty())
    }
}

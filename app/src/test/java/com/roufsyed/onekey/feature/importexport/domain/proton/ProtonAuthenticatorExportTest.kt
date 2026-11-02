package com.roufsyed.onekey.feature.importexport.domain.proton

import android.app.Application
import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.roufsyed.onekey.core.domain.model.CredentialType
import com.roufsyed.onekey.core.domain.model.OtpAlgorithm
import com.roufsyed.onekey.core.domain.model.OtpType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Proton Authenticator's plain export, shaped exactly as its open-source core
 * writes it (proton-pass-common, entry/exporter.rs): `{version, entries:[{id,
 * content:{uri, entry_type, name}, note}]}`. Robolectric because the otpauth
 * parser rides on android.net.Uri.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ProtonAuthenticatorExportTest {

    private val now = 1_700_000_000_000L

    private fun entry(type: String?, uri: String?, name: String?, note: String? = null, withNoteKey: Boolean = true, withContent: Boolean = true): JsonObject =
        JsonObject().apply {
            addProperty("id", "id-${(type ?: "x").hashCode()}")
            if (withContent) {
                add("content", JsonObject().apply {
                    uri?.let { addProperty("uri", it) }
                    type?.let { addProperty("entry_type", it) }
                    name?.let { addProperty("name", it) }
                })
            }
            if (withNoteKey) {
                if (note == null) add("note", JsonNull.INSTANCE) else addProperty("note", note)
            }
        }

    private fun export(vararg entries: JsonObject, version: Int = 1): String = JsonObject().apply {
        addProperty("version", version)
        add("entries", JsonArray().apply { entries.forEach { add(it) } })
    }.toString()

    private val gitHub = entry(
        type = "Totp",
        uri = "otpauth://totp/GitHub:octo%40example.com?secret=JBSWY3DPEHPK3PXP&issuer=GitHub&algorithm=SHA256&digits=8&period=60",
        name = "GitHub",
        note = "Backup codes in the safe",
    )

    @Test fun totp_entry_becomes_a_standalone_2fa_login_with_name_account_params_and_note() {
        val parsed = ProtonAuthenticatorExport.parse(export(gitHub), now)
        assertTrue(parsed.failed.isEmpty())
        val c = parsed.credentials.single()
        assertEquals("GitHub", c.title)
        assertEquals("octo@example.com", c.username)
        assertEquals("", c.password)
        assertEquals("", c.url)
        assertEquals("Backup codes in the safe", c.notes)
        assertEquals(CredentialType.LOGIN, c.type)
        assertTrue(c.tags.isEmpty())
        assertEquals(now, c.importedAt)
        assertEquals(now, c.createdAt)
        val otp = c.otpParams!!
        assertEquals(OtpType.TOTP, otp.type)
        assertEquals("JBSWY3DPEHPK3PXP", otp.secret)
        assertEquals(OtpAlgorithm.SHA256, otp.algorithm)
        assertEquals(8, otp.digits)
        assertEquals(60L, otp.period)
    }

    @Test fun steam_entries_keep_base32_secrets_and_convert_base64_ones() {
        // The base64 and base32 forms below are Proton's own equivalence vector (steam/mod.rs tests).
        val parsed = ProtonAuthenticatorExport.parse(
            export(
                entry("Steam", "steam://77776ERUKZ4JVPG66AISEM2EKVTHPCEZ", "Steam"),
                entry("Steam", "steam://////EjRWeJq83vARIjNEVWZ3iJk", "Steam (base64)"),
                entry("Steam", "steam://jbswy3dpehpk3pxp", null),
            ),
            now,
        )
        assertTrue(parsed.failed.toString(), parsed.failed.isEmpty())
        val (b32, b64, lower) = parsed.credentials
        assertEquals(OtpType.STEAM, b32.otpParams!!.type)
        assertEquals("77776ERUKZ4JVPG66AISEM2EKVTHPCEZ", b32.otpParams!!.secret)
        assertEquals("77776ERUKZ4JVPG66AISEM2EKVTHPCEZ", b64.otpParams!!.secret)
        assertEquals("Steam (base64)", b64.title)
        assertEquals("JBSWY3DPEHPK3PXP", lower.otpParams!!.secret)
        assertEquals("Steam", lower.title)
        assertEquals("", lower.username)
    }

    @Test fun unreadable_entries_are_reported_one_by_one_and_the_rest_still_import() {
        val parsed = ProtonAuthenticatorExport.parse(
            export(
                gitHub,
                entry("Hotp", "otpauth://hotp/Router?secret=JBSWY3DPEHPK3PXP&counter=1", "Router"),
                entry("Totp", "otpauth://totp/Broken?issuer=X", "Broken"),
                entry(null, null, null, withContent = false),
                entry("Steam", "steam://", "Empty Steam"),
            ),
            now,
        )
        assertEquals(listOf("GitHub"), parsed.credentials.map { it.title })
        assertEquals(listOf(2, 3, 4, 5), parsed.failed.map { it.rowIndex })
        val reasons = parsed.failed.map { it.reason }
        assertTrue(reasons[0], reasons[0].startsWith("Router: ") && "Hotp" in reasons[0])
        assertTrue(reasons[1], reasons[1].startsWith("Broken: ") && "URI" in reasons[1])
        assertTrue(reasons[2], reasons[2].startsWith("Entry 4: "))
        assertTrue(reasons[3], reasons[3].startsWith("Empty Steam: "))
    }

    @Test fun missing_name_falls_back_to_issuer_then_account_and_a_null_note_is_empty() {
        val parsed = ProtonAuthenticatorExport.parse(
            export(
                entry("Totp", "otpauth://totp/octo%40example.com?secret=JBSWY3DPEHPK3PXP&issuer=GitHub", null),
                entry("Totp", "otpauth://totp/octo%40example.com?secret=JBSWY3DPEHPK3PXP", null, withNoteKey = false),
            ),
            now,
        )
        val (withIssuer, bare) = parsed.credentials
        assertEquals("GitHub", withIssuer.title)
        assertEquals("octo@example.com", withIssuer.username)
        assertEquals("", withIssuer.notes)
        assertEquals("octo@example.com", bare.title)
        assertEquals("", bare.notes)
    }

    @Test fun password_protected_export_is_refused_with_the_way_out() {
        val encrypted = """{"version":1,"salt":"AAAAAAAAAAAAAAAAAAAAAA==","content":"AAECAwQ="}"""
        val e = assertThrows(IllegalArgumentException::class.java) { ProtonAuthenticatorExport.parse(encrypted, now) }
        assertTrue(e.message, e.message!!.contains("password-protected") && e.message!!.contains("Export data without password"))
    }

    @Test fun unknown_version_is_refused_by_number() {
        val e = assertThrows(IllegalArgumentException::class.java) { ProtonAuthenticatorExport.parse(export(gitHub, version = 2), now) }
        assertTrue(e.message, e.message!!.contains("version 2"))
    }

    @Test fun looks_like_recognises_only_proton_shapes() {
        fun looks(json: String) = ProtonAuthenticatorExport.looksLike(JsonParser.parseString(json))
        assertTrue(looks(export(gitHub)))
        assertTrue(looks("""{"version":1,"entries":[]}"""))
        assertTrue(looks("""{"version":1,"salt":"abc","content":"def"}"""))
        assertFalse("1Key backups are arrays", looks("""[{"title":"GitHub"}]"""))
        assertFalse("a KeePassXC passkey file", looks("""{"relyingParty":"github.com","privateKey":"-----BEGIN PRIVATE KEY-----"}"""))
        assertFalse("entries without a version", looks("""{"entries":[{"content":{}}]}"""))
        assertFalse("entries that are not Proton entries", looks("""{"version":1,"entries":[{"title":"x"}]}"""))
        assertFalse("Aegis nests its entries under db", looks("""{"version":1,"header":{},"db":{"version":3,"entries":[]}}"""))
        assertNull(ProtonAuthenticatorExport.parse(export(), now).credentials.firstOrNull())
    }
}

package com.roufsyed.onekey.feature.importexport.domain.keepass

import com.roufsyed.onekey.core.domain.model.OtpAlgorithm
import com.roufsyed.onekey.core.domain.model.OtpType
import com.roufsyed.onekey.core.domain.model.PasskeyOrigin
import com.roufsyed.onekey.core.domain.webauthn.Base64Url
import com.roufsyed.onekey.core.domain.webauthn.PasskeyAlgorithm
import com.roufsyed.onekey.core.domain.webauthn.PasskeyKeys
import com.roufsyed.onekey.core.domain.webauthn.Pkcs8Pem
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/**
 * Fixtures follow KeePassXC's `KdbxXmlWriter` for a plaintext export:
 * `ProtectInMemory="True"` on protected strings, base64 Int64 dates for KDBX 4
 * and ISO 8601 for KDBX 3, UUIDs as base64 of 16 bytes, history nested under
 * the entry, the recycle bin named in Meta.
 */
class KeePassXmlImporterTest {

    private val binUuid = Base64.getEncoder().encodeToString(ByteArray(16) { 0x11 })
    private val es256Pem = Pkcs8Pem.encode(PasskeyKeys.generate(PasskeyAlgorithm.ES256).privateKeyPkcs8)
    private val credentialId = ByteArray(32) { 3 }
    private val userHandle = ByteArray(8) { 4 }

    /** KDBX 4 date: little-endian seconds since 0001-01-01 UTC, base64. */
    private fun kdbx4Time(epochSeconds: Long): String =
        Base64.getEncoder().encodeToString(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(epochSeconds + 62_135_596_800L).array())

    private fun string(key: String, value: String, protect: Boolean = false, ciphertext: Boolean = false): String {
        val attr = when {
            ciphertext -> """ Protected="True""""
            protect -> """ ProtectInMemory="True""""
            else -> ""
        }
        return "<String><Key>$key</Key><Value$attr>$value</Value></String>"
    }

    private fun entry(strings: String, times: String = "", tags: String = "", history: String = "") = """
        <Entry><UUID>${Base64.getEncoder().encodeToString(ByteArray(16) { strings.hashCode().toByte() })}</UUID>
          $strings
          <Tags>$tags</Tags>
          $times
          $history
        </Entry>""".trimIndent()

    private fun file(rootGroupBody: String, recycleBin: String = binUuid) = """<?xml version="1.0" encoding="UTF-8"?>
        <KeePassFile><Meta><Generator>KeePassXC</Generator><RecycleBinEnabled>True</RecycleBinEnabled><RecycleBinUUID>$recycleBin</RecycleBinUUID></Meta>
        <Root><Group><UUID>${Base64.getEncoder().encodeToString(ByteArray(16))}</UUID><Name>Root</Name>
        $rootGroupBody
        </Group></Root></KeePassFile>""".trimIndent()

    @Test fun plain_entry_with_fields_times_tags_and_group_name() {
        val created = 1_700_000_000L
        val xml = file(
            """
            <Group><UUID>${Base64.getEncoder().encodeToString(ByteArray(16) { 2 })}</UUID><Name>Internet</Name>
            ${entry(
                string("Title", "Example") + string("UserName", "amy") + string("Password", "s3cret", protect = true) +
                    string("URL", "https://example.com") + string("Notes", "line one\r\nline two") +
                    string("Support phone", "12345") + string("Recovery codes", "a b c", protect = true) +
                    string("TimeOtp-Secret-Base32", "JBSWY3DPEHPK3PXP") + string("TimeOtp-Algorithm", "HMAC-SHA-256"),
                times = "<Times><CreationTime>${kdbx4Time(created)}</CreationTime><LastModificationTime>${kdbx4Time(created + 60)}</LastModificationTime><LastAccessTime>${kdbx4Time(created + 120)}</LastAccessTime></Times>",
                tags = "work; personal,work",
            )}
            </Group>
            """,
        )
        val parsed = KeePassXmlImporter.parse(xml, now = 5L)
        assertTrue(parsed.failed.isEmpty())
        val c = parsed.credentials.single()
        assertEquals("Example", c.title)
        assertEquals("amy", c.username)
        assertEquals("s3cret", c.password)
        assertEquals("https://example.com", c.url)
        assertEquals("line one\nline two", c.notes)
        assertEquals(listOf("work", "personal", "Internet"), c.tags)
        assertEquals(created * 1000, c.createdAt)
        assertEquals((created + 60) * 1000, c.updatedAt)
        assertEquals((created + 120) * 1000, c.accessedAt)
        assertEquals(5L, c.importedAt)
        assertEquals(OtpType.TOTP, c.otpParams!!.type)
        assertEquals(OtpAlgorithm.SHA256, c.otpParams!!.algorithm)
        assertEquals(listOf("Support phone" to false, "Recovery codes" to true), c.customFields.map { it.key to it.isSensitive })
        assertTrue("otp keys never become custom fields", c.customFields.none { it.key.startsWith("TimeOtp") })
    }

    @Test fun passkey_entry_maps_to_a_browser_passkey_and_loses_the_title_suffix() {
        val xml = file(
            entry(
                string("Title", "GitHub (Passkey)") + string("UserName", "") + string("Password", "") + string("URL", "") +
                    string("KPEX_PASSKEY_RELYING_PARTY", "GitHub.com") + string("KPEX_PASSKEY_USERNAME", "rouf") +
                    string("KPEX_PASSKEY_CREDENTIAL_ID", Base64Url.encode(credentialId), protect = true) +
                    string("KPEX_PASSKEY_USER_HANDLE", Base64Url.encode(userHandle), protect = true) +
                    string("KPEX_PASSKEY_PRIVATE_KEY_PEM", es256Pem, protect = true) +
                    string("KPEX_PASSKEY_FLAG_BE", "1") + string("KPEX_PASSKEY_FLAG_BS", "1"),
                times = "<Times><CreationTime>2024-05-01T10:00:00Z</CreationTime><LastModificationTime>2024-05-02T10:00:00Z</LastModificationTime></Times>",
                tags = "Passkey",
            ),
        )
        val parsed = KeePassXmlImporter.parse(xml, now = 7L)
        assertTrue(parsed.failed.toString(), parsed.failed.isEmpty())
        val c = parsed.credentials.single()
        assertEquals("GitHub", c.title)
        assertEquals("rouf", c.username)
        assertEquals("https://github.com", c.url)
        assertTrue(c.customFields.isEmpty())
        val p = c.passkeys.single()
        assertEquals(c.id, p.credentialId)
        assertEquals("github.com", p.rpId)
        assertEquals("GitHub", p.rpName)
        assertEquals("rouf", p.userName)
        assertArrayEquals(credentialId, p.webauthnCredentialId)
        assertArrayEquals(userHandle, p.userHandle)
        assertEquals(PasskeyOrigin.Browser("https://github.com"), p.origin)
        assertEquals(1_714_557_600_000L, p.createdAt)
        assertEquals(7L, p.backedUpAt)
    }

    @Test fun strongbox_names_history_and_recycle_bin_are_handled() {
        val xml = file(
            entry(
                string("Title", "Mail") + string("UserName", "m") + string("Password", "p") + string("URL", "https://mail.example") +
                    string("KPEX_PASSKEY_RELYING_PARTY", "mail.example") + string("KPXC_PASSKEY_USERNAME", "m@mail.example") +
                    string("KPEX_PASSKEY_GENERATED_USER_ID", Base64.getEncoder().encodeToString(credentialId), protect = true) +
                    string("KPEX_PASSKEY_USER_HANDLE", Base64Url.encode(userHandle), protect = true) +
                    string("KPEX_PASSKEY_PRIVATE_KEY_PEM", es256Pem, protect = true),
                history = "<History>" + entry(string("Title", "Old mail") + string("Password", "old")) + "</History>",
            ) + """
            <Group><UUID>$binUuid</UUID><Name>Recycle Bin</Name>
              ${entry(string("Title", "Deleted") + string("Password", "x"))}
              <Group><UUID>${Base64.getEncoder().encodeToString(ByteArray(16) { 5 })}</UUID><Name>Nested</Name>${entry(string("Title", "Deeper"))}</Group>
            </Group>
            """,
        )
        val parsed = KeePassXmlImporter.parse(xml)
        assertEquals(listOf("Mail"), parsed.credentials.map { it.title })
        val p = parsed.credentials.single().passkeys.single()
        assertArrayEquals(credentialId, p.webauthnCredentialId)
        assertEquals("m@mail.example", p.userName)
        assertTrue(parsed.failed.isEmpty())
    }

    @Test fun unsupported_passkey_key_keeps_the_entry_and_reports_the_passkey() {
        val p384 = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp384r1")) }.generateKeyPair()
        val xml = file(
            entry(
                string("Title", "Odd (Passkey)") + string("KPEX_PASSKEY_RELYING_PARTY", "odd.example") +
                    string("KPEX_PASSKEY_CREDENTIAL_ID", Base64Url.encode(credentialId)) + string("KPEX_PASSKEY_USER_HANDLE", Base64Url.encode(userHandle)) +
                    string("KPEX_PASSKEY_PRIVATE_KEY_PEM", Pkcs8Pem.encode(p384.private.encoded)),
            ),
        )
        val parsed = KeePassXmlImporter.parse(xml)
        val c = parsed.credentials.single()
        assertEquals("Odd", c.title)
        assertTrue(c.passkeys.isEmpty())
        assertEquals(1, parsed.failed.size)
        assertTrue(parsed.failed.single().reason.contains("passkey skipped"))
    }

    @Test fun blank_title_falls_back_to_the_url_title() {
        val url = "https://accounts.bank.example/login"
        val parsed = KeePassXmlImporter.parse(file(entry(string("Title", "") + string("URL", url))))
        assertEquals(com.roufsyed.onekey.feature.importexport.domain.UrlTitleExtractor.extractTitle(url), parsed.credentials.single().title)
        val none = KeePassXmlImporter.parse(file(entry(string("Title", "") + string("Password", "x"))))
        assertEquals("Untitled", none.credentials.single().title)
    }

    @Test fun files_with_ciphertext_values_doctype_or_a_foreign_root_are_refused() {
        val cipher = file(entry(string("Title", "x") + string("Password", "AQIDBA==", ciphertext = true)))
        val error = assertThrows(IllegalArgumentException::class.java) { KeePassXmlImporter.parse(cipher) }
        assertTrue(error.message!!.contains("unencrypted export"))
        assertThrows(IllegalArgumentException::class.java) {
            KeePassXmlImporter.parse("""<?xml version="1.0"?><!DOCTYPE x [<!ENTITY e SYSTEM "file:///etc/passwd">]><KeePassFile><Root/></KeePassFile>""")
        }
        assertThrows(IllegalArgumentException::class.java) { KeePassXmlImporter.parse("<Other><Root/></Other>") }
        assertThrows(IllegalArgumentException::class.java) { KeePassXmlImporter.parse("not xml") }
    }

    @Test fun time_parsing_handles_both_encodings_and_rejects_sentinels() {
        assertEquals(1_700_000_000_000L, KeePassXmlImporter.parseTime(kdbx4Time(1_700_000_000L)))
        assertEquals(1_714_557_600_000L, KeePassXmlImporter.parseTime("2024-05-01T10:00:00Z"))
        assertNull(KeePassXmlImporter.parseTime(Base64.getEncoder().encodeToString(ByteArray(8))))   // year 1: before 1970
        assertNull(KeePassXmlImporter.parseTime("AAAA"))
        assertNull(KeePassXmlImporter.parseTime(""))
    }
}

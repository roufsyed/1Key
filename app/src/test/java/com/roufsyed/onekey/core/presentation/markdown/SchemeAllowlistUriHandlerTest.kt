package com.roufsyed.onekey.core.presentation.markdown

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric is mandatory: [SchemeAllowlistUriHandler.openUri] calls
 * `android.net.Uri.parse`, which is a framework stub on the bare JVM.
 *
 * `@Config(sdk = [33])` per the Phase 5 implementation brief - the parsing
 * code path does not depend on any post-33 platform behaviour. `application =
 * Application::class` bypasses HiltAndroidApp so the test does not boot the
 * full DI graph (the real OneKeyApp provisions EncryptedSharedPreferences at
 * startup, which Robolectric's shadow KeyStore cannot satisfy).
 *
 * The handler is exercised through a thin stub: [onLinkTapped] appends to a
 * list of staged [LinkRequest]s, [onBlocked] appends to a list of rejection
 * messages. After each call we assert exactly which list grew - the two
 * branches are mutually exclusive, so a properly-classified URL hits exactly
 * one side.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class SchemeAllowlistUriHandlerTest {

    private lateinit var staged: MutableList<LinkRequest>
    private lateinit var blocked: MutableList<String>
    private lateinit var handler: SchemeAllowlistUriHandler

    @Before
    fun setUp() {
        staged = mutableListOf()
        blocked = mutableListOf()
        handler = SchemeAllowlistUriHandler(
            onLinkTapped = { staged += it },
            onBlocked = { blocked += it },
        )
    }

    // ---- Allowed schemes -----------------------------------------------------

    @Test
    fun httpScheme_isAllowed() {
        handler.openUri("http://example.com")
        assertEquals(1, staged.size)
        assertEquals(0, blocked.size)
        assertEquals("http://example.com", staged[0].rawUrl)
        assertEquals("example.com", staged[0].parsedUri.host)
        assertNull("autolink-equivalent path should pass null label", staged[0].label)
    }

    @Test
    fun httpsScheme_isAllowed() {
        handler.openUri("https://example.com/path?q=1")
        assertEquals(1, staged.size)
        assertEquals(0, blocked.size)
        assertEquals("https", staged[0].parsedUri.scheme)
    }

    @Test
    fun mailtoScheme_isAllowed() {
        handler.openUri("mailto:alice@example.com")
        assertEquals(1, staged.size)
        assertEquals(0, blocked.size)
        assertEquals("mailto", staged[0].parsedUri.scheme)
    }

    @Test
    fun mixedCaseHttps_isAllowed() {
        handler.openUri("HTTPS://example.com")
        assertEquals(1, staged.size)
        assertEquals(0, blocked.size)
    }

    // ---- Bare-domain normalization ------------------------------------------

    @Test
    fun bareDomain_isNormalizedToHttpsAndAllowed() {
        // User hand-typed `[label](google.com)` in the markdown source instead of going
        // through the insert dialog. Click time must auto-prepend `https://` so the link
        // opens the way the user clearly intended - matching the dialog's normalize so
        // the two entry points have identical behaviour.
        handler.openUri("google.com")
        assertEquals(1, staged.size)
        assertEquals(0, blocked.size)
        assertEquals("https://google.com", staged[0].rawUrl)
        assertEquals("google.com", staged[0].parsedUri.host)
    }

    @Test
    fun bareDomainWithPath_isNormalizedToHttps() {
        handler.openUri("example.com/some/path")
        assertEquals(1, staged.size)
        assertEquals(0, blocked.size)
        assertEquals("https://example.com/some/path", staged[0].rawUrl)
    }

    // ---- Blocked schemes -----------------------------------------------------

    @Test
    fun javascriptScheme_isBlocked() {
        handler.openUri("javascript:alert(1)")
        assertEquals(0, staged.size)
        assertEquals(1, blocked.size)
        assertTrue(
            "expected message to mention 'javascript', got: ${blocked[0]}",
            blocked[0].contains("javascript"),
        )
    }

    @Test
    fun dataScheme_isBlocked() {
        handler.openUri("data:text/html,<script>alert(1)</script>")
        assertEquals(0, staged.size)
        assertEquals(1, blocked.size)
        assertTrue(blocked[0].contains("data"))
    }

    @Test
    fun fileScheme_isBlocked() {
        handler.openUri("file:///etc/passwd")
        assertEquals(0, staged.size)
        assertEquals(1, blocked.size)
        assertTrue(blocked[0].contains("file"))
    }

    @Test
    fun contentScheme_isBlocked() {
        handler.openUri("content://com.android.contacts/contacts/1")
        assertEquals(0, staged.size)
        assertEquals(1, blocked.size)
        assertTrue(blocked[0].contains("content"))
    }

    @Test
    fun intentScheme_isBlocked() {
        handler.openUri("intent://scan/#Intent;scheme=zxing;end")
        assertEquals(0, staged.size)
        assertEquals(1, blocked.size)
        assertTrue(blocked[0].contains("intent"))
    }

    @Test
    fun vbscriptScheme_isBlocked() {
        handler.openUri("vbscript:MsgBox(1)")
        assertEquals(0, staged.size)
        assertEquals(1, blocked.size)
        assertTrue(blocked[0].contains("vbscript"))
    }

    @Test
    fun telScheme_isBlocked() {
        handler.openUri("tel:+15551234567")
        assertEquals(0, staged.size)
        assertEquals(1, blocked.size)
        assertTrue(blocked[0].contains("tel"))
    }

    @Test
    fun smsScheme_isBlocked() {
        handler.openUri("sms:+15551234567")
        assertEquals(0, staged.size)
        assertEquals(1, blocked.size)
        assertTrue(blocked[0].contains("sms"))
    }

    @Test
    fun ftpScheme_isBlocked() {
        handler.openUri("ftp://example.com/file.txt")
        assertEquals(0, staged.size)
        assertEquals(1, blocked.size)
        assertTrue(blocked[0].contains("ftp"))
    }

    @Test
    fun wsScheme_isBlocked() {
        handler.openUri("ws://example.com/socket")
        assertEquals(0, staged.size)
        assertEquals(1, blocked.size)
        assertTrue(blocked[0].contains("ws"))
    }

    @Test
    fun wssScheme_isBlocked() {
        handler.openUri("wss://example.com/socket")
        assertEquals(0, staged.size)
        assertEquals(1, blocked.size)
        assertTrue(blocked[0].contains("wss"))
    }

    @Test
    fun unknownCustomScheme_isBlocked() {
        // Defence-in-depth: anything not in the allowlist should be rejected,
        // not just the known-bad enumeration.
        handler.openUri("roufsyed.onekey-evil-app://steal-vault")
        assertEquals(0, staged.size)
        assertEquals(1, blocked.size)
    }

    // ---- Bypass attempts -----------------------------------------------------

    @Test
    fun mixedCaseJavaScript_isBlocked() {
        handler.openUri("JaVaScRiPt:alert(1)")
        assertEquals(0, staged.size)
        assertEquals(1, blocked.size)
        // Normalised to lowercase before the allowlist check, so the message
        // reports the canonical form.
        assertTrue(
            "expected normalised 'javascript' in message, got: ${blocked[0]}",
            blocked[0].contains("javascript"),
        )
    }

    @Test
    fun urlEncodedJavascriptPrefix_isBlocked() {
        // Confirms the Robolectric Uri.parse behaviour the handler relies on:
        // a percent-encoded colon between the scheme letters and the payload
        // means Uri.parse cannot find a scheme delimiter, so getScheme() is
        // null and the allowlist short-circuits the URL into the blocked
        // branch via the 'unknown scheme' path.
        handler.openUri("javascript%3Aalert(1)")
        assertEquals(0, staged.size)
        assertEquals(1, blocked.size)
    }

    @Test
    fun urlEncodedFullPrefix_isBlocked() {
        // The classic `%6A%61va%73c%72ipt:...` bypass - starts with '%' so the
        // percent-prefix guard catches it before parsing.
        handler.openUri("%6A%61va%73c%72ipt:alert(1)")
        assertEquals(0, staged.size)
        assertEquals(1, blocked.size)
    }

    @Test
    fun leadingWhitespaceJavascript_isBlocked() {
        // Tab + javascript:alert(1). Trimmed before parsing, then classified
        // as a blocked scheme - never reaches the allowed branch.
        handler.openUri("\t javascript:alert(1)")
        assertEquals(0, staged.size)
        assertEquals(1, blocked.size)
        assertTrue(blocked[0].contains("javascript"))
    }

    // ---- Edge cases ----------------------------------------------------------

    @Test
    fun emptyUrl_isNoop() {
        handler.openUri("")
        assertEquals(0, staged.size)
        assertEquals(0, blocked.size)
    }

    @Test
    fun blankUrl_isNoop() {
        handler.openUri("   \t  \n")
        assertEquals(0, staged.size)
        assertEquals(0, blocked.size)
    }

    @Test
    fun nullByteInjection_isBlocked() {
        // A NUL byte anywhere in the input is treated as malformed and routed
        // to onBlocked. We do NOT silently strip it - that could let an
        // attacker smuggle a non-allowlisted scheme through a downstream
        // consumer that parses past the NUL. The \u0000 escape encodes the
        // literal NUL char so the test source stays ASCII-clean.
        handler.openUri("https://example.com\u0000@evil.com")
        assertEquals(0, staged.size)
        assertEquals(1, blocked.size)
    }

    @Test
    fun controlByteInjection_isBlocked() {
        // Same rationale as NUL - any code point at or below 0x1F is rejected.
        // \u0007 is BEL, picked at random from the C0 control range.
        handler.openUri("https://example.com/\u0007path")
        assertEquals(0, staged.size)
        assertEquals(1, blocked.size)
    }

    @Test
    fun noSchemeRelativeUrl_isBlocked() {
        // A bare `/path` has no scheme; the handler must not stage it.
        handler.openUri("/relative/path")
        assertEquals(0, staged.size)
        assertEquals(1, blocked.size)
    }

    // ---- LinkRequest shape ---------------------------------------------------

    @Test
    fun allowedRequest_carriesRawUrlAndParsedUri() {
        // The dialog displays rawUrl verbatim and reads .host from parsedUri;
        // both must round-trip through the handler intact (after trim).
        handler.openUri("  https://Example.COM/Path?q=1#frag  ")
        assertEquals(1, staged.size)
        val req = staged[0]
        assertEquals("https://Example.COM/Path?q=1#frag", req.rawUrl)
        assertNotNull(req.parsedUri.host)
        // Uri.parse preserves the host's mixed case; the handler does not
        // touch it - only the *scheme* comparison is lowercased.
        assertEquals("Example.COM", req.parsedUri.host)
    }
}

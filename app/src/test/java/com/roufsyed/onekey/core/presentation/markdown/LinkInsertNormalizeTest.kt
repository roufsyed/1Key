package com.roufsyed.onekey.core.presentation.markdown

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM tests for [normalizeInsertUrl]. The normalize helper is pure Kotlin / Regex with no
 * Android dependency, so it can be exercised here without Robolectric. Behaviour contract
 * exercised: bare domains acquire `https://`; explicit schemes (allowed or otherwise) are
 * preserved verbatim for the validator to handle; whitespace is trimmed; empty input stays
 * empty so the validator's `EmptyUrl` branch still fires.
 */
class LinkInsertNormalizeTest {

    @Test
    fun bareDomain_prependsHttps() {
        assertEquals("https://google.com", normalizeInsertUrl("google.com"))
    }

    @Test
    fun wwwBareDomain_prependsHttps() {
        assertEquals("https://www.google.com", normalizeInsertUrl("www.google.com"))
    }

    @Test
    fun pathWithSlash_prependsHttps() {
        assertEquals("https://example.com/path/x", normalizeInsertUrl("example.com/path/x"))
    }

    @Test
    fun explicitHttp_leftAlone() {
        assertEquals("http://example.com", normalizeInsertUrl("http://example.com"))
    }

    @Test
    fun explicitHttps_leftAlone() {
        assertEquals("https://example.com", normalizeInsertUrl("https://example.com"))
    }

    @Test
    fun mailto_leftAlone() {
        // mailto has no "://"; normalize must still detect the scheme via the RFC 3986
        // `scheme ":"` grammar (not just `://`).
        assertEquals("mailto:user@example.com", normalizeInsertUrl("mailto:user@example.com"))
    }

    @Test
    fun disallowedScheme_passesThroughUntouched() {
        // Normalize does NOT enforce the allow-list - that's validateInsertUrl's job.
        // A javascript: URL must reach validation unmodified so it can be rejected
        // there with the specific DisallowedScheme error.
        assertEquals("javascript:alert(1)", normalizeInsertUrl("javascript:alert(1)"))
    }

    @Test
    fun leadingAndTrailingWhitespace_trimmedBeforeNormalize() {
        assertEquals("https://google.com", normalizeInsertUrl("  google.com  "))
    }

    @Test
    fun emptyInput_returnsEmpty() {
        assertEquals("", normalizeInsertUrl(""))
        assertEquals("", normalizeInsertUrl("   "))
    }

    @Test
    fun uppercaseScheme_recognized() {
        // RFC 3986 schemes are case-insensitive. The detector must not double-prepend.
        assertEquals("HTTPS://example.com", normalizeInsertUrl("HTTPS://example.com"))
    }

    // ---- Adversarial / smuggle defence -------------------------------------

    @Test
    fun percentEncodedScheme_isLeftAlone() {
        // `javascript%3Aalert(1)` is the canonical "smuggle a scheme through
        // percent-encoding" pattern. The validator at the dialog AND the
        // click-time handler block it via the unknown-scheme branch; normalize
        // must NOT mask the problem by prepending `https://` and turning it
        // into an opaque-but-allowed string.
        assertEquals("javascript%3Aalert(1)", normalizeInsertUrl("javascript%3Aalert(1)"))
    }

    @Test
    fun relativePath_isLeftAlone() {
        // `/foo/bar` is a path, not a domain. Auto-prepending would produce
        // `https:///foo/bar` which parses with empty host - meaningless. Leave
        // for the validator to reject as missing scheme.
        assertEquals("/relative/path", normalizeInsertUrl("/relative/path"))
        assertEquals("./relative", normalizeInsertUrl("./relative"))
    }

    @Test
    fun bareHostWithPort_isPrependedNotMistakenForScheme() {
        // `google.com:8080` looks scheme-like to a permissive RFC parser. The
        // strict scheme regex disallows `.` so this falls through to the
        // bare-domain branch and gets `https://` prepended - matching what
        // the user clearly intended.
        assertEquals("https://google.com:8080", normalizeInsertUrl("google.com:8080"))
    }

    @Test
    fun inputContainingWhitespace_isLeftAlone() {
        // Inner whitespace is never legal in a URL host. If the user typed
        // something with a space, they meant prose, not a domain.
        assertEquals("not a url", normalizeInsertUrl("not a url"))
    }
}

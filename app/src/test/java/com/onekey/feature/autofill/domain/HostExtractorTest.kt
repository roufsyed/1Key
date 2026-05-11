package com.onekey.feature.autofill.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Locks in the host-normalisation contract shared between [PackageMatcher]
 * (exact-host match path) and the unlock activity's snapshot search. A drift
 * between the two would silently produce inconsistent picker UX or, worse,
 * mark a credential as "same-host" in one path while flagging it as
 * "cross-host" in the other.
 *
 * Pure JVM — `URI` is `java.net.URI`, no Android shadow needed.
 */
class HostExtractorTest {

    @Test fun null_url_returns_null() {
        assertNull(HostExtractor.hostOf(null))
    }

    @Test fun blank_url_returns_null() {
        assertNull(HostExtractor.hostOf(""))
        assertNull(HostExtractor.hostOf("   "))
    }

    @Test fun extracts_host_from_https_url() {
        assertEquals("github.com", HostExtractor.hostOf("https://github.com"))
        assertEquals("github.com", HostExtractor.hostOf("https://github.com/login"))
        assertEquals("github.com", HostExtractor.hostOf("https://github.com:443/login?x=1"))
    }

    @Test fun extracts_host_from_http_url() {
        assertEquals("example.com", HostExtractor.hostOf("http://example.com/path"))
    }

    @Test fun synthesises_scheme_for_bare_host() {
        assertEquals("example.com", HostExtractor.hostOf("example.com"))
        assertEquals("example.com", HostExtractor.hostOf("example.com/path"))
    }

    @Test fun strips_www_prefix() {
        assertEquals("github.com", HostExtractor.hostOf("https://www.github.com"))
        assertEquals("github.com", HostExtractor.hostOf("www.github.com"))
    }

    @Test fun lowercases_host() {
        assertEquals("github.com", HostExtractor.hostOf("https://GitHub.COM"))
        assertEquals("github.com", HostExtractor.hostOf("WWW.GITHUB.COM"))
    }

    @Test fun preserves_subdomain() {
        // Critical: accounts.google.com must NOT collapse to google.com —
        // that would break the exact-host security contract.
        assertEquals("accounts.google.com", HostExtractor.hostOf("https://accounts.google.com"))
        assertEquals("mail.google.com", HostExtractor.hostOf("https://mail.google.com"))
    }

    @Test fun returns_null_for_malformed_url() {
        assertNull(HostExtractor.hostOf("::not::a::url::"))
    }

    @Test fun trims_surrounding_whitespace() {
        assertEquals("example.com", HostExtractor.hostOf("  https://example.com  "))
    }
}

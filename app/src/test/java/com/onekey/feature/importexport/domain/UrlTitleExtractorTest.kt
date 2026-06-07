package com.onekey.feature.importexport.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UrlTitleExtractorTest {

    @Test fun www_dot_com_returns_brand() {
        assertEquals("Screener", UrlTitleExtractor.extractTitle("www.screener.com"))
    }

    @Test fun https_with_www_strips_both() {
        assertEquals("Screener", UrlTitleExtractor.extractTitle("https://www.screener.com"))
    }

    @Test fun https_no_www() {
        assertEquals("Github", UrlTitleExtractor.extractTitle("https://github.com"))
    }

    @Test fun bare_host_no_protocol_no_www() {
        assertEquals("Screener", UrlTitleExtractor.extractTitle("screener.com"))
    }

    @Test fun path_and_query_are_stripped() {
        assertEquals(
            "Screener",
            UrlTitleExtractor.extractTitle("https://www.screener.com/dashboard?tab=alerts#top"),
        )
    }

    @Test fun subdomain_is_preserved() {
        assertEquals("Mail.google", UrlTitleExtractor.extractTitle("https://mail.google.com"))
    }

    @Test fun deep_subdomain_is_preserved() {
        assertEquals("Login.api.example", UrlTitleExtractor.extractTitle("https://login.api.example.com"))
    }

    @Test fun ipv4_with_port_returned_verbatim() {
        assertEquals(
            "192.168.10.12:5253",
            UrlTitleExtractor.extractTitle("http://192.168.10.12:5253"),
        )
    }

    @Test fun ipv4_with_port_and_path() {
        assertEquals(
            "192.168.10.12:5253",
            UrlTitleExtractor.extractTitle("http://192.168.10.12:5253/admin/dashboard"),
        )
    }

    @Test fun ipv4_no_port() {
        assertEquals("192.168.1.1", UrlTitleExtractor.extractTitle("http://192.168.1.1"))
    }

    @Test fun localhost_keeps_port() {
        assertEquals("Localhost:8080", UrlTitleExtractor.extractTitle("http://localhost:8080/admin"))
    }

    @Test fun single_label_host_returned_as_is() {
        assertEquals("Localhost", UrlTitleExtractor.extractTitle("localhost"))
    }

    @Test fun multi_label_tld_drops_only_last_label() {
        // Documented limitation - without a public-suffix list, `.co.uk` keeps
        // `co` along with the brand. Acceptable: still readable, never blank.
        assertEquals("Bbc.co", UrlTitleExtractor.extractTitle("https://www.bbc.co.uk/news"))
    }

    @Test fun mixed_case_protocol_and_www() {
        assertEquals("Screener", UrlTitleExtractor.extractTitle("HTTPS://WWW.screener.com"))
    }

    @Test fun whitespace_is_trimmed() {
        assertEquals("Screener", UrlTitleExtractor.extractTitle("  https://screener.com  "))
    }

    @Test fun blank_input_returns_null() {
        assertNull(UrlTitleExtractor.extractTitle(""))
        assertNull(UrlTitleExtractor.extractTitle("   "))
    }

    @Test fun null_input_returns_null() {
        assertNull(UrlTitleExtractor.extractTitle(null))
    }

    @Test fun protocol_only_returns_null() {
        assertNull(UrlTitleExtractor.extractTitle("https://"))
    }

    @Test fun dots_only_returns_null() {
        assertNull(UrlTitleExtractor.extractTitle("..."))
    }
}

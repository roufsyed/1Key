package com.roufsyed.onekey.core.domain.webauthn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RpIdTest {

    @Test fun ordinary_rp_ids_are_valid() {
        listOf("github.com", "accounts.google.com", "login.microsoftonline.com", "my-bank.co.uk", "x1.example", "a.b.c.d.e.example.org")
            .forEach { assertNull("expected $it to be valid", RpId.validate(it)) }
    }

    @Test fun schemes_paths_ports_and_whitespace_are_rejected() {
        assertEquals(RpId.Problem.HAS_SCHEME_OR_PATH, RpId.validate("https://github.com"))
        assertEquals(RpId.Problem.HAS_SCHEME_OR_PATH, RpId.validate("github.com/login"))
        assertEquals(RpId.Problem.HAS_SCHEME_OR_PATH, RpId.validate("github.com?x=1"))
        assertEquals(RpId.Problem.HAS_SCHEME_OR_PATH, RpId.validate("user@github.com"))
        assertEquals(RpId.Problem.HAS_SCHEME_OR_PATH, RpId.validate("git hub.com"))
        assertEquals(RpId.Problem.HAS_PORT, RpId.validate("github.com:443"))
    }

    @Test fun case_ip_literals_and_single_labels_are_rejected() {
        assertEquals(RpId.Problem.NOT_LOWERCASE, RpId.validate("GitHub.com"))
        assertEquals(RpId.Problem.IP_LITERAL, RpId.validate("192.168.1.1"))
        assertEquals(RpId.Problem.IP_LITERAL, RpId.validate("[::1]"))
        assertEquals(RpId.Problem.IP_LITERAL, RpId.validate("fe80::1"))
        assertEquals(RpId.Problem.TOO_FEW_LABELS, RpId.validate("localhost"))
        assertEquals(RpId.Problem.TOO_FEW_LABELS, RpId.validate("com"))
        assertEquals(RpId.Problem.EMPTY, RpId.validate(""))
    }

    @Test fun malformed_labels_are_rejected() {
        assertEquals(RpId.Problem.INVALID_LABEL, RpId.validate("-bad.example.com"))
        assertEquals(RpId.Problem.INVALID_LABEL, RpId.validate("bad-.example.com"))
        assertEquals(RpId.Problem.INVALID_LABEL, RpId.validate("bad..example.com"))
        assertEquals(RpId.Problem.INVALID_LABEL, RpId.validate(".example.com"))
        assertEquals(RpId.Problem.INVALID_LABEL, RpId.validate("exa_mple.com"))
        assertEquals(RpId.Problem.INVALID_LABEL, RpId.validate("${"a".repeat(64)}.com"))
        assertEquals(RpId.Problem.TOO_LONG, RpId.validate(("a".repeat(63) + ".").repeat(4) + "com"))
    }

    @Test fun public_suffixes_are_rejected_but_names_under_them_are_fine() {
        assertEquals(RpId.Problem.PUBLIC_SUFFIX, RpId.validate("github.io"))
        assertEquals(RpId.Problem.PUBLIC_SUFFIX, RpId.validate("co.uk"))
        assertEquals(RpId.Problem.PUBLIC_SUFFIX, RpId.validate("herokuapp.com"))
        assertNull(RpId.validate("rouf.github.io"))
        assertNull(RpId.validate("bbc.co.uk"))
        assertTrue(RpId.isValid("example.herokuapp.com"))
    }
}

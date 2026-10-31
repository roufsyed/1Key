package com.roufsyed.onekey.core.domain.webauthn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** RFC 8949 appendix A vectors for the subset [Cbor] encodes, plus canonical map ordering. */
class CborTest {

    private fun hex(value: Any?): String = Cbor.encode(value).joinToString("") { "%02x".format(it) }

    @Test fun unsigned_integers_match_rfc_vectors() {
        assertEquals("00", hex(0))
        assertEquals("01", hex(1))
        assertEquals("0a", hex(10))
        assertEquals("17", hex(23))
        assertEquals("1818", hex(24))
        assertEquals("1819", hex(25))
        assertEquals("1864", hex(100))
        assertEquals("1903e8", hex(1000))
        assertEquals("1a000f4240", hex(1_000_000))
        assertEquals("1b000000e8d4a51000", hex(1_000_000_000_000L))
    }

    @Test fun negative_integers_match_rfc_vectors() {
        assertEquals("20", hex(-1))
        assertEquals("29", hex(-10))
        assertEquals("3863", hex(-100))
        assertEquals("3903e7", hex(-1000))
        assertEquals("26", hex(CoseKey.ALG_ES256))
    }

    @Test fun byte_and_text_strings_match_rfc_vectors() {
        assertEquals("40", hex(ByteArray(0)))
        assertEquals("4401020304", hex(byteArrayOf(1, 2, 3, 4)))
        assertEquals("60", hex(""))
        assertEquals("6161", hex("a"))
        assertEquals("6449455446", hex("IETF"))
        assertEquals("62c3bc", hex("ü"))
    }

    @Test fun arrays_and_maps_match_rfc_vectors() {
        assertEquals("80", hex(emptyList<Any>()))
        assertEquals("83010203", hex(listOf(1, 2, 3)))
        assertEquals("a0", hex(emptyMap<Any, Any>()))
        assertEquals("a201020304", hex(mapOf(1 to 2, 3 to 4)))
        assertEquals("a26161016162820203", hex(mapOf("a" to 1, "b" to listOf(2, 3))))
    }

    @Test fun simple_values_match_rfc_vectors() {
        assertEquals("f4", hex(false))
        assertEquals("f5", hex(true))
        assertEquals("f6", hex(null))
    }

    @Test fun long_strings_use_the_right_head_width() {
        val s24 = "x".repeat(24)
        assertEquals("7818" + "78".repeat(24), hex(s24))
        val s256 = ByteArray(256)
        assertEquals("590100" + "00".repeat(256), hex(s256))
    }

    @Test fun map_keys_are_canonically_ordered_shorter_first_then_bytewise() {
        // "aa" encodes longer than "b", so "b" comes first regardless of insertion order.
        assertEquals("a261620262616101", hex(linkedMapOf("aa" to 1, "b" to 2)))
        // Negative integer keys sort after positive ones of the same width (0x20 > 0x03).
        assertEquals("a3010203042001", hex(linkedMapOf(-1 to 1, 1 to 2, 3 to 4)))
        // The attestation object key order the spec examples show: fmt, attStmt, authData.
        assertEquals(
            "a3" + "63666d74" + "646e6f6e65" + "6761747453746d74" + "a0" + "686175746844617461" + "40",
            hex(linkedMapOf("authData" to ByteArray(0), "fmt" to "none", "attStmt" to emptyMap<String, Any>())),
        )
    }

    @Test fun unsupported_types_are_rejected() {
        assertThrows(IllegalArgumentException::class.java) { Cbor.encode(1.5) }
    }
}

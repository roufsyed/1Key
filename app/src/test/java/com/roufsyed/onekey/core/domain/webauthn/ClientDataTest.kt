package com.roufsyed.onekey.core.domain.webauthn

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.MessageDigest

class ClientDataTest {

    @Test fun create_json_has_the_four_fields_in_order() {
        val json = ClientData.json(ClientData.TYPE_CREATE, "AQID", "https://example.com", "com.example.app")
        assertEquals(
            """{"type":"webauthn.create","challenge":"AQID","origin":"https://example.com","androidPackageName":"com.example.app"}""",
            json,
        )
    }

    @Test fun get_json_omits_package_when_absent() {
        val json = ClientData.json(ClientData.TYPE_GET, "AQID", "https://example.com")
        assertEquals("""{"type":"webauthn.get","challenge":"AQID","origin":"https://example.com"}""", json)
    }

    @Test fun strings_are_json_escaped() {
        val json = ClientData.json(ClientData.TYPE_GET, "a\"b", "https://x.y", "p\\q\n")
        assertEquals("""{"type":"webauthn.get","challenge":"a\"b","origin":"https://x.y","androidPackageName":"p\\q\n"}""", json)
    }

    @Test fun unknown_type_is_rejected() {
        assertThrows(IllegalArgumentException::class.java) { ClientData.json("webauthn.other", "x", "https://a.b") }
    }

    @Test fun hash_is_sha256_of_utf8() {
        val json = ClientData.json(ClientData.TYPE_GET, "AQID", "https://example.com")
        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(json.toByteArray(Charsets.UTF_8)), ClientData.hash(json))
    }

    @Test fun apk_key_hash_origin_uses_unpadded_base64url() {
        val digest = ByteArray(32) { 0xFB.toByte() }
        val origin = ClientData.apkKeyHashOrigin(digest)
        assertEquals("android:apk-key-hash:" + Base64Url.encode(digest), origin)
        assert(!origin.endsWith("=")) { "must be unpadded" }
        assert(!origin.contains('+') && !origin.contains('/')) { "must be url-safe" }
    }

    @Test fun base64url_round_trips_and_accepts_padding() {
        val bytes = ByteArray(33) { (it * 7).toByte() }
        val encoded = Base64Url.encode(bytes)
        assertArrayEquals(bytes, Base64Url.decode(encoded))
        assertArrayEquals(bytes, Base64Url.decode("$encoded="))
    }
}

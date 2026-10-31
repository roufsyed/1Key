package com.roufsyed.onekey.core.domain.webauthn

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.math.BigInteger

class CoseKeyTest {

    @Test fun es256_layout_is_kty_alg_crv_x_y_in_canonical_order() {
        val x = ByteArray(32) { 0x11 }
        val y = ByteArray(32) { 0x22 }
        val encoded = CoseKey.es256(x, y)
        val expected = byteArrayOf(
            0xA5.toByte(),                 // map(5)
            0x01, 0x02,                    // 1: 2 (kty EC2)
            0x03, 0x26,                    // 3: -7 (alg ES256)
            0x20, 0x01,                    // -1: 1 (crv P-256)
            0x21, 0x58, 0x20,              // -2: bytes(32)
        ) + x + byteArrayOf(0x22, 0x58, 0x20) + y
        assertArrayEquals(expected, encoded)
        assertEquals(77, encoded.size)
    }

    @Test fun coordinates_must_be_32_bytes() {
        assertThrows(IllegalArgumentException::class.java) { CoseKey.es256(ByteArray(31), ByteArray(32)) }
    }

    @Test fun toFixedLength_strips_sign_byte_and_left_pads() {
        val withSign = BigInteger(1, ByteArray(32) { 0xFF.toByte() })  // toByteArray() yields 33 bytes
        assertEquals(32, CoseKey.toFixedLength(withSign).size)
        assertArrayEquals(ByteArray(32) { 0xFF.toByte() }, CoseKey.toFixedLength(withSign))

        val small = BigInteger.valueOf(0x0102L)
        val padded = CoseKey.toFixedLength(small)
        assertEquals(32, padded.size)
        assertEquals(0x01.toByte(), padded[30])
        assertEquals(0x02.toByte(), padded[31])
    }

    @Test fun real_key_encodes_to_77_bytes() {
        val kp = Es256.generateKeyPair()
        assertEquals(77, CoseKey.es256(Es256.publicKeyOf(kp)).size)
    }
}

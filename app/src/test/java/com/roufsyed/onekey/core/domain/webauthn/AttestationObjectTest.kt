package com.roufsyed.onekey.core.domain.webauthn

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class AttestationObjectTest {

    @Test fun none_attestation_object_layout() {
        val authData = ByteArray(37) { it.toByte() }
        val obj = AttestationObject.none(authData)
        val expectedPrefix = byteArrayOf(0xA3.toByte()) +
            byteArrayOf(0x63) + "fmt".toByteArray() + byteArrayOf(0x64) + "none".toByteArray() +
            byteArrayOf(0x67) + "attStmt".toByteArray() + byteArrayOf(0xA0.toByte()) +
            byteArrayOf(0x68) + "authData".toByteArray() + byteArrayOf(0x58, 37)
        assertArrayEquals(expectedPrefix, obj.copyOfRange(0, expectedPrefix.size))
        assertArrayEquals(authData, obj.copyOfRange(expectedPrefix.size, obj.size))
        assertEquals(expectedPrefix.size + authData.size, obj.size)
    }
}

package com.roufsyed.onekey.core.domain.webauthn

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Es256Test {

    @Test fun sign_and_verify_round_trip_through_pkcs8() {
        val kp = Es256.generateKeyPair()
        val pkcs8 = Es256.privateKeyPkcs8(kp)
        val data = "hello".toByteArray()
        val sig = Es256.sign(Es256.decodePrivateKey(pkcs8), data)
        assertTrue(Es256.verify(Es256.publicKeyOf(kp), data, sig))
        assertFalse(Es256.verify(Es256.publicKeyOf(kp), "hellp".toByteArray(), sig))
    }

    @Test fun assertion_signature_covers_authdata_then_client_data_hash() {
        val kp = Es256.generateKeyPair()
        val authData = AuthenticatorData.assertion("example.com", userVerified = true, backupState = false)
        val hash = ByteArray(32) { 9 }
        val sig = Es256.signAssertion(Es256.privateKeyPkcs8(kp), authData, hash)
        assertTrue(Es256.verify(Es256.publicKeyOf(kp), authData + hash, sig))
        assertFalse(Es256.verify(Es256.publicKeyOf(kp), hash + authData, sig))
    }

    @Test fun pem_round_trip_matches_keepassxc_shape() {
        val pkcs8 = Es256.privateKeyPkcs8(Es256.generateKeyPair())
        val pem = Es256.toPem(pkcs8)
        assertTrue(pem.startsWith("-----BEGIN PRIVATE KEY-----\n"))
        assertTrue(pem.endsWith("-----END PRIVATE KEY-----\n"))
        assertTrue(pem.lines().filter { it.isNotEmpty() && !it.startsWith("-----") }.all { it.length <= 64 })
        assertArrayEquals(pkcs8, Es256.fromPem(pem))
        // Tolerates CRLF and missing armour.
        assertArrayEquals(pkcs8, Es256.fromPem(pem.replace("\n", "\r\n")))
        assertArrayEquals(pkcs8, Es256.fromPem(pem.lines().filterNot { it.startsWith("-----") }.joinToString("")))
    }

    @Test fun credential_ids_are_32_random_bytes() {
        val a = Es256.newCredentialId()
        val b = Es256.newCredentialId()
        assertEquals(32, a.size)
        assertFalse(a.contentEquals(b))
    }
}

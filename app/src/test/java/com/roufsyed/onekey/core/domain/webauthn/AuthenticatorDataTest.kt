package com.roufsyed.onekey.core.domain.webauthn

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.MessageDigest

class AuthenticatorDataTest {

    private val rpId = "example.com"
    private val credentialId = ByteArray(32) { (it + 1).toByte() }
    private val coseKey = CoseKey.es256(ByteArray(32) { 1 }, ByteArray(32) { 2 })

    @Test fun registration_layout_and_lengths() {
        val data = AuthenticatorData.registration(
            rpId = rpId,
            credentialId = credentialId,
            cosePublicKey = coseKey,
            userVerified = true,
            backupState = false,
        )
        // 32 hash + 1 flags + 4 counter + 16 aaguid + 2 length + 32 id + 77 cose
        assertEquals(32 + 1 + 4 + 16 + 2 + 32 + 77, data.size)
        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(rpId.toByteArray()), data.copyOfRange(0, 32))
        val flags = data[32].toInt() and 0xFF
        assertEquals(AuthenticatorFlags.UP or AuthenticatorFlags.UV or AuthenticatorFlags.BE or AuthenticatorFlags.AT, flags)
        assertArrayEquals(byteArrayOf(0, 0, 0, 0), data.copyOfRange(33, 37))
        assertArrayEquals(OneKeyAaguid.BYTES, data.copyOfRange(37, 53))
        assertEquals(0, data[53].toInt())
        assertEquals(32, data[54].toInt())
        assertArrayEquals(credentialId, data.copyOfRange(55, 87))
        assertArrayEquals(coseKey, data.copyOfRange(87, data.size))
    }

    @Test fun assertion_layout_flags_and_zero_counter() {
        val data = AuthenticatorData.assertion(rpId = rpId, userVerified = false, backupState = true)
        assertEquals(37, data.size)
        val flags = data[32].toInt() and 0xFF
        assertEquals(AuthenticatorFlags.UP or AuthenticatorFlags.BE or AuthenticatorFlags.BS, flags)
        assertArrayEquals(byteArrayOf(0, 0, 0, 0), data.copyOfRange(33, 37))
    }

    @Test fun backup_eligible_is_always_set_and_never_depends_on_backup_state() {
        val a = AuthenticatorData.assertion(rpId, userVerified = false, backupState = false)
        val b = AuthenticatorData.assertion(rpId, userVerified = false, backupState = true)
        assertEquals(AuthenticatorFlags.BE, a[32].toInt() and AuthenticatorFlags.BE)
        assertEquals(AuthenticatorFlags.BE, b[32].toInt() and AuthenticatorFlags.BE)
    }

    @Test fun extensions_set_the_ed_flag_and_are_appended() {
        val ext = Cbor.encode(mapOf("credProps" to mapOf("rk" to true)))
        val data = AuthenticatorData.assertion(rpId, userVerified = true, backupState = false, extensions = ext)
        assertEquals(AuthenticatorFlags.ED, data[32].toInt() and AuthenticatorFlags.ED)
        assertArrayEquals(ext, data.copyOfRange(37, data.size))
    }

    @Test fun credential_id_length_is_big_endian_two_bytes() {
        val longId = ByteArray(300) { 7 }
        val data = AuthenticatorData.registration(rpId, longId, coseKey, userVerified = false, backupState = false)
        assertEquals(1, data[53].toInt())            // 300 = 0x012C
        assertEquals(0x2C, data[54].toInt() and 0xFF)
    }

    @Test fun rejects_bad_aaguid_or_credential_id_sizes() {
        assertThrows(IllegalArgumentException::class.java) {
            AuthenticatorData.registration(rpId, ByteArray(8), coseKey, userVerified = false, backupState = false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AuthenticatorData.registration(rpId, credentialId, coseKey, userVerified = false, backupState = false, aaguid = ByteArray(15))
        }
    }

    @Test fun aaguid_constant_is_stable() {
        assertEquals("34b612ab-ee35-4a53-b81c-594986fdfab3", OneKeyAaguid.UUID_VALUE.toString())
        assertEquals("34b612abee354a53b81c594986fdfab3", OneKeyAaguid.BYTES.joinToString("") { "%02x".format(it) })
    }
}

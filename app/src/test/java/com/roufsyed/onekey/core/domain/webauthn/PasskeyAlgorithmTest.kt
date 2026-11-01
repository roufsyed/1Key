package com.roufsyed.onekey.core.domain.webauthn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec

class PasskeyAlgorithmTest {

    @Test fun picks_the_first_supported_entry_in_the_sites_order() {
        assertEquals(PasskeyAlgorithm.RS256, PasskeyAlgorithm.firstSupported(listOf(-257, -7)))
        assertEquals(PasskeyAlgorithm.EDDSA, PasskeyAlgorithm.firstSupported(listOf(-48, -8, -7)))
        assertEquals(PasskeyAlgorithm.ES256, PasskeyAlgorithm.firstSupported(listOf(-7, -8, -257)))
    }

    @Test fun fully_specified_identifiers_are_aliases() {
        assertEquals(PasskeyAlgorithm.ES256, PasskeyAlgorithm.firstSupported(listOf(-9)))
        assertEquals(PasskeyAlgorithm.EDDSA, PasskeyAlgorithm.firstSupported(listOf(-19)))
    }

    @Test fun empty_list_means_the_default_and_unknown_only_means_none() {
        assertEquals(PasskeyAlgorithm.ES256, PasskeyAlgorithm.firstSupported(emptyList()))
        assertNull(PasskeyAlgorithm.firstSupported(listOf(-48, -49, -50, -35)))
    }

    @Test fun recognises_its_own_keys_from_pkcs8() {
        for (algorithm in PasskeyAlgorithm.entries) {
            val generated = PasskeyKeys.generate(algorithm)
            assertEquals(algorithm, generated.algorithm)
            assertEquals(algorithm, PasskeyAlgorithm.ofPkcs8(generated.privateKeyPkcs8))
        }
    }

    @Test fun rejects_other_curves_and_garbage() {
        val p384 = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp384r1")) }.generateKeyPair()
        assertNull(PasskeyAlgorithm.ofPkcs8(p384.private.encoded))
        assertNull(PasskeyAlgorithm.ofPkcs8(ByteArray(20) { 1 }))
        assertNull(PasskeyAlgorithm.ofPkcs8(ByteArray(0)))
    }

    @Test fun every_algorithm_signs_and_the_signature_has_the_expected_shape() {
        val data = "authData||hash".toByteArray()
        val ed = PasskeyKeys.sign(PasskeyKeys.generate(PasskeyAlgorithm.EDDSA).privateKeyPkcs8, data)
        assertEquals("Ed25519 signatures are 64 raw bytes", 64, ed.size)
        val rsa = PasskeyKeys.sign(PasskeyKeys.generate(PasskeyAlgorithm.RS256).privateKeyPkcs8, data)
        assertEquals("RSA-2048 signatures are 256 bytes", 256, rsa.size)
        val ec = PasskeyKeys.sign(PasskeyKeys.generate(PasskeyAlgorithm.ES256).privateKeyPkcs8, data)
        assertEquals("ECDSA signatures are DER sequences", 0x30, ec[0].toInt())
        assertTrue(ec.size in 70..72)
    }
}

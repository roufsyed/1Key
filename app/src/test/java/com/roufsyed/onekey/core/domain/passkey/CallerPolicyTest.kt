package com.roufsyed.onekey.core.domain.passkey

import com.roufsyed.onekey.core.domain.model.PasskeyOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallerPolicyTest {

    private val certA = "a".repeat(64)
    private val certB = "b".repeat(64)

    private fun identity(
        packageName: String = "com.example.app",
        current: String = certA,
        lineage: Set<String> = setOf(certA),
        multiple: Boolean = false,
        claimedOrigin: Boolean = false,
        privilegedOrigin: String? = null,
    ) = CallerIdentity(packageName, current, lineage, multiple, claimedOrigin, privilegedOrigin)

    @Test fun allowlisted_browser_resolves_to_browser_with_its_origin() {
        val kind = CallerPolicy.resolve(identity(packageName = "org.mozilla.firefox", claimedOrigin = true, privilegedOrigin = "https://github.com"))
        assertEquals(CallerKind.Browser("https://github.com"), kind)
    }

    @Test fun unknown_browser_is_refused_not_downgraded_to_an_app() {
        val kind = CallerPolicy.resolve(identity(packageName = "com.unknown.browser", claimedOrigin = true, privilegedOrigin = null))
        assertEquals(CallerKind.Refused(CallerKind.Reason.UNKNOWN_BROWSER), kind)
    }

    @Test fun multi_signer_app_is_refused() {
        assertEquals(CallerKind.Refused(CallerKind.Reason.MULTIPLE_SIGNERS), CallerPolicy.resolve(identity(multiple = true)))
    }

    @Test fun native_app_resolves_with_lowercased_lineage() {
        val kind = CallerPolicy.resolve(identity(current = certA.uppercase(), lineage = setOf(certA.uppercase(), certB)))
        assertEquals(CallerKind.App("com.example.app", certA, setOf(certA, certB)), kind)
    }

    @Test fun browser_may_use_any_binding() {
        val browser = CallerKind.Browser("https://github.com")
        assertTrue(CallerPolicy.mayUse(PasskeyOrigin.Browser("https://github.com"), browser))
        assertTrue(CallerPolicy.mayUse(PasskeyOrigin.Browser("https://other.example"), browser))
        assertTrue(CallerPolicy.mayUse(PasskeyOrigin.App("com.example.app", certA), browser))
    }

    @Test fun app_may_use_only_its_own_binding_across_its_lineage() {
        val rotated = CallerKind.App("com.example.app", certB, setOf(certA, certB))
        assertTrue("created under the old key, caller rotated", CallerPolicy.mayUse(PasskeyOrigin.App("com.example.app", certA), rotated))
        assertTrue(CallerPolicy.mayUse(PasskeyOrigin.App("com.example.app", certB.uppercase()), rotated))
        assertFalse("other package, same cert", CallerPolicy.mayUse(PasskeyOrigin.App("com.other.app", certA), rotated))
        assertFalse("same package, foreign cert", CallerPolicy.mayUse(PasskeyOrigin.App("com.example.app", "c".repeat(64)), rotated))
        assertFalse("browser-created passkeys never go to apps", CallerPolicy.mayUse(PasskeyOrigin.Browser("https://github.com"), rotated))
    }

    @Test fun refused_callers_may_use_nothing_and_get_no_binding() {
        val refused = CallerKind.Refused(CallerKind.Reason.UNKNOWN_BROWSER)
        assertFalse(CallerPolicy.mayUse(PasskeyOrigin.Browser("https://github.com"), refused))
        assertNull(CallerPolicy.bindingFor(refused))
    }

    @Test fun bindings_record_who_created_the_passkey() {
        assertEquals(PasskeyOrigin.Browser("https://a.b"), CallerPolicy.bindingFor(CallerKind.Browser("https://a.b")))
        assertEquals(PasskeyOrigin.App("p", certA), CallerPolicy.bindingFor(CallerKind.App("p", certA, setOf(certA))))
    }

    @Test fun certificate_digest_helpers_round_trip() {
        val der = ByteArray(300) { it.toByte() }
        val hex = CallerPolicy.certificateSha256Hex(der)
        assertEquals(64, hex.length)
        assertEquals(hex, CallerPolicy.certificateSha256(der).joinToString("") { "%02x".format(it) })
        assertEquals(hex, CallerPolicy.hexToBytes(hex.uppercase().chunked(2).joinToString(":")).joinToString("") { "%02x".format(it) })
    }
}

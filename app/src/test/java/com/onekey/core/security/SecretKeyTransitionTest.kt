package com.onekey.core.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behavioural locks for the [SecretKeyTransition] sealed class.
 *
 * The class is the public surface the use cases use to talk to
 * [KdfMigrator.runSecretKeyTransition]. Three semantics MUST hold:
 *  - Two [SecretKeyTransition.Enable] (or [SecretKeyTransition.Rotate])
 *    instances with the same `newSk` byte content are equal. The default
 *    Kotlin `data class` equality on `ByteArray` would do reference equality
 *    (because Java's `byte[]` does), which would surprise downstream tests
 *    and any future state-snapshot logic. We override equals/hashCode.
 *  - [SecretKeyTransition.Disable] is a singleton `object`; reference
 *    equality holds and equals/hashCode are inherited.
 *  - The three variants are distinguishable by `is` (sealed hierarchy
 *    intact) so the migrator's `when` block can exhaustively branch.
 */
class SecretKeyTransitionTest {

    @Test fun Enable_with_same_byte_content_is_equal() {
        val a = SecretKeyTransition.Enable(newSk = ByteArray(16) { it.toByte() })
        val b = SecretKeyTransition.Enable(newSk = ByteArray(16) { it.toByte() })

        assertEquals(
            "Enable equality must be structural over newSk byte content",
            a,
            b,
        )
        assertEquals(
            "Equal Enable instances must have equal hashCodes",
            a.hashCode(),
            b.hashCode(),
        )
    }

    @Test fun Enable_with_different_byte_content_is_not_equal() {
        val a = SecretKeyTransition.Enable(newSk = ByteArray(16) { it.toByte() })
        val b = SecretKeyTransition.Enable(newSk = ByteArray(16) { (it + 1).toByte() })

        assertNotEquals(
            "Different newSk content must produce non-equal Enable instances",
            a,
            b,
        )
    }

    @Test fun Rotate_with_same_byte_content_is_equal() {
        val a = SecretKeyTransition.Rotate(newSk = ByteArray(16) { 0xAA.toByte() })
        val b = SecretKeyTransition.Rotate(newSk = ByteArray(16) { 0xAA.toByte() })

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test fun Disable_is_a_singleton() {
        // Disable carries no state; the sealed-class object form makes it
        // a singleton. Reference equality holds and `===` is a meaningful
        // sentinel for the migrator's switch.
        val a: SecretKeyTransition = SecretKeyTransition.Disable
        val b: SecretKeyTransition = SecretKeyTransition.Disable
        assertTrue("Disable references the same singleton", a === b)
    }

    @Test fun Enable_and_Rotate_with_same_bytes_are_not_equal_across_variants() {
        // Enable and Rotate carry the same payload shape but mean different
        // things. The migrator's `when` distinguishes them, so equality
        // must distinguish them too.
        val sameBytes = ByteArray(16) { 0xCC.toByte() }
        val enable = SecretKeyTransition.Enable(newSk = sameBytes)
        val rotate = SecretKeyTransition.Rotate(newSk = sameBytes)

        assertNotEquals(
            "Enable and Rotate are different transitions even with identical newSk",
            enable as SecretKeyTransition,
            rotate as SecretKeyTransition,
        )
    }

    @Test fun sealed_hierarchy_is_intact() {
        // Smoke check: each variant is reachable via `is` so a `when`
        // expression in the migrator can branch on all three.
        val enable: SecretKeyTransition =
            SecretKeyTransition.Enable(newSk = ByteArray(16))
        val rotate: SecretKeyTransition =
            SecretKeyTransition.Rotate(newSk = ByteArray(16))
        val disable: SecretKeyTransition = SecretKeyTransition.Disable

        assertTrue(enable is SecretKeyTransition.Enable)
        assertTrue(rotate is SecretKeyTransition.Rotate)
        assertTrue(disable === SecretKeyTransition.Disable)

        assertFalse(enable is SecretKeyTransition.Rotate)
        assertFalse(enable === SecretKeyTransition.Disable)
        assertFalse(rotate is SecretKeyTransition.Enable)
        assertFalse(rotate === SecretKeyTransition.Disable)
    }
}

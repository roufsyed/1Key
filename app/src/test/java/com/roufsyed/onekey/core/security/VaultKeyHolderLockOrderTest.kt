package com.roufsyed.onekey.core.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.spec.SecretKeySpec

/**
 * Locks in the synchronous ordering of [VaultKeyHolder.lock]:
 *
 *   STEP 1 - snapshotHook.onLockBeforeKeyZero() fires
 *   STEP 2 - _isUnlocked.value = false
 *   STEP 3 - _keyBytes.fill(0) and _key = null
 *
 * Plain JVM - no Robolectric - to assert behaviour on the lock() caller's
 * thread directly. The hook is `@Volatile` and `lock()` calls it
 * synchronously, so we can observe the in-progress state from inside the
 * hook callback without any cross-coroutine ceremony.
 */
class VaultKeyHolderLockOrderTest {

    private fun freshUnlockedHolder(): VaultKeyHolder {
        val holder = VaultKeyHolder()
        // Non-zero key bytes so STEP 3 has something visible to clear.
        val keyBytes = ByteArray(32) { (it + 1).toByte() }
        holder.setKey(SecretKeySpec(keyBytes, "AES"))
        // The setKey internals zero the caller's encoded array; we don't
        // need it anymore. The holder's private _keyBytes is its own copy.
        return holder
    }

    @Test fun hook_fires_before_isUnlocked_flips_to_false() {
        val holder = freshUnlockedHolder()
        // Sanity: precondition.
        assertTrue("Holder should start unlocked", holder.isUnlocked.value)

        var sawUnlockedInsideHook: Boolean? = null
        holder.snapshotHook = VaultLockHook {
            // Inside the hook, before STEP 2 has executed, the public flag
            // must still report `true`. If lock() flipped the flag first,
            // this would observe `false` and fail the assertion.
            sawUnlockedInsideHook = holder.isUnlocked.value
        }

        holder.lock()

        assertNotNull("Hook must have fired", sawUnlockedInsideHook)
        assertTrue(
            "Hook must observe isUnlocked == true (fires BEFORE the flag flip)",
            sawUnlockedInsideHook!!,
        )
        // Post-lock invariant: flag is now false.
        assertFalse(holder.isUnlocked.value)
    }

    @Test fun hook_fires_before_key_bytes_are_zeroed() {
        val holder = freshUnlockedHolder()

        var keyBytesSnapshotInsideHook: ByteArray? = null
        holder.snapshotHook = VaultLockHook {
            // Reflection-read the private _keyBytes field. Inside the hook,
            // before STEP 3 (`_keyBytes.fill(0)`) has executed, the bytes
            // must still hold the originals - not all zeros.
            keyBytesSnapshotInsideHook = readPrivateKeyBytes(holder)?.copyOf()
        }

        holder.lock()

        val seen = keyBytesSnapshotInsideHook
        assertNotNull("Hook must have observed _keyBytes", seen)
        assertTrue(
            "Hook must see non-zero key bytes (fires BEFORE _keyBytes.fill(0))",
            seen!!.any { it != 0.toByte() },
        )
        // Post-lock invariant: holder cleared its copy. Internal field is null.
        assertNull(readPrivateKeyBytes(holder))
    }

    @Test fun lock_without_a_hook_is_safe() {
        val holder = freshUnlockedHolder()
        assertNull("Sanity: no hook installed", holder.snapshotHook)

        // Should not throw NPE - `snapshotHook?.onLockBeforeKeyZero()` is
        // null-safe in lock()'s body.
        holder.lock()

        assertFalse(holder.isUnlocked.value)
        assertNull(readPrivateKeyBytes(holder))
    }

    @Test fun hook_fires_on_every_lock_call() {
        val holder = freshUnlockedHolder()
        var fireCount = 0
        holder.snapshotHook = VaultLockHook { fireCount++ }

        holder.lock()
        // Re-unlock and lock again - hook should fire each time.
        holder.setKey(SecretKeySpec(ByteArray(32) { (it + 5).toByte() }, "AES"))
        holder.lock()

        assertEquals("Hook must fire on every lock() invocation", 2, fireCount)
    }

    private fun readPrivateKeyBytes(holder: VaultKeyHolder): ByteArray? {
        val field = VaultKeyHolder::class.java.getDeclaredField("_keyBytes")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return field.get(holder) as ByteArray?
    }
}

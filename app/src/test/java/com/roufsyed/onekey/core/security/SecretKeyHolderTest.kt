package com.roufsyed.onekey.core.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behavioural locks for [SecretKeyHolder]. The holder is pure JVM state (no
 * Android dependencies) so this test runs without Robolectric.
 *
 * The contract pinned here:
 *  - [SecretKeyHolder.setBytes] copies its input; the caller may zero its
 *    own array without disturbing what the holder serves to [withBytes].
 *  - [SecretKeyHolder.setBytes] rejects any length other than the
 *    locked-design 16 bytes - the wrong size would silently propagate into
 *    Argon2id with no other sanity check downstream.
 *  - [SecretKeyHolder.setBytes] zeros the prior held bytes on overwrite.
 *    During rotate the OLD SK should not linger in heap once the NEW one
 *    is staged.
 *  - [SecretKeyHolder.withBytes] hands the block a FRESH defensive copy
 *    every call. Two concurrent / nested call sites cannot interfere with
 *    each other's lambda state.
 *  - [SecretKeyHolder.withBytes] zeros that copy in `finally`, so a block
 *    that escapes the array reference sees a zero-filled array on
 *    re-read (the published contract).
 *  - [SecretKeyHolder.isPresent] reflects setBytes/clear, never lies after
 *    a throw out of [withBytes].
 *  - [SecretKeyHolder.clear] is idempotent and observable.
 *
 * The class lives in core/security alongside [VaultKeyHolder] which has an
 * analogous memory-hygiene story; the two holders MUST behave identically
 * with respect to "lambda-scoped plaintext, zeroed on exit".
 */
class SecretKeyHolderTest {

    private fun makeKey(seed: Int): ByteArray =
        ByteArray(SecretKeyHolder.SECRET_KEY_RAW_LENGTH) { (seed + it).toByte() }

    @Test fun isPresent_is_false_on_construction() {
        val holder = SecretKeyHolder()
        assertFalse(
            "Fresh holder must report no SK loaded",
            holder.isPresent(),
        )
    }

    @Test fun withBytes_throws_when_no_secret_key_is_loaded() {
        val holder = SecretKeyHolder()
        assertThrows(
            "withBytes must throw when isPresent() is false",
            IllegalStateException::class.java,
        ) {
            holder.withBytes { it.size }
        }
    }

    @Test fun setBytes_rejects_wrong_length() {
        val holder = SecretKeyHolder()
        assertThrows(
            "setBytes must reject any length other than 16 bytes",
            IllegalArgumentException::class.java,
        ) {
            holder.setBytes(ByteArray(15))
        }
        assertThrows(
            "setBytes must reject any length other than 16 bytes",
            IllegalArgumentException::class.java,
        ) {
            holder.setBytes(ByteArray(17))
        }
        // Length checks must not have leaked the SK into the holder.
        assertFalse(holder.isPresent())
    }

    @Test fun setBytes_copies_input_array() {
        val holder = SecretKeyHolder()
        val input = makeKey(seed = 1)
        holder.setBytes(input)

        // Mutate the caller's array; the holder MUST still serve the
        // original bytes. This is the "defensive copy" contract that lets
        // the caller zero its own array safely after setBytes returns.
        input.fill(0)
        holder.withBytes { sk ->
            assertArrayEquals(
                "withBytes must serve the original bytes after caller mutation",
                makeKey(seed = 1),
                sk,
            )
        }
    }

    @Test fun setBytes_overwrite_zeros_prior_held_bytes() {
        val holder = SecretKeyHolder()
        val first = makeKey(seed = 7)
        holder.setBytes(first)

        // Snapshot a reference to the first defensive copy by extracting
        // the field via reflection. We never expose this in production
        // but the test needs it to assert the old buffer is wiped before
        // the new one takes over.
        val field = SecretKeyHolder::class.java.getDeclaredField("skBytes").apply {
            isAccessible = true
        }
        @Suppress("UNCHECKED_CAST")
        val atomic = field.get(holder)
            as java.util.concurrent.atomic.AtomicReference<ByteArray?>
        val firstBuffer = atomic.get()!!
        assertArrayEquals("Defensive copy must equal source", first, firstBuffer)

        // Overwrite with a different value. The old buffer should be zeroed
        // in place by setBytes before the new one is published.
        val second = makeKey(seed = 42)
        holder.setBytes(second)

        assertTrue(
            "Prior held buffer must be zero-filled after overwrite",
            firstBuffer.all { it == 0.toByte() },
        )
        holder.withBytes { sk ->
            assertArrayEquals("Holder now serves the new SK", second, sk)
        }
    }

    @Test fun withBytes_returns_generic_result_type() {
        val holder = SecretKeyHolder()
        holder.setBytes(makeKey(seed = 3))

        // The <R> generic must allow arbitrary return types (String,
        // SecretKey, Int, etc.) so callers can extract a non-secret
        // value out of the lambda without smuggling the bytes themselves.
        val length: Int = holder.withBytes { sk -> sk.size }
        assertEquals(16, length)

        val firstByte: Byte = holder.withBytes { sk -> sk[0] }
        assertEquals(3.toByte(), firstByte)
    }

    @Test fun withBytes_hands_a_fresh_defensive_copy_each_call() {
        val holder = SecretKeyHolder()
        holder.setBytes(makeKey(seed = 5))

        var firstRef: ByteArray? = null
        var secondRef: ByteArray? = null
        holder.withBytes { sk -> firstRef = sk }
        holder.withBytes { sk -> secondRef = sk }

        assertNotSame(
            "withBytes must hand a different defensive copy on each call",
            firstRef,
            secondRef,
        )
        // Both arrays are zero-filled by the time we see them outside the
        // lambda, but their identity must differ - that's what we pin.
    }

    @Test fun withBytes_zeros_the_defensive_copy_in_finally() {
        val holder = SecretKeyHolder()
        holder.setBytes(makeKey(seed = 9))

        var escaped: ByteArray? = null
        holder.withBytes { sk ->
            // Capture the reference (escaping the lambda is the
            // anti-pattern callers are warned against, but the contract
            // is that escape -> zeros).
            escaped = sk
            // Sanity inside the lambda: bytes are still live.
            assertEquals(9.toByte(), sk[0])
        }
        // Outside the lambda the copy MUST be zeroed.
        assertTrue(
            "Defensive copy must be zero-filled after withBytes returns",
            escaped!!.all { it == 0.toByte() },
        )
    }

    @Test fun withBytes_zeros_the_defensive_copy_when_block_throws() {
        val holder = SecretKeyHolder()
        holder.setBytes(makeKey(seed = 11))

        var escaped: ByteArray? = null
        assertThrows(IllegalStateException::class.java) {
            holder.withBytes { sk ->
                escaped = sk
                throw IllegalStateException("simulated failure")
            }
        }
        assertTrue(
            "Defensive copy must be zero-filled even when the block throws",
            escaped!!.all { it == 0.toByte() },
        )
        // The HOLDER's copy is unaffected; the SK is still live for the
        // next caller. isPresent() must remain true.
        assertTrue(
            "withBytes throws must NOT clear the holder's reference",
            holder.isPresent(),
        )
        holder.withBytes { sk ->
            assertEquals(11.toByte(), sk[0])
        }
    }

    @Test fun isPresent_flips_with_setBytes_and_clear() {
        val holder = SecretKeyHolder()
        assertFalse(holder.isPresent())

        holder.setBytes(makeKey(seed = 1))
        assertTrue(holder.isPresent())

        holder.clear()
        assertFalse(holder.isPresent())
    }

    @Test fun clear_zeros_the_held_buffer_in_place() {
        val holder = SecretKeyHolder()
        holder.setBytes(makeKey(seed = 13))

        val field = SecretKeyHolder::class.java.getDeclaredField("skBytes").apply {
            isAccessible = true
        }
        @Suppress("UNCHECKED_CAST")
        val atomic = field.get(holder)
            as java.util.concurrent.atomic.AtomicReference<ByteArray?>
        val buffer = atomic.get()!!
        assertArrayEquals(makeKey(seed = 13), buffer)

        holder.clear()

        assertTrue(
            "clear must zero the previously held buffer in place",
            buffer.all { it == 0.toByte() },
        )
        // And drop the reference so withBytes throws.
        assertThrows(IllegalStateException::class.java) {
            holder.withBytes { it.size }
        }
    }

    @Test fun clear_is_idempotent() {
        val holder = SecretKeyHolder()
        holder.setBytes(makeKey(seed = 17))

        holder.clear()
        // Second clear must NOT throw.
        holder.clear()
        assertFalse(holder.isPresent())
    }

    @Test fun two_distinct_keys_round_trip_independently() {
        // Regression guard: setBytes -> clear -> setBytes must not mix
        // residual bytes from the previous SK into the new one.
        val holder = SecretKeyHolder()
        holder.setBytes(makeKey(seed = 1))
        holder.clear()
        holder.setBytes(makeKey(seed = 100))

        holder.withBytes { sk ->
            assertArrayEquals(
                "Holder must serve only the most recently set SK",
                makeKey(seed = 100),
                sk,
            )
            assertNotEquals(
                "Sanity: the test keys must differ",
                makeKey(seed = 1).first(),
                sk.first(),
            )
        }
    }
}

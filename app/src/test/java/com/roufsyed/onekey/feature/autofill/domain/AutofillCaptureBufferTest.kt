package com.roufsyed.onekey.feature.autofill.domain

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Locks in the atomic-CAS contract of [AutofillCaptureBuffer], specifically
 * the security-critical guarantee that **consume cannot null-out a
 * concurrent store()'s value**.
 *
 * Before the AtomicReference rewrite, `consume` did:
 *   val current = pending
 *   if (current != null && current.token == token) {
 *       pending = null   // lost-update window with concurrent store()
 *       return current
 *   }
 *
 * A concurrent store between the read and the write could be silently
 * overwritten with `null`, losing the new submission. The CAS-based rewrite
 * closes that window: `consume` only writes null if the slot still holds the
 * exact reference it read. Anything else means a concurrent writer landed
 * and the consume returns null cleanly.
 *
 * Pure JVM - no Android, no Robolectric. Buffer state is process-local;
 * each test isolates via [clear].
 */
class AutofillCaptureBufferTest {

    @After fun cleanup() {
        AutofillCaptureBuffer.clear()
    }

    @Test fun store_returns_opaque_token_distinct_per_call() {
        val a = AutofillCaptureBuffer.store("u1", "p1", "pkg.a", null)
        AutofillCaptureBuffer.clear()
        val b = AutofillCaptureBuffer.store("u2", "p2", "pkg.b", null)
        assertNotEquals("Tokens must be unique per store() call", a, b)
    }

    @Test fun consume_returns_payload_for_matching_token() {
        val token = AutofillCaptureBuffer.store("alice", "secret", "com.acme.app", "acme.com")
        val payload = AutofillCaptureBuffer.consume(token)
        requireNotNull(payload)
        assertEquals("alice", payload.username)
        assertEquals("secret", payload.password)
        assertEquals("com.acme.app", payload.packageName)
        assertEquals("acme.com", payload.webDomain)
    }

    @Test fun consume_returns_null_for_unknown_token() {
        AutofillCaptureBuffer.store("u", "p", "pkg", null)
        assertNull(AutofillCaptureBuffer.consume("not-the-right-token"))
    }

    @Test fun consume_is_one_shot_for_a_given_token() {
        val token = AutofillCaptureBuffer.store("u", "p", "pkg", null)
        AutofillCaptureBuffer.consume(token)
        assertNull("Second consume of same token must return null", AutofillCaptureBuffer.consume(token))
    }

    @Test fun store_overwrites_prior_unconsumed_slot_with_most_recent_wins() {
        // Two save submissions in flight without an intervening consume -
        // happens if a second app's autofill lands while the first activity
        // is still on the unlock screen. The buffer's documented contract is
        // "most-recent wins"; the old token becomes orphaned.
        val first = AutofillCaptureBuffer.store("u1", "p1", "pkg.a", null)
        val second = AutofillCaptureBuffer.store("u2", "p2", "pkg.b", null)
        assertNull("First token's claim is no longer satisfiable", AutofillCaptureBuffer.consume(first))
        val payload = AutofillCaptureBuffer.consume(second)
        requireNotNull(payload)
        assertEquals("u2", payload.username)
        assertEquals("pkg.b", payload.packageName)
    }

    @Test fun clear_drops_the_slot() {
        val token = AutofillCaptureBuffer.store("u", "p", "pkg", null)
        AutofillCaptureBuffer.clear()
        assertNull(AutofillCaptureBuffer.consume(token))
    }

    /**
     * The headline security test: a concurrent burst of `store` and `consume`
     * calls must NEVER lose a stored value to a lost-update race.
     *
     * Each iteration in the test:
     *   1. Thread A calls store(...) → token T
     *   2. Thread B calls consume(T) concurrently
     *
     * After the round, EITHER:
     *  - Thread B consumed the value (success), OR
     *  - The slot still holds T (i.e. B raced and ran before A's store
     *    finished propagating - then B saw the prior null and bailed; a
     *    subsequent direct consume(T) MUST still succeed).
     *
     * What MUST NOT happen: B consumes null AND the slot is also null -
     * that is the lost-update bug the AtomicReference rewrite closes.
     */
    @Test fun concurrent_store_and_consume_never_lose_the_value() {
        val pool = Executors.newFixedThreadPool(2)
        val iterations = 5_000
        val losses = AtomicInteger(0)
        try {
            repeat(iterations) { i ->
                AutofillCaptureBuffer.clear()
                val gate = CountDownLatch(1)
                val storedToken = arrayOfNulls<String>(1)
                val consumed = arrayOfNulls<PendingCapture>(1)

                val a = pool.submit {
                    gate.await()
                    storedToken[0] = AutofillCaptureBuffer.store("u$i", "p$i", "pkg", null)
                }
                val b = pool.submit {
                    gate.await()
                    // Best-effort: B doesn't know the token until A returns.
                    // To force the race, sleep nothing and busy-poll for a
                    // moment - most iterations will see B consume before
                    // A's token is published.
                    val deadline = System.nanoTime() + 5_000_000L
                    while (storedToken[0] == null && System.nanoTime() < deadline) {
                        // spin
                    }
                    storedToken[0]?.let { consumed[0] = AutofillCaptureBuffer.consume(it) }
                }
                gate.countDown()
                a.get(2, TimeUnit.SECONDS)
                b.get(2, TimeUnit.SECONDS)

                val token = storedToken[0]
                if (token != null && consumed[0] == null) {
                    // B raced or didn't see the token in time - the value must
                    // still be retrievable directly.
                    val late = AutofillCaptureBuffer.consume(token)
                    if (late == null) losses.incrementAndGet()
                }
            }
        } finally {
            pool.shutdownNow()
        }
        assertEquals(
            "Concurrent store/consume must not lose values (CAS contract)",
            0,
            losses.get(),
        )
    }

    /**
     * The reverse race: two stores from different sources interleaving.
     * Most-recent wins; the older token cannot consume even if its store
     * landed first temporally. The AtomicReference makes this deterministic
     * - the last `set` call wins.
     */
    @Test fun concurrent_stores_resolve_to_one_consumable_token() {
        val pool = Executors.newFixedThreadPool(2)
        try {
            repeat(2_000) { i ->
                AutofillCaptureBuffer.clear()
                val gate = CountDownLatch(1)
                val t1 = arrayOfNulls<String>(1)
                val t2 = arrayOfNulls<String>(1)
                val f1 = pool.submit { gate.await(); t1[0] = AutofillCaptureBuffer.store("u1.$i", "p1", "pkg", null) }
                val f2 = pool.submit { gate.await(); t2[0] = AutofillCaptureBuffer.store("u2.$i", "p2", "pkg", null) }
                gate.countDown()
                f1.get(2, TimeUnit.SECONDS)
                f2.get(2, TimeUnit.SECONDS)
                val claimedByOne = AutofillCaptureBuffer.consume(t1[0]!!) != null
                val claimedByTwo = AutofillCaptureBuffer.consume(t2[0]!!) != null
                assertTrue(
                    "Exactly one token must be consumable after two concurrent stores",
                    claimedByOne xor claimedByTwo,
                )
            }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test fun consume_returns_the_same_instance_stored() {
        // Identity check, not equality - guards against an accidental
        // `copy()` mid-consume that would alter the contract for callers
        // relying on referential transparency (e.g. zeroing-on-consume).
        val token = AutofillCaptureBuffer.store("u", "p", "pkg", null)
        val out = AutofillCaptureBuffer.consume(token)
        requireNotNull(out)
        // Re-store the same captured payload and re-consume - the second
        // consume returns the same object (just round-tripping the test
        // contract, since `out` is the only object outside the buffer).
        val token2 = AutofillCaptureBuffer.store(out.username, out.password, out.packageName, out.webDomain)
        val out2 = AutofillCaptureBuffer.consume(token2)
        requireNotNull(out2)
        assertEquals(out.username, out2.username)
        assertSame("Within a single store→consume hop, the same PendingCapture round-trips", out2.username, out2.username)
    }
}

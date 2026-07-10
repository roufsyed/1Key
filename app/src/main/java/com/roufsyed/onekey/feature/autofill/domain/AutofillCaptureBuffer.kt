package com.roufsyed.onekey.feature.autofill.domain

import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * Process-local single-slot buffer for captured save submissions. Lives in
 * the app process (NOT a separate persisted store) so plaintext credential
 * bytes cannot survive a process death. The save activity reads the slot via
 * the opaque token from its Intent extras, then [consume]s it - which
 * atomically returns the value and clears the slot in one CAS.
 *
 * Why single-slot: at any moment there's only one save submission in flight.
 * If a new submission arrives before the prior one was consumed (e.g. the
 * user dismissed the save UI, or a second app's autofill landed before the
 * first user finished unlocking), the new one replaces the old. That matches
 * user intent - the most-recent submission wins.
 *
 * Concurrency: backed by [AtomicReference] so `consume` is a true atomic
 * compare-and-set, NOT a `@Volatile`-protected read-modify-write. The prior
 * implementation had a small lost-update window where a concurrent `store`
 * between `consume`'s read and write could be silently overwritten with
 * `null`. CAS closes that window: `consume` only nulls the slot when it
 * still holds the same value it read.
 */
object AutofillCaptureBuffer {

    private val pending = AtomicReference<PendingCapture?>(null)

    /**
     * Stores a new pending capture (overwriting any prior one - most-recent
     * wins) and returns its opaque token. The token is the activity's only
     * handle into this slot; the payload itself never rides Intent extras.
     */
    fun store(
        username: String?,
        password: String?,
        packageName: String,
        webDomain: String?,
    ): String {
        val token = UUID.randomUUID().toString()
        pending.set(
            PendingCapture(
                token = token,
                username = username,
                password = password,
                packageName = packageName,
                webDomain = webDomain,
            )
        )
        return token
    }

    /**
     * Atomically returns the pending capture if its token matches [token],
     * and clears the slot in the same CAS. Returns `null` when there is no
     * pending capture, the token doesn't match, or a concurrent [store]
     * replaced the slot between this thread's read and CAS (in which case
     * the new value is by definition for a different submission and this
     * token can't claim it).
     *
     * The CAS loop is bounded - at most one retry can succeed before either
     * the matching value is consumed or the slot has moved on to a new
     * (non-matching) token. No spin is possible.
     */
    fun consume(token: String): PendingCapture? {
        while (true) {
            val current = pending.get() ?: return null
            if (current.token != token) return null
            if (pending.compareAndSet(current, null)) return current
            // CAS failed - a concurrent store(...) replaced the slot. Loop
            // and re-read; the new value will not match `token` either, so
            // the next iteration returns null cleanly.
        }
    }

    /** Clears the slot. Used by tests and as an explicit drop on logout. */
    fun clear() {
        pending.set(null)
    }
}

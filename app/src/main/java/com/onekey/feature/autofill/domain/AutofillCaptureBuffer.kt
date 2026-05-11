package com.onekey.feature.autofill.domain

import java.util.UUID

/**
 * Process-local single-slot buffer for captured save submissions. Lives in
 * the app process (NOT a separate persisted store) so plaintext credential
 * bytes cannot survive a process death. The save activity reads the slot via
 * the opaque token from its Intent extras, then [consume]s it — which
 * atomically returns the value and clears the slot.
 *
 * Why single-slot: at any moment there's only one save submission in flight.
 * If a new submission arrives before the prior one was consumed (e.g. the
 * user dismissed the save UI), the new one replaces the old. That matches
 * user intent — the most recent submission wins.
 *
 * Concurrency: the only mutating operation is `replace`-style write,
 * protected by `@Volatile` for memory-visibility across the service's
 * coroutine threads and the activity's main thread. We accept the small
 * race window where two concurrent writes lose data — at most one ends up
 * stored, which is the same outcome.
 */
object AutofillCaptureBuffer {

    @Volatile
    private var pending: PendingCapture? = null

    /**
     * Stores [capture] and returns its [PendingCapture.token]. Overwrites
     * any prior pending value.
     */
    fun store(
        username: String?,
        password: String?,
        packageName: String,
        webDomain: String?,
    ): String {
        val token = UUID.randomUUID().toString()
        pending = PendingCapture(
            token = token,
            username = username,
            password = password,
            packageName = packageName,
            webDomain = webDomain,
        )
        return token
    }

    /**
     * Atomically returns the pending capture if its token matches [token],
     * and clears the slot. Returns `null` when there is no pending capture
     * or the token does not match (e.g. stale Intent from a process that
     * died after storing).
     */
    fun consume(token: String): PendingCapture? {
        val current = pending
        if (current != null && current.token == token) {
            pending = null
            return current
        }
        return null
    }

    /** Clears the slot. Used by tests and as an explicit drop on logout. */
    fun clear() {
        pending = null
    }
}

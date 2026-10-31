package com.roufsyed.onekey.feature.passkey.presentation

import android.os.SystemClock
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One-shot, in-memory proof that the user unlocked the vault inside a passkey
 * ceremony moments ago.
 *
 * A sign-in with a locked vault crosses the system selector twice: 1Key's
 * unlock-and-list activity unlocks and hands entries back, the selector shows
 * them, and the entry the user picks starts a second 1Key activity. Without
 * this grant the second activity would ask again. The unlock-and-list step
 * issues a grant for exactly the passkeys it listed and puts the token in each
 * entry's intent; the sign activity redeems it once, within [VALIDITY_MS], as
 * that ceremony's user verification.
 *
 * Nothing is persisted and the token is random, so a process restart or an
 * expiry simply means the user verifies again. The PendingIntents that carry it
 * are held by the system, never by other apps.
 */
@Singleton
class PasskeyUnlockGrant @Inject constructor() {

    private var token: String? = null
    private var passkeyIds: Set<String> = emptySet()
    private var expiresAt = 0L

    /** Replaces any earlier grant. Returns the token to put in the entries' intents. */
    @Synchronized
    fun issue(passkeyIds: Set<String>, now: Long = SystemClock.elapsedRealtime()): String {
        val fresh = UUID.randomUUID().toString()
        token = fresh
        this.passkeyIds = passkeyIds
        expiresAt = now + VALIDITY_MS
        return fresh
    }

    /**
     * True exactly once per issued grant, when [presented] matches, [passkeyId]
     * was among the listed passkeys and the grant has not expired. A failed
     * attempt leaves the grant in place; a successful one consumes it.
     */
    @Synchronized
    fun redeem(presented: String?, passkeyId: String, now: Long = SystemClock.elapsedRealtime()): Boolean {
        val current = token ?: return false
        if (presented == null || !MessageDigest.isEqual(presented.toByteArray(), current.toByteArray())) return false
        if (passkeyId !in passkeyIds || now > expiresAt) return false
        clear()
        return true
    }

    @Synchronized
    fun clear() {
        token = null
        passkeyIds = emptySet()
        expiresAt = 0L
    }

    companion object {
        /** Matches the auto-lock manager's credential-selector hold. */
        const val VALIDITY_MS = 60_000L
    }
}

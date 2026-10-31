package com.roufsyed.onekey.feature.passkey.presentation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PasskeyUnlockGrantTest {

    private val grant = PasskeyUnlockGrant()

    @Test fun redeems_once_for_a_listed_passkey_within_the_window() {
        val token = grant.issue(setOf("a", "b"), now = 1_000L)
        assertTrue(grant.redeem(token, "b", now = 1_000L + PasskeyUnlockGrant.VALIDITY_MS))
        assertFalse("single use", grant.redeem(token, "b", now = 1_001L))
        assertFalse("nothing left to redeem", grant.redeem(token, "a", now = 1_001L))
    }

    @Test fun rejects_wrong_token_unlisted_passkey_and_expiry_without_consuming() {
        val token = grant.issue(setOf("a"), now = 1_000L)
        assertFalse(grant.redeem(null, "a", now = 1_000L))
        assertFalse(grant.redeem("not-the-token", "a", now = 1_000L))
        assertFalse(grant.redeem(token, "zzz", now = 1_000L))
        assertFalse(grant.redeem(token, "a", now = 1_000L + PasskeyUnlockGrant.VALIDITY_MS + 1))
        assertTrue("failed attempts leave the grant in place", grant.redeem(token, "a", now = 2_000L))
    }

    @Test fun a_new_issue_replaces_the_old_grant_and_clear_forgets_it() {
        val first = grant.issue(setOf("a"), now = 0L)
        val second = grant.issue(setOf("a"), now = 0L)
        assertNotEquals(first, second)
        assertFalse(grant.redeem(first, "a", now = 0L))
        grant.clear()
        assertFalse(grant.redeem(second, "a", now = 0L))
    }
}

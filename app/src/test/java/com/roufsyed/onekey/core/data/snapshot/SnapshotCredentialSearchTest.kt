package com.roufsyed.onekey.core.data.snapshot

import com.roufsyed.onekey.core.domain.model.CredentialType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotCredentialSearchTest {

    private val credential = SnapshotCredential(
        id = "1", title = "GitHub", username = "alice@example.com", url = "https://github.com/signin",
        tags = listOf("Developer"), isFavorite = true, type = CredentialType.LOGIN,
        createdAt = 0L, updatedAt = 0L, accessedAt = null, hasOtp = true,
    )

    @Test fun matches_title_username_and_url_ignoring_case() {
        assertTrue(credential.matchesSearch("github"))
        assertTrue(credential.matchesSearch("HUB"))
        assertTrue(credential.matchesSearch("alice"))
        assertTrue(credential.matchesSearch("EXAMPLE.COM"))
        assertTrue(credential.matchesSearch("/signin"))
    }

    @Test fun does_not_match_fields_outside_the_three_text_fields() {
        assertFalse("tags are a filter, not a search field", credential.matchesSearch("Developer"))
        assertFalse("the type name is not a search field", credential.matchesSearch("LOGIN"))
        assertFalse(credential.matchesSearch("nothing here"))
    }

    @Test fun blank_query_matches_nothing() {
        assertFalse(credential.matchesSearch(""))
        assertFalse(credential.matchesSearch("   "))
    }
}

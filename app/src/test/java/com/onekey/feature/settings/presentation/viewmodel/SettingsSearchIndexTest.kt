package com.onekey.feature.settings.presentation.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Static-data coverage for the Settings search index. The index drives the
 * search overlay on top of the Settings shell; missing keywords or wrong
 * highlight keys are silent regressions because the index has no runtime
 * consumer beyond the search UI.
 *
 * These tests do not exercise UI; they assert the shape of the data the
 * search overlay consumes. The Secret Key entry is the focus here because
 * the entry was added as part of the Secret Key feature wiring and
 * regressions would mean users cannot find the SK row via search.
 */
class SettingsSearchIndexTest {

    private val index = buildSettingsIndex()

    @Test
    fun secret_key_entry_is_present() {
        val entry = index.firstOrNull { it.title == "Secret Key" }
        assertNotNull(
            "Settings search index must include a Secret Key entry so users can find the row via search.",
            entry,
        )
    }

    @Test
    fun secret_key_entry_navigates_to_security_with_secret_key_highlight() {
        val entry = index.first { it.title == "Secret Key" }
        val action = entry.action
        assertTrue(
            "Secret Key search entry must navigate (not open a dialog) - the row lives on the Security sub-screen.",
            action is SettingsAction.Navigate,
        )
        action as SettingsAction.Navigate
        assertEquals(SettingsDestination.Security, action.destination)
        assertEquals(SettingsHighlightKeys.SECRET_KEY, entry.highlightKey)
    }

    @Test
    fun secret_key_entry_keywords_cover_locked_design_phrases() {
        val entry = index.first { it.title == "Secret Key" }
        // The locked-design keyword list. Every phrase below MUST be
        // discoverable by exact-keyword match on the SK entry; adding new
        // keywords is fine but losing one is a regression because each
        // captures a different user mental model (security jargon vs.
        // friendly phrasing vs. raw bit-length).
        val required = listOf(
            "secret key", "secret-key", "secretkey",
            "emergency kit", "emergency", "kit",
            "2fa", "two factor", "two-factor", "second factor",
            "extra security", "qr", "qr code", "scan", "pdf", "recovery",
            "rotate secret key", "disable secret key", "enable secret key",
            "128 bit", "128-bit", "entropy",
        )
        required.forEach { keyword ->
            assertTrue(
                "Secret Key entry must include keyword '$keyword' so search picks it up.",
                entry.keywords.contains(keyword),
            )
        }
    }

    @Test
    fun secret_key_entry_is_placed_after_encryption_strength() {
        // Users scanning Settings > Security expect Secret Key to appear
        // after Encryption strength - the two rows are visually adjacent on
        // the live screen, and the search-result list mirrors the
        // declaration order. Asserting the order keeps the two surfaces
        // congruent.
        val encryptionIdx = index.indexOfFirst { it.title == "Encryption strength" }
        val secretKeyIdx = index.indexOfFirst { it.title == "Secret Key" }
        assertTrue(
            "Encryption strength entry must precede Secret Key entry in the index.",
            encryptionIdx in 0 until secretKeyIdx,
        )
    }
}

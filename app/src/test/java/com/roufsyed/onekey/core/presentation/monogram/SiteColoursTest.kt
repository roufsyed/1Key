package com.roufsyed.onekey.core.presentation.monogram

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The monogram colour is a promise to the eye: one site, one colour, in every
 * list and both themes, and always readable. These tests pin the key rules
 * and check WCAG AA contrast for every hue in the palette.
 */
class SiteColoursTest {

    @Test fun same_site_shares_a_key_across_subdomains_and_country_suffixes() {
        assertEquals("github", SiteColours.keyFor("https://github.com/login", "GitHub"))
        assertEquals("github", SiteColours.keyFor("login.github.com", "Anything"))
        assertEquals("github", SiteColours.keyFor("https://github.co.uk", "GitHub UK"))
    }

    @Test fun key_falls_back_to_the_host_then_the_title() {
        assertEquals("localhost", SiteColours.keyFor("https://localhost:8443/admin", "Router"))
        assertEquals("my bank", SiteColours.keyFor("", "  My Bank "))
        assertEquals("my bank", SiteColours.keyFor(null, "My Bank"))
    }

    @Test fun colours_are_deterministic_and_theme_specific() {
        val light = SiteColours.forKey("github", dark = false)
        assertEquals(light, SiteColours.forKey("github", dark = false))
        val dark = SiteColours.forKey("github", dark = true)
        assertNotEquals(light, dark)
        assertTrue(light.container.luminance() > dark.container.luminance())
    }

    @Test fun different_sites_spread_across_the_palette() {
        val keys = listOf("github", "google", "paytm", "amazon", "netflix", "reddit", "apple", "icici", "zomato", "proton", "steam", "spotify")
        val distinct = keys.map { SiteColours.paletteIndex(it) }.toSet()
        assertTrue("expected a spread, got $distinct", distinct.size >= 6)
        keys.forEach { assertTrue(SiteColours.paletteIndex(it) in SiteColours.HUES.indices) }
    }

    @Test fun every_hue_meets_aa_contrast_in_both_themes() {
        SiteColours.HUES.indices.forEach { index ->
            val key = keyWithIndex(index)
            listOf(false, true).forEach { dark ->
                val pair = SiteColours.forKey(key, dark)
                val ratio = contrast(pair.container, pair.content)
                assertTrue("hue ${SiteColours.HUES[index]} dark=$dark ratio=$ratio", ratio >= 4.5)
            }
        }
    }

    /** Finds a key that lands on [index], so the test covers the palette rather than a lucky subset. */
    private fun keyWithIndex(index: Int): String =
        (0..10_000).asSequence().map { "site$it" }.first { SiteColours.paletteIndex(it) == index }

    private fun contrast(a: Color, b: Color): Double {
        val la = a.luminance().toDouble() + 0.05
        val lb = b.luminance().toDouble() + 0.05
        return maxOf(la, lb) / minOf(la, lb)
    }
}

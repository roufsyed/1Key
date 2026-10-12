package com.roufsyed.onekey.core.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behavioural locks for [CardTheme]. The enum name is the persisted identity
 * (`card_theme` column, DB v16) and the JSON export value, so the golden list
 * below fails the build if an entry is ever renamed or removed - see the
 * contract on [CardTheme].
 */
class CardThemeTest {

    @Test fun golden_names_are_stable() {
        assertEquals(
            listOf(
                "CHARCOAL", "MIDNIGHT", "MAROON", "GRAPHITE", "PINE", "GRAPE", "SLATE",
                "TEAL", "RUST", "SOFT_BLUE", "CORAL", "MUSTARD", "POWDER", "PEACH", "LEMON", "IVORY",
            ),
            CardTheme.entries.map { it.name },
        )
    }

    @Test fun default_is_graphite() {
        assertSame(CardTheme.GRAPHITE, CardTheme.DEFAULT)
    }

    @Test fun fromNameOrDefault_resolves_every_entry_by_exact_name() {
        CardTheme.entries.forEach { theme ->
            assertSame(theme, CardTheme.fromNameOrDefault(theme.name))
        }
    }

    @Test fun fromNameOrDefault_falls_back_for_null_blank_and_unknown() {
        assertSame(CardTheme.GRAPHITE, CardTheme.fromNameOrDefault(null))
        assertSame(CardTheme.GRAPHITE, CardTheme.fromNameOrDefault(""))
        assertSame(CardTheme.GRAPHITE, CardTheme.fromNameOrDefault("NOT_A_THEME"))
    }

    @Test fun fromNameOrDefault_is_case_sensitive_like_CredentialType() {
        // Mirrors CredentialType.fromNameOrDefault: the wire form is the exact
        // enum name; a lower-case display id is not accepted.
        assertSame(CardTheme.GRAPHITE, CardTheme.fromNameOrDefault("midnight"))
    }

    @Test fun display_names_are_unique_trimmed_and_non_blank() {
        val names = CardTheme.entries.map { it.displayName }
        assertEquals(names.size, names.toSet().size)
        names.forEach { name ->
            assertTrue(name.isNotBlank())
            assertEquals(name, name.trim())
        }
    }
}

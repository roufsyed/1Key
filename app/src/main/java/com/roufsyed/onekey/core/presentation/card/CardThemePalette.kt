package com.roufsyed.onekey.core.presentation.card

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import com.roufsyed.onekey.core.domain.model.CardTheme

/**
 * Colours for one [CardTheme]. Solid fills only, fixed across the light and dark
 * app themes like a physical card. Body text clears WCAG 2.1 contrast of at least
 * 6.2:1 on every background and the muted text at least 4.58:1, so even the 11 sp
 * labels meet the small-text bar; the original fifteen backgrounds were chosen for a
 * minimum pairwise CIE76 distance of 10.4 under protan, deutan and tritan
 * simulation, so no two themes rely on hue alone. Soft Blue (#7FA9E6, ink text,
 * body 7.06:1, muted 4.68:1, 24.6 CIE76 from Powder) was added on request afterwards. Only the enum NAME is persisted;
 * these values can be retuned without a migration.
 */
@Immutable
data class CardPalette(
    val background: Color,
    /** Body text and glyph colour. */
    val onCard: Color,
    private val mutedAlpha: Float,
) {
    /** Labels and secondary text. */
    val muted: Color get() = onCard.copy(alpha = mutedAlpha)

    /** Chip glyph fill. */
    val glyph: Color get() = onCard.copy(alpha = 0.24f)

    /** Back-face stripe fill and the hairline outline. */
    val stripe: Color get() = onCard.copy(alpha = 0.12f)
}

private val White = Color(0xFFFFFFFF)
private val Ink = Color(0xFF1A1C24)

private fun onLight(background: Long) = CardPalette(Color(background), Ink, mutedAlpha = 0.78f)
private fun onDark(background: Long) = CardPalette(Color(background), White, mutedAlpha = 0.80f)

fun CardTheme.palette(): CardPalette = when (this) {
    CardTheme.CHARCOAL -> onDark(0xFF15171C)
    CardTheme.MIDNIGHT -> onDark(0xFF1B2A4A)
    CardTheme.MAROON -> onDark(0xFF5C1A1A)
    CardTheme.GRAPHITE -> onDark(0xFF30353D)
    CardTheme.PINE -> onDark(0xFF1E4A36)
    CardTheme.GRAPE -> onDark(0xFF6A2C91)
    CardTheme.SLATE -> onDark(0xFF4C5B7F)
    CardTheme.TEAL -> onDark(0xFF0F6A6A)
    CardTheme.RUST -> onDark(0xFFA0461C)
    CardTheme.SOFT_BLUE -> onLight(0xFF7FA9E6)
    CardTheme.CORAL -> onLight(0xFFF0A08A)
    CardTheme.MUSTARD -> onLight(0xFFE3B94E)
    CardTheme.POWDER -> onLight(0xFFB9D3EE)
    CardTheme.PEACH -> onLight(0xFFF4CFB8)
    CardTheme.LEMON -> onLight(0xFFF2E6A8)
    CardTheme.IVORY -> onLight(0xFFF3EFE6)
}

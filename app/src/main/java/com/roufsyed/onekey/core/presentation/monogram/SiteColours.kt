package com.roufsyed.onekey.core.presentation.monogram

import androidx.compose.ui.graphics.Color
import com.roufsyed.onekey.core.domain.url.HostExtractor

/**
 * Stable per-site colours for the monogram tile, so one site always wears the
 * same colour in every list and rows can be told apart at a glance without
 * any artwork. Pure functions: nothing here touches Compose runtime state, so
 * the palette is unit-testable on the JVM.
 *
 * The key is the brand label of the entry's URL (`github` for github.com,
 * login.github.com and github.co.uk alike, via [HostExtractor]), falling back
 * to the trimmed lower-case title for entries without a usable URL. Two
 * entries for the same site therefore share a colour even when their titles
 * differ, and a title-only 2FA account still gets a stable one.
 */
object SiteColours {

    /** Container and content pair for one tile. */
    data class Pair(val container: Color, val content: Color)

    /**
     * Hues chosen to stay distinct from each other and readable in both
     * themes. Yellow-green and pure yellow are left out: at the lightness
     * used here they wash out in light mode and muddy in dark mode.
     */
    internal val HUES = floatArrayOf(4f, 24f, 40f, 95f, 140f, 170f, 195f, 215f, 245f, 275f, 305f, 335f)

    /** Resolves the colour key for an entry: brand label of the URL, else the normalised title. */
    fun keyFor(url: String?, label: String): String {
        val brand = HostExtractor.registrableNameOf(url)
        if (!brand.isNullOrBlank()) return brand
        val host = HostExtractor.hostOf(url)
        if (!host.isNullOrBlank()) return host
        return label.trim().lowercase()
    }

    /** Colours for [key] in the light or dark palette. Same key, same colours, every time. */
    fun forKey(key: String, dark: Boolean): Pair {
        val hue = HUES[paletteIndex(key)]
        return if (dark) {
            Pair(container = Color.hsl(hue, 0.32f, 0.26f), content = Color.hsl(hue, 0.60f, 0.86f))
        } else {
            Pair(container = Color.hsl(hue, 0.58f, 0.90f), content = Color.hsl(hue, 0.62f, 0.28f))
        }
    }

    /** FNV-1a over the key's UTF-8 bytes; defined here rather than relying on String.hashCode so the mapping is explicit. */
    internal fun paletteIndex(key: String): Int {
        var hash = 0x811C9DC5.toInt()
        for (byte in key.toByteArray(Charsets.UTF_8)) {
            hash = hash xor (byte.toInt() and 0xFF)
            hash *= 0x01000193
        }
        return (hash ushr 1) % HUES.size
    }
}

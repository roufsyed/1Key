package com.roufsyed.onekey.core.presentation.monogram

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.roufsyed.onekey.core.presentation.theme.LocalIsDarkTheme

/**
 * A coloured tile with the first letter of [label]: a stable per-site anchor
 * for list rows without fetching favicons or bundling logos, neither of which
 * 1Key does. The colour comes from [colourKey] through [SiteColours], so the
 * same site wears the same colour in every list and in both themes. Pass the
 * entry's URL-derived key (see [SiteColours.keyFor]); it defaults to the label.
 * Falls back to [fallbackIcon] when the label has no letter or digit to show.
 */
@Composable
fun SiteMonogram(
    label: String,
    modifier: Modifier = Modifier,
    colourKey: String = label.trim().lowercase(),
    fallbackIcon: ImageVector = Icons.Outlined.Key,
    size: Dp = 40.dp,
) {
    val letter = label.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString()
    val dark = LocalIsDarkTheme.current
    val colours = remember(colourKey, dark) { SiteColours.forKey(colourKey, dark) }
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(10.dp))
            .background(colours.container),
        contentAlignment = Alignment.Center,
    ) {
        if (letter == null) {
            Icon(fallbackIcon, contentDescription = null, tint = colours.content, modifier = Modifier.size(size / 2))
        } else {
            Text(letter, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = colours.content)
        }
    }
}

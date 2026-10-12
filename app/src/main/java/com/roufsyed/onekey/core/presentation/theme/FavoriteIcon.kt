package com.roufsyed.onekey.core.presentation.theme

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The one gold used for favourites, fixed across light and dark mode so a star always reads as gold. */
val FavoriteGold = Color(0xFFF4B400)

/** Default star size: 2 dp over the stock 24 dp icon, since a star fills its box less than a heart did. */
val FavoriteIconSize: Dp = 26.dp

/** Rounded-corner star marks for favourites: filled for "is a favourite", outlined for "is not". */
object FavoriteIcons {
    val Filled: ImageVector get() = Icons.Rounded.Star
    val Outlined: ImageVector get() = Icons.Rounded.StarOutline
}

/**
 * The favourite marker used everywhere a credential's favourite state is shown or
 * toggled: a gold rounded star when [isFavorite], otherwise an outlined star in the
 * surrounding content colour. One composable so every screen agrees on shape and tint.
 */
@Composable
fun FavoriteIcon(
    isFavorite: Boolean,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    size: Dp = FavoriteIconSize,
) {
    Icon(
        imageVector = if (isFavorite) FavoriteIcons.Filled else FavoriteIcons.Outlined,
        contentDescription = contentDescription,
        tint = if (isFavorite) FavoriteGold else LocalContentColor.current,
        modifier = modifier.size(size),
    )
}

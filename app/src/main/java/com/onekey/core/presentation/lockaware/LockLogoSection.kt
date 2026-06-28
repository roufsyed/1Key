package com.onekey.core.presentation.lockaware

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOutBack
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.onekey.R
import com.onekey.core.presentation.animation.AppIconBlue
import com.onekey.feature.auth.presentation.viewmodel.AuthUiState
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Animated brand mark used by both the main `LockScreen` and the autofill
 * `AutofillLockedSurface` so the two unlock surfaces visually read as the
 * same product.
 *
 * Layout: a 60.dp ripple Box (always 2× scaled at idle, alpha 0 at idle) sits
 * behind a 44.dp AppIconBlue circle holding a 28.dp key vector. Both inner
 * Boxes are scaled to 2× at idle via `graphicsLayer`, so the visible size
 * reads as ~88.dp / 56.dp without inflating layout cost.
 *
 * State-driven animations:
 *  - [AuthUiState.Unlocked] → quick scale pulse (1.22 → 2) on the key,
 *    full 360° rotation, and a ripple that fades out as it expands.
 *  - [AuthUiState.Error]    → horizontal shake on the outer Box (the same
 *    keyframe curve as the password field shake).
 *
 * The animations are deliberately one-shot per [state] change; the autofill
 * surface is short-lived so we don't run idle loops here.
 */
@Composable
fun LockLogoSection(
    state: AuthUiState,
    modifier: Modifier = Modifier,
) {
    val iconScale   = remember { Animatable(2f) }
    val keyRotation = remember { Animatable(2f) }
    val rippleScale = remember { Animatable(2f) }
    val rippleAlpha = remember { Animatable(0f) }
    val shakeOffset = remember { Animatable(0f) }

    val keyColor = MaterialTheme.colorScheme.primary
    val density = LocalDensity.current
    val shakePx = remember(density) { with(density) { 10.dp.toPx() } }

    LaunchedEffect(state) {
        when (state) {
            is AuthUiState.Unlocked -> {
                coroutineScope {
                    launch { iconScale.animateTo(1.22f, tween(150, easing = EaseOutBack)) }
                    launch { delay(0); iconScale.animateTo(2f, tween(130, easing = FastOutSlowInEasing)) }
                }
                coroutineScope {
                    launch {
                        keyRotation.animateTo(360f, tween(260, easing = FastOutSlowInEasing))
                    }
                    launch {
                        rippleAlpha.snapTo(0.20f)
                        coroutineScope {
                            launch {
                                rippleScale.animateTo(2.8f, tween(340, easing = LinearOutSlowInEasing))
                            }
                            launch {
                                delay(50)
                                rippleAlpha.animateTo(0f, tween(300, easing = LinearEasing))
                            }
                        }
                    }
                }
            }
            is AuthUiState.Error -> {
                shakeOffset.animateTo(
                    targetValue = 0f,
                    animationSpec = keyframes {
                        durationMillis = 400
                        0f               at 0
                        (-shakePx)       at 55
                        shakePx          at 110
                        (-shakePx * .6f) at 175
                        (shakePx * .6f)  at 235
                        (-shakePx * .3f) at 310
                        0f               at 400
                    },
                )
            }
            else -> Unit
        }
    }

    Box(
        contentAlignment = Alignment.CenterStart,
        modifier = modifier.graphicsLayer { translationX = shakeOffset.value },
    ) {
        Box(
            modifier = Modifier
                .size(60.dp)
                .graphicsLayer {
                    scaleX = rippleScale.value
                    scaleY = rippleScale.value
                    alpha = rippleAlpha.value
                }
                .background(keyColor, CircleShape),
        )
        Box(
            modifier = Modifier
                .size(44.dp)
                .graphicsLayer {
                    rotationZ = keyRotation.value
                    scaleX = iconScale.value
                    scaleY = iconScale.value
                }
                .background(AppIconBlue, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(id = R.drawable.ic_lockscreen_key_foreground),
                contentDescription = "1Key app icon",
                modifier = Modifier.size(28.dp),
            )
        }
    }
}

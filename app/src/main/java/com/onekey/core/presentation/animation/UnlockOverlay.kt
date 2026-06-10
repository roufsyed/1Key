package com.onekey.core.presentation.animation

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.onekey.core.presentation.viewmodel.AppViewModel
import kotlinx.coroutines.delay
import kotlin.math.hypot

val AppIconBlue = Color(0xFF1A56DB)
val PremiumMorphEasing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

object UnlockTransitionTimings {
    const val LOGO_CELEBRATION_DELAY_MS = 430L
    // Single-phase linear expansion - circle grows at a constant rate from the icon
    // outward, so it reads as "gradual reveal" rather than the previous front-loaded
    // burst-then-settle. Tune this to taste.
    const val MORPH_EXPAND_DURATION_MS = 300
    const val MORPH_HELD_MS = 220L
    const val MORPH_DISMISS_MS = 280
    const val POST_HELD_NAV_BUFFER_MS = 60L
}

sealed class UnlockTransitionPhase {
    data object Idle : UnlockTransitionPhase()
    data object Expanding : UnlockTransitionPhase()
    data object Held : UnlockTransitionPhase()
    data object Dismissing : UnlockTransitionPhase()
}

@Composable
fun UnlockOverlay(appViewModel: AppViewModel) {
    val phase by appViewModel.unlockPhase.collectAsStateWithLifecycle()
    val expandProgress = remember { Animatable(0f) }
    val overlayAlpha = remember { Animatable(0f) }

    LaunchedEffect(phase) {
        when (phase) {
            UnlockTransitionPhase.Idle -> {
                overlayAlpha.snapTo(0f)
                expandProgress.snapTo(0f)
            }
            UnlockTransitionPhase.Expanding -> {
                expandProgress.snapTo(0f)
                overlayAlpha.snapTo(1f)
                expandProgress.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(
                        durationMillis = UnlockTransitionTimings.MORPH_EXPAND_DURATION_MS,
                        easing = LinearEasing,
                    ),
                )
                appViewModel.markUnlockMorphHeld()
            }
            UnlockTransitionPhase.Held -> {
                expandProgress.snapTo(1f)
                overlayAlpha.snapTo(1f)
                delay(UnlockTransitionTimings.MORPH_HELD_MS)
                appViewModel.requestDismissUnlockMorph()
            }
            UnlockTransitionPhase.Dismissing -> {
                overlayAlpha.animateTo(
                    targetValue = 0f,
                    animationSpec = tween(
                        durationMillis = UnlockTransitionTimings.MORPH_DISMISS_MS,
                        easing = FastOutSlowInEasing,
                    ),
                )
                appViewModel.resetUnlockMorph()
            }
        }
    }

    if (overlayAlpha.value <= 0f) return

    Canvas(modifier = Modifier.fillMaxSize()) {
        val rawProgress = expandProgress.value.coerceIn(0f, 1f)
        val maxRadius = hypot(size.width.toDouble(), size.height.toDouble()).toFloat() * 1.1f
        // Radius scales linearly with progress so the circle grows at a steady,
        // perceptible rate from the icon outward.
        val radius = maxRadius * rawProgress
        // Origin sits over the LockScreen logo across typical phone sizes (icon center
        // lands at ~13-15% width / 21-23% height after status-bar + topSpacing).
        val origin = Offset(
            x = size.width * 0.15f,
            y = size.height * 0.22f,
        )
        val a = overlayAlpha.value

        drawCircle(
            color = AppIconBlue.copy(alpha = (0.82f + 0.16f * rawProgress) * a),
            radius = radius,
            center = origin,
        )

        // Fill any uncovered corners once the circle has reached most of the screen.
        if (rawProgress > 0.72f) {
            val fillAlpha = ((rawProgress - 0.72f) / 0.28f).coerceIn(0f, 1f) * a
            drawRect(color = AppIconBlue.copy(alpha = fillAlpha))
        }
    }
}

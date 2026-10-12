package com.roufsyed.onekey.feature.vault.presentation.screen.card

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.roufsyed.onekey.core.domain.card.CardFace
import com.roufsyed.onekey.core.domain.card.CardSlot
import com.roufsyed.onekey.core.domain.card.CardValueFormatter
import com.roufsyed.onekey.core.domain.card.ExpiryParser
import com.roufsyed.onekey.core.domain.model.CardTheme
import com.roufsyed.onekey.core.presentation.card.CardPalette
import com.roufsyed.onekey.core.presentation.card.CardSchemeBadge
import com.roufsyed.onekey.core.presentation.card.palette
import java.time.YearMonth

/** ISO/IEC 7810 ID-1: 85.60 x 53.98 mm. */
const val CARD_ASPECT_RATIO = 1.586f

/**
 * Compose passes this value straight to RenderNode.setCameraDistance, whose unit is
 * View pixels divided by densityDpi (160 dp per unit on every device), so a plain
 * constant is already density independent. 14 is about 6.6 card widths: unmistakably
 * 3D, no visible bulge. Never multiply by density: the common `12f * density` idiom
 * makes the perspective vary by device.
 */
const val CARD_CAMERA_DISTANCE = 14f

const val CARD_FLIP_DURATION_MS = 380

/** Hoisted so the per-frame graphicsLayer block never allocates. */
val CardShape = RoundedCornerShape(12.dp)

/**
 * Animation state for one card. Only the LOGICAL face is saved state (a non-secret
 * Boolean-equivalent held by the caller); the rotation is plain remembered state
 * initialised from that face, so after a recreation the card rests on the requested
 * face with no replay. Exposed as a class so a UI test can observe the rotation.
 */
@Stable
class CardFlipState(initialFace: CardFace) {
    val rotation = Animatable(if (initialFace == CardFace.BACK) 180f else 0f).apply {
        updateBounds(0f, 180f)
    }
}

@Composable
fun rememberCardFlipState(key: Any?, initialFace: CardFace): CardFlipState =
    remember(key) { CardFlipState(initialFace) }

/**
 * Per-slot gestures on the drawn values: a tap behaves exactly like tapping the card
 * (flip), a long press copies that field. Null in the editor preview.
 */
@Stable
class CardSlotGestures(
    val onTap: () -> Unit,
    val onLongPress: (CardSlot) -> Unit,
)

/**
 * The flip card, one layout for CREDIT_CARD and BANK_ACCOUNT alike (a bank account
 * shows the debit card attached to it). One parent layer carries the 3D rotation; both
 * faces are always composed and switched in the draw phase at 90 degrees, where they
 * are edge-on, so the swap is invisible. The animation value is read ONLY inside
 * graphicsLayer blocks: nothing recomposes per frame. Compose honours the system
 * animator duration scale, so with "Remove animations" the flip snaps to the
 * requested face in one frame.
 *
 * @param face the logical face to show; changing it animates the turn.
 * @param revealedSlots sensitive slots currently shown in full (the caller decides).
 * @param showPlaceholders editor preview: draw placeholders for unbound slots.
 * @param onClick tap-to-flip; null in the editor preview, which has its own Flip button.
 * @param slotGestures long-press-to-copy on the drawn values; null in the editor preview.
 */
@Composable
fun CredentialFlipCard(
    model: CardDisplayModel,
    theme: CardTheme,
    face: CardFace,
    revealedSlots: Set<CardSlot>,
    showPlaceholders: Boolean,
    onClick: (() -> Unit)?,
    flipState: CardFlipState,
    modifier: Modifier = Modifier,
    slotGestures: CardSlotGestures? = null,
) {
    val palette = remember(theme) { theme.palette() }
    val rotation = flipState.rotation

    LaunchedEffect(face) {
        rotation.animateTo(
            targetValue = if (face == CardFace.BACK) 180f else 0f,
            animationSpec = tween(durationMillis = CARD_FLIP_DURATION_MS, easing = FastOutSlowInEasing),
        )
    }

    // Turn from the leading edge under RTL. The 90 degree test below uses the
    // unsigned value, and the back face's static half-turn is sign agnostic.
    val sign = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1f else 1f
    val interactionSource = remember { MutableInteractionSource() }

    Box(
        modifier = modifier
            .widthIn(max = 400.dp)
            .aspectRatio(CARD_ASPECT_RATIO)
            // The tap target sits OUTSIDE the rotating layer so its hit area stays the
            // full untransformed rectangle for the whole flip (a clickable inside the
            // layer would shrink to a line at 90 degrees). No ripple: the flip itself
            // and the haptic are the feedback.
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        onClickLabel = "Flip card",
                        role = Role.Button,
                        onClick = onClick,
                    )
                } else {
                    Modifier
                }
            )
            .semantics { stateDescription = if (face == CardFace.FRONT) "Showing front" else "Showing back" }
            .graphicsLayer {
                rotationY = sign * rotation.value
                cameraDistance = CARD_CAMERA_DISTANCE
                shape = CardShape
                clip = true
            }
            // After the layer, so the hairline rotates with the card.
            .border(1.dp, palette.stripe, CardShape),
    ) {
        CardFaceLayer(
            palette = palette,
            isBack = false,
            isVisible = { rotation.value <= 90f },
            isHidden = face != CardFace.FRONT,
        ) {
            FrontFace(model, palette, revealedSlots, showPlaceholders, slotGestures)
        }
        CardFaceLayer(
            palette = palette,
            isBack = true,
            isVisible = { rotation.value > 90f },
            isHidden = face != CardFace.BACK,
        ) {
            BackFace(model, palette, revealedSlots, showPlaceholders, slotGestures)
        }
    }
}

/**
 * One face. Visibility is a 0 or 1 alpha decided in the draw phase (a fractional
 * alpha would force an offscreen buffer). The back face is composed pre-mirrored by
 * a static half turn so it reads correctly once the parent passes 90 degrees. The
 * face that is not logically showing is removed from the accessibility tree.
 */
@Composable
private fun CardFaceLayer(
    palette: CardPalette,
    isBack: Boolean,
    isVisible: () -> Boolean,
    isHidden: Boolean,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                alpha = if (isVisible()) 1f else 0f
                if (isBack) rotationY = 180f
            }
            .then(if (isHidden) Modifier.clearAndSetSemantics { hideFromAccessibility() } else Modifier)
            .background(palette.background)
            .padding(20.dp),
        content = content,
    )
}

// ── Faces ─────────────────────────────────────────────────────────────────

@Composable
private fun FrontFace(
    model: CardDisplayModel,
    palette: CardPalette,
    revealedSlots: Set<CardSlot>,
    showPlaceholders: Boolean,
    gestures: CardSlotGestures?,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            // The issuing bank heads the card; the credential title stands in only
            // while no Bank Name field is set.
            if (model[CardSlot.BANK_NAME] != null || (showPlaceholders && model.title.isBlank())) {
                SlotText(
                    model = model,
                    slot = CardSlot.BANK_NAME,
                    palette = palette,
                    revealedSlots = revealedSlots,
                    showPlaceholders = showPlaceholders,
                    gestures = gestures,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Medium),
                    color = palette.onCard,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Text(
                    text = model.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    color = palette.onCard,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        // The scheme mark takes the chip's place; the plain chip glyph stands in while
        // no scheme is set (or while a user-flagged-sensitive scheme is masked).
        val scheme = model[CardSlot.SCHEME]
        if (scheme != null && (!scheme.isSensitive || CardSlot.SCHEME in revealedSlots)) {
            val description = scheme.accessibilityText(revealed = true)
            CardSchemeBadge(
                value = scheme.rawValue,
                modifier = Modifier
                    .slotGestures(gestures, CardSlot.SCHEME)
                    .clearAndSetSemantics { contentDescription = description },
            )
        } else {
            ChipGlyph(palette)
        }
        Spacer(Modifier.weight(1f))
        HeroSlot(model, CardSlot.CARD_NUMBER, palette, revealedSlots, showPlaceholders, gestures)
        Spacer(Modifier.height(12.dp))
        val now = remember { YearMonth.now() }
        val expiry = model[CardSlot.EXPIRY]
        val expired = expiry != null && !expiry.isSensitive &&
            ExpiryParser.parse(expiry.rawValue)?.let { ExpiryParser.isExpired(it, now) } == true
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LabelledSlot(
                label = "NAME ON CARD",
                model = model,
                slot = CardSlot.CARDHOLDER,
                palette = palette,
                revealedSlots = revealedSlots,
                showPlaceholders = showPlaceholders,
                gestures = gestures,
                valueStyle = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
            )
            LabelledSlot(
                label = if (expired) "EXPIRED" else "EXPIRES",
                model = model,
                slot = CardSlot.EXPIRY,
                palette = palette,
                revealedSlots = revealedSlots,
                showPlaceholders = showPlaceholders,
                gestures = gestures,
                valueStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
            )
        }
    }
}

@Composable
private fun BackFace(
    model: CardDisplayModel,
    palette: CardPalette,
    revealedSlots: Set<CardSlot>,
    showPlaceholders: Boolean,
    gestures: CardSlotGestures?,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        SlotLabel("CVV", palette)
        Spacer(Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .background(palette.stripe)
                // The full stripe, not just the three characters, takes the long press.
                .slotGestures(if (model[CardSlot.CVV] != null) gestures else null, CardSlot.CVV),
            contentAlignment = Alignment.CenterEnd,
        ) {
            SlotText(
                model = model,
                slot = CardSlot.CVV,
                palette = palette,
                revealedSlots = revealedSlots,
                showPlaceholders = showPlaceholders,
                gestures = null,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 2.sp,
                    textDirection = TextDirection.Ltr,
                ),
                color = palette.onCard,
                modifier = Modifier.padding(end = 16.dp),
            )
        }
        Spacer(Modifier.weight(1f))
        // Small identifying echo of the number. ALWAYS masked: the reveal control
        // never touches it, so the number and the CVV are never visible together.
        val number = model[CardSlot.CARD_NUMBER]
        val echo = number?.let { CardValueFormatter.maskedText(CardSlot.CARD_NUMBER, it.rawValue) }
            ?: if (showPlaceholders) CardValueFormatter.MASKED_NUMBER else null
        if (echo != null) {
            val description = number?.let {
                "Card number, hidden" +
                    (CardValueFormatter.accessibleLastFour(it.slot, it.rawValue)?.let { tail -> ", ends in $tail" } ?: "")
            } ?: "Card number, not set"
            Text(
                text = echo,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    textDirection = TextDirection.Ltr,
                ),
                color = palette.muted,
                maxLines = 1,
                modifier = Modifier
                    .slotGestures(if (number != null) gestures else null, CardSlot.CARD_NUMBER)
                    .clearAndSetSemantics { contentDescription = description },
            )
        }
    }
}

// ── Building blocks ───────────────────────────────────────────────────────

/** The only decoration: a chip-shaped rounded rectangle in its own layout slot. */
@Composable
private fun ChipGlyph(palette: CardPalette) {
    Box(
        modifier = Modifier
            .size(width = 32.dp, height = 24.dp)
            .background(palette.glyph, RoundedCornerShape(4.dp)),
    )
}

@Composable
private fun SlotLabel(text: String, palette: CardPalette) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.sp),
        fontWeight = FontWeight.Medium,
        color = palette.muted,
        maxLines = 1,
    )
}

/**
 * Tap flips the card (same as tapping anywhere on it), long press copies the slot.
 * Placed BEFORE the slot's clearAndSetSemantics in the chain so the click and
 * long-click actions stay in the accessibility tree while the drawn text does not.
 * No-op when [gestures] is null (editor preview, unbound placeholder).
 */
private fun Modifier.slotGestures(gestures: CardSlotGestures?, slot: CardSlot): Modifier =
    if (gestures == null) {
        this
    } else {
        combinedClickable(
            interactionSource = null,
            indication = null,
            onClickLabel = "Flip card",
            onLongClickLabel = "Copy ${slot.clipLabel.lowercaseUnlessAcronym()}",
            onLongClick = { gestures.onLongPress(slot) },
            onClick = gestures.onTap,
        )
    }

/** "Card number" becomes "card number"; all-caps labels such as "CVV" stay as they are. */
private fun String.lowercaseUnlessAcronym(): String {
    val first = firstOrNull() ?: return this
    val rest = drop(1)
    return if (rest.any { it.isLowerCase() }) first.lowercaseChar() + rest else this
}

/**
 * A slot's text with its accessibility description, or the editor placeholder, or
 * nothing when the slot is unbound and placeholders are off.
 */
@Composable
private fun SlotText(
    model: CardDisplayModel,
    slot: CardSlot,
    palette: CardPalette,
    revealedSlots: Set<CardSlot>,
    showPlaceholders: Boolean,
    gestures: CardSlotGestures?,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    uppercase: Boolean = false,
    maxLines: Int = 1,
) {
    val display = model[slot]
    if (display != null) {
        val revealed = slot in revealedSlots
        val text = display.text(revealed).let { if (uppercase) it.uppercase() else it }
        val description = display.accessibilityText(revealed)
        Text(
            text = text,
            style = style,
            color = color,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            modifier = modifier
                .slotGestures(gestures, slot)
                .clearAndSetSemantics { contentDescription = description },
        )
    } else if (showPlaceholders) {
        val placeholder = CardDisplayModel.placeholderFor(slot) ?: return
        Text(
            text = placeholder,
            style = style,
            color = palette.muted,
            maxLines = 1,
            modifier = modifier.clearAndSetSemantics { contentDescription = "${slot.canonicalLabel}, not set" },
        )
    }
}

@Composable
private fun LabelledSlot(
    label: String,
    model: CardDisplayModel,
    slot: CardSlot,
    palette: CardPalette,
    revealedSlots: Set<CardSlot>,
    showPlaceholders: Boolean,
    gestures: CardSlotGestures?,
    valueStyle: TextStyle,
) {
    val display = model[slot]
    if (display == null && !(showPlaceholders && CardDisplayModel.placeholderFor(slot) != null)) return
    // The whole label-plus-value block is the gesture target: a short value such as
    // an expiry would otherwise be too small to long press reliably.
    Column(modifier = Modifier.slotGestures(if (display != null) gestures else null, slot)) {
        SlotLabel(label, palette)
        SlotText(
            model = model,
            slot = slot,
            palette = palette,
            revealedSlots = revealedSlots,
            showPlaceholders = showPlaceholders,
            gestures = null,
            style = valueStyle.copy(textDirection = if (slot.numeric) TextDirection.Ltr else TextDirection.Unspecified),
            color = palette.onCard,
            maxLines = 2,
        )
    }
}

/**
 * The large number. Auto-sizes on one line from 24 sp down to 18 sp; if the text
 * still overflows at 18 sp (large font scales on narrow screens) the digit groups
 * are packed greedily by measured width onto as few lines as needed, never clipped.
 * The line split is decided synchronously and cached per text, width and font
 * scale, so it is never recomputed per frame; rotation never re-measures text.
 */
@Composable
private fun HeroSlot(
    model: CardDisplayModel,
    slot: CardSlot,
    palette: CardPalette,
    revealedSlots: Set<CardSlot>,
    showPlaceholders: Boolean,
    gestures: CardSlotGestures?,
) {
    val display = model[slot]
    val text: String
    val color: Color
    val description: String
    when {
        display != null -> {
            val revealed = slot in revealedSlots
            text = display.text(revealed)
            color = palette.onCard
            description = display.accessibilityText(revealed)
        }
        showPlaceholders -> {
            text = CardDisplayModel.placeholderFor(slot) ?: return
            color = palette.muted
            description = "${slot.canonicalLabel}, not set"
        }
        else -> return
    }

    val baseStyle = MaterialTheme.typography.headlineSmall.copy(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        letterSpacing = 1.sp,
        textDirection = TextDirection.Ltr,
    )
    val minStyle = baseStyle.copy(fontSize = HERO_MIN_SP.sp)

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .slotGestures(if (display != null) gestures else null, slot)
            .clearAndSetSemantics { contentDescription = description },
    ) {
        val measurer = rememberTextMeasurer()
        val density = LocalDensity.current
        val maxWidthPx = constraints.maxWidth
        val lines = remember(text, maxWidthPx, density.density, density.fontScale) {
            packLines(text, measurer, minStyle, maxWidthPx)
        }
        if (lines.size <= 1) {
            Text(
                text = text,
                style = baseStyle,
                color = color,
                maxLines = 1,
                softWrap = false,
                autoSize = TextAutoSize.StepBased(minFontSize = HERO_MIN_SP.sp, maxFontSize = HERO_MAX_SP.sp, stepSize = 1.sp),
            )
        } else {
            Column {
                lines.forEach { line ->
                    Text(text = line, style = minStyle, color = color, maxLines = 1, softWrap = false)
                }
            }
        }
    }
}

private const val HERO_MIN_SP = 18
private const val HERO_MAX_SP = 24

/**
 * Greedy packing of space-separated groups at the minimum size. Returns a single
 * line when the whole text fits (auto-size then grows it), or when the width is
 * unbounded or the text has no groups to split.
 */
internal fun packLines(text: String, measurer: TextMeasurer, style: TextStyle, maxWidthPx: Int): List<String> {
    if (maxWidthPx == Constraints.Infinity || maxWidthPx <= 0) return listOf(text)
    fun fits(candidate: String) = measurer.measure(AnnotatedString(candidate), style).size.width <= maxWidthPx
    if (fits(text)) return listOf(text)
    val groups = text.split(' ').filter { it.isNotEmpty() }
    if (groups.size <= 1) return listOf(text)
    val lines = ArrayList<String>()
    var current = ""
    for (group in groups) {
        val candidate = if (current.isEmpty()) group else "$current $group"
        if (current.isEmpty() || fits(candidate)) {
            current = candidate
        } else {
            lines += current
            current = group
        }
    }
    if (current.isNotEmpty()) lines += current
    return lines
}

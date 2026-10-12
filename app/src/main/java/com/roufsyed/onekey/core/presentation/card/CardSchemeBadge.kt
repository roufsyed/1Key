package com.roufsyed.onekey.core.presentation.card

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.roufsyed.onekey.core.domain.card.CardScheme

/**
 * The scheme mark drawn on the card in place of the chip glyph. Each mark is a
 * simple vector approximation drawn in code (no bundled artwork): the scheme's
 * usual colours and shapes, enough to recognise the network at a glance. A custom
 * scheme shows its own text. Sized like a chip (52 x 32 dp) on a white tile so it
 * reads on every theme.
 */
@Composable
fun CardSchemeBadge(value: String, modifier: Modifier = Modifier) {
    val scheme = remember(value) { CardScheme.fromValue(value) }
    Box(
        modifier = modifier
            .size(width = 52.dp, height = 32.dp)
            .background(Color.White, RoundedCornerShape(6.dp)),
        contentAlignment = Alignment.Center,
    ) {
        when (scheme) {
            CardScheme.VISA -> BadgeText("VISA", VisaBlue, FontWeight.Black, FontStyle.Italic, maxSp = 15)
            CardScheme.MASTERCARD -> MastercardCircles()
            CardScheme.AMERICAN_EXPRESS -> Box(
                modifier = Modifier
                    .size(width = 44.dp, height = 24.dp)
                    .background(AmexBlue, RoundedCornerShape(3.dp)),
                contentAlignment = Alignment.Center,
            ) {
                BadgeText("AMEX", Color.White, FontWeight.Bold, FontStyle.Normal, maxSp = 11, letterSpacingSp = 1f)
            }
            CardScheme.RUPAY -> Row(verticalAlignment = Alignment.CenterVertically) {
                BadgeText("RuPay", RuPayBlue, FontWeight.Bold, FontStyle.Normal, maxSp = 12)
                Spacer(Modifier.width(2.dp))
                RuPayChevrons()
            }
            CardScheme.DINERS_CLUB -> DinersDisc()
            null -> BadgeText(
                text = value.trim().uppercase().take(CUSTOM_MAX_CHARS),
                color = Ink,
                weight = FontWeight.Bold,
                style = FontStyle.Normal,
                maxSp = 11,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

@Composable
private fun BadgeText(
    text: String,
    color: Color,
    weight: FontWeight,
    style: FontStyle,
    maxSp: Int,
    letterSpacingSp: Float = 0.25f,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        color = color,
        fontWeight = weight,
        fontStyle = style,
        letterSpacing = letterSpacingSp.sp,
        maxLines = 1,
        softWrap = false,
        autoSize = TextAutoSize.StepBased(minFontSize = 6.sp, maxFontSize = maxSp.sp, stepSize = 0.5.sp),
        modifier = modifier,
    )
}

/** Two overlapping discs with a blended lens, the shape most people know Mastercard by. */
@Composable
private fun MastercardCircles() {
    Canvas(modifier = Modifier.size(width = 34.dp, height = 20.dp)) {
        val radius = size.height / 2f
        val left = Offset(radius, radius)
        val right = Offset(size.width - radius, radius)
        drawCircle(MastercardRed, radius, left)
        drawCircle(MastercardOrange, radius, right)
        val leftDisc = Path().apply { addOval(Rect(left, radius)) }
        clipPath(leftDisc) { drawCircle(MastercardLens, radius, right) }
    }
}

/** Two slanted strokes, orange then green, after the wordmark. */
@Composable
private fun RuPayChevrons() {
    Canvas(modifier = Modifier.size(width = 9.dp, height = 12.dp)) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.42f
        fun bar(x: Float, color: Color) {
            val path = Path().apply {
                moveTo(x, h)
                lineTo(x + stroke, h)
                lineTo(x + stroke + w * 0.3f, 0f)
                lineTo(x + w * 0.3f, 0f)
                close()
            }
            drawPath(path, color)
        }
        bar(0f, RuPayOrange)
        bar(w * 0.5f, RuPayGreen)
    }
}

/** A blue disc split by a white vertical band. */
@Composable
private fun DinersDisc() {
    Canvas(modifier = Modifier.size(24.dp)) {
        val radius = size.minDimension / 2f
        val center = Offset(size.width / 2f, size.height / 2f)
        drawCircle(DinersBlue, radius, center)
        val bandWidth = radius * 0.55f
        val bandHeight = radius * 1.45f
        drawRoundRect(
            color = Color.White,
            topLeft = Offset(center.x - bandWidth / 2f, center.y - bandHeight / 2f),
            size = Size(bandWidth, bandHeight),
            cornerRadius = CornerRadius(bandWidth / 2f, bandWidth / 2f),
        )
    }
}

private const val CUSTOM_MAX_CHARS = 10

private val Ink = Color(0xFF1A1C24)
private val VisaBlue = Color(0xFF1A1F71)
private val MastercardRed = Color(0xFFEB001B)
private val MastercardOrange = Color(0xFFF79E1B)
private val MastercardLens = Color(0xFFFF5F00)
private val AmexBlue = Color(0xFF016FD0)
private val RuPayBlue = Color(0xFF1B3F95)
private val RuPayOrange = Color(0xFFF26B21)
private val RuPayGreen = Color(0xFF2E8B57)
private val DinersBlue = Color(0xFF0079BE)

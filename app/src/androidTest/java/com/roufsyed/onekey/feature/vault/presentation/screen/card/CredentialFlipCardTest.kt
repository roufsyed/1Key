package com.roufsyed.onekey.feature.vault.presentation.screen.card

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.roufsyed.onekey.core.domain.card.CardFace
import com.roufsyed.onekey.core.domain.card.CardSlot
import com.roufsyed.onekey.core.domain.model.CardTheme
import com.roufsyed.onekey.core.domain.model.CredentialType
import com.roufsyed.onekey.core.domain.model.CustomField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Behavioural locks for [CredentialFlipCard] under a paused clock:
 *
 *  - a tap turns the card monotonically from 0 to 180 degrees, crossing 90 exactly
 *    once, and the state description follows the logical face;
 *  - a second tap mid-flip reverses from the current angle (no queue, no jump);
 *  - digits are never in the semantics tree in any state (so masking is asserted
 *    through the slot descriptions, not through text lookups);
 *  - the face that is not showing contributes no slot nodes.
 *
 * Instrumented tests are compile-only in CI; run on a device by hand.
 */
@RunWith(AndroidJUnit4::class)
class CredentialFlipCardTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val fullNumber = "4111 1111 1111 1111"
    private val model = CardDisplayModel.build(
        type = CredentialType.CREDIT_CARD,
        title = "Visa",
        customFields = listOf(
            CustomField("Cardholder", "Alice Example", false),
            CustomField("Card Number", fullNumber, true),
            CustomField("Expiry", "12/27", false),
            CustomField("CVV", "123", true),
        ),
    )

    private lateinit var flipState: CardFlipState

    private fun setCard(revealed: Set<CardSlot> = emptySet(), initialFace: CardFace = CardFace.FRONT) {
        composeRule.setContent {
            var face by remember { mutableStateOf(initialFace) }
            flipState = rememberCardFlipState("test", face)
            CredentialFlipCard(
                model = model,
                theme = CardTheme.GRAPHITE,
                face = face,
                revealedSlots = revealed,
                showPlaceholders = false,
                onClick = { face = face.other() },
                flipState = flipState,
            )
        }
    }

    @Test
    fun tap_turns_the_card_monotonically_and_lands_on_the_back() {
        composeRule.mainClock.autoAdvance = false
        setCard()
        composeRule.onNode(hasStateDescription("Showing front")).performClick()

        var last = 0f
        var crossings = 0
        repeat(60) {
            composeRule.mainClock.advanceTimeByFrame()
            val value = flipState.rotation.value
            assertTrue("rotation must not run backwards ($last -> $value)", value >= last)
            assertTrue("rotation must stay within 0..180", value in 0f..180f)
            if (last <= 90f && value > 90f) crossings++
            last = value
        }
        composeRule.mainClock.advanceTimeBy(CARD_FLIP_DURATION_MS + 16L)
        assertEquals(180f, flipState.rotation.value, 0.01f)
        assertEquals(1, crossings)
        composeRule.onNode(hasStateDescription("Showing back")).assertExists()
    }

    @Test
    fun second_tap_mid_flip_returns_without_exceeding_the_prior_angle() {
        composeRule.mainClock.autoAdvance = false
        setCard()
        composeRule.onNode(hasStateDescription("Showing front")).performClick()
        repeat(8) { composeRule.mainClock.advanceTimeByFrame() }
        val turned = flipState.rotation.value
        assertTrue("expected the card to be mid-flip", turned > 0f && turned < 180f)

        composeRule.onNode(hasStateDescription("Showing back")).performClick()
        repeat(40) {
            composeRule.mainClock.advanceTimeByFrame()
            assertTrue(flipState.rotation.value <= turned + 0.01f)
        }
        composeRule.mainClock.advanceTimeBy(CARD_FLIP_DURATION_MS + 16L)
        assertEquals(0f, flipState.rotation.value, 0.01f)
        composeRule.onNode(hasStateDescription("Showing front")).assertExists()
    }

    @Test
    fun digits_never_enter_the_semantics_tree_and_descriptions_follow_masking() {
        setCard()
        composeRule.onAllNodesWithText(fullNumber).assertCountEquals(0)
        composeRule.onAllNodesWithText("4111").assertCountEquals(0)
        composeRule.onNodeWithContentDescription("Card Number, hidden, ends in 1111").assertExists()
        composeRule.onNodeWithContentDescription("Name on Card, Alice Example").assertExists()
    }

    @Test
    fun revealed_number_is_still_hidden_from_accessibility_services() {
        setCard(revealed = setOf(CardSlot.CARD_NUMBER))
        composeRule.onAllNodesWithText(fullNumber).assertCountEquals(0)
        composeRule.onNodeWithContentDescription("Card Number revealed, hidden from screen readers. Use Copy.").assertExists()
    }

    @Test
    fun the_hidden_face_contributes_no_slot_nodes() {
        setCard()
        composeRule.onAllNodesWithContentDescription("CVV, hidden").assertCountEquals(0)

        composeRule.onNode(hasStateDescription("Showing front")).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("CVV, hidden").assertExists()
        composeRule.onAllNodesWithContentDescription("Card Number, hidden, ends in 1111").assertCountEquals(0)
    }
}

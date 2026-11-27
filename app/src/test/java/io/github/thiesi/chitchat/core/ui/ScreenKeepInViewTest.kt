package io.github.thiesi.chitchat.core.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** B (#351, P-78): the screen's keep-in-view rule, in the viewport's coordinates (0 is where the view is now). */
class ScreenKeepInViewTest {
    private val margin = 16f
    private val band = 32f
    private fun distance(top: Float, bottom: Float, v: Float, kept: ClosedFloatingPointRange<Float> = (top + band)..bottom) =
        keepInViewDistance(top, bottom, kept, v, margin, band)

    @Test fun aFieldAlreadyClearDoesNotMove() {
        assertEquals(0f, distance(top = 40f, bottom = 200f, v = 600f))
        assertEquals("exactly at the margin and the bottom edge", 0f, distance(top = 16f, bottom = 600f, v = 600f))
    }

    @Test fun theKeyboardCoveringTheBottomMovesExactlyTheCoveredAmount() {
        // Each frame of the slide shrinks the viewport a little: the field rides the edge, one covered amount at a time.
        assertEquals(10f, distance(top = 300f, bottom = 460f, v = 450f))
        assertEquals(25f, distance(top = 290f, bottom = 450f, v = 425f))
    }

    @Test fun aClippedLabelComesBackWithTheMargin() {
        // The label's top 14px above the edge (the #351 clip): it comes down to the margin, no further.
        assertEquals(-30f, distance(top = -14f, bottom = 146f, v = 600f))
    }

    @Test fun withoutRoomForTheMarginTheFieldStaysWhole() {
        // 160px field, 170px view: the margin gives way, the field (with its label) is whole.
        assertEquals(-4f, distance(top = -4f, bottom = 156f, v = 170f))
        assertEquals(6f, distance(top = 10f, bottom = 176f, v = 170f))
    }

    @Test fun aFieldTallerThanTheViewKeepsItsTextLineAndTheLabelInOrOut() {
        // 200px field in a 120px view, its label half cut (top -10): the kept line [22, 110] is already in. Bringing the
        // label in would push the line's bottom out, so the label rule takes the label wholly out (top at -32), never half.
        val moved = distance(top = -10f, bottom = 190f, v = 120f, kept = 22f..110f)
        assertEquals(22f, moved)
        val labelTop = -10f - moved
        assertEquals("the label entirely out", true, labelTop <= -band)
    }

    @Test fun aRequestedCaretInsideAFieldThatFitsLandsByTheFieldRule() {
        // A focus move (steady view) asking for the caret line only: the field comes in with its label and margin.
        assertEquals(-20f, distance(top = -4f, bottom = 156f, v = 600f, kept = 40f..80f))
        assertEquals(56f, distance(top = 500f, bottom = 656f, v = 600f, kept = 540f..580f))
    }
}

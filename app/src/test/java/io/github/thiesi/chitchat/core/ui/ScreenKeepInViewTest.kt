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

    @Test fun theKeyboardCoveringTheBottomMarginMovesExactlyTheCoveredAmount() {
        // Each frame of the slide shrinks the viewport a little: the field rides the edge at the margin, one covered amount
        // at a time (tier 1: 160 + 2 × 16 fits).
        assertEquals(26f, distance(top = 300f, bottom = 460f, v = 450f))
        assertEquals(41f, distance(top = 290f, bottom = 450f, v = 425f))
        assertEquals("clear of the bottom margin: no move", 0f, distance(top = 200f, bottom = 360f, v = 400f))
    }

    @Test fun theTiersInOrderAtTheirBoundaries() {
        // margin 16, viewport 200; every field's label at 20 below the top edge.
        assertEquals("tier 1 exactly (168 + 32): both margins", 4f, distance(top = 20f, bottom = 188f, v = 200f))
        assertEquals("tier 2 (169): the top margin holds, the bottom inside, no move", 0f, distance(top = 20f, bottom = 189f, v = 200f))
        assertEquals("tier 2 exactly (184 + 16): the bottom flush", 4f, distance(top = 20f, bottom = 204f, v = 200f))
        assertEquals("tier 3 (185): whole, the margin gives", 5f, distance(top = 20f, bottom = 205f, v = 200f))
        assertEquals("tier 3 exactly (200): whole", 20f, distance(top = 20f, bottom = 220f, v = 200f))
        // tier 4 (201): the kept line [52, 100] stays, the label goes wholly out (top at -32), never half.
        val moved = distance(top = 20f, bottom = 221f, v = 200f, kept = 52f..100f)
        assertEquals(true, 20f - moved <= -band || 20f - moved >= 16f)
    }

    @Test fun thePasswordFieldLandsAtTheBottomMarginNotFlush() {
        // The recording (diag run 45969824617): Password 88 px reached by Next in a 293 px viewport from below, at
        // 300..388. Tier 1 with the 8dp (8 px at density 1) margin: its bottom 8 px above the IME, at 197..285.
        assertEquals(103f, keepInViewDistance(300f, 388f, 332f..380f, 293f, 8f, 32f))
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

    // The 200% Create Username field before the keyboard (#351 fix B diag run 45969824617, density 1): the label's top at
    // the viewport's edge, the field 284 px (with its supporting line) in a 262 px viewport, the label's line 32 px, the
    // first text line 32..80 px from the label's top (half the label's line, 16dp of content padding, bodyLarge's 48 px).
    private val firstLine = 32f..80f
    private fun create(top: Float) = androidx.compose.ui.geometry.Rect(0f, top, 0f, top + 284f)
    private fun caretRequest(field: androidx.compose.ui.geometry.Rect, memory: CaretMemory) =
        requestDistance(field, field.top + 32f, 48f, 262f, 8f, band, firstLine, memory)
    private fun boxRequest(field: androidx.compose.ui.geometry.Rect, memory: CaretMemory) =
        requestDistance(field, field.top + band / 2f, field.height - band / 2f, 262f, 8f, band, firstLine, memory)

    @Test fun bothRequestsOfATapGiveOneTargetInEitherOrderAndMoveOnce() {
        // Caret, then box: the caret request takes the label in (8 px, the margin); the box request, read after that move,
        // keeps the remembered caret line and doesn't move (diag run 45969824617 went on to align the box's top: 390→412).
        val caretFirst = CaretMemory()
        assertEquals(-8f, caretRequest(create(0f), caretFirst))
        assertEquals("the box after the caret", 0f, boxRequest(create(8f), caretFirst))
        // Box, then caret: no caret known yet, so the first line is kept, the same target; the caret request then agrees.
        val boxFirst = CaretMemory()
        assertEquals(-8f, boxRequest(create(0f), boxFirst))
        assertEquals("the caret after the box", 0f, caretRequest(create(8f), boxFirst))
        // Box only.
        assertEquals(-8f, boxRequest(create(0f), CaretMemory()))
    }

    @Test fun aRequestsExtentIsNeverWhatsKept() {
        // A box-sized request (an accessibility service's, during a text session) keeps the caret's line, not the box: the
        // field can't fit, so the text line wins and the label comes in with its margin, never the box's top aligned.
        val memory = CaretMemory().apply { line = 32f..80f }
        assertEquals(-8f, boxRequest(create(0f), memory))
        // A caret on a later line is remembered and kept: its line comes in, then the label rule.
        val lower = CaretMemory()
        val moved = requestDistance(create(0f), 230f, 48f, 262f, 8f, band, firstLine, lower)
        assertEquals(230f..278f, lower.line)
        assertEquals("the caret's line inside", true, 230f - moved >= 0f && 278f - moved <= 262f)
    }

    @Test fun aRequestedCaretInsideAFieldThatFitsLandsByTheFieldRule() {
        // A focus move (steady view) asking for the caret line only: the field comes in with its label and margin.
        assertEquals(-20f, distance(top = -4f, bottom = 156f, v = 600f, kept = 40f..80f))
        assertEquals(72f, distance(top = 500f, bottom = 656f, v = 600f, kept = 540f..580f))
    }

    @Test fun aHeightChangeIsTakenOnceByWhicheverPathComesFirst() {
        val claim = HeightClaim()
        assertEquals("the first layout", -1, claim.take(262))
        assertEquals("the placement pass takes the change", 262, claim.take(293))
        assertEquals("the fallback after it doesn't apply again", null, claim.take(293))
        assertEquals("a later change is new", 293, claim.take(262))
    }

    @Test fun theCachedPlaceIsUsedOnlyWhenTheContentWasntRemeasured() {
        val field = Any()
        val cached = FieldInContent(field, androidx.compose.ui.geometry.Rect(24f, 400f, 296f, 684f), contentHeight = 1460, width = 320)
        assertEquals("a viewport-only change: valid", true, cachedFieldValid(cached, field, contentHeight = 1460, width = 320))
        assertEquals("the content re-measured taller (text re-wrapped, content arrived): defer", false,
            cachedFieldValid(cached, field, contentHeight = 1500, width = 320))
        assertEquals("a width change (rotation): defer", false, cachedFieldValid(cached, field, contentHeight = 1460, width = 640))
        assertEquals("another focused field: defer", false, cachedFieldValid(cached, Any(), contentHeight = 1460, width = 320))
        assertEquals("nothing cached yet: defer", false, cachedFieldValid(null, field, contentHeight = 1460, width = 320))
    }
}

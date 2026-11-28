package io.github.thiesi.chitchat.core.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #378 failure 3 (TL, PL): during a keyboard slide the caret line moves only with the slide, the label is whole or out and
 * changes state at most once, the end gets the full rule. Inputs from diag run 46066588478 (report form, 200%): the label
 * node 1198..1312, the field 1324..1470, its caret line 1340..1378, the counter span to 1522; margin 8.
 */
class FormDialogSlideTest {
    private val margin = 8f
    private val labelTop = 1198f; private val labelBottom = 1312f

    private fun monotone(xs: List<Float>, arriving: Boolean) =
        xs.zipWithNext().all { (a, b) -> if (arriving) b >= a - 1f else b <= a + 1f }

    private fun labelState(t: Float) = when {
        labelBottom - t <= 0f -> "out"; labelTop - t >= margin -> "whole"; else -> "HALF" }

    @Test fun anArrivalEndingInItsOwnModeTakesTheEndsTargetInEveryFrame() {
        // The logged pinned frames 256 -> 201 -> 165, ending pinned at 165: the end's full rule is 1312 (label out, the
        // span not fitting). Every frame gives 1312: nothing to move back from, the end needs no exception.
        val frames = listOf(256f, 201f, 165f).map {
            sameModeFrameTarget(1312f, 1340f, 1378f, it, labelTop, labelBottom, margin, maxValue = 1700) }
        assertEquals(listOf(1312f, 1312f, 1312f), frames)
        assertTrue(frames.all { labelState(it) == "out" })
    }

    @Test fun theFramesBeforeASwapAreClampedToTheSlide() {
        // The same frames with a swap ahead (per-frame rule: 1312, 1321, 1312, the span fitting only at 201): clamped to
        // 1312, 1321, 1321; at 1321 for v 165 the label stays out and the caret line is in view (19..57 of 165).
        val c = SlideClamp()
        val out = listOf(256f to 1312f, 201f to 1321f, 165f to 1312f).map { (v, raw) ->
            c.apply(raw, pinned = true, arriving = true, caretTop = 1340f, caretBottom = 1378f, v = v,
                labelTop = labelTop, labelBottom = labelBottom, margin = margin) }
        assertEquals(listOf(1312f, 1321f, 1321f), out)
        assertEquals("out", labelState(1321f))
        assertTrue(1340f - 1321f >= 0f && 1378f - 1321f <= 165f)
    }

    @Test fun textFirstsHideFallsToTheEndsTarget() {
        // T_end 2944 (the caret line 3126..3164), the logged pinned viewports after the swap: 2963, 2944, 2944, 2944
        // (today's per-frame rule: 3031, 2984, 2957, 2944). The counter is off screen mid-slide (PR text).
        val frames = listOf(201f, 248f, 275f, 288f).map {
            sameModeFrameTarget(2944f, 3126f, 3164f, it, labelTop, labelBottom, margin, maxValue = 3339) }
        assertEquals(listOf(2963f, 2944f, 2944f, 2944f), frames)
        assertTrue(monotone(frames, arriving = false))
    }

    @Test fun aHideStartingBelowTheSpanStaysAtTheEndsTarget() {
        // The residual case: a short field, T_end 1312 (label out) at v 288; frames from 136 grow past the span's 182.
        val frames = listOf(136f, 165f, 201f, 250f, 288f).map {
            sameModeFrameTarget(1312f, 1340f, 1378f, it, labelTop, labelBottom, margin, maxValue = 1700) }
        assertTrue(frames.all { it == 1312f })
    }

    @Test fun aHideEndingWithTheLabelWholeChangesItsStateOnce() {
        // T_end 1150 (label whole), the caret line's bottom at 1500: the floor, then the label's bottom (a plateau while
        // the floor is in the half band), then one step to whole; monotone, out -> whole once, never half.
        val frames = listOf(150f, 200f, 250f, 310f, 400f).map {
            sameModeFrameTarget(1150f, 1462f, 1500f, it, labelTop, labelBottom, margin, maxValue = 2000) }
        assertEquals(listOf(1350f, 1312f, 1312f, 1190f, 1150f), frames)
        assertTrue(monotone(frames, arriving = false))
        val states = frames.map { labelState(it) }
        assertFalse(states.contains("HALF"))
        assertEquals(1, states.zipWithNext().count { (a, b) -> a != b })
    }

    @Test fun theScrollRangeLandingHalfIsLoweredToWholeWhenTheCaretLineStaysInView() {
        // maxValue 1250 is in the half band (1190, 1312): lowered to 1190 (whole) when the caret line's bottom stays in
        // view (1500 - 1190 = 310 <= 320); otherwise the range's end, half, as the end frame itself settles there (the
        // pre-existing label/maxValue gap, its own PR: whole with the kept range cut to the caret line).
        assertEquals(1190f, sameModeFrameTarget(1312f, 1462f, 1500f, 320f, labelTop, labelBottom, margin, maxValue = 1250))
        assertEquals("the pre-existing v1 gap: the end settles at maxValue too", 1250f,
            sameModeFrameTarget(1312f, 1462f, 1500f, 300f, labelTop, labelBottom, margin, maxValue = 1250))
    }

    @Test fun theClampsCaretFloorWinsOverTheClamp() {
        // Arrival, last 1400, raw 1320 with the caret line's top at 1340: the clamp (1400) would push it out; raw wins.
        val c = SlideClamp()
        c.apply(1400f, true, true, null, null, 300f, labelTop, labelBottom, margin)
        assertEquals(1320f, c.apply(1320f, true, true, 1340f, 1378f, 300f, labelTop, labelBottom, margin))
    }

    @Test fun aHalfLabelInAClampedFrameMovesWithTheSlide() {
        // Arrival: last 1250 (half), raw 1240: clamped 1250 is half -> out (1312), the caret line still in view.
        val c = SlideClamp()
        c.apply(1250f, true, true, null, null, 300f, 2000f, 2001f, margin)
        assertEquals(1312f, c.apply(1240f, true, true, 1340f, 1378f, 300f, labelTop, labelBottom, margin))
    }

    @Test fun theClampResets() {
        val c = SlideClamp()
        fun step(raw: Float, pinned: Boolean = true, arriving: Boolean = true) =
            c.apply(raw, pinned, arriving, null, null, 400f, 0f, 1f, margin)
        step(1321f)
        assertEquals("a mode switch", 1300f, step(1300f, pinned = false))
        step(1400f, pinned = false)
        assertEquals("a direction flip without a settle (the 244/0/0 shape)", 1350f, step(1350f, pinned = false, arriving = false))
        c.reset() // settle, or a re-anchor (an edit isn't slide motion)
        assertEquals("after a reset", 1200f, step(1200f, pinned = false, arriving = true))
        // A held pass claims nothing and never reaches the clamp, so it records nothing: the next frame starts from it.
        assertEquals(1210f, step(1210f, pinned = false, arriving = true))
    }

    @Test fun theEndLayoutFromTheImeTarget() {
        // Keyboard-first's arrival (logged): maxHeight 505 at ime 111, target 259, the navigation bar 24, title and buttons
        // 216, chrome 88: the end maxHeight is 357, available 53 < 120: one column, viewport min(357, outer content).
        val swap = endLayout(505f, 24f, 111f, 259f, 88f, 216f, 120f, 3540f, 3842f)
        assertFalse(swap.pinned); assertEquals(357f, swap.viewport)
        // A taller window (maxHeight 800 at ime 100): the end maxHeight 641, available 337: pinned, viewport min(337, body).
        val stays = endLayout(800f, 24f, 100f, 259f, 88f, 216f, 120f, 3540f, 3842f)
        assertTrue(stays.pinned); assertEquals(337f, stays.viewport)
        // A hide (target 0): the end is the full height less the navigation bar.
        val hide = endLayout(357f, 24f, 259f, 0f, 88f, 216f, 120f, 3540f, 3842f)
        assertTrue(hide.pinned); assertEquals(288f, hide.viewport)
    }
}

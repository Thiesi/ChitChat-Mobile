package io.github.thiesi.chitchat.core.ui

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #378 (b), TL and PL: one baseline per keyboard transition. Sequences replayed from diag run 46043589704's logged
 * placements of the dialog window's IME inset, Compose's animation source and target, and the layout key's height (dp).
 */
class KeyboardTransitionBaselineTest {
    private class Row(val ime: Int, val source: Int, val target: Int, val key: Int)

    /** Replays [rows]; each placement's record afterwards is "r<n>" (the place that pass laid out). */
    private fun replay(t: KeyboardTransitionBaseline<String>, rows: List<Row>, start: String, check: (Int, Row) -> Unit = { _, _ -> }) {
        var latest = start
        rows.forEachIndexed { i, r ->
            t.observe(r.ime, r.source, r.target, r.key) { latest }
            check(i, r)
            latest = "r$i"
        }
    }

    @Test fun theApi36NoticeReversalKeepsThePlaceFromBeforeAnyKeyboardChange() {
        // API36 notice-on, the re-tap's show: a hide request mid-show (target 0 while the inset is 244, then 257), then a
        // fresh show from 0. Unsettled throughout; settled only at 259/259/259.
        val rows = listOf(Row(0, 0, 259, 592), Row(149, 0, 259, 467), Row(244, 0, 0, 372), Row(257, 0, 0, 359),
            Row(0, 0, 259, 592), Row(11, 0, 259, 592), Row(111, 0, 259, 505), Row(174, 0, 259, 442), Row(210, 0, 259, 406),
            Row(244, 0, 259, 372), Row(253, 0, 259, 363), Row(257, 0, 259, 359), Row(258, 0, 259, 358), Row(259, 259, 259, 357))
        val t = KeyboardTransitionBaseline<String>()
        t.observe(0, 0, 0, 592) { "settled" }
        assertFalse(t.applies(592))
        replay(t, rows, start = "before") { i, r ->
            assertTrue("row $i applies", t.applies(r.key))
            assertEquals("row $i keeps the place from before the keyboard", "before", t.baseline())
            assertEquals("row $i: settled only at the end", i < rows.lastIndex, t.holding)
        }
        // The final layout (357) still reads it; another key afterwards releases.
        assertTrue(t.applies(357))
        t.observe(259, 259, 259, 357) { "later" }
        assertTrue("a repeat placement of the final layout", t.applies(357))
        t.observe(259, 259, 259, 360) { "later" }
        assertFalse(t.applies(360)); assertNull(t.baseline())
    }

    @Test fun animationsOffAChangeIsOneUnsettledPlacementThenTheFinalOne() {
        // keyboard-first off: the old inset with the new target, then settled at the new layout.
        val t = KeyboardTransitionBaseline<String>()
        t.observe(0, 0, 0, 592) { "pre" }
        t.observe(0, 0, 259, 592) { "pre" }
        assertTrue(t.holding); assertEquals("pre", t.baseline())
        t.observe(259, 259, 259, 357) { "r0" }
        assertFalse(t.holding)
        assertTrue("the final layout reads the record from before the change", t.applies(357))
        assertEquals("pre", t.baseline())
    }

    @Test fun neverUnsettledNeverFreezes() {
        // API < 30, or no animation reported: inset = source = target throughout; keys change, nothing is held.
        val t = KeyboardTransitionBaseline<String>()
        for ((i, key) in listOf(592, 560, 467, 357).withIndex()) {
            t.observe(i * 80, i * 80, i * 80, key) { "r$i" }
            assertFalse(t.applies(key)); assertNull(t.baseline())
        }
    }

    @Test fun aSettledPlacementAlwaysEndsTheTransition() {
        // The release can't stick: whatever came before, ime = source = target ends holding (Compose's state whenever no
        // IME animation runs), including after a reversal and at an inset that isn't the original target.
        val unsettled = listOf(Row(0, 0, 259, 592), Row(244, 0, 0, 372), Row(100, 259, 0, 500), Row(259, 0, 259, 357))
        for (u in unsettled) for (end in listOf(0, 120, 259)) {
            val t = KeyboardTransitionBaseline<String>()
            t.observe(u.ime, u.source, u.target, u.key) { "pre" }
            assertTrue(t.holding)
            t.observe(end, end, end, 999) { "x" }
            assertFalse("released after $end/$end/$end", t.holding)
            t.observe(end, end, end, 998) { "x" }
            assertFalse(t.applies(998)); assertNull(t.baseline())
        }
    }

    @Test fun anEditDuringATransitionReBaselines() {
        val t = KeyboardTransitionBaseline<String>()
        t.observe(0, 0, 259, 592) { "pre" }
        t.observe(100, 0, 259, 500) { "r0" }
        t.rebaseline { "edited" }
        assertEquals("edited", t.baseline())
        t.observe(259, 259, 259, 357) { "r1" }
        assertEquals("edited", t.baseline())
        // Outside a transition an edit changes nothing here (today's path reads its own records).
        t.observe(259, 259, 259, 358) { "r2" }
        t.rebaseline { "again" }
        assertNull(t.baseline())
    }

    @Test fun aSelectionChangeInTheShowsFirstFrameIsRestoredOnceNotTwice() {
        // Finding 2, diag run 46056107630 (v1, API36 text-first's second arrival): the tap moved the caret one line
        // (3126 -> 3050 in the body) in the frame the show began; the frozen baseline had the caret 182 px down the viewport.
        // One column, the body 136 px down; the viewport 422 px; the caret line 38 px.
        val shift = 136f; val v = 422f; val h = 38f
        val stale = Rect(16f, 3126f, 18f, 3164f); val live = Rect(16f, 3050f, 18f, 3088f)
        // Before: the placement restore used the stale caret (3080), the after-a-frame step the live one (3004): two moves.
        assertEquals(3080f, keepCaretOffset(stale.top + shift, 182f, h, v))
        assertEquals(3004f, keepCaretOffset(live.top + shift, 182f, h, v))
        // Now: the live caret, and the baseline's caret moved by the same distance; both steps agree on 3080.
        val dy = caretShift(stale, live)
        assertEquals(-76f, dy)
        val t = KeyboardTransitionBaseline<Float>()
        t.observe(0, 0, 259, 592) { 182f }
        t.replace(t.baseline()!! + dy)
        assertEquals(106f, t.baseline())
        assertEquals("the placement restore", 3080f, keepCaretOffset(live.top + shift, t.baseline()!!, h, v))
        assertEquals("the after-a-frame step finds nothing to move", 3080f, keepCaretOffset(live.top + shift, t.baseline()!!, h, v))
        assertEquals("no caret known: no shift", 0f, caretShift(null, live))
    }

    @Test fun releaseIsTodaysPath() {
        // The holder releases for a field change or no record before the transition.
        val t = KeyboardTransitionBaseline<String>()
        t.observe(0, 0, 259, 592) { "pre" }
        t.release()
        assertFalse(t.holding); assertFalse(t.applies(592)); assertNull(t.baseline())
        // Still unsettled: the next placement freezes again, from the place then.
        t.observe(50, 0, 259, 560) { "now" }
        assertEquals("now", t.baseline())
    }
}

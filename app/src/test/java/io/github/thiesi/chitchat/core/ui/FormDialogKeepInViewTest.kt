package io.github.thiesi.chitchat.core.ui

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A2 (TL): the keep-in-view priority is the caret's line first, then the last-line span (caret line to field bottom and
 * counter) when it fits, then the label rule (label in or entirely out, never half). Pure geometry, in content
 * coordinates; a target is the scroll value (the viewport's top in the content).
 */
class FormDialogKeepInViewTest {
    private val margin = 8f
    private val band = 16f

    /** The label can't come in without pushing the fitting span out, so it goes out instead. */
    @Test fun theLabelGoesOutRatherThanPushTheCounterOut() {
        val v = 100f
        val box = Rect(0f, 1000f, 300f, 1080f)        // the field
        val caret = Rect(16f, 1050f, 18f, 1064f)      // its last line
        val counter = Rect(0f, 1088f, 300f, 1100f)    // the trailing line
        // The span is 1050..1100 (50px), which fits; at target 1000 the field's top sits at 0 (inside the label band).
        val spanned = lastLineSpan(1000f, box, caret, counter, v, margin)
        val kept = keptRange(box, caret, counter, v, margin)
        assertEquals(1050f..1108f, kept) // the span plus its bottom margin fits (58 of 100)
        val target = labelRule(spanned, box, kept, v, margin, band)
        // Label in (target 992) would leave the counter's bottom at 108 > 100; the label goes out (target 1016) instead.
        assertEquals(1016f, target)
        assertEquals(true, counter.bottom - target <= v && caret.top - target >= 0f)
        assertEquals(true, box.top - target <= -band) // entirely out, never half
    }

    /** When everything fits, the label comes in with its margin and the span stays in view. */
    @Test fun theLabelComesInWhenTheSpanStillFits() {
        val v = 200f
        val box = Rect(0f, 1000f, 300f, 1080f)
        val caret = Rect(16f, 1050f, 18f, 1064f)
        val counter = Rect(0f, 1088f, 300f, 1100f)
        val kept = keptRange(box, caret, counter, v, margin)
        val target = labelRule(1000f, box, kept, v, margin, band)
        assertEquals(992f, target)
        assertEquals(true, counter.bottom - target <= v)
    }

    /** A span that doesn't fit isn't kept: the caret's line wins, and the label rule only protects the caret. */
    @Test fun aSpanThatDoesntFitLeavesTheCaretLineWinning() {
        val v = 40f
        val box = Rect(0f, 1000f, 300f, 1080f)
        val caret = Rect(16f, 1050f, 18f, 1064f)
        val counter = Rect(0f, 1088f, 300f, 1100f)
        assertEquals(caret.top..caret.bottom, keptRange(box, caret, counter, v, margin))
        assertEquals(1030f, lastLineSpan(1030f, box, caret, counter, v, margin)) // unchanged: the span doesn't fit
    }

    /** Without a trailing line (the caret not on the last line, or no counter), the span changes nothing. */
    @Test fun noTrailingLineChangesNothing() {
        val box = Rect(0f, 1000f, 300f, 1080f)
        val caret = Rect(16f, 1020f, 18f, 1034f)
        assertEquals(1000f, lastLineSpan(1000f, box, caret, null, 100f, margin))
        assertEquals(caret.top..caret.bottom, keptRange(box, caret, null, 100f, margin))
    }

    /** (e) From below with room: the span ends exactly [margin] above the viewport's bottom edge. */
    @Test fun fromBelowTheSpanEndsTheMarginAboveTheEdge() {
        val v = 100f
        val box = Rect(0f, 1000f, 300f, 1080f)
        val caret = Rect(16f, 1050f, 18f, 1064f)
        val counter = Rect(0f, 1088f, 300f, 1100f)
        val target = lastLineSpan(900f, box, caret, counter, v, margin)
        assertEquals(1008f, target)
        assertEquals(v - margin, counter.bottom - target)
    }

    /** (f) Room for the span but not its margin: the margin gives first, and the span ends flush. */
    @Test fun withoutRoomForTheMarginTheSpanEndsFlush() {
        val v = 55f
        val box = Rect(0f, 1000f, 300f, 1080f)
        val caret = Rect(16f, 1050f, 18f, 1064f)
        val counter = Rect(0f, 1088f, 300f, 1100f)
        val target = lastLineSpan(900f, box, caret, counter, v, margin)
        assertEquals(1045f, target)
        assertEquals(v, counter.bottom - target)
        assertEquals(caret.top..counter.bottom, keptRange(box, caret, counter, v, margin))
    }

    /** (g) The label can't come in without eating the bottom margin, so it goes out (the margin ranks above it). */
    @Test fun theLabelGoesOutRatherThanEatTheBottomMargin() {
        val v = 110f
        val box = Rect(0f, 1000f, 300f, 1080f)
        val caret = Rect(16f, 1050f, 18f, 1064f)
        val counter = Rect(0f, 1088f, 300f, 1100f)
        val spanned = lastLineSpan(1000f, box, caret, counter, v, margin) // 1100 <= 1000 + 110 - 8: already clear
        assertEquals(1000f, spanned)
        // Label-in (992) would keep the span (1100 - 992 = 108 <= 110) but leave only 2 of the 8dp margin.
        val target = labelRule(spanned, box, keptRange(box, caret, counter, v, margin), v, margin, band)
        assertEquals(1016f, target)
        assertEquals(true, v - (counter.bottom - target) >= margin && box.top - target <= -band)
    }
}

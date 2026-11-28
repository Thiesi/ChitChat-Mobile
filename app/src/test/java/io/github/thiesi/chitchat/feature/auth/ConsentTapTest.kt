package io.github.thiesi.chitchat.feature.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #390 (owner's amended rule; PL placement): the consent tap decision, in px. The measured 200% short-window numbers
 * (#372 probe (d), run 46156251647, density 1): the line 168 px tall, the viewport 148 px; 12dp = 12, 48dp = 48, 8dp = 8.
 */
class ConsentTapTest {
    private val gap = 12f; private val button = 48f; private val margin = 8f

    private fun tap(top: Float, height: Float, viewport: Float, reviewed: Boolean = false, firstLine: Float = 42f) =
        consentTap(top, height, firstLine, viewport, reviewed, gap, button, margin)

    @Test fun theThresholdLineTwelveAndFortyEightEqualToTheViewportFitsOnePixelMoreDoesnt() {
        // 100 + 12 + 48 = 160: a 160 px viewport is the fits branch (the line visible: submit; not visible: #275's bring).
        assertEquals(ConsentTap.Submit, tap(top = 10f, height = 100f, viewport = 160f))
        assertEquals(ConsentTap.BringFits, tap(top = -1f, height = 100f, viewport = 160f))
        // 1 px less viewport (or 1 px more line): can't fit, the first tap brings the top.
        assertTrue(tap(top = 10f, height = 100f, viewport = 159f) is ConsentTap.BringTop)
        assertTrue(tap(top = 10f, height = 101f, viewport = 160f) is ConsentTap.BringTop)
    }

    @Test fun theFirstTapLandsTheTopEightBelowTheViewportTopOrFlushWhenTheFirstLineWouldntBeWhole() {
        // The measured case: line 168, viewport 148, first line 42 (8 + 42 <= 148): the top to 8.
        assertEquals(ConsentTap.BringTop(-10f - 8f), tap(top = -10f, height = 168f, viewport = 148f))
        assertEquals(ConsentTap.BringTop(120f - 8f), tap(top = 120f, height = 168f, viewport = 148f))
        // The edge: 8 + first line == viewport keeps the 8; one more px and the top goes flush.
        assertEquals(ConsentTap.BringTop(50f - 8f), tap(top = 50f, height = 300f, viewport = 148f, firstLine = 140f))
        assertEquals(ConsentTap.BringTop(50f), tap(top = 50f, height = 300f, viewport = 148f, firstLine = 141f))
    }

    @Test fun onceReviewedAnyTapSubmits() {
        assertEquals(ConsentTap.Submit, tap(top = -10f, height = 168f, viewport = 148f, reviewed = true))
        assertEquals(ConsentTap.Submit, tap(top = 500f, height = 168f, viewport = 148f, reviewed = true))
    }

    @Test fun reviewedDoesntChangeTheFitsBranch() {
        assertEquals(ConsentTap.BringFits, tap(top = -1f, height = 100f, viewport = 160f, reviewed = true))
    }

    /**
     * TL: the first tap's target is inside the scroll range, with the measured numbers. Below the line come the 12dp gap,
     * the 48dp Create and the scroller's 24dp bottom padding, so the content ends 84 px below the line's bottom. With the
     * line's top at t (viewport px) and the scroll at s: the range's end allows s_max − s = (t + 168 + 84) − 148, and the
     * distance needed is t − 8, which is within it for every t (−8 ≤ 104). The range's start: s + t − 8 ≥ 0 holds since the
     * line's top sits far below the content's top (the form above it: the logged content position is in the hundreds).
     */
    @Test fun theFirstTapTargetIsInsideTheScrollRange() {
        val height = 168f; val viewport = 148f; val below = gap + button + 24f
        for (t in listOf(-200f, -10f, 0f, 60f, 140f, 400f)) {
            val distance = (tap(top = t, height = height, viewport = viewport) as ConsentTap.BringTop).distance
            val roomToEnd = (t + height + below) - viewport
            assertTrue("t=$t: distance $distance within the range's end ($roomToEnd)", distance <= roomToEnd)
        }
    }
}

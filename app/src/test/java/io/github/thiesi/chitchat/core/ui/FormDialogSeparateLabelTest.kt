package io.github.thiesi.chitchat.core.ui

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #378 (TL, PL): the label rule keeps a field's separate label (a Text above the field, every wrapped line) whole with
 * 8dp above it, or entirely out, never half. Inputs from diag run 46037971164's keyboard-first portrait 200% case, where
 * the band rule left the report form's 3-line label half in at 1402: the field's box, its caret line and the counter in
 * content coordinates; the label's node measured by pixels (base run 46031154545, f00108) from 127 to 13 px above the
 * field's top. Targets are scroll values (the viewport's top in the content).
 */
class FormDialogSeparateLabelTest {
    private val margin = 8f
    private val band = 32f

    // One column (the outer scroller, the body 136 px down): viewport 357.
    private val box = Rect(24f, 1460f, 296f, 1606f)
    private val caret = Rect(40f, 1476f, 42f, 1514f)
    private val counter = Rect(24f, 1618f, 296f, 1650f)
    private val label = Rect(24f, 1333f, 296f, 1447f)

    private fun extent(l: Rect = label) = labelExtent(box, l, band)
    private fun kept(v: Float, b: Rect = box, c: Rect = caret, t: Rect = counter) = keptRange(b, c, t, v, margin)

    @Test fun theBandRuleSawTheHalfLabelAsWhole() {
        // What #381's first head did: the band at the field's top edge is 58 px down, "whole"; the real label is half in.
        assertEquals(1402f, labelRule(1402f, box, kept(357f), 357f, margin, band))
        assertTrue(label.top - 1402f < 0f && label.bottom - 1402f > 0f)
    }

    @Test fun whole() {
        // The off control's end: the label's top 15 px down, at least the margin: nothing moves.
        assertEquals(1318f, labelRule(1318f, extent(), kept(357f), 357f, margin))
    }

    @Test fun out() {
        // #376's text-first end: the label's bottom 5 px above the top: entirely out, nothing moves.
        assertEquals(1452f, labelRule(1452f, extent(), kept(357f), 357f, margin))
    }

    @Test fun upWhenTheKeptRangeStillFits() {
        // At 1402 the label is half in (top -69, bottom 45). Up by the minimum: its top to 8 px, target 1325; the kept
        // range (caret line to the counter plus its margin, 1476..1658) stays wholly in the 357 px viewport.
        val target = labelRule(1402f, extent(), kept(357f), 357f, margin)
        assertEquals(1325f, target)
        assertEquals(margin, label.top - target)
        assertTrue(kept(357f).endInclusive - target <= 357f)
    }

    @Test fun downWhenUpWouldPushTheKeptRangeOut() {
        // The pinned intermediate frame (inner scroller, no body shift): viewport 256, target 1266, the label half in.
        val b = box.translate(0f, -136f); val c = caret.translate(0f, -136f); val t = counter.translate(0f, -136f)
        val l = label.translate(0f, -136f)
        val k = kept(256f, b, c, t)
        // Up (target 1189) would leave the kept range's bottom (1522) at 333 > 256; down by the minimum instead: the
        // label's bottom to the top, target 1311, the kept range's top (the caret line, 1340) still in view.
        val target = labelRule(1266f, labelExtent(b, l, band), k, 256f, margin)
        assertEquals(1311f, target)
        assertEquals(0f, l.bottom - target)
        assertTrue(k.start - target >= 0f)
    }

    @Test fun aFieldWithoutAPublishedLabelKeepsTheBand() {
        // Step-up's floating label publishes nothing: the band form and the extent form agree for every target.
        for (t in listOf(1300f, 1440f, 1455f, 1460f, 1470f, 1500f)) {
            assertEquals(labelRule(t, box, kept(357f), 357f, margin, band),
                labelRule(t, labelExtent(box, null, band), kept(357f), 357f, margin))
        }
        assertEquals(1460f..1492f, labelExtent(box, null, band))
    }
}

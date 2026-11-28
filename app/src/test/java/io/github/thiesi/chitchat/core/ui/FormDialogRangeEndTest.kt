package io.github.thiesi.chitchat.core.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Label-gap PR (TL, PL 2026-11-28): a target that meets the scroll range's end never leaves a published separate label half
 * in view. Geometries measured on the report form (content px, margin 8 px): the font-scale sweep (diag run 46112715890,
 * portrait, keyboard down after the hide, 1.40..1.55, API35 = API36), 100% (the same run, 1.00) and 200% (#381's runs).
 *
 * The PL asked for a viewport sweep: for a fixed form, the scroll range's end is maxValue = content - v, so sweeping maxValue
 * 1 px at a time across the label's half band and beyond is the same sweep for the rule, which sees only maxValue, the label,
 * the caret line and v.
 */
class FormDialogRangeEndTest {
    private val margin = 8f

    /** A measured end state: the label node, the caret line and the viewport in content px, and the content's height. */
    private class Geometry(val name: String, val labelTop: Float, val labelBottom: Float, val caretTop: Float,
        val caretBottom: Float, val v: Float, val content: Float)

    // content = the measured maxValue + v (the scroller's content height, fixed for the form at that width).
    private val measured = listOf(
        Geometry("1.40 portrait, keyboard down", 653f, 713f, 741f, 771f, 419f, 655f + 419f),
        Geometry("1.45 portrait, keyboard down", 733f, 795f, 823f, 854f, 419f, 747f + 419f),
        Geometry("1.50 portrait, keyboard down", 794f, 858f, 886f, 918f, 418f, 852f + 418f),
        Geometry("1.55 portrait, keyboard down", 817f, 881f, 909f, 941f, 417f, 877f + 417f),
        Geometry("100% portrait, keyboard up (pinned)", 516f, 556f, 584f, 604f, 189f, 591f + 189f),
        Geometry("100% portrait, keyboard down (pinned)", 516f, 556f, 584f, 604f, 424f, 356f + 424f),
        Geometry("100% landscape, keyboard up (column)", 588f, 628f, 656f, 676f, 82f, 866f + 82f),
        Geometry("200% portrait, keyboard down (pinned)", 1198f, 1312f, 1340f, 1378f, 288f, 1542f + 288f),
        Geometry("200% portrait, keyboard up (column)", 1334f, 1448f, 1476f, 1514f, 357f, 1777f + 357f),
        Geometry("200% landscape, keyboard up (column)", 1334f, 1448f, 1476f, 1514f, 82f, 2052f + 82f),
    )

    private fun half(t: Float, g: Geometry) = labelHalf(t, g.labelTop, g.labelBottom, margin)

    @Test fun theLoggedHalfLabelEndsLandTheLabelWhole() {
        // Diag run 46112715890 (main, both levels): the hide's target beyond the range, main's end at maxValue, label half.
        fun end(target: Float, max: Int, g: Geometry) =
            rangeEndTarget(target, max, g.labelTop..g.labelBottom, g.caretTop, g.caretBottom, g.v, margin)
        assertEquals("1.40: 713 beyond 655", 645f, end(713f, 655, measured[0]))
        assertEquals("1.45: 795 beyond 747", 725f, end(795f, 747, measured[1]))
        assertEquals("1.50: 859 beyond 852", 786f, end(859f, 852, measured[2]))
        assertEquals("1.55: 884 beyond 877", 809f, end(884f, 877, measured[3]))
        assertEquals("1.35: 689 beyond 620, the label already whole there: the range's end",
            620f, rangeEndTarget(689f, 620, 631f..689f, 717f, 746f, 420f, margin))
        assertEquals("kb100's hide (#381's run): 947 beyond 796, the label out there: the range's end",
            796f, end(947f, 796, measured[5]))
    }

    @Test fun aOnePixelViewportSweepNeverLeavesTheLabelHalfAtTheRangesEnd() {
        // The PL's sweep: the viewport 1 px at a time, from the caret line's own height to the whole content, the form fixed:
        // maxValue = content - v moves across the label's half band and beyond. At each v, the targets beyond the range that
        // keep the caret line in view (the line at the bottom edge, and at the top edge) meet the range's end.
        val report = StringBuilder()
        for (g in measured) {
            var minSpare = Float.MAX_VALUE; var fellBack = 0; var inBand = 0
            var v = g.caretBottom - g.caretTop
            while (v <= g.content) {
                val max = (g.content - v).toInt()
                if (half(max.toFloat(), g)) inBand++
                for (target in listOf(g.caretBottom - v, g.caretTop)) {
                    if (target <= max) continue
                    val t = rangeEndTarget(target, max, g.labelTop..g.labelBottom, g.caretTop, g.caretBottom, v, margin)
                    val at = "${g.name} v $v max $max target $target -> $t"
                    assertTrue("$at: within the range", t <= max)
                    assertFalse("$at: never half", half(t, g))
                    assertTrue("$at: the caret line's top in view", g.caretTop >= t)
                    assertTrue("$at: the caret line's bottom in view", g.caretBottom - t <= v)
                    if (t < max) { fellBack++; minSpare = minOf(minSpare, v - (g.caretBottom - t)) }
                }
                v += 1f
            }
            report.append("${g.name}: range's end in the half band at $inBand viewports; fallback 2 in $fellBack states, " +
                "minimum spare ${if (fellBack == 0) "-" else minSpare} px; fallback 3 never\n")
        }
        println(report) // The margin by which fallback 2 holds, per geometry and mode (PR text).
    }

    @Test fun whenTheCaretLineWouldLeaveTheViewTheRangesEndStays() {
        // Fallback 3 (the scroll-range extension) isn't built: a geometry where the label whole would push the caret line
        // out of view keeps today's clamp, the range's end, even with the label half there. No current form reaches it.
        val t = rangeEndTarget(900f, 850f.toInt(), 800f..860f, 1100f, 1130f, 200f, margin)
        assertEquals(850f, t)
    }

    @Test fun aFieldWithoutAPublishedLabelKeepsTodaysClamp() {
        // The step-up's floating label (the band form): no published label, so the target is returned as is and the
        // caller's own coerceIn clamps it to the range's end, exactly as before.
        assertEquals(947f, rangeEndTarget(947f, 796, null, 1004f, 1024f, 424f, margin))
        assertEquals("in range: unchanged", 500f, rangeEndTarget(500f, 796, 516f..556f, 584f, 604f, 424f, margin))
    }

    @Test fun aClampedPreSwapTargetBeyondTheRangeIsHandledToo() {
        // SlideClamp holds an arrival's pinned target; if the range shrinks below it (a frame before a mode switch), the held
        // target lies beyond the range. The placement restore applies rangeEndTarget after the clamp: here the held 860
        // against a range ending at 852 lands the label whole, not half at 852.
        val clamp = SlideClamp()
        clamp.apply(860f, pinned = true, arriving = true, caretTop = 886f, caretBottom = 918f, v = 418f,
            labelTop = 2000f, labelBottom = 2001f, margin = margin)
        val held = clamp.apply(840f, pinned = true, arriving = true, caretTop = 886f, caretBottom = 918f, v = 418f,
            labelTop = 2000f, labelBottom = 2001f, margin = margin)
        assertEquals("the clamp holds the arrival's last target", 860f, held)
        assertEquals(786f, rangeEndTarget(held, 852, 794f..858f, 886f, 918f, 418f, margin))
    }

    @Test fun theSlideFramesShareTheRule() {
        // sameModeFrameTarget's range step is rangeEndTarget: the same result as before for #381's logged frames.
        assertEquals(1190f, sameModeFrameTarget(1312f, 1462f, 1500f, 320f, 1198f, 1312f, margin, maxValue = 1250))
        assertEquals(1250f, sameModeFrameTarget(1312f, 1462f, 1500f, 300f, 1198f, 1312f, margin, maxValue = 1250))
    }
}

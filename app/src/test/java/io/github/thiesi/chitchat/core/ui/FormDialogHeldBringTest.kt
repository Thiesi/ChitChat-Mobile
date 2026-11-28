package io.github.thiesi.chitchat.core.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #384 (PL, TL 2026-11-28): a held notice's bring-into-view lands with the field's separate label whole or out, the notice
 * (else its lead) whole and in view, by the minimum move, out preferred. Geometries in viewport px (margin 8 px):
 * - 200%, measured (CI form-dialog metrics, the person report's held stage; pinned body viewport 336): the label node 114
 *   tall and 12 above the field (#378's measurement), the field 146, the notice 56 below the field; did-not-arrive's notice
 *   108 tall, the picture-gone line 114, the snapshot notice 360 (taller than the viewport); a WarningNotice's lead 12 + its
 *   first line (bodyLarge 24sp: 48 px).
 * - 100%, derived from the same layout at scale 1 (label 40, field 92, the gaps 12 and 44, the notice 52, lead 36, the
 *   pinned body viewport 424); the proof diagnostic measures them.
 */
class FormDialogHeldBringTest {
    private val margin = 8f

    private class Form(val name: String, val label: Float, val gapToField: Float, val field: Float, val gapToNotice: Float,
        val notice: Float, val lead: Float, val container: Float)

    private val forms = listOf(
        Form("200% did not arrive", 114f, 12f, 146f, 56f, 108f, 60f, 336f),
        Form("200% picture gone", 114f, 12f, 146f, 56f, 114f, 114f, 336f),
        Form("200% snapshot", 114f, 12f, 146f, 56f, 360f, 60f, 336f),
        Form("100% did not arrive", 40f, 12f, 92f, 44f, 52f, 36f, 424f),
        Form("100% snapshot", 40f, 12f, 92f, 44f, 160f, 36f, 424f),
    )

    /** Compose's default distance (DefaultBringIntoViewSpec): nothing when the request is in view or fills it, else the nearer edge. */
    private fun composeDistance(offset: Float, size: Float, container: Float): Float {
        val trailing = offset + size
        return when {
            offset >= 0f && trailing <= container -> 0f
            offset < 0f && trailing > container -> 0f
            kotlin.math.abs(offset) < kotlin.math.abs(trailing - container) -> offset
            else -> trailing - container
        }
    }

    /** Where everything sits for a label top at [labelTop] in the viewport. */
    private class Place(val labelTop: Float, val labelBottom: Float, val keepTop: Float, val keepBottom: Float, val leadBottom: Float)

    private fun place(f: Form, labelTop: Float): Place {
        val labelBottom = labelTop + f.label
        val keepTop = labelBottom + f.gapToField + f.field + f.gapToNotice
        return Place(labelTop, labelBottom, keepTop, keepTop + f.notice, keepTop + f.lead)
    }

    private fun decide(f: Form, p: Place, forward: Float = 10_000f, backward: Float = 10_000f): HeldBring {
        // The notice's first request: the whole notice; Compose's distance from its rule.
        val d0 = composeDistance(p.keepTop, p.keepBottom - p.keepTop, f.container)
        return heldBringDistance(d0, f.container, p.labelTop, p.labelBottom, p.keepTop, p.keepBottom, p.leadBottom, forward, backward, margin)
    }

    @Test fun aOnePixelSweepOfTheHeldPlacementCountsEachStep() {
        val report = StringBuilder()
        for (f in forms) {
            val steps = IntArray(5)
            // Every place from the notice wholly below the viewport to the label wholly above it, 1 px apart.
            var labelTop = -(f.label + f.gapToField + f.field + f.gapToNotice + f.notice) - 20f
            while (labelTop <= f.container + 20f) {
                val p = place(f, labelTop)
                val r = decide(f, p)
                steps[r.step]++
                val d = r.distance
                val at = "${f.name} labelTop $labelTop -> step ${r.step}, distance $d"
                if (r.step != 4) assertFalse("$at: never half", labelHalf(d, p.labelTop, p.labelBottom, margin))
                if (r.step == 2 || r.step == 3) {
                    assertTrue("$at: the lead's top in view", p.keepTop - d >= -0.5f)
                    assertTrue("$at: the lead's bottom in view", minOf(p.leadBottom, p.keepBottom) - d <= f.container + 0.5f)
                }
                if (r.step == 1) assertEquals("$at: Compose's own distance when the label isn't half", composeDistance(p.keepTop, p.keepBottom - p.keepTop, f.container), d)
                labelTop += 1f
            }
            report.append("${f.name}: step 1 ${steps[1]}, step 2 ${steps[2]}, step 3 ${steps[3]}, step 4 ${steps[4]}\n")
            assertEquals("${f.name}: step 4 (a half label remaining) never, for the current forms", 0, steps[4])
        }
        println(report) // Per geometry, how often each step is taken (PL's J).
    }

    @Test fun theMeasuredHeldStagesLand() {
        // CI's held stage (200% did not arrive, pinned 336): field 26..172, so the label -100..14 (half), the notice
        // 228..336. Compose's distance 0 (the notice already in view); the label goes out by its 14 px, the notice 214..322.
        val f = forms[0]
        val r = heldBringDistance(0f, 336f, -100f, 14f, 228f, 336f, 288f, 2000f, 1556f, margin)
        assertEquals(2, r.step); assertEquals(14f, r.distance)
        // Picture gone (field 20..166, label -106..8, the line 222..336): out by 8.
        val g = heldBringDistance(0f, 336f, -106f, 8f, 222f, 336f, 336f, 2000f, 1556f, margin)
        assertEquals(2, g.step); assertEquals(8f, g.distance)
        assertEquals(f.container, 336f)
    }

    @Test fun eachAnimationFrameKeepsTheSameTarget() {
        // Compose asks again on every frame, with every position shifted by what's scrolled. Replayed: the remaining
        // distance shrinks to 0 without overshoot or a sign change, and the step doesn't change on the way.
        for (f in forms) {
            var labelTop = -(f.label + f.gapToField + f.field + f.gapToNotice + f.notice) - 20f
            while (labelTop <= f.container + 20f) {
                val start = place(f, labelTop)
                val first = decide(f, start)
                if (first.step != 1 && kotlin.math.abs(first.distance) >= 1f) {
                    var scrolled = 0f
                    var remaining = first.distance
                    var frames = 0
                    while (kotlin.math.abs(remaining) >= 0.5f && frames < 40) {
                        scrolled += remaining * 0.35f // part of the way, as an animation frame
                        val shifted = Place(start.labelTop - scrolled, start.labelBottom - scrolled, start.keepTop - scrolled,
                            start.keepBottom - scrolled, start.leadBottom - scrolled)
                        val next = decide(f, shifted)
                        val at = "${f.name} labelTop $labelTop frame $frames"
                        assertEquals("$at: the same target", first.distance - scrolled, next.distance, 0.01f)
                        assertTrue("$at: no sign change", next.distance * first.distance >= -0.01f)
                        assertTrue("$at: shrinking", kotlin.math.abs(next.distance) <= kotlin.math.abs(remaining) + 0.01f)
                        remaining = next.distance; frames++
                    }
                }
                labelTop += 7f
            }
        }
    }

    @Test fun atTheRangesEndTheDistanceStaysInRange() {
        // The held stage (label -100..14, the notice 228..336): with 20 px left to scroll, out (14) is in range and taken.
        val out = heldBringDistance(0f, 336f, -100f, 14f, 228f, 336f, 288f, forward = 20f, backward = 1556f, margin = margin)
        assertEquals(2, out.step); assertEquals(14f, out.distance)
        // With 6 left, out is beyond the range; whole (-108) would push the notice and its lead out of view: neither is
        // taken, and the distance stays Compose's (step 4, the lead winning). It never exceeds the range, so no clamp at
        // the range's end can land the label half on its own. (The current forms' held stages have hundreds of px left:
        // the sweep above counts step 4 at 0.)
        val none = heldBringDistance(0f, 336f, -100f, 14f, 228f, 336f, 288f, forward = 6f, backward = 1556f, margin = margin)
        assertEquals(4, none.step); assertEquals(0f, none.distance)
        // A taller notice (120..336) whose whole would leave the view after whole's -108 (to 444), but whose lead
        // (120..200, to 228..308) stays in it: step 3 takes the label whole, within the range.
        val whole = heldBringDistance(0f, 336f, -100f, 14f, 120f, 336f, 200f, forward = 6f, backward = 1556f, margin = margin)
        assertEquals(3, whole.step); assertEquals(-108f, whole.distance)
        assertFalse(labelHalf(whole.distance, -100f, 14f, margin))
    }

    @Test fun onlyRequestsInsideTheHeldKeepAreAdjusted() {
        // The notice 228..336: its whole rect and its top edge (the second request) are inside; the field's focus request
        // (26..172), arriving in the same frame, is not, and keeps Compose's distance.
        assertTrue(insideHeldKeep(228f, 108f, 228f, 336f))
        assertTrue(insideHeldKeep(228f, 1f, 228f, 336f))
        assertFalse(insideHeldKeep(26f, 146f, 228f, 336f))
        assertFalse("a rect spanning the field and the notice", insideHeldKeep(26f, 310f, 228f, 336f))
    }
}

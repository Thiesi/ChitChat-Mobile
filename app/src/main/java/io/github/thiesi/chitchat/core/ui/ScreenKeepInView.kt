package io.github.thiesi.chitchat.core.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import kotlin.math.roundToInt

/**
 * B (#351, P-78; TL and PL, 2026-11-25): a screen's focused text field stays in view with its floating label whole: the
 * label plus [margin] below the scroller's top edge and the field above its bottom edge (the keyboard or the pinned
 * action). When the field can't fit with the margin it stays whole if it fits at all; otherwise the text line wins and the
 * label is entirely in or entirely out of view, never half ([keepInViewDistance]).
 *
 * Two places apply the rule:
 * - [onViewportLaidOut], on every height change of the viewport (each frame of the keyboard's slide, a rotation, a window
 *   resize), in the layout pass, before the frame is drawn. It moves only when the field breaks the rule, by exactly the
 *   distance the moving edge would otherwise cover, so the field rides the keyboard and a field already clear doesn't
 *   move. A move supersedes Compose's own bring-into-view animation (it's cancelled), so nothing moves afterwards and the
 *   end state doesn't depend on how far that animation had got.
 * - [spec], the screen's [BringIntoViewSpec]: a focus move in a steady view (Next, a tap) keeps Compose's animation, landing
 *   by the same rule.
 *
 * Only for a focused text field, judged by the view's own [android.view.View.onCheckIsTextEditor] (a text input session
 * is active), so a focused button or accessibility focus never triggers it, and with nothing focused a height change
 * doesn't scroll. A field the person scrolled out of view stays where it is (Compose's own rule: only a field that was
 * in view is kept in view). With animations off, Compose's animation is already a snap; nothing here changes that.
 */
internal class ScreenKeepInView(
    private val textEditor: () -> Boolean,
) {
    /** The focused child's coordinates (from onFocusedBoundsChanged): a text field's focus bounds, below its label's top half. */
    var focused: LayoutCoordinates? = null

    /** The scroller's own box: its height is the viewport's. */
    var frame: LayoutCoordinates? = null

    /** The label rule's margin and the floating label's line height, in px (set from the composition). */
    var margin = 0f
    var labelBand = 0f

    private var lastHeight = -1

    /**
     * The focused text field in the viewport's coordinates, from its label's top (half the label's line above the focus
     * bounds: M3's outlined field centres the floating label on its top border, outside the focus bounds) to its bottom.
     * Null unless a text field is focused.
     */
    fun fieldInViewport(): Rect? {
        if (!textEditor()) return null
        val child = focused?.takeIf { it.isAttached } ?: return null
        val box = frame?.takeIf { it.isAttached }?.localBoundingBoxOf(child, clipBounds = false) ?: return null
        return Rect(box.left, box.top - labelBand / 2f, box.right, box.bottom)
    }

    /**
     * Called from the scroller's position callback, after the layout pass and before the frame is drawn. On a height change
     * it applies the rule's minimum move with [dispatchRawDelta] (synchronous, so the frame drawn at the new height already
     * has it) and then [cancelRunning], which takes the scroll mutex so a running bring-into-view can't move the field
     * afterwards. Returns the applied delta, or 0.
     */
    fun onViewportLaidOut(value: Int, maxValue: Int, dispatchRawDelta: (Float) -> Float, cancelRunning: () -> Unit): Int {
        val height = frame?.takeIf { it.isAttached }?.size?.height ?: return 0
        val previous = lastHeight
        lastHeight = height
        if (previous < 0 || previous == height) return 0
        val field = fieldInViewport() ?: return 0
        // In view before the change: only the height changed since the last layout, so its place in the viewport did not.
        if (field.bottom <= 0f || field.top >= previous) return 0
        val kept = (field.top + labelBand).coerceAtMost(field.bottom)..field.bottom
        val distance = keepInViewDistance(field.top, field.bottom, kept, height.toFloat(), margin, labelBand)
        val target = (value + distance).roundToInt().coerceIn(0, maxValue)
        if (target == value) return 0
        dispatchRawDelta((target - value).toFloat())
        cancelRunning()
        return target - value
    }

    /**
     * The screen's bring-into-view spec: a request that lies within the focused text field (its caret, or the field's own
     * bounds) lands by the rule, keeping the requested rect in view; any other request keeps [fallback]'s behaviour.
     */
    @OptIn(ExperimentalFoundationApi::class)
    fun spec(fallback: BringIntoViewSpec): BringIntoViewSpec = object : BringIntoViewSpec {
        override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
            val field = fieldInViewport() ?: return fallback.calculateScrollDistance(offset, size, containerSize)
            if (offset < field.top - 1f || offset + size > field.bottom + 1f) {
                return fallback.calculateScrollDistance(offset, size, containerSize)
            }
            return keepInViewDistance(field.top, field.bottom, offset..(offset + size), containerSize, margin, labelBand)
        }
    }
}

/**
 * B's rule (PL, 2026-11-25), as the distance to scroll (positive moves the content up) from where the view is now, for a
 * field at [top]..[bottom] in the viewport's coordinates ([top] is its floating label's top) in a viewport [v] high:
 * 1. the field fits with the label's [margin]: the minimum move that puts its top at least [margin] below the top edge
 *    and its bottom inside (a field already there doesn't move);
 * 2. else the field fits: the minimum move that keeps it whole (the margin gives way first; the label is inside the field);
 * 3. else [kept] (the text line, or the requested rect) comes inside by the minimum move, then the label is entirely in or
 *    entirely out of view ([labelRule], the same rule as the form dialog's, with [labelBand] the label's line height).
 */
internal fun keepInViewDistance(top: Float, bottom: Float, kept: ClosedFloatingPointRange<Float>, v: Float, margin: Float,
    labelBand: Float): Float {
    val height = bottom - top
    if (height + margin <= v) return 0f.coerceIn(bottom - v, top - margin)
    if (height <= v) return 0f.coerceIn(bottom - v, top)
    val target = if (kept.endInclusive - kept.start <= v) 0f.coerceIn(kept.endInclusive - v, kept.start) else kept.start
    return labelRule(target, Rect(0f, top, 0f, bottom), kept, v, margin, labelBand)
}

package io.github.thiesi.chitchat.core.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import kotlin.math.roundToInt

/**
 * B (#351, P-78; TL and PL, 2026-11-25 and 2026-11-27): a screen's focused text field stays in view with its floating label
 * whole, by four tiers in order ([keepInViewDistance]): both margins ([margin] below the top edge for the label, [margin]
 * above the bottom edge for the field, its supporting line included); else the top margin with the bottom flush; else the
 * field whole; else the caret's line, with the label entirely in or entirely out, never half. The bottom edge is the
 * viewport's: the keyboard's top, or the pinned action's edge while the keyboard is hidden.
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
        set(value) {
            if (value !== field) caret.line = null // Another field (or none): its caret isn't known yet.
            field = value
        }

    /**
     * The focused field's caret line, relative to the field's top (its label's top), remembered from the field's own
     * bring-into-view request (CoreTextField asks for its cursor rect). Reset when the focused child changes or the text
     * input session ends. See [requestDistance].
     */
    private val caret = CaretMemory()

    /** The scroller's own box: its height is the viewport's. */
    var frame: LayoutCoordinates? = null

    /** The label rule's margin and the floating label's line height, in px (set from the composition). */
    var margin = 0f
    var labelBand = 0f

    /**
     * Where an outlined field's first text line sits, from its label's top, in px (set from the composition, from live
     * values): minimizedLabelHalfHeight (half of bodySmall's line height, the same value as the label's offset in
     * [fieldInViewport]) down to the border, then `OutlinedTextFieldDefaults.contentPadding()`'s top, for
     * `bodyLarge.lineHeight` (the field's text style). For a single-line field this is the caret's line, known before the
     * field's own caret request.
     */
    var firstLine: ClosedFloatingPointRange<Float> = 0f..0f

    private var lastHeight = -1

    /**
     * The focused text field in the viewport's coordinates, from its label's top to its bottom. Null unless a text field
     * is focused.
     *
     * The label's top is half the label's line height above the focus bounds. Derived from M3 1.4.0's bytecode
     * (`OutlinedTextFieldKt$OutlinedTextField$3`): the String overload passes BasicTextField the caller's modifier, then
     * `semantics(mergeDescendants = true).padding(top = minimizedLabelHalfHeight())`, and BasicTextField's focus target
     * comes after that. So onFocusedBoundsChanged reports bounds that start at the outlined border, below the padding.
     * The floating label (bodySmall) is centred on that border, half of it inside the padding. minimizedLabelHalfHeight
     * is half of bodySmall's line height (bodySmall.lineHeight.toDp() / 2, with the type-scale token as its fallback when
     * the line height isn't in sp), which [labelBand] holds. The caller's tagged semantics node, measured by the tests,
     * starts above the padding, at the label's top. [firstLine] builds on the same offset.
     */
    fun fieldInViewport(): Rect? {
        if (!textEditor()) { caret.line = null; return null } // The session ended: a later one starts afresh.
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
        val kept = keptLine(field, caret.line, firstLine)
        val distance = keepInViewDistance(field.top, field.bottom, kept, height.toFloat(), margin, labelBand)
        val target = (value + distance).roundToInt().coerceIn(0, maxValue)
        if (target == value) return 0
        dispatchRawDelta((target - value).toFloat())
        cancelRunning()
        return target - value
    }

    /**
     * The screen's bring-into-view spec: a request that lies within the focused text field lands by the rule, from the
     * rule's own inputs only ([requestDistance]); any other request keeps [fallback]'s behaviour.
     */
    @OptIn(ExperimentalFoundationApi::class)
    fun spec(fallback: BringIntoViewSpec): BringIntoViewSpec = object : BringIntoViewSpec {
        override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
            val field = fieldInViewport() ?: return fallback.calculateScrollDistance(offset, size, containerSize)
            if (offset < field.top - 1f || offset + size > field.bottom + 1f) {
                return fallback.calculateScrollDistance(offset, size, containerSize)
            }
            return requestDistance(field, offset, size, containerSize, margin, labelBand, firstLine, caret)
        }
    }
}

/** The focused field's caret line, relative to the field's top; null until the field asks for it. */
internal class CaretMemory {
    var line: ClosedFloatingPointRange<Float>? = null
}

/** What the rule keeps when the field can't fit, in the viewport's coordinates: the caret's line, else the first line. */
internal fun keptLine(field: Rect, caret: ClosedFloatingPointRange<Float>?, firstLine: ClosedFloatingPointRange<Float>):
    ClosedFloatingPointRange<Float> {
    val line = caret ?: firstLine
    val top = (field.top + line.start).coerceAtMost(field.bottom)
    return top..(field.top + line.endInclusive).coerceIn(top, field.bottom)
}

/**
 * A bring-into-view request inside the focused [field] (TL, 2026-11-27): the target comes from the rule's inputs only,
 * the field and its caret's line ([keptLine]), never from the request's extent. A tap makes two requests, the caret's
 * rect and the whole focus box; letting the box's extent be what's kept sent them to two targets (#351 fix B diag run
 * 45969824617: the caret request took the label in, 398→390, then the box, taller than the viewport, aligned its top,
 * 390→412, the label half out). A request that isn't the whole focus box (±1 px) is the field's caret and is remembered
 * first. A box request before any caret request keeps the first line, which for a single-line field is the caret's line,
 * so either order gives one target. An accessibility service's box-sized request during a text session shows the
 * caret's line the same way when the field can't fit: by the rule, the text line wins.
 *
 * A multi-line field whose caret is below its first line can move once more, forward, when its caret request arrives
 * after a box request: that's the real caret replacing the first-line fallback. No ScreenContent form has such a field
 * (TL, 2026-11-27); a later multi-line form gets that single move.
 */
internal fun requestDistance(field: Rect, offset: Float, size: Float, v: Float, margin: Float, labelBand: Float,
    firstLine: ClosedFloatingPointRange<Float>, caret: CaretMemory): Float {
    val boxTop = field.top + labelBand / 2f
    val wholeBox = kotlin.math.abs(offset - boxTop) <= 1f && kotlin.math.abs(offset + size - field.bottom) <= 1f
    if (!wholeBox) caret.line = (offset - field.top)..(offset + size - field.top)
    return keepInViewDistance(field.top, field.bottom, keptLine(field, caret.line, firstLine), v, margin, labelBand)
}

/**
 * B's rule (PL, 2026-11-25; the bottom margin and the order, PL 2026-11-27, A2's order), as the distance to scroll
 * (positive moves the content up) from where the view is now, for a field at [top]..[bottom] in the viewport's
 * coordinates ([top] is its floating label's top, [bottom] includes its supporting line) in a viewport [v] high. Each tier
 * is the minimum move, so a field already where its tier puts it doesn't move:
 * 1. both margins fit: the label's top at least [margin] below the top edge and the bottom at least [margin] above the
 *    bottom edge;
 * 2. else the top margin fits: the label's top at least [margin] below the top edge and the bottom inside (the bottom
 *    margin gives first: flush, or as close as the move leaves it);
 * 3. else the field fits: whole, both edges inside;
 * 4. else [kept] (the caret's line) comes inside, then the label is entirely in or entirely out of view ([labelRule], the
 *    form dialog's rule, with [labelBand] the label's line height).
 */
internal fun keepInViewDistance(top: Float, bottom: Float, kept: ClosedFloatingPointRange<Float>, v: Float, margin: Float,
    labelBand: Float): Float {
    val height = bottom - top
    if (height + 2 * margin <= v) return 0f.coerceIn(bottom + margin - v, top - margin)
    if (height + margin <= v) return 0f.coerceIn(bottom - v, top - margin)
    if (height <= v) return 0f.coerceIn(bottom - v, top)
    val target = if (kept.endInclusive - kept.start <= v) 0f.coerceIn(kept.endInclusive - v, kept.start) else kept.start
    return labelRule(target, Rect(0f, top, 0f, bottom), kept, v, margin, labelBand)
}

package io.github.thiesi.chitchat.core.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.onFocusedBoundsChanged
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.math.roundToInt

/**
 * The width a platform dialog window is first laid out at (AOSP's `config_prefDialogWidth`): a WRAP_CONTENT dialog keeps
 * it whenever its content fits, which Compose content always reports, so an M3 AlertDialog is min(320dp, the space) wide
 * in portrait and landscape alike. Measured locally against M3 AlertDialog at 320 and 411dp wide, within 1px.
 */
internal val PlatformDialogWidth: Dp = 320.dp

/** Below this, the pinned body is too short to use: title, body and buttons scroll as one column (PL, P-220 amendment). */
internal val FormDialogMinimumBody: Dp = 120.dp

/**
 * The form dialog's current layout: the height above the keyboard, the mode and whether the keyboard shows (the dialog
 * window's own insets). It changes when the keyboard shows or hides or the mode switches; a caller's in-form notice that
 * must outlive such a change keys on it (see `holdFocusedInView`).
 */
internal data class FormDialogLayout(val height: Dp, val pinned: Boolean, val ime: Boolean)

internal val LocalFormDialogLayout = androidx.compose.runtime.staticCompositionLocalOf<FormDialogLayout?> { null }

/**
 * A form dialog (P-220 amendment, PL and TL of 2026-11-21): the AlertDialog look in its own window, laid out above the
 * keyboard, so the buttons never sit under it (the M5b2 own-window lesson).
 *
 * - **Pinned** while the body gets at least [FormDialogMinimumBody]: the title on top, the body scrolling in between
 *   (with P-220's scroll-edge dividers), the buttons at the bottom, exactly as a fitting AlertDialog.
 * - **One column** below that: title, body and buttons scroll together, with the edge dividers where content is hidden.
 *
 * The space is measured (the window above the IME and the system bars), never guessed from the device or orientation.
 * Both modes share one composition tree and only the body's height changes, so a switch when the keyboard shows or hides
 * never recreates a focused field (TL). Buttons sit side by side when both fit, otherwise stacked with the primary on top,
 * each end-aligned, as M3 does; reading order follows placement.
 */
@Composable
internal fun FormDialog(onDismissRequest: () -> Unit, title: @Composable () -> Unit, confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier, dismissButton: (@Composable () -> Unit)? = null,
    bodyArrangement: Arrangement.Vertical = Arrangement.Top, dismissOnOutsideTap: Boolean = true,
    holdFocusedInView: Boolean = false,
    body: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismissRequest, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        // The usable area is the window minus the system bars and the keyboard; a tap on it outside the surface dismisses,
        // as a platform dialog's outside tap does, unless [dismissOnOutsideTap] is false (PL: a form where an accidental
        // dismissal loses a choice or composed text): then it does nothing, with no feedback and no dismiss action.
        // Back and the dialog's own buttons still close it.
        BoxWithConstraints(Modifier.fillMaxSize()
            .then(if (dismissOnOutsideTap) Modifier.pointerInput(onDismissRequest) { detectTapGestures { onDismissRequest() } }
                else Modifier)
            .safeDrawingPadding().imePadding().testTag("form_dialog_area"), contentAlignment = Alignment.Center) {
            // The width an M3 AlertDialog gets: M3's 280 to 560dp inside the platform's preferred dialog width.
            val sized = Modifier.sizeIn(minWidth = minOf(280.dp, maxWidth), maxWidth = minOf(PlatformDialogWidth, 560.dp, maxWidth))
            Surface(modifier.then(sized).pointerInput(Unit) { detectTapGestures { } },
                shape = AlertDialogDefaults.shape, color = AlertDialogDefaults.containerColor,
                tonalElevation = AlertDialogDefaults.TonalElevation) {
                FormDialogContent(title, confirmButton, dismissButton, bodyArrangement, holdFocusedInView, body)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FormDialogContent(title: @Composable () -> Unit, confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)?, bodyArrangement: Arrangement.Vertical, holdFocusedInView: Boolean,
    body: @Composable ColumnScope.() -> Unit) {
    val density = LocalDensity.current
    // The 24dp padding scrolls with the content, so a short window's viewport is the whole surface: with the keyboard
    // up in landscape at 200% only about 80dp may be left, and a fixed padding would leave no room for a 48dp button.
    // The tags name the two viewports (this box's content is the one-column scroller's; the body box's is the pinned
    // body scroller's), for tests to measure a field against the area that actually clips it. Semantics only (#369, TL).
    BoxWithConstraints(Modifier.testTag("form_dialog_viewport")) {
        var titleHeight by remember { mutableIntStateOf(0) }
        var buttonsHeight by remember { mutableIntStateOf(0) }
        // What the pinned layout leaves the body: the height above the keyboard less the title, the buttons and their gaps.
        val available = maxHeight - 48.dp - with(density) { (titleHeight + buttonsHeight).toDp() } - 16.dp - 24.dp
        val pinned = available >= FormDialogMinimumBody
        // The layout callers' held notices key on (see holdFocusedInView), with the dialog window's own keyboard state.
        val layoutKey = FormDialogLayout(maxHeight, pinned, WindowInsets.ime.getBottom(density) > 0)
        val outer = rememberScrollState()
        val inner = rememberScrollState()
        // Keep the focused field in view when the space changes (the keyboard shows or hides, or the mode switches): the
        // scroller in use scrolls to it. A scroll, never a focus request; typing then keeps the caret in view as usual.
        // #369 (TL, PL, 2026-11-25): a mode switch swaps scrollers, which lost the position, and a field taller than the
        // view went to its top, so its caret could leave the view. Now what was in view stays in view: the caret's line
        // (or the field) keeps its offset from the viewport's top, clamped inside the new viewport, and the label rule
        // holds (the label plus 8dp below the top edge, or the label entirely out; the caret line wins).
        val focused = remember { FocusedChild() }
        val margin = with(density) { FormDialogLabelMargin.toPx() }
        val labelBand = with(density) { MaterialTheme.typography.bodySmall.lineHeight.toPx() }
        val key = maxHeight to pinned
        // The hook (PL and TL, 2026-11-22): while the caller holds an in-form notice, a layout change doesn't scroll back to
        // the focused field; the notice keeps the view until the person's next interaction. Off by default: unchanged.
        val held by androidx.compose.runtime.rememberUpdatedState(holdFocusedInView)
        LaunchedEffect(maxHeight, pinned) {
            withFrameNanos { } // After the layout at the new height.
            if (held) return@LaunchedEffect
            val child = focused.coordinates?.takeIf { it.isAttached } ?: return@LaunchedEffect
            val (state, content, viewport) = if (pinned) Triple(inner, focused.innerContent, focused.innerViewport)
                else Triple(outer, focused.outerContent, focused.outerViewport)
            val inside = content?.takeIf { it.isAttached } ?: return@LaunchedEffect
            val box = inside.localBoundingBoxOf(child, clipBounds = false)
            val caret = focused.caretIn(inside, box)
            val before = focused.before(key, child)
            val v = viewport.toFloat()
            var target: Float
            if (before != null && before.visible) {
                // What was in view stays in view: the anchor keeps its offset from the viewport's top, clamped inside.
                val caretBefore = before.caret
                if (caret != null && caretBefore != null) {
                    target = caret.top - caretBefore.coerceIn(0f, (v - caret.height).coerceAtLeast(0f))
                } else {
                    val lo = margin; val hi = v - box.height
                    target = box.top - (if (hi >= lo) before.field.coerceIn(lo, hi) else before.field.coerceIn(hi, lo))
                }
            } else {
                val top = state.value.toFloat(); val bottom = top + v
                target = when {
                    box.top - margin < top || box.height + margin > v -> box.top - margin // Above, or taller: its top.
                    box.bottom > bottom -> box.bottom - v                                   // Below: just enough.
                    else -> state.value.toFloat()
                }
            }
            // The caret's line, when known, is always inside: a field entering from unfocused (no earlier position for
            // it) or taller than the view never leaves it out (#369, TL).
            if (caret != null) {
                if (caret.top - target < 0f) target = caret.top
                else if (caret.top + caret.height - target > v) target = caret.top + caret.height - v
            }
            if (caret != null) target = labelRule(target, box, caret, v, margin, labelBand)
            val next = target.roundToInt().coerceIn(0, state.maxValue)
            if (next != state.value) state.scrollTo(next)
        }
        // A text or selection change (any input path: the keyboard, a paste, or an accessibility service setting the text
        // through semantics, which doesn't bring the caret into view by itself) that put the caret's line outside the view
        // brings it back, just enough, then the label rule. When the line is still inside, nothing moves (#369, TL).
        LaunchedEffect(focused.caret.version) {
            if (focused.caret.version == 0) return@LaunchedEffect
            withFrameNanos { } // After the layout of the change.
            // A selection-only change lays nothing out, so the body's position callback doesn't record it: record here, so
            // a later height or mode change restores from where the caret is now, not where it was (#371's restore assert).
            focused.record(key, pinned)
            if (held) return@LaunchedEffect
            val child = focused.coordinates?.takeIf { it.isAttached } ?: return@LaunchedEffect
            val (state, content, viewport) = if (pinned) Triple(inner, focused.innerContent, focused.innerViewport)
                else Triple(outer, focused.outerContent, focused.outerViewport)
            val inside = content?.takeIf { it.isAttached } ?: return@LaunchedEffect
            val box = inside.localBoundingBoxOf(child, clipBounds = false)
            val caret = focused.caretIn(inside, box) ?: return@LaunchedEffect
            val v = viewport.toFloat()
            val top = state.value.toFloat()
            if (caret.top >= top && caret.bottom <= top + v) return@LaunchedEffect
            var target = if (caret.top < top) caret.top else caret.bottom - v
            target = labelRule(target, box, caret, v, margin, labelBand)
            val next = target.roundToInt().coerceIn(0, state.maxValue)
            if (next != state.value) state.scrollTo(next)
        }
        Column(Modifier.onFocusedBoundsChanged { focused.coordinates = it; focused.record(key, pinned) }
            .onSizeChanged { focused.outerViewport = it.height }
            .onGloballyPositioned { focused.outerFrame = it }
            .scrollEdgeDividers(outer, MaterialTheme.colorScheme.outline).verticalScroll(outer)
            .onGloballyPositioned { focused.outerContent = it }
            .testTag(if (pinned) "form_dialog_pinned" else "form_dialog_column").padding(24.dp)) {
            Box(Modifier.padding(bottom = 16.dp).onSizeChanged { titleHeight = it.height }) {
                CompositionLocalProvider(LocalContentColor provides AlertDialogDefaults.titleContentColor,
                    LocalTextStyle provides MaterialTheme.typography.headlineSmall) { title() }
            }
            // The modes differ only in modifiers, never in the tree, so a focused field keeps its node across a switch:
            // pinned, the body gets the leftover height and scrolls inside it; one column, it takes its own height and the
            // whole dialog scrolls.
            Box(Modifier.padding(bottom = 24.dp).then(if (pinned) Modifier.heightIn(max = available) else Modifier)
                .testTag("form_dialog_body_viewport")) {
                CompositionLocalProvider(LocalContentColor provides AlertDialogDefaults.textContentColor,
                    LocalTextStyle provides MaterialTheme.typography.bodyMedium,
                    LocalFormDialogLayout provides layoutKey, LocalFormDialogCaret provides focused.caret) {
                    Column(Modifier.onSizeChanged { focused.innerViewport = it.height }
                        .onGloballyPositioned { focused.innerFrame = it }
                        .then(if (pinned) Modifier.scrollEdgeDividers(inner, MaterialTheme.colorScheme.outline)
                        .verticalScroll(inner) else Modifier).onGloballyPositioned {
                            focused.innerContent = it
                            focused.record(key, pinned)
                        },
                        verticalArrangement = bodyArrangement, content = body)
                }
            }
            Box(Modifier.align(Alignment.End).onSizeChanged { buttonsHeight = it.height }) {
                CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.primary,
                    LocalTextStyle provides MaterialTheme.typography.labelLarge) {
                    FormDialogButtons(dismissButton, confirmButton)
                }
            }
        }
    }
}

/**
 * The label rule (#369, PL): a field's top keeps its floating label plus [margin] below the viewport's top edge, or the
 * label is entirely out of view, never half; the caret's line wins when both can't fit. Returns the adjusted target.
 */
private fun labelRule(target: Float, box: androidx.compose.ui.geometry.Rect, caret: androidx.compose.ui.geometry.Rect,
    v: Float, margin: Float, labelBand: Float): Float {
    val fieldRel = box.top - target
    val caretRel = caret.top - target
    if (fieldRel <= -labelBand || fieldRel >= margin) return target
    val up = margin - fieldRel
    val down = fieldRel + labelBand
    return when {
        caretRel + up <= v - caret.height -> target - up
        caretRel - down >= 0f -> target + down
        else -> target
    }
}

/**
 * Where a focused form-dialog field's caret is (#369, TL): its line in [coordinates] (the field's text area), from the
 * field's own selection and text layout, so it's known whatever put the text there. Published on every layout or
 * selection change; [version] tells the dialog to check that the line is still in view. Not saved; memory only.
 */
internal class FormDialogCaret {
    var coordinates: LayoutCoordinates? = null
        private set
    var rect: androidx.compose.ui.geometry.Rect? = null
        private set
    var version by androidx.compose.runtime.mutableIntStateOf(0)
        private set

    fun publish(coordinates: LayoutCoordinates, rect: androidx.compose.ui.geometry.Rect) {
        if (coordinates === this.coordinates && rect == this.rect) return
        this.coordinates = coordinates; this.rect = rect
        version++
    }
}

/** The form dialog's caret holder, for a field inside its body to publish to; null outside a form dialog. */
internal val LocalFormDialogCaret = androidx.compose.runtime.staticCompositionLocalOf<FormDialogCaret?> { null }

/** The label's air above a field brought into view (#369, PL): 8dp below the viewport's top edge. */
internal val FormDialogLabelMargin: Dp = 8.dp

/**
 * Where the focused child is, and the two scrollers' content and viewport, for keeping it in view. Not state.
 *
 * #369: it also records, on every layout, where the focused field and its caret line sit relative to the top of the
 * scroller in use (in that viewport's own coordinates, so a mode switch doesn't change their meaning), tagged with the
 * layout they were measured in, so the keep-in-view step can read where they were just before a height or mode change.
 */
private class FocusedChild {
    var coordinates: LayoutCoordinates? = null
    var outerContent: LayoutCoordinates? = null
    var innerContent: LayoutCoordinates? = null
    var outerFrame: LayoutCoordinates? = null
    var innerFrame: LayoutCoordinates? = null
    var outerViewport = 0
    var innerViewport = 0

    /** A field and caret-line offset from the viewport's top, and whether the anchor was in view. */
    class Seen(val child: LayoutCoordinates, val field: Float, val caret: Float?, val visible: Boolean)

    private var lastKey: Any? = null
    private var last: Seen? = null
    private var previous: Seen? = null

    /** The focused field's caret, published by a field that reports it (see [FormDialogCaret]). */
    val caret = FormDialogCaret()

    /** The caret's line in [inside]'s coordinates, if a field published one and it lies within the focused field. */
    fun caretIn(inside: LayoutCoordinates, box: androidx.compose.ui.geometry.Rect): androidx.compose.ui.geometry.Rect? {
        val rect = caret.rect ?: return null
        val from = caret.coordinates?.takeIf { it.isAttached } ?: return null
        val topLeft = inside.localPositionOf(from, rect.topLeft)
        val mapped = androidx.compose.ui.geometry.Rect(topLeft, rect.size)
        return mapped.takeIf { it.top >= box.top - 1f && it.bottom <= box.bottom + 1f }
    }

    fun record(key: Any, pinned: Boolean) {
        val child = coordinates?.takeIf { it.isAttached } ?: return
        val frame = (if (pinned) innerFrame else outerFrame)?.takeIf { it.isAttached } ?: return
        val inside = innerContent?.takeIf { it.isAttached } ?: return
        // Relative to the viewport's own coordinates, never through a window conversion: in this dialog localToWindow
        // measured off by the inner scroller's value, which changes across a mode switch (#371, #372, TL).
        val box = inside.localBoundingBoxOf(child, clipBounds = false)
        val field = frame.localPositionOf(inside, box.topLeft).y
        val caret = caretIn(inside, box)?.let { frame.localPositionOf(inside, it.topLeft).y }
        val height = frame.size.height
        val anchorTop = caret ?: field
        val anchorHeight = caret?.let { caretIn(inside, box)!!.height } ?: box.height
        val seen = Seen(child, field, caret, anchorTop + anchorHeight > 0f && anchorTop < height)
        if (key != lastKey) { previous = last; lastKey = key }
        last = seen
    }

    /** Where they were in the layout before [key], only if measured for the same focused [child]. */
    fun before(key: Any, child: LayoutCoordinates): Seen? =
        (if (key == lastKey) previous else last)?.takeIf { it.child === child }
}

/** M3's arrangement: side by side (dismiss, then the primary) when both fit, else the primary on top; end-aligned. */
@Composable
private fun FormDialogButtons(dismissButton: (@Composable () -> Unit)?, confirmButton: @Composable () -> Unit) {
    val main = with(LocalDensity.current) { 8.dp.roundToPx() }
    val cross = with(LocalDensity.current) { 12.dp.roundToPx() }
    Layout(contents = listOf({ if (dismissButton != null) dismissButton() }, confirmButton)) { (dismiss, confirm), constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val d = dismiss.map { it.measure(loose) }
        val c = confirm.map { it.measure(loose) }
        val dw = d.maxOfOrNull { it.width } ?: 0; val dh = d.maxOfOrNull { it.height } ?: 0
        val cw = c.maxOfOrNull { it.width } ?: 0; val ch = c.maxOfOrNull { it.height } ?: 0
        val room = constraints.maxWidth.takeIf { it != Constraints.Infinity } ?: Int.MAX_VALUE
        // The block wraps its buttons (the dialog keeps its content width) and sits at the end, as M3's does.
        if (d.isEmpty() || dw + main + cw <= room) {
            val width = dw + (if (d.isEmpty()) 0 else main) + cw
            val height = maxOf(dh, ch)
            layout(width, height) {
                c.forEach { it.place(width - cw, (height - it.height) / 2) }
                d.forEach { it.place(width - cw - (if (c.isEmpty()) 0 else main) - dw, (height - it.height) / 2) }
            }
        } else layout(maxOf(dw, cw), ch + cross + dh) {
            val width = maxOf(dw, cw)
            // Stacked: the primary on top, then dismiss, both end-aligned.
            c.forEach { it.place(width - it.width, 0) }
            d.forEach { it.place(width - it.width, ch + cross) }
        }
    }
}

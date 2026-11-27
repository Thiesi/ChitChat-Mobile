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
import androidx.compose.foundation.layout.imeAnimationSource
import androidx.compose.foundation.layout.imeAnimationTarget
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
import androidx.compose.ui.layout.layout
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
import kotlinx.coroutines.launch

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
        // A3 (TL, 2026-11-26): the height step runs where the new layout is first known, in the outer scroller content's
        // position callback, after the layout pass and before the frame is drawn: each scroller's new maxValue is already
        // measured there, so a synchronous delta lands the restore in the first frame drawn at the new layout, where the
        // after-a-frame step alone drew one frame at the swapped-in scroller's old value (often 0) first. Once per key:
        // later callbacks for the same key (a scroll fires them too) never re-apply it against the person's own scrolling.
        // A target outside 0..maxValue is left to the after-a-frame step, which keeps A's clamp. A scroll in progress
        // isn't waited for, it's cancelled: in the same layout pass Compose's own bring-into-view starts one on the
        // swapped-in scroller, which would align a field taller than the viewport to its top, against the label rule
        // (#376's landscape probe: the field glided 28 px to the top edge after the restore). Waiting for it brought back
        // the frame at the old value; letting it run moves the field off the restored place. Main cancelled it implicitly,
        // through the after-a-frame step's scrollTo. Now both sites take the scroll mutex: right after an applied delta,
        // in this callback, and again at the after-a-frame step, which also covers one started after this callback in the
        // same pass (children's position callbacks run after this one) and stays the fallback for a key no callback came
        // for.
        // The rule (A, A2, the label rule), from its inputs: the field's box, its caret line and trailing line in the
        // scroller's content, the baseline before the change, and the viewport. Unchanged by #378; only where the inputs
        // come from moved (see heightStep and restoreInPlacement).
        fun heightTarget(state: androidx.compose.foundation.ScrollState, box: androidx.compose.ui.geometry.Rect,
            caret: androidx.compose.ui.geometry.Rect?, trailing: androidx.compose.ui.geometry.Rect?, before: FocusedChild.Seen?,
            v: Float): Int {
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
            // A field that fits whole (with the label's margin) stays whole in view, just enough, as Compose's own
            // keep-in-view did before this step superseded it (#371's captures: a short filled field was left cut at the
            // bottom edge). Its caret is inside it, so the line stays in view; a taller field keeps the caret rule above.
            if (box.height + margin <= v) {
                if (box.top - margin < target) target = box.top - margin
                else if (box.bottom > target + v) target = box.bottom - v
            }
            if (caret != null) {
                target = lastLineSpan(target, box, caret, trailing, v, margin)
                target = labelRule(target, box, keptRange(box, caret, trailing, v, margin), v, margin, labelBand)
            }
            return target.roundToInt()
        }
        // The after-a-frame step and the callback fallback: the inputs from the current placement.
        fun heightStep(): Pair<androidx.compose.foundation.ScrollState, Int>? {
            if (held) return null
            val child = focused.coordinates?.takeIf { it.isAttached } ?: return null
            val (state, content, viewport) = if (pinned) Triple(inner, focused.innerContent, focused.innerViewport)
                else Triple(outer, focused.outerContent, focused.outerViewport)
            val inside = content?.takeIf { it.isAttached } ?: return null
            val box = inside.localBoundingBoxOf(child, clipBounds = false)
            val caret = focused.caretIn(inside, box)
            val trailing = if (caret != null) focused.trailingIn(inside) else null
            return state to heightTarget(state, box, caret, trailing, focused.before(key, child), viewport.toFloat())
        }
        val sameFrame = remember { FormDialogSameFrameRestore() }
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        val cancelRunning: (androidx.compose.foundation.ScrollState) -> Unit = { state ->
            scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                state.scroll(androidx.compose.foundation.MutatePriority.Default) { }
            }
        }
        // #378 (TL ruling): the restore in the placement pass, from a layout modifier directly before the scroller in use's
        // verticalScroll, after it measured the scroll node (this pass's maxValue and viewport) and before it places it
        // (the node reads the value in its own placement, so the content is placed at the restored offset in this pass).
        // The position callback restore below made its scroll after the layout loop: the next measureAndLayout laid it out,
        // so a frame could be drawn at the old placement, and the body's own position callback, later in that same dispatch,
        // recorded the pre-restore placement; with a new key every frame of an animated keyboard, the next restore read that
        // stale record as its baseline, and the targets alternated (#376's +-38 px shake, diag run 46031148507). The inputs
        // come from the field's place in the body column's own content, cached at the last placement ([FocusedChild.body]),
        // which a mode switch doesn't move (the body is one node in both modes); valid only while the content wasn't
        // re-measured (otherwise the callback restore below still runs, the fallback).
        fun restoreInPlacement(forPinned: Boolean, state: androidx.compose.foundation.ScrollState, viewportHeight: Int) {
            if (forPinned != pinned) return
            focused.layoutKey = key
            focused.layoutPinned = pinned
            val place = focused.body ?: return
            val child = focused.coordinates?.takeIf { it.isAttached } ?: return
            val expected = if (pinned) place.bodyHeight.toFloat() else place.bodyTop + place.bodyHeight + place.belowBody
            val valid = bodyPlaceValid(place, child, pinned, state.maxValue, viewportHeight)
            KeepProbe.log("PLACE check forPinned=$forPinned key=$key valid=$valid sameChild=${place.child === child} max=${state.maxValue} " +
                "viewport=$viewportHeight sum=${state.maxValue + viewportHeight} expected=$expected bodyH=${place.bodyHeight} bodyTop=${place.bodyTop} below=${place.belowBody}")
            if (!valid) return
            val claimed = sameFrame.claim(key)
            KeepProbe.log("PLACE claim forPinned=$forPinned key=$key ok=$claimed")
            if (!claimed) return
            focused.newChange() // As in the callback restore: each change reads its baseline afresh.
            if (held) return
            val shift = if (pinned) 0f else place.bodyTop
            val box = place.box.translate(0f, shift)
            val caret = place.caret?.translate(0f, shift)
            val trailing = place.trailing?.translate(0f, shift)
            val beforeSeen = focused.before(key, child)
            val target = heightTarget(state, box, caret, trailing, beforeSeen, viewportHeight.toFloat())
            KeepProbe.log("PLACE restore forPinned=$forPinned key=$key box=${place.box.top}..${place.box.bottom} caret=${place.caret?.top} bodyTop=${place.bodyTop} " +
                "shift=$shift value=${state.value} max=${state.maxValue} v=$viewportHeight before=${KeepProbe.source(beforeSeen)} target=$target " +
                "delta=${sameFrameDelta(target, state.value, state.maxValue)}")
            applySameFrame(sameFrameDelta(target, state.value, state.maxValue), apply = state::dispatchRawDelta) {
                cancelRunning(state)
            }
        }
        // The fallback: a change the placement pass couldn't take (the content was re-measured), after the layout pass.
        fun restoreInLayoutPass() {
            // The layout pass's key and mode, first: this outer callback runs before the children's, so a record() later
            // in the same pass is attributed to this layout and measured against its frame, whatever key the calling
            // lambda captured (#376: an old-key record after the same-frame scroll, against the inner frame's 3540 px).
            focused.layoutKey = key
            focused.layoutPinned = pinned
            val claimedHere = sameFrame.claim(key)
            KeepProbe.log("CB claim key=$key pinned=$pinned ok=$claimedHere")
            if (!claimedHere) return
            // A new change: its baseline is read afresh, here, before heightStep()'s held check, so a held change resets
            // it too (a notice holding the view while the keyboard hides, then the person's tap: K, J, K). Assumes every
            // height or mode change relays out the outer scroller, so this callback comes for each one.
            focused.newChange()
            val (state, target) = heightStep() ?: return
            KeepProbe.log("CB restore key=$key pinned=$pinned value=${state.value} max=${state.maxValue} target=$target before=${KeepProbe.source(focused.before(key, focused.coordinates ?: return))}")
            // After an applied delta, a zero-distance mutation takes the scroll mutex here, undispatched, so the running
            // bring-into-view is cancelled inside this callback and can't move the field a frame (see above).
            applySameFrame(sameFrameDelta(target, state.value, state.maxValue), apply = state::dispatchRawDelta) {
                cancelRunning(state)
            }
        }
        LaunchedEffect(maxHeight, pinned) {
            withFrameNanos { } // After the layout at the new height: the fallback.
            val (state, target) = heightStep() ?: return@LaunchedEffect
            // Always scrolls, even when the target is already reached: taking the scroll mutex cancels a bring-into-view
            // that would otherwise move the field off the restored place (see above), as main's scrollTo did.
            KeepProbe.log("AF key=$key pinned=$pinned value=${state.value} max=${state.maxValue} target=$target")
            fallbackScroll(target, state.maxValue, state::scrollTo)
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
            focused.recordBody()
            if (held) return@LaunchedEffect
            val child = focused.coordinates?.takeIf { it.isAttached } ?: return@LaunchedEffect
            val (state, content, viewport) = if (pinned) Triple(inner, focused.innerContent, focused.innerViewport)
                else Triple(outer, focused.outerContent, focused.outerViewport)
            val inside = content?.takeIf { it.isAttached } ?: return@LaunchedEffect
            val box = inside.localBoundingBoxOf(child, clipBounds = false)
            val caret = focused.caretIn(inside, box) ?: return@LaunchedEffect
            val v = viewport.toFloat()
            val top = state.value.toFloat()
            val spanned = lastLineSpan(top, box, caret, focused.trailingIn(inside), v, margin)
            if (caret.top >= top && caret.bottom <= top + v && spanned == top) return@LaunchedEffect
            var target = if (caret.top < top) caret.top else if (caret.bottom > top + v) caret.bottom - v else top
            val trailing = focused.trailingIn(inside)
            target = lastLineSpan(target, box, caret, trailing, v, margin)
            target = labelRule(target, box, keptRange(box, caret, trailing, v, margin), v, margin, labelBand)
            val next = target.roundToInt().coerceIn(0, state.maxValue)
            if (next != state.value) state.scrollTo(next)
        }
        // DIAGNOSTIC (never merged), #378 condition 1: the insets objects (composable getters), read in placement below.
        val diagIme = WindowInsets.ime
        @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class) val diagSrc = WindowInsets.imeAnimationSource
        @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class) val diagTgt = WindowInsets.imeAnimationTarget
        Column(Modifier.onFocusedBoundsChanged { focused.coordinates = it; focused.record(key, pinned); focused.recordBody() }
            .onSizeChanged { focused.outerViewport = it.height }
            .onGloballyPositioned { focused.outerFrame = it; KeepProbe.outer = outer; KeepProbe.inner = inner }
            .scrollEdgeDividers(outer, MaterialTheme.colorScheme.outline)
            .layout { measurable, constraints ->
                // DIAGNOSTIC (never merged), #378 condition 1: the dialog window's IME animation source and target.
                val placeable = measurable.measure(constraints)
                layout(placeable.width, placeable.height) {
                    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                    run {
                        val d = this@layout
                        val ime = diagIme.getBottom(d)
                        val src = diagSrc.getBottom(d)
                        val tgt = diagTgt.getBottom(d)
                        KeepProbe.log("IMEA ime=$ime src=$src tgt=$tgt running=${src != tgt} key=$key pinned=$pinned h=${placeable.height}")
                    }
                    restoreInPlacement(forPinned = false, outer, placeable.height)
                    placeable.place(0, 0)
                }
            }
            .verticalScroll(outer)
            .onGloballyPositioned { KeepProbe.newDispatch(); focused.outerContent = it; restoreInLayoutPass() }
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
                            .layout { measurable, constraints ->
                                val placeable = measurable.measure(constraints)
                                layout(placeable.width, placeable.height) {
                                    restoreInPlacement(forPinned = true, inner, placeable.height)
                                    placeable.place(0, 0)
                                }
                            }
                            .verticalScroll(inner) else Modifier).onGloballyPositioned {
                            focused.innerContent = it
                            focused.record(key, pinned)
                            focused.recordBody()
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
 * #378: the focused field's place in the body column's own content (its box, caret line and trailing line), the body's
 * top in the outer content, the body content's height and the outer content's height below the body, at a placement.
 */
internal class BodyPlace(val child: Any, val box: androidx.compose.ui.geometry.Rect, val caret: androidx.compose.ui.geometry.Rect?,
    val trailing: androidx.compose.ui.geometry.Rect?, val bodyTop: Float, val bodyHeight: Int, val belowBody: Float)

/**
 * #378: whether [place] is this pass's place for [child] in the scroller in use, measured before it's placed: the same
 * field, and the content not re-measured. Pinned, the inner scroller's content is the body (`maxValue + viewport` its
 * height); one column, the outer content is the body's top, the body and the part below it. Content no taller than the
 * viewport (maxValue 0) can't scroll, so any target is clamped to 0 there anyway.
 */
internal fun bodyPlaceValid(place: BodyPlace, child: Any, pinned: Boolean, maxValue: Int, viewportHeight: Int): Boolean {
    if (place.child !== child) return false
    if (maxValue == 0) return true
    val expected = if (pinned) place.bodyHeight.toFloat() else place.bodyTop + place.bodyHeight + place.belowBody
    return kotlin.math.abs(maxValue + viewportHeight - expected) <= 1f
}

/** A3: claims each layout key once, so only the first position callback after a height or mode change restores. */
internal class FormDialogSameFrameRestore {
    private var claimed: Any? = null

    /** True the first time for [key] (a change back to an earlier key is a new change); false for later callbacks. */
    fun claim(key: Any): Boolean {
        if (claimed == key) return false
        claimed = key
        return true
    }
}

/**
 * A3: the synchronous delta for the same-frame restore, or null: no move needed, or a target outside 0..[maxValue], left
 * to the after-a-frame step (A's clamp applies there, never a clipped value here). A scroll in progress isn't a reason
 * to wait (see the call site).
 */
internal fun sameFrameDelta(target: Int, value: Int, maxValue: Int): Float? = when {
    target !in 0..maxValue || target == value -> null
    else -> (target - value).toFloat()
}

/** A3: applies [delta] and then [cancelRunning], only when there is a delta (no move, no cancel: the fallback acts). */
internal inline fun applySameFrame(delta: Float?, apply: (Float) -> Unit, cancelRunning: () -> Unit) {
    if (delta == null) return
    apply(delta)
    cancelRunning()
}

/** A3: the after-a-frame step always scrolls (clamped as A), even at the target, so it takes the scroll mutex. */
internal suspend fun fallbackScroll(target: Int, maxValue: Int, scrollTo: suspend (Int) -> Unit) {
    scrollTo(target.coerceIn(0, maxValue))
}

/**
 * A2 (PL ruling, "a disabled action must not hide its reason"): while the caret is on the field's last line, the view
 * also keeps the field's bottom edge and the field's trailing line (the report form's counter) when they fit with the
 * caret's line: the span from the caret line's top to the lower of the field's bottom and the trailing line's bottom
 * moves into view by the minimum distance. When it doesn't fit, the caret's line wins (the target is unchanged).
 * [trailing] is null unless the caret is on the last line and a trailing line is published.
 */
internal fun lastLineSpan(target: Float, box: androidx.compose.ui.geometry.Rect, caret: androidx.compose.ui.geometry.Rect,
    trailing: androidx.compose.ui.geometry.Rect?, v: Float, margin: Float): Float {
    trailing ?: return target
    val top = caret.top
    val bottom = maxOf(box.bottom, trailing.bottom)
    if (bottom - top > v) return target
    return when {
        top < target -> top
        // From below, or within the bottom margin (PL, 2026-11-26): the span ends [margin] above the edge, so the
        // scroll-edge divider doesn't read as an underline under the counter; the margin gives first (flush) when only
        // the span fits. A span already clear of the margin doesn't move (text first keeps its restored place).
        bottom > target + v - margin -> if (bottom + margin - top <= v) bottom + margin - v else maxOf(target, bottom - v)
        else -> target
    }
}

/**
 * What must stay in view (A2; TL priority: the caret's line, then the last-line span, then its 8dp bottom margin, then
 * the label): the span from the
 * caret line's top to the lower of the field's bottom and the trailing line's bottom when the caret is on the last line
 * and it fits, else the caret's line. The label rule may only move within this.
 */
internal fun keptRange(box: androidx.compose.ui.geometry.Rect, caret: androidx.compose.ui.geometry.Rect,
    trailing: androidx.compose.ui.geometry.Rect?, v: Float, margin: Float): ClosedFloatingPointRange<Float> {
    if (trailing != null) {
        val bottom = maxOf(box.bottom, trailing.bottom)
        // The span with its bottom margin when that fits (it ranks above the label), else the span, else the caret line.
        if (bottom + margin - caret.top <= v) return caret.top..(bottom + margin)
        if (bottom - caret.top <= v) return caret.top..bottom
    }
    return caret.top..caret.bottom
}

/**
 * The label rule (#369, PL), last in priority (TL): a field's top keeps its floating label plus [margin] below the
 * viewport's top edge, or the label is entirely out of view, never half. It moves the label in only if [kept] (the caret's
 * line, or the last-line span when it fits) stays wholly in view; otherwise it takes the label out if [kept]'s top stays
 * in view; otherwise nothing moves. Returns the adjusted target.
 */
internal fun labelRule(target: Float, box: androidx.compose.ui.geometry.Rect, kept: ClosedFloatingPointRange<Float>,
    v: Float, margin: Float, labelBand: Float): Float {
    val fieldRel = box.top - target
    if (fieldRel <= -labelBand || fieldRel >= margin) return target
    val up = margin - fieldRel
    val down = fieldRel + labelBand
    return when {
        kept.endInclusive - (target - up) <= v -> target - up
        kept.start - (target + down) >= 0f -> target + down
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

    /** Whether the caret is on the field's last line (by line index; an empty field is on its last line). A2. */
    var lastLine = false
        private set

    /**
     * The field's trailing line (the report form's counter), if it publishes one: kept in view with the caret's line
     * while the caret is on the last line (A2). Cleared when it leaves composition, so a stale one can't drive a scroll.
     */
    var trailing: LayoutCoordinates? = null
        private set

    fun publish(coordinates: LayoutCoordinates, rect: androidx.compose.ui.geometry.Rect, lastLine: Boolean = false) {
        if (coordinates === this.coordinates && rect == this.rect && lastLine == this.lastLine) return
        this.coordinates = coordinates; this.rect = rect; this.lastLine = lastLine
        version++
    }

    fun publishTrailing(coordinates: LayoutCoordinates?) {
        if (coordinates === trailing) return
        trailing = coordinates
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

    /** The trailing line in [inside]'s coordinates, only while the caret is on the field's last line (A2). */
    fun trailingIn(inside: LayoutCoordinates): androidx.compose.ui.geometry.Rect? {
        if (!caret.lastLine) return null
        val trailing = caret.trailing?.takeIf { it.isAttached } ?: return null
        return inside.localBoundingBoxOf(trailing, clipBounds = false)
    }

    /** The current layout pass's key and mode, set first in the outer position callback (A3; see record()). */
    var layoutKey: Any? = null
    var layoutPinned = false

    /**
     * Records where the focused field and its caret sit in the scroller in use. The key and mode come from the layout
     * pass ([layoutKey], [layoutPinned]) once it has set them, not from the calling lambda, which can be a composition
     * behind; and a frame taller than the dialog's viewport (impossible for the scroller in use) is refused (A3, TL).
     */
    fun record(capturedKey: Any, capturedPinned: Boolean) {
        val (key, pinned) = recordAttribution(layoutKey, layoutPinned, capturedKey, capturedPinned)
        val child = coordinates?.takeIf { it.isAttached } ?: return
        val frame = (if (pinned) innerFrame else outerFrame)?.takeIf { it.isAttached } ?: return
        if (!acceptsFrame(frame.size.height, outerViewport)) return
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
        val valueOffset = if (pinned) box.top - (KeepProbe.inner?.value ?: 0)
            else (outerContent?.takeIf { it.isAttached }?.localPositionOf(inside, androidx.compose.ui.geometry.Offset.Zero)?.y ?: Float.NaN) +
                box.top - (KeepProbe.outer?.value ?: 0)
        val stale = kotlin.math.abs(field - valueOffset) > 0.5f
        val callers = Throwable().stackTrace.drop(1).take(3).joinToString("/") { it.lineNumber.toString() }
        KeepProbe.meta[seen] = "rec(d=${KeepProbe.dispatch} pinned=$pinned outer=${KeepProbe.outer?.value} inner=${KeepProbe.inner?.value} stale=$stale field=$field caret=$caret)"
        KeepProbe.log("REC key=$key pinned=$pinned field=$field valueOffset=$valueOffset stale=$stale caret=$caret visible=${seen.visible} " +
            "outer=${KeepProbe.outer?.value} inner=${KeepProbe.inner?.value} callers=$callers")
        if (key != lastKey) { previous = last; lastKey = key }
        last = seen
    }

    /**
     * Where they were in the layout before [key], only if measured for the same focused [child]. Frozen per change at
     * the first read (A3, TL): the readers of one height or mode change, the same-frame restore and its after-a-frame
     * fallback, see the same baseline. Without it, a record written between the two reads (after the same-frame scroll,
     * a child's position callback could still carry the old key) became `previous`, and the fallback restored from it
     * (#376's portrait text-first probe: 3080 in the same frame, then 2867 from the stray record, the caret at 319 not 106).
     */
    fun before(key: Any, child: LayoutCoordinates): Seen? =
        baseline.get(key) { if (key == lastKey) previous else last }?.takeIf { it.child === child }

    /** A new height or mode change was claimed: its baseline is read afresh (see [before]). */
    fun newChange() = baseline.reset()

    /** The focused field's place in the body column's own content, at the last placement (see [BodyPlace]). */
    var body: BodyPlace? = null
        private set

    /**
     * Records where the focused field, its caret line and its trailing line sit in the body column's own content, which a
     * scroll or a mode switch doesn't move, with the body's top and the space below it in the outer content, for the next
     * pass's placement-pass restore (#378).
     */
    fun recordBody() {
        val child = coordinates?.takeIf { it.isAttached }
        val inside = innerContent?.takeIf { it.isAttached }
        val frame = innerFrame?.takeIf { it.isAttached }
        val outerContentNow = outerContent?.takeIf { it.isAttached }
        if (child == null || inside == null || frame == null || outerContentNow == null) { body = null; return }
        val box = inside.localBoundingBoxOf(child, clipBounds = false)
        val bodyTop = outerContentNow.localPositionOf(frame, androidx.compose.ui.geometry.Offset.Zero).y
        body = BodyPlace(child, box, caretIn(inside, box), trailingIn(inside), bodyTop, inside.size.height,
            outerContentNow.size.height - (bodyTop + frame.size.height))
    }

    private val baseline = FrozenPerChange<Seen?>()
}

/** A3: a record's key and mode: the layout pass's once it has set them, else the caller's (before the first pass). */
internal fun recordAttribution(layoutKey: Any?, layoutPinned: Boolean, key: Any, pinned: Boolean): Pair<Any, Boolean> =
    if (layoutKey != null) layoutKey to layoutPinned else key to pinned

/**
 * A3: whether a record's frame can be the scroller in use: no taller than the dialog's viewport (±1 px). The outer
 * scroller's frame is that viewport and the pinned body's is inside it; one column's inner content is far taller (#376's
 * stray record: 3540 px against 357). An unmeasured viewport (0) accepts.
 */
internal fun acceptsFrame(frameHeight: Int, viewport: Int): Boolean = viewport <= 0 || frameHeight <= viewport + 1

/**
 * A3: the first value read in a change, kept for that change's later reads until [reset] marks a new one. A read for a
 * different key also reads afresh (a key no position callback claimed). A key coming back after another change is a new
 * change only through [reset], which the claim of each change calls.
 */
internal class FrozenPerChange<T> {
    private var key: Any? = null
    private var hasValue = false
    private var value: T? = null

    fun reset() {
        hasValue = false
        value = null
    }

    @Suppress("UNCHECKED_CAST")
    fun get(key: Any, compute: () -> T): T {
        if (!hasValue || this.key != key) {
            this.key = key
            value = compute()
            hasValue = true
        }
        return value as T
    }
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

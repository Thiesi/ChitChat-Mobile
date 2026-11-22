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
    BoxWithConstraints {
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
        val focused = remember { FocusedChild() }
        // The hook (PL and TL, 2026-11-22): while the caller holds an in-form notice, a layout change doesn't scroll back to
        // the focused field; the notice keeps the view until the person's next interaction. Off by default: unchanged.
        val held by androidx.compose.runtime.rememberUpdatedState(holdFocusedInView)
        LaunchedEffect(maxHeight, pinned) {
            withFrameNanos { } // After the layout at the new height.
            if (held) return@LaunchedEffect
            val child = focused.coordinates?.takeIf { it.isAttached } ?: return@LaunchedEffect
            val (state, content, viewport) = if (pinned) Triple(inner, focused.innerContent, focused.innerViewport)
                else Triple(outer, focused.outerContent, focused.outerViewport)
            val box = content?.takeIf { it.isAttached }?.localBoundingBoxOf(child, clipBounds = false) ?: return@LaunchedEffect
            val top = state.value.toFloat(); val bottom = top + viewport
            val target = when {
                box.top < top || box.height > viewport -> box.top          // Above, or taller than the view: its top.
                box.bottom > bottom -> box.bottom - viewport                // Below: just enough to show it whole.
                else -> return@LaunchedEffect
            }
            state.scrollTo(target.roundToInt())
        }
        Column(Modifier.onFocusedBoundsChanged { focused.coordinates = it }
            .onSizeChanged { focused.outerViewport = it.height }
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
            Box(Modifier.padding(bottom = 24.dp).then(if (pinned) Modifier.heightIn(max = available) else Modifier)) {
                CompositionLocalProvider(LocalContentColor provides AlertDialogDefaults.textContentColor,
                    LocalTextStyle provides MaterialTheme.typography.bodyMedium,
                    LocalFormDialogLayout provides layoutKey) {
                    Column(Modifier.onSizeChanged { focused.innerViewport = it.height }
                        .then(if (pinned) Modifier.scrollEdgeDividers(inner, MaterialTheme.colorScheme.outline)
                        .verticalScroll(inner) else Modifier).onGloballyPositioned { focused.innerContent = it },
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

/** Where the focused child is, and the two scrollers' content and viewport, for keeping it in view. Not state. */
private class FocusedChild {
    var coordinates: LayoutCoordinates? = null
    var outerContent: LayoutCoordinates? = null
    var innerContent: LayoutCoordinates? = null
    var outerViewport = 0
    var innerViewport = 0
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

package io.github.thiesi.chitchat.core.ui

import android.graphics.drawable.Drawable
import android.widget.ImageView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.focusable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import io.github.thiesi.chitchat.core.media.TransferState
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import io.github.thiesi.chitchat.R
import io.github.thiesi.chitchat.core.media.FullImage
import io.github.thiesi.chitchat.core.media.ImageState
import io.github.thiesi.chitchat.core.media.ViewerImage
import io.github.thiesi.chitchat.core.media.ViewerState
import io.github.thiesi.chitchat.core.media.attachmentSizeText
import io.github.thiesi.chitchat.core.status.Wait
import io.github.thiesi.chitchat.feature.status.waitText
import io.github.thiesi.chitchat.core.theme.Lantern
import kotlinx.coroutines.delay

private const val MAX_ZOOM = 5f

/**
 * The full-screen viewer (received files F4b; files-media.md "Full-screen viewer", TL and PL rulings of 2026-11-19).
 *
 * A Dialog window: it takes window focus, so the screens' read-proof gate is closed while it's open, and the screens also
 * count it as a modal for typing. Black ground, edge to edge, the same in every theme. Pinch and double-tap zoom, pan, and
 * swipe down at fit scale close it; Back and Close always do, so swipe-down is never the only way out. The zoom group is the
 * accessible alternative to the gestures. Save and Share arrive in F5 and aren't shown.
 *
 * F6 (PL spec, TL conditions of 2026-11-20): a single tap on the image, after the double-tap window, hides or shows the
 * chrome (the top bar, the bottom block, the zoom pill and the GIF badge). Hidden controls leave semantics and the focus
 * order; the image doesn't move. The toggle is off, the chrome always shown, with no image to look at, while a transfer
 * runs and while assistive input is on; a new state line or a transfer result brings it back, and so does any key but
 * Back and Esc (which close), with focus on Close. The image's "Show controls" and "Hide controls" actions mean a
 * service the detection misses can always bring it back.
 */
@Composable
internal fun ReceivedImageViewer(viewer: ViewerState, held: ImageState?, sender: String, time: String, caption: String?,
    description: String, onClose: () -> Unit, onLoadFull: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val still = (viewer.original as? ViewerImage.Still)?.image ?: (held as? ImageState.Loaded)?.image
        val animated = (viewer.original as? ViewerImage.Animated)?.animation
        val showsImage = animated != null || still?.bitmap != null
        val gif = viewer.ref.attachment.mimeType.equals("image/gif", ignoreCase = true)
        var scale by remember(viewer.key) { mutableFloatStateOf(1f) }
        var pan by remember(viewer.key) { mutableStateOf(Offset.Zero) }
        var captionOpen by remember(viewer.key) { mutableStateOf(false) }
        var area by remember { mutableStateOf(IntSize.Zero) }
        var topBar by remember { mutableIntStateOf(0) }
        // F6: hidden survives a rotation within this opening; a new opening starts shown.
        var hidden by rememberSaveable(viewer.key) { mutableStateOf(false) }
        var focusClose by remember { mutableStateOf(false) }
        val closeFocus = remember { FocusRequester() }
        val imageFocus = remember { FocusRequester() }
        val assistive = rememberAssistiveInput()
        val transfersUi = LocalFileTransfers.current
        val transferFlow = remember(transfersUi) { transfersUi?.transfers?.state ?: MutableStateFlow<TransferState?>(null) }
        val transfer by transferFlow.collectAsState()
        val running = transfer is TransferState.Running || transfer is TransferState.Ready
        val hideable = showsImage && viewer.full != FullImage.GONE && viewer.full != FullImage.NOT_ALLOWED && !running && !assistive
        val shown = !hidden || !hideable
        val currentHideable by rememberUpdatedState(hideable)
        // Whatever turns the toggle off shows the chrome for good (assistive input turning on shows it at once).
        LaunchedEffect(hideable) { if (!hideable) hidden = false }
        // A state line appearing or changing, or a transfer's result line, brings it back (never a progress tick).
        val line = viewer.full.takeIf { it == FullImage.OFFLINE || it == FullImage.WAITING || it == FullImage.FAILED || it == FullImage.NO_MEMORY }
        LaunchedEffect(line) { if (line != null) hidden = false }
        // The notice shows any transfer's line until it's dismissed, so this is the line on screen (PL).
        val result = transfer?.takeIf { it is TransferState.Saved || (it is TransferState.Stopped && it.hasLine()) }
        LaunchedEffect(result) { if (result != null) hidden = false }
        // Nothing keeps focus on a hidden control: hidden, the image holds it; shown after a key, Close takes it.
        LaunchedEffect(shown, focusClose) {
            if (!shown) runCatching { imageFocus.requestFocus() }
            else if (focusClose) { focusClose = false; runCatching { closeFocus.requestFocus() } }
        }
        val showControls = stringResource(R.string.viewer_show_controls)
        val hideControls = stringResource(R.string.viewer_hide_controls)
        // Pan stays within the scaled image's bounds, so a zoomed image can't be dragged off screen (TL, #313).
        fun bounded(offset: Offset, at: Float): Offset {
            val x = area.width * (at - 1f) / 2f; val y = area.height * (at - 1f) / 2f
            return Offset(offset.x.coerceIn(-x, x), offset.y.coerceIn(-y, y))
        }
        BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black).testTag("viewer").onPreviewKeyEvent { event ->
            when {
                // Esc and Back always close, hidden or not; Esc closes here so every API level does the same.
                event.key == Key.Escape -> { if (event.type == KeyEventType.KeyUp) onClose(); true }
                event.key == Key.Back -> false
                // System keys (volume, mute, media, headset, call) and a modifier alone pass through and change nothing (TL).
                event.nativeKeyEvent.isSystem || android.view.KeyEvent.isModifierKey(event.nativeKeyEvent.keyCode) -> false
                // Any other key while hidden first shows the chrome and puts focus on Close.
                !shown -> { if (event.type == KeyEventType.KeyDown) { hidden = false; focusClose = true }; true }
                else -> false
            }
        }) {
            val window = maxHeight
            val density = LocalDensity.current
            // The image, with the viewer's own description (TL condition 7, #313).
            Box(Modifier.fillMaxSize().onSizeChanged { area = it }
                .focusRequester(imageFocus).focusable(enabled = !shown)
                .semantics {
                    contentDescription = description
                    // TL: a service the detection misses can always bring the chrome back without a gesture.
                    if (!shown) onClick(label = showControls) { hidden = false; focusClose = true; true }
                    else if (hideable) customActions = listOf(CustomAccessibilityAction(hideControls) { hidden = true; true })
                }.testTag("viewer_image")
                .pointerInput(viewer.key) {
                    // onTap waits out the double-tap window, so a double-tap zooms and never toggles.
                    // A no-op onLongPress: without it a held, still press would end as a tap and toggle (PL).
                    detectTapGestures(onDoubleTap = { if (scale > 1f) { scale = 1f; pan = Offset.Zero } else scale = 2.5f },
                        onLongPress = { },
                        onTap = {
                            // A tap anywhere that isn't a control toggles (PL and TL): the scrims, the sender and time, a
                            // collapsed caption and the state lines are plain content. Controls keep their own taps.
                            if (currentHideable) hidden = !hidden
                        })
                }
                .pointerInput(viewer.key) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        var drag = 0f
                        var travelled = Offset.Zero
                        do {
                            val event = awaitPointerEvent()
                            val zoom = event.calculateZoom()
                            val move = event.calculatePan()
                            if (zoom != 1f || scale > 1f) {
                                scale = (scale * zoom).coerceIn(1f, MAX_ZOOM)
                                pan = if (scale == 1f) Offset.Zero else bounded(pan + move, scale)
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            } else if (event.changes.size == 1) {
                                drag += move.y
                                travelled += move
                                // F6: a drag is never a tap. Past the touch slop it's consumed, so the tap detector cancels.
                                if (travelled.getDistance() > viewConfiguration.touchSlop) event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        } while (event.changes.any { it.pressed })
                        // Swipe down at fit scale closes; a zoomed image pans instead.
                        if (scale == 1f && drag > 120.dp.toPx()) onClose()
                    }
                }, contentAlignment = Alignment.Center) {
                val layer = Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale; translationX = pan.x; translationY = pan.y }
                when {
                    animated != null -> AnimatedDrawable(animated.drawable as? Drawable, layer)
                    still?.bitmap != null -> {
                        val bitmap = remember(still) { still.bitmap.asImageBitmap() }
                        Image(bitmap, contentDescription = null, modifier = layer, contentScale = ContentScale.Fit)
                    }
                }
                // PL J5: a GIF shown still (a preview, offline, failed, or API 26–27) keeps its badge at the top start of the
                // fitted image, 8dp in; not while zoomed or animating.
                if (gif && animated == null && still != null && scale == 1f && area.width > 0 && still.width > 0 && still.height > 0) {
                    val fit = minOf(area.width.toFloat() / still.width, area.height.toFloat() / still.height)
                    val left = ((area.width - still.width * fit) / 2f).toInt()
                    val top = ((area.height - still.height * fit) / 2f).toInt()
                    val inset = with(density) { 8.dp.roundToPx() }
                    Chrome(shown, Modifier.align(Alignment.TopStart).offset { IntOffset(left + inset, top + inset) }) {
                        GifBadge(Modifier.testTag("viewer_gif_badge"))
                    }
                }
            }
            // Top bar on a scrim, inside the top and side insets only (PL J1): Close, the sender and the time. The sender is
            // server text: never ellipsized, so it may take several lines at 200% and break mid-token (PL).
            // Measured outside its inset padding: the bar's full height from the window's top edge, the status bar
            // included, is what the bottom must stay below (#313).
            Chrome(shown) {
            Row(Modifier.fillMaxWidth().background(Lantern.overlayScrim).onSizeChanged { topBar = it.height }
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .padding(horizontal = 4.dp, vertical = 4.dp).testTag("viewer_top"),
                verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose, modifier = Modifier.keepsTaps().size(Lantern.target).focusRequester(closeFocus).testTag("viewer_close")) {
                    Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.viewer_close), tint = Lantern.overlayText)
                }
                Column(Modifier.padding(horizontal = 8.dp).weight(1f).semantics(mergeDescendants = true) { heading() }) {
                    Text(sender, color = Lantern.overlayText, style = MaterialTheme.typography.titleSmall)
                    Text(time, color = Lantern.overlayText, style = MaterialTheme.typography.bodySmall)
                }
                // F5b (PL): Save, then Share outermost; absent when there's nothing to fetch, disabled offline and while
                // another transfer runs (its notice is in the bottom block). No Share for an APK-like file (TL).
                val transfers = LocalFileTransfers.current
                if (transfers != null && viewer.full != FullImage.GONE && viewer.full != FullImage.NOT_ALLOWED) {
                    val usable = viewer.full != FullImage.OFFLINE && rememberTransfersUsable(transfers)
                    TopIcon(R.drawable.ic_save, R.string.viewer_save, usable, "viewer_save") { transfers.save(viewer.ref) }
                    if (!io.github.thiesi.chitchat.core.media.FileNames.isApkLike(viewer.ref.attachment))
                        TopIcon(R.drawable.ic_share, R.string.viewer_share, usable, "viewer_share") {
                            transfers.hand(viewer.ref, io.github.thiesi.chitchat.core.media.TransferPurpose.SHARE)
                        }
                }
            }
            }
            // The bottom: the zoom pill and the bottom block. Together they never reach the top bar (PL J2): they're capped to
            // the space below it, and the expanded caption is what gives way.
            val below = (window - with(density) { topBar.toDp() }).coerceAtLeast(0.dp)
            // PL J8: in a window wider than tall the pill sits beside the bottom block, so the caption keeps its height.
            val wide = maxWidth > maxHeight
            val pill: (@Composable (Modifier) -> Unit)? = if (!showsImage) null else { modifier ->
                ZoomGroup(scale, modifier,
                    onZoomIn = { scale = (scale * 2f).coerceAtMost(MAX_ZOOM) },
                    onZoomOut = { scale = (scale / 2f).coerceAtLeast(1f); pan = if (scale == 1f) Offset.Zero else bounded(pan, scale) },
                    onReset = { scale = 1f; pan = Offset.Zero })
            }
            val showsSomething = still != null || animated != null
            @Composable fun BottomBlock(modifier: Modifier, sides: WindowInsetsSides) =
                Column(modifier.background(Lantern.overlayScrim).windowInsetsPadding(WindowInsets.safeDrawing.only(sides))
                    .padding(horizontal = 16.dp, vertical = 8.dp).testTag("viewer_bottom")
                    // Wide: the visual order differs from the reading order, so the block orders its own traversal (PL J8).
                    .then(if (wide) Modifier.semantics { isTraversalGroup = true } else Modifier),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val hasCaption = !caption.isNullOrBlank()
                    fun order(index: Float) = if (wide) Modifier.semantics { traversalIndex = index } else Modifier
                    if (hasCaption) {
                        // PL: two lines with an ellipsis and More; expanded, at most 40% of the window and scrolling.
                        val text = Modifier.testTag("viewer_caption").then(order(0f))
                        if (captionOpen) Text(caption!!, color = Lantern.overlayText,
                            modifier = text.weight(1f, fill = false).heightIn(max = window * 0.4f).verticalScroll(rememberScrollState()))
                        else Text(caption!!, color = Lantern.overlayText, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = text)
                    }
                    val toggle: @Composable () -> Unit = {
                        OverlayButton(stringResource(if (captionOpen) R.string.viewer_less else R.string.viewer_more), "viewer_caption_toggle",
                            modifier = order(1f)) { captionOpen = !captionOpen }
                    }
                    if (!wide) {
                        // Portrait: caption, More/Less, the state line, the action, one under another.
                        if (hasCaption) toggle()
                        FullImageState(viewer, showsSomething)
                        TransferNotice(overlay = true, withNote = false)
                        FullImageAction(viewer, onLoadFull)
                    } else {
                        // PL J8: the state line keeps its own row; More/Less and the action share the row under it, More/Less at
                        // the start and the action at the far end, two rows only when they can't fit side by side.
                        FullImageState(viewer, showsSomething, order(2f))
                        TransferNotice(order(2.5f), overlay = true, withNote = false)
                        val action = hasFullImageAction(viewer)
                        when {
                            hasCaption && action -> SharedRow(toggle) { FullImageAction(viewer, onLoadFull, order(3f)) }
                            hasCaption -> toggle()
                            else -> FullImageAction(viewer, onLoadFull, order(3f))
                        }
                    }
                }
            Chrome(shown, Modifier.align(Alignment.BottomCenter)) {
            if (!wide) Column(Modifier.fillMaxWidth().heightIn(max = below)) {
                pill?.invoke(Modifier.align(Alignment.End).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                    .padding(8.dp))
                BottomBlock(Modifier.fillMaxWidth().weight(1f, fill = false), WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)
            } else Row(Modifier.fillMaxWidth().heightIn(max = below).semantics { isTraversalGroup = true },
                verticalAlignment = Alignment.Bottom) {
                // The column narrows by the pill's width plus its 8dp; TalkBack still reaches the zoom group before the bar.
                BottomBlock(Modifier.weight(1f).semantics { traversalIndex = 1f },
                    if (pill == null) WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal else WindowInsetsSides.Bottom + WindowInsetsSides.Start)
                pill?.invoke(Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.End))
                    .padding(end = 8.dp, bottom = 8.dp).semantics { traversalIndex = 0f })
            }
            }
            // Too large and nothing to show: the line on the black ground, centred (PL J7), no zoom group.
            if (!showsImage && viewer.full == FullImage.TOO_LARGE) Text(stringResource(R.string.viewer_too_large),
                color = Lantern.overlayText, textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center).padding(24.dp).testTag("viewer_ground_line"))
        }
    }
}

/** Tests run the fade with a test clock here (null follows the system animator setting). */
internal val LocalChromeFade = staticCompositionLocalOf<Boolean?> { null }

/**
 * F6: a piece of the viewer's chrome. It fades for 150 ms both ways, instantly when the system animator scale is 0, and
 * its semantics clear as the fade-out starts, so TalkBack and the focus order lose it at once.
 */
@Composable
private fun Chrome(shown: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val fade = LocalChromeFade.current ?: remember { android.animation.ValueAnimator.areAnimatorsEnabled() }
    AnimatedVisibility(shown, modifier, enter = if (fade) fadeIn(tween(150)) else EnterTransition.None,
        exit = if (fade) fadeOut(tween(150)) else ExitTransition.None) {
        Box(if (shown) Modifier else Modifier.clearAndSetSemantics { }) { content() }
    }
}

@Composable
private fun AnimatedDrawable(drawable: Drawable?, modifier: Modifier) {
    val animatable = drawable as? android.graphics.drawable.Animatable
    // GIFs animate here only, and stop when the viewer closes or the app backgrounds (TL condition 5).
    LifecycleEventEffect(Lifecycle.Event.ON_START) { animatable?.start() }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { animatable?.stop() }
    DisposableEffect(drawable) { animatable?.start(); onDispose { animatable?.stop() } }
    AndroidView(factory = { ImageView(it).apply { scaleType = ImageView.ScaleType.FIT_CENTER } },
        update = { it.setImageDrawable(drawable) }, modifier = modifier.clearAndSetSemantics { })
}

/**
 * F6: a control keeps its taps, enabled or not, so a tap on it never reaches the image and toggles the chrome. A disabled
 * button's clickable may take no part in hit testing; this always does. It works because Compose sends a pointer event
 * along the first hit path only: a hit chrome sibling hides the event from the image sibling behind it, though nothing is
 * consumed, so the button's own handling is unchanged. Don't "fix" it by consuming, or by sharing input with siblings
 * (`sharePointerInputWithSiblings`): that would let the tap through to the image. `aTapOnADisabledSaveDoesNotToggle`
 * is the proof.
 */
private fun Modifier.keepsTaps() = pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }

/** Viewer text buttons are overlay-coloured, never persimmon (3.71:1 on black; PL J3); disabled at 38%. */
@Composable
private fun OverlayButton(text: String, tag: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) =
    TextButton(onClick = onClick, enabled = enabled, modifier = modifier.keepsTaps().heightIn(min = Lantern.target).testTag(tag),
        colors = ButtonDefaults.textButtonColors(contentColor = Lantern.overlayText,
            disabledContentColor = Lantern.overlayText.copy(alpha = 0.38f))) { Text(text) }

/** A 48dp overlay icon in the top bar, disabled at 38% (PL, F5b). */
@Composable
private fun TopIcon(icon: Int, label: Int, enabled: Boolean, tag: String, onClick: () -> Unit) =
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.keepsTaps().size(Lantern.target).testTag(tag)) {
        Icon(painterResource(icon), contentDescription = stringResource(label),
            tint = Lantern.overlayText.copy(alpha = if (enabled) 1f else 0.38f))
    }

@Composable
private fun ZoomGroup(scale: Float, modifier: Modifier, onZoomIn: () -> Unit, onZoomOut: () -> Unit, onReset: () -> Unit) {
    // PL J2: one horizontal pill on the overlay scrim with a 1dp border at 24% overlay text, so its shape holds where it
    // crosses the image edge. Zoom out and reset are disabled at fit, zoom in at the maximum (a state, not a missing feature).
    Row(modifier.keepsTaps().background(Lantern.overlayScrim, Lantern.pill).border(1.dp, Lantern.overlayText.copy(alpha = 0.24f), Lantern.pill)
        .testTag("viewer_zoom")) {
        ZoomButton(R.drawable.ic_zoom_in, R.string.viewer_zoom_in, scale < MAX_ZOOM, "viewer_zoom_in", onZoomIn)
        ZoomButton(R.drawable.ic_zoom_out, R.string.viewer_zoom_out, scale > 1f, "viewer_zoom_out", onZoomOut)
        ZoomButton(R.drawable.ic_zoom_reset, R.string.viewer_zoom_reset, scale > 1f, "viewer_zoom_reset", onReset)
    }
}

@Composable
private fun ZoomButton(icon: Int, label: Int, enabled: Boolean, tag: String, onClick: () -> Unit) {
    val text = stringResource(label)
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(Lantern.target).testTag(tag)
        .semantics { contentDescription = text }) {
        Icon(painterResource(icon), contentDescription = null, tint = Lantern.overlayText.copy(alpha = if (enabled) 1f else 0.38f))
    }
}

/**
 * Row 7's wait line with the shared strings and formatter (P-106): display only, only the server's positive wait, rounded
 * up, else "a little while". The countdown is bound to this composition; screen readers hear one stable line under a
 * minute and at most one announcement per minute bucket, as in the status slot.
 */
@Composable
private fun WaitLine(until: Long?, modifier: Modifier = Modifier) {
    var now by remember(until) { mutableStateOf(System.currentTimeMillis()) }
    if (until != null) LaunchedEffect(until) {
        while (true) {
            now = System.currentTimeMillis()
            val remaining = until - now
            if (remaining <= 0) break
            delay(if (remaining <= 60_000) minOf(1_000L, remaining) else minOf(remaining - 60_000, 60_000L))
        }
    }
    val wait = until?.let { (it - now).takeIf { left -> left > 0 }?.let(Wait::of) }
    val shown = if (wait == null) stringResource(R.string.status_rate_limited_unknown)
        else stringResource(R.string.status_rate_limited, waitText(wait))
    val spoken = if (wait is Wait.Seconds) stringResource(R.string.status_rate_limited_under_minute) else shown
    val bucket: Any = when (wait) {
        null -> "unknown"; is Wait.Seconds -> "seconds"; Wait.AboutAMinute -> "minute"
        is Wait.AboutMinutes -> "minutes:${wait.minutes}"; is Wait.AboutHours -> "hours:${wait.hours}"
    }
    androidx.compose.runtime.key(bucket) {
        Text(shown, color = Lantern.overlayText, style = MaterialTheme.typography.bodyMedium,
            // The tag goes inside: clearAndSetSemantics drops one chained after it (#313).
            modifier = modifier.clearAndSetSemantics { testTag = "viewer_line"; contentDescription = spoken; liveRegion = LiveRegionMode.Polite })
    }
}

/** The full-image state lines (PL J8: their own row in every layout); [modifier] goes on each line. */
@Composable
private fun FullImageState(viewer: ViewerState, showsSomething: Boolean, modifier: Modifier = Modifier) {
    @Composable fun line(text: String) = Text(text, color = Lantern.overlayText, style = MaterialTheme.typography.bodyMedium,
        modifier = modifier.testTag("viewer_line"))
    if (viewer.animationUnavailable) line(stringResource(R.string.viewer_no_animation))
    when (viewer.full) {
        FullImage.NONE, FullImage.OFFER -> Unit
        // PL J4: offline says why the button is off.
        FullImage.OFFLINE -> line(stringResource(R.string.viewer_offline))
        // PL: status row 7's line, since the viewer covers the status slot.
        FullImage.WAITING -> WaitLine(viewer.waitUntilMillis, modifier)
        FullImage.LOADING -> {
            var long by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) { delay(2_000); long = true }
            Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(progress = { 0.75f }, Modifier.size(20.dp), color = Lantern.overlayText, strokeWidth = 2.dp)
                if (long) Text(stringResource(R.string.viewer_loading_full), color = Lantern.overlayText,
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("viewer_line"))
            }
        }
        FullImage.FAILED -> line(stringResource(R.string.viewer_failed))
        FullImage.NO_MEMORY -> line(stringResource(R.string.viewer_no_memory))
        // With nothing shown the line sits on the black ground instead (see the viewer).
        FullImage.TOO_LARGE -> if (showsSomething) line(stringResource(R.string.viewer_too_large_preview))
        FullImage.GONE -> line(stringResource(R.string.image_gone))
        FullImage.NOT_ALLOWED -> line(stringResource(R.string.image_not_allowed))
    }
}

private fun hasFullImageAction(viewer: ViewerState) = viewer.full in setOf(FullImage.OFFER, FullImage.OFFLINE, FullImage.WAITING,
    FullImage.FAILED, FullImage.NO_MEMORY)

/** The full-image action: Load full image (disabled offline and during a 429 wait) or Try again. */
@Composable
private fun FullImageAction(viewer: ViewerState, onLoadFull: () -> Unit, modifier: Modifier = Modifier) {
    val locale = LocalConfiguration.current.locales[0]
    val load = stringResource(R.string.viewer_load_full, sizeText(attachmentSizeText(viewer.ref.attachment.sizeBytes, locale)))
    when (viewer.full) {
        FullImage.OFFER -> OverlayButton(load, "viewer_load_full", modifier = modifier, onClick = onLoadFull)
        FullImage.OFFLINE, FullImage.WAITING -> OverlayButton(load, "viewer_load_full", enabled = false, modifier = modifier, onClick = onLoadFull)
        FullImage.FAILED, FullImage.NO_MEMORY -> OverlayButton(stringResource(R.string.retry), "viewer_retry", modifier = modifier, onClick = onLoadFull)
        else -> Unit
    }
}

/**
 * PL J8: More/Less at the start and the action at the far end of one row, at least 8dp apart so they never touch; when
 * they can't both fit, two rows, More/Less first.
 */
@Composable
private fun SharedRow(start: @Composable () -> Unit, end: @Composable () -> Unit) {
    val gap = with(LocalDensity.current) { 8.dp.roundToPx() }
    androidx.compose.ui.layout.Layout(contents = listOf(start, end), modifier = Modifier.fillMaxWidth().testTag("viewer_action_row")) {
            (first, second), constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val a = first.map { it.measure(loose) }
        val b = second.map { it.measure(loose) }
        val aw = a.maxOfOrNull { it.width } ?: 0; val ah = a.maxOfOrNull { it.height } ?: 0
        val bw = b.maxOfOrNull { it.width } ?: 0; val bh = b.maxOfOrNull { it.height } ?: 0
        val width = constraints.maxWidth
        if (aw + gap + bw <= width) {
            val height = maxOf(ah, bh)
            layout(width, height) {
                a.forEach { it.place(0, (height - it.height) / 2) }
                b.forEach { it.place(width - it.width, (height - it.height) / 2) }
            }
        } else layout(width, ah + bh) {
            a.forEach { it.place(0, 0) }
            b.forEach { it.place(0, ah) }
        }
    }
}

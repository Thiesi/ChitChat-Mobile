package io.github.thiesi.chitchat.core.ui

import android.graphics.drawable.Drawable
import android.widget.ImageView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
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
 */
@Composable
internal fun ReceivedImageViewer(viewer: ViewerState, held: ImageState?, sender: String, time: String, caption: String?,
    description: String, onClose: () -> Unit, onLoadFull: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val still = (viewer.original as? ViewerImage.Still)?.image ?: (held as? ImageState.Loaded)?.image
        val animated = (viewer.original as? ViewerImage.Animated)?.animation
        val showsImage = animated != null || still?.bitmap != null
        var scale by remember(viewer.key) { mutableFloatStateOf(1f) }
        var pan by remember(viewer.key) { mutableStateOf(Offset.Zero) }
        var captionOpen by remember(viewer.key) { mutableStateOf(false) }
        Box(Modifier.fillMaxSize().background(Color.Black).testTag("viewer")) {
            // The image, with the frame's description (label plus state; TL condition 7).
            Box(Modifier.fillMaxSize().semantics { contentDescription = description }.testTag("viewer_image")
                .pointerInput(viewer.key) {
                    detectTapGestures(onDoubleTap = { if (scale > 1f) { scale = 1f; pan = Offset.Zero } else scale = 2.5f })
                }
                .pointerInput(viewer.key) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        var drag = 0f
                        do {
                            val event = awaitPointerEvent()
                            val zoom = event.calculateZoom()
                            val move = event.calculatePan()
                            if (zoom != 1f || scale > 1f) {
                                scale = (scale * zoom).coerceIn(1f, MAX_ZOOM)
                                pan = if (scale == 1f) Offset.Zero else pan + move
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            } else if (event.changes.size == 1) drag += move.y
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
            }
            // Top bar on a scrim: Close, then the sender (wrapping, never ellipsized; P-222) and the time.
            Row(Modifier.fillMaxWidth().background(Lantern.overlayScrim).windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose, modifier = Modifier.size(Lantern.target).testTag("viewer_close")) {
                    Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.viewer_close), tint = Lantern.overlayText)
                }
                Column(Modifier.padding(horizontal = 8.dp).weight(1f).semantics(mergeDescendants = true) { heading() }) {
                    Text(sender, color = Lantern.overlayText, style = MaterialTheme.typography.titleSmall)
                    Text(time, color = Lantern.overlayText, style = MaterialTheme.typography.bodySmall)
                }
            }
            // The bottom block on a scrim: the zoom group above it, then the caption and the full-image line.
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                if (showsImage) ZoomGroup(scale, Modifier.align(Alignment.End).padding(8.dp),
                    onZoomIn = { scale = (scale * 2f).coerceAtMost(MAX_ZOOM) },
                    onZoomOut = { scale = (scale / 2f).coerceAtLeast(1f); if (scale == 1f) pan = Offset.Zero },
                    onReset = { scale = 1f; pan = Offset.Zero })
                Column(Modifier.fillMaxWidth().background(Lantern.overlayScrim).windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (!caption.isNullOrBlank()) Caption(caption, captionOpen) { captionOpen = !captionOpen }
                    FullImageLine(viewer, still != null || animated != null, onLoadFull)
                }
            }
            // Too large and nothing to show: the line on the black ground, no zoom group.
            if (!showsImage && viewer.full == FullImage.TOO_LARGE) Text(stringResource(R.string.viewer_too_large),
                color = Lantern.overlayText, modifier = Modifier.align(Alignment.Center).padding(24.dp).testTag("viewer_ground_line"))
        }
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

@Composable
private fun ZoomGroup(scale: Float, modifier: Modifier, onZoomIn: () -> Unit, onZoomOut: () -> Unit, onReset: () -> Unit) {
    // PL: one pill on the overlay scrim; zoom out and reset are disabled at fit, zoom in at the maximum (a state, not a
    // missing feature).
    Column(modifier.background(Lantern.overlayScrim, Lantern.pill).testTag("viewer_zoom")) {
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

@Composable
private fun Caption(caption: String, open: Boolean, onToggle: () -> Unit) {
    // PL: two lines with an ellipsis and More; expanded, at most 40% of the window and scrolling inside.
    val cap = with(LocalDensity.current) { (androidx.compose.ui.platform.LocalWindowInfo.current.containerSize.height * 0.4f).toDp() }
    run {
        Column {
            val text = Modifier.testTag("viewer_caption")
            if (open) Text(caption, color = Lantern.overlayText, modifier = text.heightIn(max = cap).verticalScroll(rememberScrollState()))
            else Text(caption, color = Lantern.overlayText, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = text)
            TextButton(onClick = onToggle, modifier = Modifier.heightIn(min = Lantern.target).testTag("viewer_caption_toggle")) {
                Text(stringResource(if (open) R.string.viewer_less else R.string.viewer_more), color = Lantern.overlayText)
            }
        }
    }
}

@Composable
private fun FullImageLine(viewer: ViewerState, showsSomething: Boolean, onLoadFull: () -> Unit) {
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    val size = sizeText(attachmentSizeText(viewer.ref.attachment.sizeBytes, locale))
    @Composable fun line(text: String) = Text(text, color = Lantern.overlayText, style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.testTag("viewer_line"))
    @Composable fun action(text: String, enabled: Boolean = true, tag: String = "viewer_load_full") =
        TextButton(onClick = onLoadFull, enabled = enabled, modifier = Modifier.heightIn(min = Lantern.target).testTag(tag)) { Text(text) }
    if (viewer.animationUnavailable) line(stringResource(R.string.viewer_no_animation))
    when (viewer.full) {
        FullImage.NONE -> Unit
        FullImage.OFFER -> action(stringResource(R.string.viewer_load_full, size))
        FullImage.OFFLINE, FullImage.WAITING -> action(stringResource(R.string.viewer_load_full, size), enabled = false)
        FullImage.LOADING -> {
            var long by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) { delay(2_000); long = true }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(progress = { 0.75f }, Modifier.size(20.dp), color = Lantern.overlayText, strokeWidth = 2.dp)
                if (long) line(stringResource(R.string.viewer_loading_full))
            }
        }
        FullImage.FAILED -> { line(stringResource(R.string.viewer_failed)); action(stringResource(R.string.retry), tag = "viewer_retry") }
        FullImage.NO_MEMORY -> { line(stringResource(R.string.viewer_no_memory)); action(stringResource(R.string.retry), tag = "viewer_retry") }
        // With nothing shown the line sits on the black ground instead (see the viewer).
        FullImage.TOO_LARGE -> if (showsSomething) line(stringResource(R.string.viewer_too_large_preview))
        FullImage.GONE -> line(stringResource(R.string.image_gone))
        FullImage.NOT_ALLOWED -> line(stringResource(R.string.image_not_allowed))
    }
}

package io.github.thiesi.chitchat.core.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import io.github.thiesi.chitchat.R
import io.github.thiesi.chitchat.core.conversation.MessageAttachment
import io.github.thiesi.chitchat.core.media.AttachmentDownloads
import io.github.thiesi.chitchat.core.media.AttachmentRef
import io.github.thiesi.chitchat.core.media.ImageKey
import io.github.thiesi.chitchat.core.media.ImagePanes
import io.github.thiesi.chitchat.core.media.ImageState
import io.github.thiesi.chitchat.core.media.attachmentExtension
import io.github.thiesi.chitchat.core.media.attachmentFrameSize
import io.github.thiesi.chitchat.core.media.attachmentSizeText
import io.github.thiesi.chitchat.core.theme.ChitChatTheme
import io.github.thiesi.chitchat.core.theme.Lantern
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay

/**
 * What a transcript row needs to show received files (received files F3): the screen's [pane] on the app-scoped loader
 * (null while the screen is stopped), the loader's [states], and which message IDs are actually inside the viewport.
 */
internal class ReceivedImages(
    val pane: ImagePanes.Pane?,
    val states: Map<ImageKey, ImageState>,
    val key: (AttachmentRef) -> ImageKey?,
    val onScreen: Set<Long>,
) {
    override fun toString() = "ReceivedImages"
}

private val FrameShape = Lantern.frame

/**
 * A received file inside the message: a fixed-size image frame for a previewable image, else a file card. Either is its
 * own TalkBack node with no click or long-click action; taps on it reach the row's message actions (TL/PL, F3 (b)).
 * Only Show and Try again inside the frame are buttons. In F4 a tap on the frame opens the viewer ("Open photo").
 */
@Composable
internal fun ReceivedAttachment(attachment: MessageAttachment, direct: Boolean, messageId: Long, sender: String, time: String,
    caption: String?, images: ReceivedImages?, modifier: Modifier = Modifier) {
    if (attachment.previewable) ReceivedImageFrame(attachment, direct, messageId, sender, time, caption, images, modifier)
    else ReceivedFileCard(attachment, modifier)
}

@Composable
private fun ReceivedImageFrame(attachment: MessageAttachment, direct: Boolean, messageId: Long, sender: String, time: String,
    caption: String?, images: ReceivedImages?, modifier: Modifier) {
    val colors = ChitChatTheme.colors
    val locale = LocalConfiguration.current.locales[0]
    BoxWithConstraints(modifier) {
        val size = attachmentFrameSize(attachment.width, attachment.height, maxWidth.value)
        val density = LocalDensity.current
        val widthPx = with(density) { size.widthDp.dp.roundToPx() }.coerceAtLeast(1)
        val heightPx = with(density) { size.heightDp.dp.roundToPx() }.coerceAtLeast(1)
        val ref = remember(attachment, direct) { AttachmentRef(attachment, direct) }
        val pane = images?.pane
        val onScreen = images?.onScreen?.contains(messageId) == true
        if (pane != null) {
            // Visibility comes from the viewport, never from composition alone (TL, F3): prefetched rows don't load.
            LaunchedEffect(pane, onScreen, widthPx, heightPx) { if (onScreen) pane.visible(ref, widthPx, heightPx) else pane.hidden(ref) }
            DisposableEffect(pane, ref) { onDispose { pane.hidden(ref) } }
        }
        val state = images?.key?.invoke(ref)?.let { images.states[it] }
        val label = caption?.takeIf { it.isNotBlank() }?.let { stringResource(R.string.image_description_caption, sender, time, it) }
            ?: stringResource(R.string.image_description, sender, time)
        val photoSize = sizeText(attachmentSizeText(attachment.sizeBytes, locale))
        val dashed = state == ImageState.Gone
        val outline = colors.fieldLine
        Box(Modifier.size(size.widthDp.dp, size.heightDp.dp).clip(FrameShape).background(colors.raised)
            .then(if (dashed) Modifier.drawBehind {
                drawRoundRect(outline, cornerRadius = CornerRadius(14.dp.toPx()),
                    style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))))
            } else Modifier)
            .semantics(mergeDescendants = true) { contentDescription = label }
            .testTag("attachment_frame:${attachment.id}"), contentAlignment = Alignment.Center) {
            when (state) {
                is ImageState.Loaded -> state.image.bitmap?.let { bitmap ->
                    val image = remember(bitmap) { bitmap.asImageBitmap() }
                    // Contain-fit: the frame takes the image's ratio, and a clamped frame letterboxes on raised (no crop).
                    Image(image, contentDescription = null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                }
                ImageState.Loading -> FrameLoading()
                is ImageState.NotLoaded -> FrameBox(when (state.reason) {
                    ImageState.Reason.OFFLINE -> stringResource(R.string.image_offline, photoSize)
                    else -> stringResource(R.string.image_photo_size, photoSize)
                }, action = stringResource(R.string.image_show), enabled = state.reason == ImageState.Reason.POLICY,
                    tag = "attachment_show:${attachment.id}") { pane?.show(ref) }
                ImageState.Failed -> FrameBox(stringResource(R.string.image_failed), stringResource(R.string.retry),
                    tag = "attachment_retry:${attachment.id}") { pane?.retry(ref) }
                ImageState.NoMemory -> FrameBox(stringResource(R.string.image_no_memory), stringResource(R.string.retry),
                    tag = "attachment_retry:${attachment.id}") { pane?.retry(ref) }
                ImageState.TooLarge -> FrameBox(stringResource(R.string.image_too_large, photoSize))
                ImageState.Gone -> FrameBox(stringResource(R.string.image_gone), icon = false)
                ImageState.NotAllowed -> FrameBox(stringResource(R.string.image_not_allowed))
                // Not yet evaluated (off screen, or the instant before a decision): the size, nothing to press.
                null -> FrameBox(if (tooLargeForDisplay(attachment)) stringResource(R.string.image_too_large, photoSize)
                    else stringResource(R.string.image_photo_size, photoSize))
            }
            if (attachment.mimeType.equals("image/gif", ignoreCase = true) && state !is ImageState.Gone && state !is ImageState.NotAllowed)
                GifBadge(Modifier.align(Alignment.TopStart))
        }
    }
}

/** Beyond the display caps, decided from metadata (an original without a preview; previews are ≤ 1024 px). */
private fun tooLargeForDisplay(attachment: MessageAttachment): Boolean = attachment.preview == null && (
    attachment.sizeBytes > AttachmentDownloads.DISPLAY_MAX_BYTES ||
        (attachment.width ?: 0).toLong() * (attachment.height ?: 0) > AttachmentDownloads.DISPLAY_MAX_PIXELS)

@Composable
private fun FrameBox(text: String, action: String? = null, enabled: Boolean = true, tag: String? = null, icon: Boolean = true,
    onAction: () -> Unit = {}) {
    val colors = ChitChatTheme.colors
    Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (icon) Icon(painterResource(R.drawable.ic_image), contentDescription = null, Modifier.size(24.dp), tint = colors.textMuted)
        Text(text, style = MaterialTheme.typography.bodySmall, color = colors.textMuted, textAlign = TextAlign.Center)
        // Its own 48dp button: it takes the tap itself and never also opens message actions (TL/PL, F3).
        if (action != null) TextButton(onClick = onAction, enabled = enabled,
            modifier = Modifier.heightIn(min = Lantern.target).then(if (tag != null) Modifier.testTag(tag) else Modifier)) { Text(action) }
    }
}

@Composable
private fun FrameLoading() {
    // P-34: no shimmer; a static ring, and after about 2 seconds it says what is happening.
    var long by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(2_000); long = true }
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        CircularProgressIndicator(progress = { 0.75f }, Modifier.size(24.dp), strokeWidth = 2.dp)
        if (long) Text(stringResource(R.string.image_loading), style = MaterialTheme.typography.bodySmall,
            color = ChitChatTheme.colors.textMuted)
    }
}

@Composable
private fun GifBadge(modifier: Modifier) {
    val colors = ChitChatTheme.colors
    // A badge only in F3: the play glyph promises a tap that arrives with the viewer in F4 (PL).
    Text(stringResource(R.string.image_gif), style = MaterialTheme.typography.labelMedium, color = colors.inverseOnSurface,
        modifier = modifier.padding(6.dp).background(colors.scrim.copy(alpha = 0.6f), Lantern.chip)
            .padding(horizontal = 6.dp, vertical = 2.dp))
}

/**
 * Other files: type icon, the name (two lines, the middle elided so the extension stays), "PDF · 2.4 MB". No buttons
 * until F5 and no click semantics (TL/PL): its label is "{name}, {type}, {size}", APKs included.
 */
@Composable
private fun ReceivedFileCard(attachment: MessageAttachment, modifier: Modifier) {
    val colors = ChitChatTheme.colors
    val locale = LocalConfiguration.current.locales[0]
    val type = attachmentExtension(attachment.name)
    val size = sizeText(attachmentSizeText(attachment.sizeBytes, locale))
    val label = listOfNotNull(attachment.name, type, size).joinToString(", ")
    Surface(modifier.widthIn(max = 320.dp).semantics(mergeDescendants = true) { contentDescription = label }
        .testTag("attachment_card:${attachment.id}"), color = colors.raised, shape = FrameShape) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).background(colors.surface, MaterialTheme.shapes.small), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_document), contentDescription = null, Modifier.size(24.dp), tint = colors.textMuted)
            }
            Column(Modifier.padding(start = 12.dp)) {
                MiddleElidedName(attachment.name)
                Text(listOfNotNull(type, size).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = colors.textMuted,
                    maxLines = 1)
            }
        }
    }
}

/** Up to two lines; when longer, the middle is elided so the start and the extension stay visible. */
@Composable
private fun MiddleElidedName(name: String) {
    val style = MaterialTheme.typography.bodyMedium.copy(color = ChitChatTheme.colors.text)
    val measurer = rememberTextMeasurer()
    BoxWithConstraints {
        val constraints = Constraints(maxWidth = this.constraints.maxWidth)
        fun fits(text: String): TextLayoutResult? = measurer.measure(text, style, maxLines = 2, constraints = constraints)
            .takeIf { !it.hasVisualOverflow }
        val shown = remember(name, constraints.maxWidth, style) { middleElided(name) { fits(it) != null } }
        Text(shown, style = style, maxLines = 2, modifier = Modifier.testTag("attachment_card_name"))
    }
}

/** The longest "start…end" form of [name] that [fits], keeping the extension and a few characters before it. */
internal fun middleElided(name: String, fits: (String) -> Boolean): String {
    if (fits(name)) return name
    val dot = name.lastIndexOf('.')
    val tailLength = (if (dot > 0) name.length - dot else 0).plus(4).coerceAtMost(name.length / 2)
    val tail = name.takeLast(tailLength)
    var low = 0
    var high = name.length - tailLength
    while (low < high) {
        val mid = (low + high + 1) / 2
        if (fits(name.take(mid) + "…" + tail)) low = mid else high = mid - 1
    }
    return name.take(low) + "…" + tail
}

/**
 * A conversation screen's view of received images: a pane opened on ON_START with the screen's owner token and nav entry,
 * released on ON_STOP and on disposal (TL, F3), the loader's states, and which messages lie inside the viewport.
 */
@Composable
internal fun rememberReceivedImages(images: io.github.thiesi.chitchat.core.media.AttachmentImagesViewModel?, owner: Any, entry: String,
    timeline: androidx.compose.foundation.lazy.LazyListState, keyPrefix: String): ReceivedImages? = if (images == null) null else {
    var pane by remember { mutableStateOf<ImagePanes.Pane?>(null) }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_START) { pane = images.panes.open(owner, entry) }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_STOP) { pane?.release(); pane = null }
    DisposableEffect(images) { onDispose { pane?.release(); pane = null } }
    val states by images.images.states.collectAsStateWithLifecycle()
    val onScreen by remember(timeline, keyPrefix) { androidx.compose.runtime.derivedStateOf { onScreenIds(timeline.layoutInfo, keyPrefix) } }
    ReceivedImages(pane, states, images.images::key, onScreen)
}

/**
 * Message IDs whose rows intersect the viewport. visibleItemsInfo can include items outside it (content padding,
 * beyond-bounds layout for focus search), so only an actual overlap with viewportStartOffset..viewportEndOffset counts.
 */
internal fun onScreenIds(info: androidx.compose.foundation.lazy.LazyListLayoutInfo, keyPrefix: String): Set<Long> =
    info.visibleItemsInfo.asSequence()
        .filter { it.offset < info.viewportEndOffset && it.offset + it.size > info.viewportStartOffset }
        .mapNotNull { (it.key as? String)?.takeIf { key -> key.startsWith(keyPrefix) }?.removePrefix(keyPrefix)?.toLongOrNull() }
        .toSet()

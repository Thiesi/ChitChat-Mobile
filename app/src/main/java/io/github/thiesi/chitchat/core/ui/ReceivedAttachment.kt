package io.github.thiesi.chitchat.core.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.runtime.collectAsState
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
    /** The open viewer, when it was opened from this screen's nav entry (F4). */
    val viewer: io.github.thiesi.chitchat.core.media.ViewerState? = null,
    /** Whether GIFs animate in the viewer here; the play glyph promises exactly that (PL, F4). */
    val animates: Boolean = false,
) {
    override fun toString() = "ReceivedImages"
}

private val FrameShape = Lantern.frame

/**
 * A received file inside the message: a fixed-size image frame for a previewable image, else a file card, each its own
 * TalkBack node inside the row's message-actions target (TL/PL, F3 (b)). The card has no click semantics until F5. The
 * frame's tap opens the viewer ("Open photo", F4) and takes precedence; long-press keeps [onActions]. Gone and not allowed
 * have nothing to show, so their frame has no tap and taps reach the row's actions (TL, F4). Show and Try again are buttons.
 */
@Composable
internal fun ReceivedAttachment(attachment: MessageAttachment, direct: Boolean, messageId: Long, sender: String, time: String,
    caption: String?, images: ReceivedImages?, modifier: Modifier = Modifier, onActions: (() -> Unit)? = null) {
    // An APK-like file (the MIME type or the extension, TL) is never previewed, even under an image type: its card is Save-only.
    if (attachment.previewable && !io.github.thiesi.chitchat.core.media.FileNames.isApkLike(attachment)) ReceivedImageFrame(attachment, direct, messageId, sender, time, caption, images, modifier, onActions)
    else ReceivedFileCard(attachment, direct, modifier)
}

@Composable
private fun ReceivedImageFrame(attachment: MessageAttachment, direct: Boolean, messageId: Long, sender: String, time: String,
    caption: String?, images: ReceivedImages?, modifier: Modifier, onActions: (() -> Unit)?) {
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
        val gif = attachment.mimeType.equals("image/gif", ignoreCase = true)
        val text = imageText(attachment, state, sender, time)
        val shown = text.shown
        val description = text.description
        val dashed = state == ImageState.Gone
        val outline = colors.fieldLine
        // Nothing to show behind Gone and Not allowed: no "Open photo" there (TL condition 6).
        val opens = state != ImageState.Gone && state != ImageState.NotAllowed
        val openLabel = stringResource(R.string.image_open)
        val actionsLabel = stringResource(R.string.message_actions)
        val screen = androidx.compose.ui.platform.LocalWindowInfo.current.containerSize
        Box(Modifier.size(size.widthDp.dp, size.heightDp.dp).clip(FrameShape).background(colors.raised)
            .then(if (dashed) Modifier.drawBehind {
                drawRoundRect(outline, cornerRadius = CornerRadius(14.dp.toPx()),
                    style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))))
            } else Modifier)
            .then(if (opens && pane != null) Modifier.combinedClickable(onClickLabel = openLabel, onLongClickLabel = actionsLabel,
                onClick = { pane.openViewer(ref, screen.width, screen.height) }, onLongClick = { onActions?.invoke() }) else Modifier)
            .semantics(mergeDescendants = true) { contentDescription = description }
            .testTag("attachment_frame:${attachment.id}"), contentAlignment = Alignment.Center) {
            when (state) {
                is ImageState.Loaded -> state.image.bitmap?.let { bitmap ->
                    val image = remember(bitmap) { bitmap.asImageBitmap() }
                    // Contain-fit: the frame takes the image's ratio, and a clamped frame letterboxes on raised (no crop).
                    Image(image, contentDescription = null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                }
                ImageState.Loading -> FrameLoading()
                is ImageState.NotLoaded -> FrameBox(shown.orEmpty(), action = stringResource(R.string.image_show),
                    enabled = state.reason == ImageState.Reason.POLICY, tag = "attachment_show:${attachment.id}") { pane?.show(ref) }
                ImageState.Failed, ImageState.NoMemory -> FrameBox(shown.orEmpty(), stringResource(R.string.retry),
                    tag = "attachment_retry:${attachment.id}") { pane?.retry(ref) }
                ImageState.Gone -> FrameBox(shown.orEmpty(), icon = false)
                // Too large, not allowed, and not yet evaluated (off screen, or the instant before a decision): text only.
                ImageState.TooLarge, ImageState.NotAllowed, null -> FrameBox(shown.orEmpty())
            }
            if (gif && state !is ImageState.Gone && state !is ImageState.NotAllowed) {
                GifBadge(Modifier.align(Alignment.TopStart))
                // The play glyph promises the viewer's animation, so it shows only where the phone can animate (PL, F4).
                if (state is ImageState.Loaded && images?.animates == true)
                    Icon(painterResource(R.drawable.ic_play), contentDescription = null, tint = Lantern.overlayText,
                        modifier = Modifier.size(Lantern.target).background(Lantern.overlayScrim, Lantern.pill).padding(12.dp)
                            .testTag("attachment_play:${attachment.id}"))
            }
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
    // A frame can be as short as 96dp (a panorama) and its height never changes, so the box lays out by priority
    // (review, #310): the button always fits whole, the line takes what's left (ellipsized; TalkBack still hears it in
    // full through the frame's description), and the icon shows only when there's room for all three.
    BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 4.dp), contentAlignment = Alignment.Center) {
        val line = with(LocalDensity.current) { MaterialTheme.typography.bodySmall.lineHeight.toDp() }
        val button = if (action != null) Lantern.target else 0.dp
        val roomForIcon = icon && maxHeight >= 24.dp + line + button + 8.dp
        Column(horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically)) {
            if (roomForIcon) Icon(painterResource(R.drawable.ic_image), contentDescription = null, Modifier.size(24.dp), tint = colors.textMuted)
            Text(text, style = MaterialTheme.typography.bodySmall, color = colors.textMuted, textAlign = TextAlign.Center,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            // Its own 48dp button: it takes the tap itself and never also opens message actions (TL/PL, F3).
            if (action != null) TextButton(onClick = onAction, enabled = enabled,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
                modifier = Modifier.heightIn(min = Lantern.target).then(if (tag != null) Modifier.testTag(tag) else Modifier)) {
                Text(action, maxLines = 1)
            }
        }
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
internal fun GifBadge(modifier: Modifier) {
    // A badge only in F3: the play glyph promises a tap that arrives with the viewer in F4 (PL). It sits on the image, so
    // its colours are theme-independent (PL, #310 J1).
    Text(stringResource(R.string.image_gif), style = MaterialTheme.typography.labelMedium, color = Lantern.overlayText,
        modifier = modifier.padding(6.dp).background(Lantern.overlayScrim, Lantern.chip)
            .padding(horizontal = 6.dp, vertical = 2.dp))
}

/**
 * Other files: type icon, the name (two lines, the middle elided so the extension stays), "PDF · 2.4 MB", then **Open**
 * and **Save** (48dp each; an APK-like file has Save only, TL). The card itself has no click; its label is
 * "{name}, {type}, {size}, Open, Save" (or ", Save"). The name drops bidi controls and is an isolated run, so a
 * right-to-left name can't reorder the rest (PL, F5b).
 */
@Composable
private fun ReceivedFileCard(attachment: MessageAttachment, direct: Boolean, modifier: Modifier) {
    val colors = ChitChatTheme.colors
    val locale = LocalConfiguration.current.locales[0]
    val transfers = LocalFileTransfers.current
    val ref = remember(attachment, direct) { io.github.thiesi.chitchat.core.media.AttachmentRef(attachment, direct) }
    val apk = io.github.thiesi.chitchat.core.media.FileNames.isApkLike(attachment)
    val name = io.github.thiesi.chitchat.core.media.FileNames.withoutBidiControls(attachment.name)
    val type = io.github.thiesi.chitchat.core.media.FileNames.typeLabel(attachment)
    val size = sizeText(attachmentSizeText(attachment.sizeBytes, locale))
    val openLabel = stringResource(R.string.file_open)
    val saveLabel = stringResource(R.string.file_save)
    val actions = if (transfers == null) emptyList() else listOfNotNull(openLabel.takeIf { !apk }, saveLabel)
    val label = (listOfNotNull(androidx.core.text.BidiFormatter.getInstance().unicodeWrap(name), type, size) + actions).joinToString(", ")
    Surface(modifier.widthIn(max = 320.dp).semantics(mergeDescendants = true) { contentDescription = label }
        .testTag("attachment_card:${attachment.id}"), color = colors.raised, shape = FrameShape) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).background(colors.surface, MaterialTheme.shapes.small), contentAlignment = Alignment.Center) {
                    Icon(painterResource(R.drawable.ic_document), contentDescription = null, Modifier.size(24.dp), tint = colors.textMuted)
                }
                Column(Modifier.padding(start = 12.dp)) {
                    MiddleElidedName(name)
                    Text(listOfNotNull(type, size).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = colors.textMuted,
                        maxLines = 1)
                }
            }
            if (transfers != null) {
                // Disabled while another transfer runs or during the session's wait; that transfer's notice is on this
                // screen (PL), and the status slot shows the wait.
                val state by transfers.transfers.state.collectAsState()
                val enabled = state == null || state is io.github.thiesi.chitchat.core.media.TransferState.Saved ||
                    state is io.github.thiesi.chitchat.core.media.TransferState.Stopped
                val usable = enabled && !transfers.transfers.busy && !transfers.transfers.waiting()
                Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!apk) TextButton(onClick = { transfers.hand(ref, io.github.thiesi.chitchat.core.media.TransferPurpose.OPEN) },
                        enabled = usable, modifier = Modifier.heightIn(min = Lantern.target).testTag("attachment_open:${attachment.id}")) { Text(openLabel) }
                    TextButton(onClick = { transfers.save(ref) }, enabled = usable,
                        modifier = Modifier.heightIn(min = Lantern.target).testTag("attachment_save:${attachment.id}")) { Text(saveLabel) }
                }
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
    timeline: androidx.compose.foundation.lazy.LazyListState, keyPrefix: String,
    isEntryCurrent: () -> Boolean = { true }): ReceivedImages? = if (images == null) null else {
    var pane by remember { mutableStateOf<ImagePanes.Pane?>(null) }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_START) { pane = images.panes.open(owner, entry) }
    val activity = androidx.activity.compose.LocalActivity.current
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_STOP) {
        pane?.release(); pane = null
        // The entry's own departure closes a viewer opened from it (TL, #313): ON_STOP, not for a configuration change,
        // with the entry no longer the current one (navigation, or it was popped). A genuine background keeps it, and a
        // composition dispose (a re-keyed or re-branched screen) is never a departure.
        if (activity?.isChangingConfigurations != true && !isEntryCurrent()) images.panes.departed(entry)
    }
    DisposableEffect(images, entry) {
        onDispose {
            pane?.release(); pane = null
            // The same check on dispose, in case Navigation disposes the leaving entry before its ON_STOP reaches us
            // (TL, #313): whichever comes first closes it (departed is idempotent). A dispose while the entry is still
            // current (a re-key, a ready flip) closes nothing.
            if (activity?.isChangingConfigurations != true && !isEntryCurrent()) images.panes.departed(entry)
        }
    }
    val states by images.images.states.collectAsStateWithLifecycle()
    val viewer by images.viewer.state.collectAsStateWithLifecycle()
    val onScreen by remember(timeline, keyPrefix) { androidx.compose.runtime.derivedStateOf { onScreenIds(timeline.layoutInfo, keyPrefix) } }
    ReceivedImages(pane, states, images.images::key, onScreen, viewer?.takeIf { it.entry == entry }, images.viewer.animates)
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

/**
 * The viewer image's TalkBack description (TL, #313): what the viewer itself shows, not the transcript frame's state. Showing
 * an image (its original, or the transcript's held one), it's the label alone; showing nothing, the label plus the viewer's
 * own line. The full-image line below stays its own node.
 */
@Composable
internal fun viewerDescription(viewer: io.github.thiesi.chitchat.core.media.ViewerState, held: ImageState?, sender: String, time: String): String {
    val attachment = viewer.ref.attachment
    val gif = attachment.mimeType.equals("image/gif", ignoreCase = true)
    val label = stringResource(if (gif) R.string.image_description_gif else R.string.image_description, sender, time)
    val shows = viewer.original != null || (held as? ImageState.Loaded)?.image?.bitmap != null
    if (shows) return label
    val line = when (viewer.full) {
        io.github.thiesi.chitchat.core.media.FullImage.TOO_LARGE -> stringResource(R.string.viewer_too_large)
        io.github.thiesi.chitchat.core.media.FullImage.GONE -> stringResource(R.string.image_gone)
        io.github.thiesi.chitchat.core.media.FullImage.NOT_ALLOWED -> stringResource(R.string.image_not_allowed)
        io.github.thiesi.chitchat.core.media.FullImage.FAILED -> stringResource(R.string.viewer_failed)
        io.github.thiesi.chitchat.core.media.FullImage.NO_MEMORY -> stringResource(R.string.viewer_no_memory)
        io.github.thiesi.chitchat.core.media.FullImage.LOADING -> stringResource(R.string.image_loading_spoken)
        else -> null
    }
    return line?.let { "$label. $it" } ?: imageText(attachment, held, sender, time).description
}

/** A frame's visible state line ([shown], null when loaded) and its TalkBack [description]. */
internal class ImageText(val shown: String?, val description: String)

/**
 * The frame's line and description, shared with the viewer's image (TL condition 7). The caption isn't repeated: the
 * message's actions node reads it. The box shows the state's parts after "Photo", joined with " · "; TalkBack reads a
 * node's contentDescription instead of its merged text, so the description carries the same parts after the label and a
 * full stop, joined with ", " (TL/PL, #310). Loading speaks "Loading"; a loaded image is the label alone.
 */
@Composable
internal fun imageText(attachment: MessageAttachment, state: ImageState?, sender: String, time: String): ImageText {
    val locale = LocalConfiguration.current.locales[0]
    val gif = attachment.mimeType.equals("image/gif", ignoreCase = true)
    val label = stringResource(if (gif) R.string.image_description_gif else R.string.image_description, sender, time)
    val photoSize = sizeText(attachmentSizeText(attachment.sizeBytes, locale))
    val photo = stringResource(R.string.image_photo)
    val parts: List<String>? = when (state) {
        is ImageState.Loaded -> null
        ImageState.Loading -> listOf(stringResource(R.string.image_loading_spoken))
        is ImageState.NotLoaded -> if (state.reason == ImageState.Reason.OFFLINE)
            listOf(photoSize, stringResource(R.string.image_offline_part)) else listOf(photoSize)
        ImageState.Failed -> listOf(stringResource(R.string.image_failed))
        ImageState.NoMemory -> listOf(stringResource(R.string.image_no_memory))
        ImageState.TooLarge -> listOf(photoSize, stringResource(R.string.image_too_large_part))
        ImageState.Gone -> listOf(stringResource(R.string.image_gone))
        ImageState.NotAllowed -> listOf(stringResource(R.string.image_not_allowed))
        null -> if (tooLargeForDisplay(attachment)) listOf(photoSize, stringResource(R.string.image_too_large_part)) else listOf(photoSize)
    }
    // Size-led lines get the "Photo" lead on screen; status sentences stand alone.
    val sized = parts?.firstOrNull() == photoSize
    return ImageText(parts?.let { (if (sized) listOf(photo) + it else it).joinToString(" · ") },
        parts?.let { "$label. ${it.joinToString(", ")}" } ?: label)
}

/** The viewer over a conversation screen, for the message whose attachment it shows; it closes if that message is gone. */
@Composable
internal fun ReceivedViewerHost(received: ReceivedImages?, settled: Boolean, find: @Composable (Long) -> Triple<String, String, String?>?) {
    val viewer = received?.viewer ?: return
    val meta = find(viewer.ref.attachment.id)
    // Only a settled conversation without the message closes it; a reload (empty for a moment) never does (TL, #313).
    if (meta == null) { if (settled) LaunchedEffect(viewer.key) { received.pane?.closeViewer() }; return }
    val (sender, time, caption) = meta
    val held = received.states[viewer.key]
    ReceivedImageViewer(viewer, held, sender, time, caption, viewerDescription(viewer, held, sender, time),
        onClose = { received.pane?.closeViewer() }, onLoadFull = { received.pane?.loadFullImage() })
}

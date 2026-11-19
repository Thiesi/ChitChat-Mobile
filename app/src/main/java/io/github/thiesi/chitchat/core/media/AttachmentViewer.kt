package io.github.thiesi.chitchat.core.media

import io.github.thiesi.chitchat.core.session.SessionPhase
import io.github.thiesi.chitchat.core.session.SessionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A decoded, possibly animated image the viewer owns while it's open. The UI starts and stops it. */
internal interface DecodedAnimation {
    val width: Int
    val height: Int
    /** The platform drawable to draw, or null in tests. */
    val drawable: Any?
    fun start()
    fun stop()
}

internal sealed interface AnimationResult {
    class Decoded(val animation: DecodedAnimation) : AnimationResult
    data object TooLarge : AnimationResult
    data object Unreadable : AnimationResult
    data object NoMemory : AnimationResult
}

/**
 * Decodes an animated image (API 28+, `ImageDecoder`) at most [maxWidth]×[maxHeight], within the 16 Mpx guard. [lease] sets the
 * charge before allocating: it must cover the source bytes, which the drawable keeps for its whole life, plus its frame
 * buffers (TL, F4).
 */
internal fun interface AnimatedDecoding {
    fun decode(bytes: ByteArray, maxWidth: Int, maxHeight: Int, lease: (Long) -> Boolean): AnimationResult
}

/** What the viewer shows from its own original. Until it arrives, the transcript's held image (pinned) shows. */
internal sealed interface ViewerImage {
    class Still(val image: DecodedImage) : ViewerImage { override fun toString() = "ViewerImage.Still" }
    class Animated(val animation: DecodedAnimation) : ViewerImage { override fun toString() = "ViewerImage.Animated" }
}

/** The bottom bar's state for the original (files-media.md "Full-screen viewer"). */
internal enum class FullImage {
    /** Nothing to offer: the original is shown, or there's nothing more to load. */
    NONE,
    /** "Load full image · {size}": beyond the automatic rule; only that tap fetches it. */
    OFFER,
    LOADING,
    /** "Couldn't load the full image" + Try again; the preview stays. An integrity mismatch is never shown. */
    FAILED,
    /** "Couldn't show the full image right now" + Try again. */
    NO_MEMORY,
    /** Beyond the display caps (metadata): never fetched. */
    TOO_LARGE,
    GONE,
    NOT_ALLOWED,
    /** Offline: Load full image is disabled until back online. */
    OFFLINE,
    /** A session 429 wait: nothing starts, and nothing starts by itself afterwards. */
    WAITING,
}

internal data class ViewerState(
    val ref: AttachmentRef,
    val key: ImageKey,
    /** The nav entry it was opened from: only a departure from that entry closes it. */
    val entry: String,
    val original: ViewerImage?,
    val full: FullImage,
    /** A GIF on a phone that can't animate it (API 26–27): shown still, with the PL's line. */
    val animationUnavailable: Boolean,
)

/** What a pane drives (F4): [AttachmentViewer] in the app. */
internal interface ImageViewer {
    fun open(ref: AttachmentRef, entry: String, width: Int, height: Int)
    fun loadFull()
    fun close()
    fun closeIfFrom(entry: String)
}

/**
 * The full-screen viewer's session (received files F4a; TL and PL rulings of 2026-11-19). Main-confined; one at a time.
 *
 * - **Preview first:** the transcript's held image shows at once (pinned in the loader, so it's never evicted underneath).
 *   The original loads by itself only under the loader's rule (at most 2 MiB, unmetered, online, no 429 wait); otherwise
 *   the bar offers it, and only that tap fetches it. Without a preview, opening loads the original (as the transcript did).
 *   Opening a GIF is the explicit request for its original, which animates on API 28+.
 * - **The original goes through F1 unchanged** (caps, integrity, the session wait). A failure keeps the preview and nothing
 *   reloads by itself.
 * - **Memory:** the original decodes to the screen, within the 16 Mpx guard, on the shared ledger with the lease before every
 *   allocation. It's held only while the viewer is open: close, a departure from its entry, a session change or a cancelled
 *   load releases it. Nothing is cached.
 */
internal class AttachmentViewer(
    private val scope: CoroutineScope,
    private val session: StateFlow<SessionState>,
    private val network: StateFlow<NetworkState>,
    private val source: AttachmentSource,
    private val images: AttachmentImages,
    private val decoder: ImageDecoding,
    /** Null below API 28: a GIF shows still. */
    private val animated: AnimatedDecoding?,
    private val decodeOn: CoroutineDispatcher = Dispatchers.Default,
) : ImageViewer {
    private class Held(val lease: AttachmentMemoryBudget.Reservation, val image: ViewerImage)

    private val mutable = MutableStateFlow<ViewerState?>(null)
    val state: StateFlow<ViewerState?> = mutable.asStateFlow()
    private var held: Held? = null
    private var job: Job? = null
    private var width = 1
    private var height = 1
    private var scopeOf: Any? = session.value.scope()

    init {
        scope.launch { session.collect { next -> if (next.scope() != scopeOf) { scopeOf = next.scope(); close() } } }
    }

    override fun open(ref: AttachmentRef, entry: String, width: Int, height: Int) {
        close()
        val key = images.key(ref) ?: return
        this.width = width.coerceAtLeast(1); this.height = height.coerceAtLeast(1)
        val attachment = ref.attachment
        val gif = attachment.mimeType.equals("image/gif", ignoreCase = true)
        images.pin(ref)
        mutable.value = ViewerState(ref, key, entry, null, FullImage.NONE, animationUnavailable = gif && animated == null)
        val preview = images.loadsPreview(ref)
        // The transcript's image underneath: Show is implied by opening (an explicit request), but never a second
        // download of the original when there's no preview: the viewer loads that one itself.
        if (preview) images.show(ref)
        when {
            attachment.originalBeyondDisplayCaps() -> set(FullImage.TOO_LARGE)
            !preview || (gif && animated != null) -> fetch()
            automatic(attachment.sizeBytes) -> fetch()
            !network.value.online -> set(FullImage.OFFLINE)
            else -> set(FullImage.OFFER)
        }
    }

    /** Load full image or Try again: the explicit request. */
    override fun loadFull() {
        val current = mutable.value ?: return
        if (current.original != null || current.full == FullImage.LOADING || current.full == FullImage.TOO_LARGE) return
        fetch()
    }

    override fun close() {
        job?.cancel(); job = null
        release()
        mutable.value?.let { images.unpin(it.ref) }
        mutable.value = null
    }

    override fun closeIfFrom(entry: String) { if (mutable.value?.entry == entry) close() }

    private fun automatic(size: Long) = network.value.online && !network.value.metered && !images.waitingNow() &&
        size <= AttachmentImages.AUTO_MAX_BYTES

    private fun set(full: FullImage) { mutable.value = mutable.value?.copy(full = full) }

    private fun fetch() {
        val opened = mutable.value ?: return
        if (!network.value.online) { set(FullImage.OFFLINE); return }
        if (images.waitingNow()) { set(FullImage.WAITING); return }
        set(FullImage.LOADING)
        val expected = session.value
        val animate = opened.ref.attachment.mimeType.equals("image/gif", ignoreCase = true) && animated != null
        job = scope.launch {
            val result = source.load(opened.ref, AttachmentVariant.ORIGINAL, expected, null)
            if (mutable.value?.key != opened.key || mutable.value?.ref !== opened.ref) { (result as? DownloadResult.Bytes)?.release(); return@launch }
            when (result) {
                is DownloadResult.Bytes -> settle(opened, result, animate)
                DownloadResult.TooLarge -> set(FullImage.TOO_LARGE)
                DownloadResult.NoMemory -> set(FullImage.NO_MEMORY)
                DownloadResult.Gone -> set(FullImage.GONE)
                DownloadResult.NotAllowed -> set(FullImage.NOT_ALLOWED)
                is DownloadResult.RateLimited -> set(FullImage.WAITING)
                else -> set(FullImage.FAILED) // Network, a SHA-256 mismatch (never shown) or anything unexpected.
            }
        }
    }

    /** What a decode produced; applied on Main only after the stale check (review, #312). */
    private sealed interface Outcome {
        class Shown(val image: ViewerImage) : Outcome
        class Refused(val full: FullImage) : Outcome
    }

    private suspend fun settle(opened: ViewerState, result: DownloadResult.Bytes, animate: Boolean) {
        val lease: (Long) -> Boolean = { size -> result.resize(size) }
        // The decode is CPU-bound and runs off Main: it only returns an outcome and never touches the state, so a decode for
        // a viewer that has since closed or moved on can't write into its successor.
        val outcome: Outcome = try {
            withContext(decodeOn) {
                if (animate) when (val decoded = animated!!.decode(result.bytes, width, height, lease)) {
                    is AnimationResult.Decoded -> Outcome.Shown(ViewerImage.Animated(decoded.animation))
                    AnimationResult.TooLarge -> Outcome.Refused(FullImage.TOO_LARGE)
                    AnimationResult.NoMemory -> Outcome.Refused(FullImage.NO_MEMORY)
                    AnimationResult.Unreadable -> Outcome.Refused(FullImage.FAILED)
                } else when (val decoded = decoder.decode(result.bytes, width, height, true, lease)) {
                    is DecodeResult.Decoded -> Outcome.Shown(ViewerImage.Still(decoded.image))
                    DecodeResult.TooLarge -> Outcome.Refused(FullImage.TOO_LARGE)
                    DecodeResult.NoMemory -> Outcome.Refused(FullImage.NO_MEMORY)
                    DecodeResult.Unreadable -> Outcome.Refused(FullImage.FAILED)
                }
            }
        } catch (cancelled: CancellationException) { result.release(); throw cancelled }
        if (mutable.value?.key != opened.key || mutable.value?.ref !== opened.ref) {
            ((outcome as? Outcome.Shown)?.image as? ViewerImage.Animated)?.animation?.stop()
            result.release(); return
        }
        when (outcome) {
            is Outcome.Refused -> { result.release(); set(outcome.full) }
            is Outcome.Shown -> {
                // A still image's lease is now exactly its bitmap; an animation's keeps the source bytes it retains.
                held = Held(result.lease(), outcome.image)
                mutable.value = mutable.value?.copy(original = outcome.image, full = FullImage.NONE)
            }
        }
    }

    private fun release() {
        held?.let { (it.image as? ViewerImage.Animated)?.animation?.stop(); it.lease.close() }
        held = null
    }

    private data class Scope(val origin: String, val userId: Long, val generation: Long)
    private fun SessionState.scope(): Scope? = user?.takeIf { phase == SessionPhase.AUTHENTICATED && !it.guest }
        ?.let { Scope(server?.value ?: return null, it.id, generation) }
}

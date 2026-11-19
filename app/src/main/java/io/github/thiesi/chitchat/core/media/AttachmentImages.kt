package io.github.thiesi.chitchat.core.media

import io.github.thiesi.chitchat.core.session.SessionPhase
import io.github.thiesi.chitchat.core.session.SessionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The download seam the loader uses: [AttachmentDownloads] in the app, a fake in tests. */
internal interface AttachmentSource {
    suspend fun load(ref: AttachmentRef, variant: AttachmentVariant, expected: SessionState, ifNoneMatch: String? = null): DownloadResult
}

/** One received image, scoped to the account and session generation it was shown in. */
data class ImageKey(val origin: String, val userId: Long, val generation: Long, val direct: Boolean, val attachmentId: Long)

/** What a frame shows (files-media.md "Image states"). The loader never touches read or typing proof. */
sealed interface ImageState {
    data class NotLoaded(val reason: Reason) : ImageState
    enum class Reason {
        /** Show: beyond the automatic rule (over 2 MiB or a metered network). */
        POLICY,
        /** Offline: Show disabled until back online. */
        OFFLINE,
        /** The session is waiting out a 429: nothing starts automatically meanwhile (TL). */
        WAITING,
    }
    data object Loading : ImageState
    class Loaded(val image: DecodedImage, val variant: AttachmentVariant) : ImageState {
        override fun toString() = "ImageState.Loaded"
    }
    /** "Couldn't load the photo" + Try again: network, a mismatch with the server's SHA-256, or unreadable bytes. */
    data object Failed : ImageState
    /** "Couldn't show the photo right now" + Try again: no memory budget. Nothing retries by itself. */
    data object NoMemory : ImageState
    /** Beyond the display caps: Save or share it instead. */
    data object TooLarge : ImageState
    data object Gone : ImageState
    data object NotAllowed : ImageState
}

/**
 * Received images for the transcript (received files F2; files-media.md "Image loading policy"). Main-confined.
 *
 * - **Automatic loading** only for a frame that's on screen, online and unmetered, whose bytes (the preview when there
 *   is one, else the original) are at most 2 MiB; otherwise Show. A frame gets at most one automatic attempt; Try again
 *   and Show are the explicit requests. Once shown, an image stays shown for that message during the session.
 * - **A session wait (429) starts nothing automatically** and queues nothing (TL): a frame that would auto-load shows
 *   [ImageState.Reason.WAITING]; when the wait ends, frames still on screen auto-load once each, never as a burst of
 *   frames no longer visible.
 * - **Memory only** (TL 2026-11-09; privacy policy "in memory only"): each held image keeps its memory-budget lease,
 *   resized to the decoded size; least-recently-used images not on screen are evicted beyond [capacityBytes]. Everything
 *   is dropped on a change of server, account, generation or phase. A held preview keeps its ETag and is revalidated
 *   when shown again, **at most once per conversation entry** ([enteredConversation]; TL, #308), so scrolling back and
 *   forth never turns into a stream of requests (a 304 keeps it); an original has no validator and is held as shown (TL).
 * - **A missing preview** falls back to the original under the same rule, never retried as a preview this session.
 */
internal class AttachmentImages(
    private val scope: CoroutineScope,
    private val session: StateFlow<SessionState>,
    private val network: StateFlow<NetworkState>,
    private val source: AttachmentSource,
    private val decoder: ImageDecoding,
    private val capacityBytes: Long,
    private val clock: () -> Long = System::currentTimeMillis,
    private val decodeOn: CoroutineDispatcher = Dispatchers.Default,
) : ImageFrames {
    private class Held(val image: DecodedImage, val variant: AttachmentVariant, val etag: String?, val lease: AttachmentMemoryBudget.Reservation)

    private class Frame(var ref: AttachmentRef, var width: Int, var height: Int) {
        var visible = false
        var shown = false
        var autoTried = false
        var previewUnavailable = false
        var held: Held? = null
        var job: Job? = null
        var lastUsed = 0L
        /** The conversation entry in which this frame last revalidated its held preview. */
        var revalidatedIn = -1L
        /** Open viewers showing this frame: while pinned, its held image counts as visible and is never evicted (TL, F4). */
        var pinned = 0
    }

    private val frames = LinkedHashMap<ImageKey, Frame>()
    private val mutable = MutableStateFlow<Map<ImageKey, ImageState>>(emptyMap())
    val states: StateFlow<Map<ImageKey, ImageState>> = mutable.asStateFlow()
    private var scopeOf: Scope? = session.value.scope()
    private var waitEnd: Job? = null
    private var use = 0L
    private var entry = 0L

    /** A new visit to a room or DM: each held preview may revalidate once more (TL, #308). */
    override fun enteredConversation() { entry++ }

    init {
        scope.launch { session.collect { onSession(it) } }
        scope.launch {
            var wasOnline = network.value.online
            network.collect { now ->
                // Back online: frames still on screen are evaluated once under the ordinary rule.
                if (now.online && !wasOnline) frames.keys.toList().forEach { key -> frames[key]?.takeIf { it.visible }?.let { evaluate(key, it) } }
                wasOnline = now.online
            }
        }
    }

    fun key(ref: AttachmentRef): ImageKey? = scopeOf?.let { ImageKey(it.origin, it.userId, it.generation, ref.direct, ref.attachment.id) }

    /** The frame for [ref] is on screen, [width]×[height] px: decode no larger than that. */
    override fun visible(ref: AttachmentRef, width: Int, height: Int) {
        val key = key(ref) ?: return
        val frame = frames.getOrPut(key) { Frame(ref, width, height) }
        frame.ref = ref; frame.width = width; frame.height = height
        val returning = !frame.visible
        frame.visible = true
        touch(frame)
        val held = frame.held
        if (held != null) { if (returning) revalidate(key, frame, held); return }
        evaluate(key, frame)
    }

    override fun hidden(ref: AttachmentRef) { key(ref)?.let { frames[it]?.visible = false } }

    /** Show (explicit): loads now when online, even over 2 MiB or on a metered network. */
    override fun show(ref: AttachmentRef) {
        val key = key(ref) ?: return
        val frame = frames.getOrPut(key) { Frame(ref, 0, 0) }
        frame.shown = true
        explicit(key, frame)
    }

    /** Try again (explicit): one new GET. */
    override fun retry(ref: AttachmentRef) {
        val key = key(ref) ?: return
        explicit(key, frames[key] ?: return)
    }

    private fun explicit(key: ImageKey, frame: Frame) {
        if (frame.job?.isActive == true || frame.held != null) return
        // Beyond the caps even an explicit Show fetches nothing: Save or share it instead.
        if (tooLarge(frame)) { set(key, ImageState.TooLarge); return }
        if (!network.value.online) { set(key, ImageState.NotLoaded(ImageState.Reason.OFFLINE)); return }
        start(key, frame)
    }

    /** The automatic rule for a frame without a held image. */
    private fun evaluate(key: ImageKey, frame: Frame) {
        if (frame.job?.isActive == true || frame.held != null) return
        if (tooLarge(frame)) { set(key, ImageState.TooLarge); return }
        val current = mutable.value[key]
        if (current != null && current !is ImageState.NotLoaded) return // A settled outcome stays until an explicit request.
        when {
            !network.value.online -> set(key, ImageState.NotLoaded(ImageState.Reason.OFFLINE))
            !frame.visible -> Unit
            waiting() -> { set(key, ImageState.NotLoaded(ImageState.Reason.WAITING)); scheduleWaitEnd() }
            frame.autoTried -> Unit
            frame.shown || (frameBytes(frame) <= AUTO_MAX_BYTES && !network.value.metered) -> { frame.autoTried = true; start(key, frame) }
            else -> set(key, ImageState.NotLoaded(ImageState.Reason.POLICY))
        }
    }

    private fun start(key: ImageKey, frame: Frame) {
        val expected = session.value
        val variant = variant(frame)
        val sentIn = entry // The visit this request is sent in (TL, F3).
        set(key, ImageState.Loading)
        frame.job = scope.launch {
            val result = source.load(frame.ref, variant, expected, null)
            if (frames[key] !== frame) { (result as? DownloadResult.Bytes)?.release(); return@launch }
            settle(key, frame, variant, result, sentIn)
        }
    }

    private suspend fun settle(key: ImageKey, frame: Frame, variant: AttachmentVariant, result: DownloadResult, sentIn: Long) {
        // The job stays set through the decode, so Show, Try again and revalidation can't start a second load meanwhile
        // (review, #308). Only the preview fallback, which restarts from inside this job, clears it first.
        when (result) {
            is DownloadResult.Bytes -> {
                // The decoder sets the lease before each allocation (M5g3h) and leaves it at exactly the kept bitmap. EXIF
                // orientation applies to originals only (TL, #308). The resize is thread-safe; eviction touches main-confined
                // state, so a refused lease comes back here: evict on main, then one more decode of the bytes still held.
                var refused = 0L
                val lease: (Long) -> Boolean = { size -> result.resize(size).also { if (!it) refused = size } }
                suspend fun decode() = try {
                    withContext(decodeOn) {
                        decoder.decode(result.bytes, frame.width.coerceAtLeast(1), frame.height.coerceAtLeast(1),
                            variant == AttachmentVariant.ORIGINAL, lease)
                    }
                } catch (cancelled: CancellationException) { result.release(); throw cancelled }
                var decoded = decode()
                if (decoded == DecodeResult.NoMemory && refused > 0 && frames[key] === frame && makeRoom(result, refused)) {
                    refused = 0L
                    decoded = decode()
                }
                when (decoded) {
                    is DecodeResult.Decoded -> {
                        if (frames[key] !== frame) { result.release(); return }
                        drop(frame) // Never replace a held image without closing its lease.
                        frame.held = Held(decoded.image, variant, result.etag, result.lease())
                        // A fresh 200 is the validation for the visit it was sent in: no conditional GET on a re-show in
                        // that visit (a rotation, a scroll back). A load that settles after a fresh entry leaves the new
                        // visit its one conditional GET, since the server could have changed meanwhile (TL, F3).
                        frame.revalidatedIn = sentIn
                        touch(frame)
                        set(key, ImageState.Loaded(decoded.image, variant))
                        evict(0)
                    }
                    DecodeResult.TooLarge -> { result.release(); set(key, ImageState.TooLarge) }
                    DecodeResult.Unreadable -> { result.release(); set(key, ImageState.Failed) }
                    DecodeResult.NoMemory -> { result.release(); set(key, ImageState.NoMemory) }
                }
            }
            DownloadResult.PreviewUnavailable -> {
                // Not an error: the original under the same rule, never the preview again this session.
                frame.previewUnavailable = true
                frame.job = null
                mutable.value = mutable.value - key
                if (frame.shown) start(key, frame) else { frame.autoTried = false; evaluate(key, frame) }
            }
            is DownloadResult.RateLimited -> {
                // The session now waits: nothing starts meanwhile, and this frame may auto-load once after it, if on screen.
                frame.autoTried = false
                set(key, ImageState.NotLoaded(ImageState.Reason.WAITING))
                scheduleWaitEnd()
            }
            DownloadResult.TooLarge -> set(key, ImageState.TooLarge)
            DownloadResult.NoMemory -> set(key, ImageState.NoMemory)
            DownloadResult.Gone -> set(key, ImageState.Gone)
            DownloadResult.NotAllowed -> set(key, ImageState.NotAllowed)
            DownloadResult.NotModified, DownloadResult.Saved, DownloadResult.IntegrityFailed, is DownloadResult.Failed -> set(key, ImageState.Failed)
        }
    }

    /** A held preview shown again: revalidated with its ETag when a request may start; a 304 keeps it. */
    private fun revalidate(key: ImageKey, frame: Frame, held: Held) {
        if (held.variant != AttachmentVariant.PREVIEW || held.etag == null || frame.job?.isActive == true ||
            !network.value.online || waiting() || frame.revalidatedIn == entry) return
        val sentIn = entry
        frame.revalidatedIn = sentIn
        val expected = session.value
        frame.job = scope.launch {
            val result = source.load(frame.ref, AttachmentVariant.PREVIEW, expected, held.etag)
            if (frames[key] !== frame || frame.held !== held) { (result as? DownloadResult.Bytes)?.release(); return@launch }
            when (result) {
                DownloadResult.NotModified -> Unit
                is DownloadResult.Bytes -> { drop(frame); settle(key, frame, AttachmentVariant.PREVIEW, result, sentIn) }
                DownloadResult.Gone, DownloadResult.NotAllowed -> { drop(frame); settle(key, frame, AttachmentVariant.PREVIEW, result, sentIn) }
                // Anything else leaves the held preview as it was: a failed revalidation never blanks a shown image.
                else -> Unit
            }
        }
    }

    private fun variant(frame: Frame) = if (frame.ref.attachment.preview != null && !frame.previewUnavailable &&
        frame.ref.attachment.preview!!.validFor(frame.ref.attachment.id, frame.ref.direct)) AttachmentVariant.PREVIEW else AttachmentVariant.ORIGINAL

    private fun frameBytes(frame: Frame): Long = if (variant(frame) == AttachmentVariant.PREVIEW)
        frame.ref.attachment.preview!!.sizeBytes else frame.ref.attachment.sizeBytes

    /** Decided from the metadata before any fetch: only an original can be beyond the caps (previews are ≤ 1024 px). */
    private fun tooLarge(frame: Frame): Boolean =
        variant(frame) == AttachmentVariant.ORIGINAL && frame.ref.attachment.originalBeyondDisplayCaps()

    private fun waiting() = clock() < session.value.retryAtMillis

    /** The viewer (F4) shows this frame: its held image is never evicted while pinned. */
    internal fun pin(ref: AttachmentRef) { val key = key(ref) ?: return; frames.getOrPut(key) { Frame(ref, 0, 0) }.pinned++ }
    internal fun unpin(ref: AttachmentRef) { key(ref)?.let { frames[it] }?.let { it.pinned = (it.pinned - 1).coerceAtLeast(0) } }
    /** A session 429 wait is running: nothing starts automatically (TL, #306). */
    internal fun waitingNow() = waiting()
    /** What the transcript frame loads for [ref]: the preview unless there's none, it's invalid, or it was unavailable. */
    internal fun loadsPreview(ref: AttachmentRef): Boolean {
        val frame = key(ref)?.let { frames[it] } ?: Frame(ref, 0, 0)
        return variant(frame) == AttachmentVariant.PREVIEW
    }

    /** One pass when the session's wait ends: frames still on screen and waiting auto-load once each. */
    private fun scheduleWaitEnd() {
        if (waitEnd?.isActive == true) return
        waitEnd = scope.launch {
            while (waiting()) delay((session.value.retryAtMillis - clock()).coerceAtLeast(1))
            frames.entries.toList().forEach { (key, frame) ->
                if (frame.visible && mutable.value[key] == ImageState.NotLoaded(ImageState.Reason.WAITING)) {
                    mutable.value = mutable.value - key
                    evaluate(key, frame)
                }
            }
        }
    }

    /** Frees least-recently-used held images not on screen until [needed] more bytes fit; true when they do. */
    private fun evict(needed: Long): Boolean {
        var held = frames.values.sumOf { it.held?.image?.byteCount ?: 0L }
        val candidates = frames.entries.filter { it.value.held != null && !it.value.visible && it.value.pinned == 0 }.sortedBy { it.value.lastUsed }
        for ((key, frame) in candidates) {
            if (held + needed <= capacityBytes) break
            held -= frame.held!!.image.byteCount
            drop(frame)
            // Evicted, not settled: back on screen it's evaluated afresh, so it reloads (review, #308).
            frame.autoTried = false
            mutable.value = mutable.value - key
        }
        return held + needed <= capacityBytes
    }

    /**
     * The shared memory budget refused [needed]: drop images not on screen, least recently used first, until the lease fits.
     * Only when something was dropped and it then fits is a second decode worth it.
     */
    private fun makeRoom(result: DownloadResult.Bytes, needed: Long): Boolean {
        val candidates = frames.entries.filter { it.value.held != null && !it.value.visible && it.value.pinned == 0 }.sortedBy { it.value.lastUsed }
        for ((key, frame) in candidates) {
            drop(frame)
            frame.autoTried = false
            mutable.value = mutable.value - key
            if (result.resize(needed)) return true
        }
        return false
    }

    private fun drop(frame: Frame) { frame.held?.lease?.close(); frame.held = null }

    private fun touch(frame: Frame) { frame.lastUsed = ++use }

    private fun set(key: ImageKey, state: ImageState) { mutable.value = mutable.value + (key to state) }

    private data class Scope(val origin: String, val userId: Long, val generation: Long)
    private fun SessionState.scope(): Scope? = user?.takeIf { phase == SessionPhase.AUTHENTICATED && !it.guest }
        ?.let { Scope(server?.value ?: return null, it.id, generation) }

    /** Any change of server, account, generation or phase drops every image and job (account-scoped, memory only). */
    private fun onSession(state: SessionState) {
        val next = state.scope()
        if (next == scopeOf) return
        scopeOf = next
        clear()
    }

    fun clear() {
        frames.values.forEach { it.job?.cancel(); drop(it) }
        frames.clear()
        waitEnd?.cancel()
        mutable.value = emptyMap()
    }

    companion object {
        /** The automatic rule's limit on what a frame would load (files-media.md). */
        const val AUTO_MAX_BYTES = 2L * 1024 * 1024
    }
}

/** Beyond the display caps (TL 2026-11-19: 32 MiB raw or 16 megapixels), decided from metadata before any fetch. */
internal fun io.github.thiesi.chitchat.core.conversation.MessageAttachment.originalBeyondDisplayCaps(): Boolean {
    val dimensions = displayDimensions
    return sizeBytes > AttachmentDownloads.DISPLAY_MAX_BYTES ||
        (dimensions != null && dimensions.width.toLong() * dimensions.height > AttachmentDownloads.DISPLAY_MAX_PIXELS)
}

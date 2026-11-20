package io.github.thiesi.chitchat.core.media

import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.network.FailureKind
import io.github.thiesi.chitchat.core.session.SessionState
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicLong
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

/** Where a received original is streamed (F5): [AttachmentDownloads.stream] in the app. */
internal fun interface FileSource {
    suspend fun stream(ref: AttachmentRef, expected: SessionState, open: () -> OutputStream, ensureCurrent: () -> Unit): DownloadResult
}

/** A Save target: opened only when the first body byte arrives (TL, 2026-11-20), never deleted by the app. */
internal fun interface SaveDestination {
    fun open(): OutputStream
}

/** The free space for a share copy (`StorageManager.getAllocatableBytes` on the cache volume in the app). */
internal fun interface CacheSpace {
    fun allocatableBytes(): Long
}

/** What a transfer is for. Share and Open use the same private copy (TL, 2026-11-19). */
internal enum class TransferPurpose { SAVE, SHARE, OPEN }

/** Why a transfer stopped; the presentation words each (files-media.md, PL rulings of 2026-11-20). */
internal enum class TransferStop {
    GONE, NOT_ALLOWED, RATE_LIMITED, FAILED, DAMAGED, STORAGE, NO_SPACE, NO_APP, CANCELLED, BACKGROUND
}

internal sealed interface TransferState {
    val ref: AttachmentRef
    val purpose: TransferPurpose

    /** Downloading: [written] of [total] bytes. */
    data class Running(override val ref: AttachmentRef, override val purpose: TransferPurpose, val written: Long, val total: Long) : TransferState

    /** Share or Open: the verified copy, waiting for the presentation to hand it over ([FileTransfers.handedOff]). */
    data class Ready(override val ref: AttachmentRef, override val purpose: TransferPurpose, val copy: ShareCopies.Copy, val mimeType: String?) : TransferState

    /** Save finished and verified. */
    data class Saved(override val ref: AttachmentRef) : TransferState { override val purpose get() = TransferPurpose.SAVE }

    /**
     * Stopped. [wroteBody] says whether a Save's document received any byte (opened only then): false means nothing
     * was written, so a chosen existing file is unchanged. [retryAfterSeconds] is the server's positive wait, if any.
     */
    data class Stopped(override val ref: AttachmentRef, override val purpose: TransferPurpose, val reason: TransferStop,
        val wroteBody: Boolean, val retryAfterSeconds: Long? = null) : TransferState
}

/**
 * Save, Share and Open for received files (F5a; files-media.md "Save" and "Share out"; TL plan of 2026-11-20).
 *
 * One transfer at a time, bound to the rendered session it started in (origin, account, generation): a session change
 * cancels it, discards a private copy and drops a pending Save, with no notice into another session. Bytes stream to
 * disk through [FileSource] (F1's path: integrity, the 429 session wait, never resent), never into memory.
 *
 * - **Save**: [requestSave] binds a pending request to the session and attachment before the picker opens; only that
 *   request's [saveChosen] starts the download, which opens the document at the first body byte. A late result, one
 *   after a session change, or one with no pending request (a process death) does nothing. The document is never
 *   deleted: a failure says whether any byte was written.
 * - **Share / Open**: [prepare] checks the space, sweeps expired copies, streams into a fresh private copy and checks the
 *   hash before [TransferState.Ready]. Any failure, integrity mismatch, [cancel] or [noApp] before [handedOff] deletes the
 *   copy at once; a handed-off copy lives under [ShareCopies]' lifetime rules.
 * - A genuine background cancels whatever runs ([backgrounded]); navigation and configuration changes don't.
 */
internal class FileTransfers(
    private val scope: CoroutineScope,
    private val session: StateFlow<SessionState>,
    private val source: FileSource,
    private val copies: ShareCopies,
    private val space: CacheSpace,
    private val clock: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    /** A Save waiting for its picker result: valid only for this session and attachment. */
    class PendingSave internal constructor(internal val identity: Identity, val ref: AttachmentRef) {
        val suggestedTitle: String get() = FileNames.sanitizedFileName(ref.attachment.name)
        val mimeType: String get() = ref.attachment.mimeType.trim().ifEmpty { OCTET_STREAM }
        override fun toString() = "PendingSave"
    }

    internal data class Identity(val origin: String, val userId: Long, val generation: Long)

    private val mutable = MutableStateFlow<TransferState?>(null)
    val state: StateFlow<TransferState?> = mutable.asStateFlow()
    private var job: Job? = null
    /** Observable (#318 review), so screens re-enable their buttons when a pending Save ends. */
    private val pendingFlow = MutableStateFlow<PendingSave?>(null)
    val pendingSaveState: StateFlow<PendingSave?> = pendingFlow.asStateFlow()
    private var pending: PendingSave?
        get() = pendingFlow.value
        set(value) { pendingFlow.value = value }
    /** The session the transfers follow, for screens that show its 429 wait (#318 review). */
    val sessionState: StateFlow<SessionState> get() = session
    private var copy: ShareCopies.Copy? = null
    private var owner: Identity? = null
    /**
     * Whether the running transfer's destination has been opened. A Save's document is opened (and so truncated) at the
     * first body byte, so this, not the progress, says whether a picked file may have changed (#317 review).
     */
    private var opened = java.util.concurrent.atomic.AtomicBoolean()

    init {
        // A session change retires everything that belonged to the old one, silently.
        scope.launch { session.collect { next -> if (owner != null && identityOf(next) != owner) retire() } }
    }

    /**
     * The Save waiting for its picker result, read-only (#318 review): a picker result is matched to this, never to a copy
     * kept in a composition, so an Activity recreated while the picker is open still delivers it. [saveChosen]'s own
     * identity and request checks stay the only authority.
     */
    val pendingSave: PendingSave? get() = pending

    /** Running or waiting for a picker or a handoff: the other actions are disabled (PL). */
    val busy: Boolean get() = pending != null || job?.isActive == true || mutable.value is TransferState.Ready

    /** During the session's 429 wait nothing starts and no picker opens (F1/F2 rule). */
    fun waiting(): Boolean = clock() < session.value.retryAtMillis

    /** Step one of a Save: null when nothing may start (signed out, busy, the 429 wait). */
    fun requestSave(ref: AttachmentRef): PendingSave? {
        val identity = identityOf(session.value) ?: return null
        if (busy || waiting()) return null
        clearFinished()
        owner = identity
        return PendingSave(identity, ref).also { pending = it }
    }

    /** Step two: the picker's result for [request]; a null [destination] is a cancelled picker. */
    fun saveChosen(request: PendingSave, destination: SaveDestination?) {
        if (pending !== request) return
        pending = null
        val expected = session.value
        if (destination == null || identityOf(expected) != request.identity) { idleIfNothingRuns(); return }
        val ref = request.ref
        run(ref, TransferPurpose.SAVE, expected, request.identity, open = { destination.open() }, done = { result ->
            if (result == DownloadResult.Saved) TransferState.Saved(ref)
            else stopped(ref, TransferPurpose.SAVE, result, opened.get())
        })
    }

    /** Share or Open: a fresh private copy, verified before it's offered for handoff. */
    fun prepare(ref: AttachmentRef, purpose: TransferPurpose) {
        require(purpose != TransferPurpose.SAVE)
        val expected = session.value
        val identity = identityOf(expected) ?: return
        if (busy || waiting() || FileNames.isApkLike(ref.attachment)) return
        clearFinished()
        owner = identity
        copies.sweep()
        // Room for the whole file plus a margin, decided before anything is written (TL).
        if (space.allocatableBytes() < ref.attachment.sizeBytes + ref.attachment.sizeBytes / 10) {
            mutable.value = TransferState.Stopped(ref, purpose, TransferStop.NO_SPACE, wroteBody = false); return
        }
        val created = try { copies.create(ShareCopies.accountKey(identity.origin, identity.userId), ref.attachment.name) }
            catch (_: Exception) { mutable.value = TransferState.Stopped(ref, purpose, TransferStop.STORAGE, wroteBody = false); return }
        copy = created
        run(ref, purpose, expected, identity, open = { FileOutputStream(created.file) }, done = { result ->
            if (result == DownloadResult.Saved) TransferState.Ready(ref, purpose, created, ref.attachment.mimeType.trim().ifEmpty { null })
            else {
                // Never handed over: a partial, failed or mismatching copy is deleted now.
                discardCopy()
                stopped(ref, purpose, result, wroteBody = false)
            }
        })
    }

    /** The presentation opened the share sheet or the viewer app with [ready]'s copy. */
    fun handedOff(ready: TransferState.Ready) {
        if (mutable.value !== ready) return
        copies.handedOff(ready.copy)
        copy = null
        mutable.value = null
        owner = null
    }

    /** Open found no app for the type: the copy goes, with the PL's line. */
    fun noApp(ready: TransferState.Ready) {
        if (mutable.value !== ready) return
        discardCopy()
        mutable.value = TransferState.Stopped(ready.ref, ready.purpose, TransferStop.NO_APP, wroteBody = false)
    }

    /** The person's Cancel. */
    fun cancel() = stop(TransferStop.CANCELLED)

    /** A genuine background (ON_STOP, not a configuration change): everything running or pending stops (TL). */
    fun backgrounded() = stop(TransferStop.BACKGROUND)

    /** The finished line was seen or the screen left: back to idle. */
    fun dismiss() { if (job?.isActive != true && mutable.value !is TransferState.Ready) { mutable.value = null; if (pending == null) owner = null } }

    private fun run(ref: AttachmentRef, purpose: TransferPurpose, expected: SessionState, identity: Identity,
        open: () -> OutputStream, done: (DownloadResult) -> TransferState) {
        val total = ref.attachment.sizeBytes
        val written = AtomicLong()
        val destinationOpened = java.util.concurrent.atomic.AtomicBoolean().also { opened = it }
        val running = TransferState.Running(ref, purpose, 0, total)
        mutable.value = running
        // Progress from the destination's own writes: one update per whole percent, from the download's thread.
        fun advance(count: Int) {
            val before = written.get()
            val after = written.addAndGet(count.toLong())
            if (total > 0 && after * 100 / total != before * 100 / total) {
                val now = mutable.value
                if (now is TransferState.Running && now.ref === ref) mutable.value = now.copy(written = after)
            }
        }
        job = scope.launch {
            val result = try {
                withContext(io) {
                    source.stream(ref, expected, open = { CountingStream(open().also { destinationOpened.set(true) }, ::advance) }, ensureCurrent = {
                        if (identityOf(session.value) != identity) throw ApiFailure(FailureKind.EXPIRED)
                    })
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { DownloadResult.Failed(ApiFailure(FailureKind.STORAGE)) }
            // A stale result never lands: only the transfer that's still current finishes.
            if (owner != identity || identityOf(session.value) != identity) return@launch
            mutable.value = done(result)
            job = null
        }
    }

    private fun stop(reason: TransferStop) {
        val current = mutable.value
        val wasPending = pending
        pending = null
        val stopping = job
        val running = stopping?.isActive == true
        stopping?.cancel(); job = null
        when {
            running && current != null -> {
                if (current.purpose != TransferPurpose.SAVE) discardCopy(after = stopping)
                // A Share or Open that never reached its handoff ends silently on background; a Save says what's left.
                mutable.value = if (current.purpose == TransferPurpose.SAVE || reason == TransferStop.CANCELLED)
                    TransferState.Stopped(current.ref, current.purpose, reason,
                        wroteBody = current.purpose == TransferPurpose.SAVE && opened.get()) else null
            }
            current is TransferState.Ready -> { discardCopy(); mutable.value = null }
            wasPending != null -> Unit
        }
        if (mutable.value == null) owner = null
    }

    private fun stopped(ref: AttachmentRef, purpose: TransferPurpose, result: DownloadResult, wroteBody: Boolean): TransferState.Stopped {
        val reason = when (result) {
            DownloadResult.Gone -> TransferStop.GONE
            DownloadResult.NotAllowed -> TransferStop.NOT_ALLOWED
            is DownloadResult.RateLimited -> TransferStop.RATE_LIMITED
            DownloadResult.IntegrityFailed -> TransferStop.DAMAGED
            is DownloadResult.Failed -> if (result.failure.kind == FailureKind.STORAGE) TransferStop.STORAGE else TransferStop.FAILED
            else -> TransferStop.FAILED
        }
        return TransferState.Stopped(ref, purpose, reason, wroteBody, (result as? DownloadResult.RateLimited)?.retryAfterSeconds)
    }

    private fun retire() {
        pending = null
        val stopping = job
        stopping?.cancel(); job = null
        discardCopy(after = stopping)
        mutable.value = null
        owner = null
    }

    /**
     * Deletes the private copy, but only once [after] (a cancelled download) has really ended: the transport returns only
     * after its producer stopped writing, so a file is never deleted while bytes may still go into it. Until then the copy
     * stays in flight, out of every sweep's reach.
     */
    private fun discardCopy(after: Job? = null) {
        val target = copy ?: return
        copy = null
        if (after == null || after.isCompleted) copies.discard(target)
        else scope.launch { after.join(); withContext(io) { copies.discard(target) } }
    }

    private fun clearFinished() { if (mutable.value is TransferState.Saved || mutable.value is TransferState.Stopped) mutable.value = null }

    private fun idleIfNothingRuns() { if (job?.isActive != true) { if (mutable.value == null) owner = null } }

    /**
     * The account a transfer belongs to: origin, account and generation, while a member is published. A revalidation or
     * a passing network failure keeps all three; a sign-out or an ended session clears the user and moves the generation.
     */
    private fun identityOf(state: SessionState): Identity? {
        val user = state.user?.takeIf { !it.guest } ?: return null
        val origin = state.server?.value ?: return null
        return Identity(origin, user.id, state.generation)
    }

    /** Counts what reaches the destination; closing closes the destination. */
    private class CountingStream(private val target: OutputStream, private val counted: (Int) -> Unit) : OutputStream() {
        override fun write(b: Int) { target.write(b); counted(1) }
        override fun write(b: ByteArray, off: Int, len: Int) { target.write(b, off, len); counted(len) }
        override fun flush() = target.flush()
        override fun close() = target.close()
    }

    companion object { private const val OCTET_STREAM = "application/octet-stream" }
}

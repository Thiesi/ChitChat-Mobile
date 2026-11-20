package io.github.thiesi.chitchat.core.media

import io.github.thiesi.chitchat.core.conversation.MessageAttachment
import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.network.FailureKind
import io.github.thiesi.chitchat.core.network.SessionHttp
import io.github.thiesi.chitchat.core.session.SessionRepository
import io.github.thiesi.chitchat.core.session.SessionState
import java.io.OutputStream

/** Which bytes of a received file: the original, or the server's WebP preview rendition. */
enum class AttachmentVariant { ORIGINAL, PREVIEW }

/** A received file as the transcript knows it: its metadata and whether it came from a direct message. */
class AttachmentRef(val attachment: MessageAttachment, val direct: Boolean) {
    override fun toString() = "AttachmentRef"
}

/** Every outcome of one download (received files F1). Never retried here; Try again is an explicit new request. */
sealed interface DownloadResult {
    /** The verified bytes, charged to the shared memory budget until [release]. Holds no string form of its content. */
    class Bytes internal constructor(val bytes: ByteArray, val etag: String?, private val lease: AttachmentMemoryBudget.Reservation) :
        DownloadResult {
        fun release() = lease.close()
        /** Grow or shrink the charge to what is actually kept (the decoded image, F2); false leaves it unchanged. */
        internal fun resize(bytes: Long): Boolean = lease.resize(bytes)
        /** Hands the lease to the holder of the decoded image; the raw bytes are no longer kept. */
        internal fun lease(): AttachmentMemoryBudget.Reservation = lease
        override fun toString() = "DownloadResult.Bytes"
    }
    /** A preview revalidated with its ETag: the held bytes stay valid. */
    data object NotModified : DownloadResult
    /** Every byte went to the caller's stream and matched the server's SHA-256. */
    data object Saved : DownloadResult
    /** 410: deleted, or the server's stored copy failed its own integrity check. */
    data object Gone : DownloadResult
    /** 403, or 404 other than a missing preview: this account can't open the file (any more). */
    data object NotAllowed : DownloadResult
    /** `404 attachment_preview_unavailable`: no rendition; never silently replaced by the original. */
    data object PreviewUnavailable : DownloadResult
    /** 429, with the server's positive wait when it gave one; never retried automatically. */
    data class RateLimited(val retryAfterSeconds: Long?) : DownloadResult
    /** The bytes didn't match the metadata (length or SHA-256): discarded, never shown, saved as good or shared. */
    data object IntegrityFailed : DownloadResult
    /** Beyond the display caps: it can still be saved or shared, streamed. */
    data object TooLarge : DownloadResult
    /** The shared memory budget can't hold it right now; Try again may succeed later. */
    data object NoMemory : DownloadResult
    /** Network, session or server failures, typed as everywhere else. */
    class Failed(val failure: ApiFailure) : DownloadResult {
        override fun toString() = "DownloadResult.Failed"
    }
}

/**
 * Received-file downloads (received files F1; files-media.md, attachments.md, image-previews.md). Each request is bound
 * to the rendered session it was asked for (a stale session never gets its result), uses the session's same-origin
 * transport, and is one GET, never replayed. A preview is fetched only from its metadata-validated relative URL; an
 * original only from the attachment's ID in its own namespace. Display downloads take a memory lease before allocating
 * and stay in memory; [stream] writes straight to the caller's stream for Save and the share copy. The upload policy
 * (and its 413s) doesn't govern downloads: received files stay readable under every policy state.
 */
internal class AttachmentDownloads(private val sessions: SessionRepository, private val budget: AttachmentMemoryBudget) : AttachmentSource {

    /** The bytes for display: the preview, or an original within the display caps. */
    override suspend fun load(ref: AttachmentRef, variant: AttachmentVariant, expected: SessionState, ifNoneMatch: String?): DownloadResult {
        val target = target(ref, variant) ?: return DownloadResult.Failed(ApiFailure(FailureKind.INCOMPATIBLE))
        if (target.size > DISPLAY_MAX_BYTES) return DownloadResult.TooLarge
        val dimensions = ref.attachment.displayDimensions
        if (variant == AttachmentVariant.ORIGINAL && dimensions != null &&
            dimensions.width.toLong() * dimensions.height > DISPLAY_MAX_PIXELS) return DownloadResult.TooLarge
        val lease = budget.reserve(target.size) ?: return DownloadResult.NoMemory
        var kept = false
        try {
            val bytes = ByteArray(target.size.toInt())
            var at = 0
            val reply = request(expected) {
                download(target.path, target.size, ifNoneMatch.takeIf { variant == AttachmentVariant.PREVIEW }, target.type) { chunk, count ->
                    System.arraycopy(chunk, 0, bytes, at, count); at += count
                }
            }
            val result = when {
                reply is DownloadResult -> reply
                (reply as SessionHttp.DownloadReply).notModified -> DownloadResult.NotModified
                !reply.sha256.equals(target.sha256, ignoreCase = true) -> DownloadResult.IntegrityFailed
                else -> DownloadResult.Bytes(bytes, reply.etag, lease).also { kept = true }
            }
            return result
        } finally { if (!kept) lease.close() }
    }

    /**
     * The original streamed to a destination (Save, and the Share and Open copy; F5): no display cap, no memory lease and
     * no whole-file buffer, only the transport's 64 KiB chunk. [open] is called once, when the first body byte has
     * arrived, so a failure before the body (403, 410, 429, network, a session change) never opens, creates or truncates
     * the destination (TL, 2026-11-20: a picked document may be an existing file). [ensureCurrent] runs before every
     * chunk and may throw to stop. The stream is closed here; on anything but [DownloadResult.Saved] it may hold a
     * partial or mismatching file, and the caller decides what to do with it (a chosen document is never deleted; a
     * private copy is).
     */
    suspend fun stream(ref: AttachmentRef, expected: SessionState, open: () -> OutputStream,
        ensureCurrent: () -> Unit = {}): DownloadResult {
        val target = target(ref, AttachmentVariant.ORIGINAL) ?: return DownloadResult.Failed(ApiFailure(FailureKind.INCOMPATIBLE))
        var output: OutputStream? = null
        var closeFailed = false
        val reply = try {
            request(expected) {
                download(target.path, target.size, streamed = true) { chunk, count ->
                    ensureCurrent()
                    (output ?: open().also { output = it }).write(chunk, 0, count)
                }
            }
        } finally {
            // Closing flushes the provider; a failure there is the destination's, as for any write.
            val opened = output
            if (opened != null) try { opened.close() } catch (_: Exception) { closeFailed = true }
        }
        if (reply is DownloadResult) return reply
        reply as SessionHttp.DownloadReply
        if (closeFailed) return DownloadResult.Failed(ApiFailure(FailureKind.STORAGE))
        return if (reply.sha256.equals(target.sha256, ignoreCase = true)) DownloadResult.Saved else DownloadResult.IntegrityFailed
    }

    /** One GET under the rendered session's access; typed failures come back as results, never as a replay. */
    private suspend fun request(expected: SessionState, operation: suspend SessionHttp.() -> SessionHttp.DownloadReply): Any = try {
        val access = sessions.openAccess(expected)
        sessions.authorized(access, operation)
    } catch (failure: ApiFailure) { classify(failure) }

    private class Target(val path: String, val size: Long, val sha256: String, val type: String?)

    /** The same-origin path for [variant], or null when the metadata can't safely name one. */
    private fun target(ref: AttachmentRef, variant: AttachmentVariant): Target? {
        val attachment = ref.attachment
        if (attachment.id <= 0 || attachment.sizeBytes <= 0 || !SHA256.matches(attachment.sha256)) return null
        return when (variant) {
            AttachmentVariant.ORIGINAL -> {
                val base = if (ref.direct) "/api/v1/direct-messages/attachments/download.php" else "/api/v1/attachments/download.php"
                Target("$base?id=${attachment.id}", attachment.sizeBytes, attachment.sha256, null)
            }
            AttachmentVariant.PREVIEW -> attachment.preview?.takeIf { it.validFor(attachment.id, ref.direct) }
                ?.let { Target(it.url, it.sizeBytes, it.sha256, "image/webp") }
        }
    }

    companion object {
        /** Display caps (TL, 2026-11-19): larger originals are TooLarge for display, still saved or shared streamed. */
        const val DISPLAY_MAX_BYTES = 32L * 1024 * 1024
        const val DISPLAY_MAX_PIXELS = 16_000_000L
        private val SHA256 = Regex("[a-fA-F0-9]{64}")

        internal fun classify(failure: ApiFailure): DownloadResult = when {
            failure.kind == FailureKind.INCOMPATIBLE && failure.code == SessionHttp.LENGTH_MISMATCH -> DownloadResult.IntegrityFailed
            failure.status == 410 -> DownloadResult.Gone
            failure.status == 404 && failure.code == "attachment_preview_unavailable" -> DownloadResult.PreviewUnavailable
            failure.status == 403 || failure.status == 404 -> DownloadResult.NotAllowed
            failure.status == 429 -> DownloadResult.RateLimited(failure.retryAfterSeconds?.takeIf { it > 0 })
            else -> DownloadResult.Failed(failure)
        }
    }
}

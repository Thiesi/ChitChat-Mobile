package io.github.thiesi.chitchat.core.media

import io.github.thiesi.chitchat.core.conversation.phpTrim
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okio.BufferedSink
import okio.ByteString

/** One explicit attempt over frozen, already prepared bytes. No URI/provider is reopened here.
 * Photo cleaning, bounded preparation and the user's Send choice belong to the later consumer.
 * Keep this and its receipt out of saved state/logs; neither has a content-bearing toString.
 */
internal class AttachmentUpload(
    val filename: String,
    val mimeType: String?,
    private val bytes: ByteString,
    caption: String,
    val replyId: Long? = null,
) {
    val caption = caption.phpTrim()
    val sizeBytes: Long get() = bytes.size.toLong()
    private val claimed = AtomicBoolean()

    init {
        validateAttachmentMetadata(filename, mimeType)
        require(bytes.size > 0) { "Empty upload" }
        require(this.caption.codePointCount(0, this.caption.length) <= 4000) { "Upload caption too long" }
        require(replyId == null || replyId > 0) { "Invalid upload reply" }
    }

    /** Claimed before enqueue, even if the call later fails before writing any bytes. */
    fun claimBody(): RequestBody {
        check(claimed.compareAndSet(false, true)) { "Upload attempt already used" }
        return object : RequestBody() {
            private val written = AtomicBoolean()
            override fun contentType() = (mimeType ?: "application/octet-stream").toMediaType()
            override fun contentLength() = sizeBytes
            override fun isOneShot() = true
            override fun writeTo(sink: BufferedSink) {
                check(written.compareAndSet(false, true)) { "Upload body already written" }
                sink.write(bytes)
            }
        }
    }
}

/** Namespace-specific confirmed POST IDs, not canonical history or attachment metadata. */
internal class RoomUploadReceipt(val roomId: Long, val messageId: Long)
internal class DirectUploadReceipt(val peerId: Long, val messageId: Long)

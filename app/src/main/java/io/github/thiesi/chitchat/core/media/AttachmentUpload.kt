package io.github.thiesi.chitchat.core.media

import io.github.thiesi.chitchat.core.conversation.phpTrim
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.io.IOException
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okio.BufferedSink

/** One explicit attempt over frozen, already prepared bytes. No URI/provider is reopened here.
 * Photo cleaning, bounded preparation and the user's Send choice belong to the later consumer.
 * Keep this and its receipt out of saved state/logs; neither has a content-bearing toString.
 */
internal class AttachmentUpload(
    payload: PreparedAttachmentPayload,
    caption: String,
    val replyId: Long? = null,
) {
    val caption = caption.phpTrim()
    val filename = payload.filename
    val mimeType = payload.mimeType
    val sizeBytes = payload.sizeBytes
    val sha256 = payload.sha256
    private val source = AtomicReference<PreparedAttachmentPayload?>(payload)
    private val exchange = UploadExchange(sizeBytes) { source.set(null) }
    val transferFacts = exchange.facts
    private val claimed = AtomicBoolean()

    init {
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
                val payload = source.get() ?: throw IOException("Upload body unavailable")
                var offset = 0
                while (offset < payload.bytes.size) {
                    val end = minOf(offset + 8192, payload.bytes.size)
                    sink.write(payload.bytes.substring(offset, end))
                    exchange.fileBuffered((end - offset).toLong())
                    offset = end
                }
            }
        }
    }

    internal fun wrapMultipart(body: RequestBody) = exchange.wrapMultipart(body)
    internal fun bindCall(call: Call) = exchange.bind(call)
    internal fun transportCallbackFinished() = exchange.callbackFinished()
    internal suspend fun awaitTransportStopped() = exchange.awaitStopped()
}

/** Namespace-specific confirmed POST IDs, not canonical history or attachment metadata. */
internal class RoomUploadReceipt(val roomId: Long, val messageId: Long)
internal class DirectUploadReceipt(val peerId: Long, val messageId: Long)

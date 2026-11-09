package io.github.thiesi.chitchat.core.media

import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.RequestBody
import okio.Buffer
import okio.BufferedSink
import okio.ForwardingSink
import okio.buffer

/** Private attempt facts, not send/privacy consent, delivery status or permission to free bytes.
 * Buffered file bytes are NOT full multipart completion; the delimiter and transport count matter.
 * Keep these out of logs/saved state and fence every later consumer to its original owner/attempt.
 */
internal data class UploadTransferFacts(
    val fileSizeBytes: Long,
    val fileBytesBuffered: Long = 0,
    val multipartSizeBytes: Long? = null,
    val multipartAttemptedUpperBound: Long = 0,
    val requestBodyEndObserved: Boolean = false,
    val requestBodyEndBytes: Long? = null,
    val cancellationRequested: Boolean = false,
    val writerStarted: Boolean = false,
    val writerRunning: Boolean = false,
    val callbackFinished: Boolean = false,
    val stopped: Boolean = false,
) {
    val fullBodyReported: Boolean get() = requestBodyEndObserved && multipartSizeBytes != null &&
        requestBodyEndBytes == multipartSizeBytes
    override fun toString() = "UploadTransferFacts"
}

/** One concrete non-duplex multipart exchange. Callback completion, not cancel/callEnd notification,
 * proves the asynchronous interceptor chain has unwound. Also fence the actual owned body writer.
 * No application callback runs here; releaseBytes only clears the attempt's atomic byte reference.
 */
internal class UploadExchange(fileSize: Long, private val releaseBytes: () -> Unit) {
    private val mutable = MutableStateFlow(UploadTransferFacts(fileSize))
    val facts = mutable.asStateFlow()
    private val delegate = AtomicReference<RequestBody?>()
    private val wrapped = AtomicBoolean()
    private val bound = AtomicBoolean()
    private val released = AtomicBoolean()
    private val completion = CompletableDeferred<Unit>() // Independent of caller cancellation.

    fun fileBuffered(bytes: Long) {
        mutable.update { it.copy(fileBytesBuffered = (it.fileBytesBuffered + bytes).coerceAtMost(it.fileSizeBytes)) }
    }

    fun wrapMultipart(body: RequestBody): RequestBody {
        check(wrapped.compareAndSet(false, true)) { "Upload multipart already prepared" }
        require(!body.isDuplex() && body.isOneShot()) { "Upload multipart must be one-shot" }
        val length = body.contentLength()
        require(length > 0) { "Upload multipart length unavailable" }
        val type = body.contentType()
        delegate.set(body)
        mutable.update { it.copy(multipartSizeBytes = length) }
        return object : RequestBody() {
            override fun contentType() = type
            override fun contentLength() = length
            override fun isOneShot() = true
            override fun writeTo(sink: BufferedSink) {
                // Mark active before accessing the delegate. A stopped call cannot start later.
                if (!startWriter()) throw IOException("Upload body unavailable")
                try {
                    val current = delegate.get() ?: throw IOException("Upload body unavailable")
                    val counted = object : ForwardingSink(sink) {
                        override fun write(source: Buffer, byteCount: Long) {
                            // Upper bound BEFORE delegation: a throwing final write may have sent
                            // some/all of its bytes. Never turn a partial lower count into proof.
                            mutable.update {
                                it.copy(multipartAttemptedUpperBound = if (byteCount > Long.MAX_VALUE - it.multipartAttemptedUpperBound)
                                    Long.MAX_VALUE else it.multipartAttemptedUpperBound + byteCount)
                            }
                            super.write(source, byteCount)
                        }
                    }.buffer()
                    current.writeTo(counted)
                    counted.emit() // Includes the final delimiter; don't close/flush the caller's sink.
                } finally {
                    mutable.update { it.copy(writerRunning = false) }
                    finishIfStopped()
                }
            }
        }
    }

    fun bind(call: Call) {
        check(wrapped.get() && !call.isExecuted() && bound.compareAndSet(false, true)) { "Upload call already bound" }
        // Adds to existing listeners; do not replace the runtime client's factory or retain Call.
        call.addEventListener(object : EventListener() {
            override fun requestBodyEnd(call: Call, byteCount: Long) {
                mutable.update { if (it.stopped) it else it.copy(requestBodyEndObserved = true, requestBodyEndBytes = byteCount) }
            }
            override fun canceled(call: Call) {
                // Cancellation can be reported after the response callback finished. Record the
                // fact without reopening the exchange or claiming when/effect it had on delivery.
                mutable.update { it.copy(cancellationRequested = true) }
            }
        })
    }

    private fun startWriter(): Boolean {
        while (true) {
            val old = mutable.value
            if (old.writerStarted || old.callbackFinished) return false
            if (mutable.compareAndSet(old, old.copy(writerStarted = true, writerRunning = true))) return true
        }
    }

    /** Only the upload's actual OkHttp onResponse/onFailure finally may signal this. */
    fun callbackFinished() {
        mutable.update { it.copy(callbackFinished = true) }
        finishIfStopped()
    }

    private fun finishIfStopped() {
        val current = mutable.value
        if (!current.callbackFinished || current.writerRunning || !released.compareAndSet(false, true)) return
        delegate.set(null)
        releaseBytes()
        mutable.update { it.copy(stopped = true) }
        completion.complete(Unit)
    }

    suspend fun awaitStopped() { completion.await() }
}

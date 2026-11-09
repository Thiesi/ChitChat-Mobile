package io.github.thiesi.chitchat.core.media

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.Buffer
import okio.BufferedSink
import okio.ForwardingSink
import okio.buffer
import okio.ByteString.Companion.encodeUtf8
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UploadExchangeTest {
    @Test fun aLateCancelFactDoesNotReopenAStoppedExchange() = runTest {
        var releases = 0
        val exchange = UploadExchange(1) { releases++ }
        val body = exchange.wrapMultipart(object : RequestBody() {
            override fun contentType() = null
            override fun contentLength() = 1L
            override fun isOneShot() = true
            override fun writeTo(sink: BufferedSink) { error("No writer may start") }
        })
        val client = OkHttpClient()
        val call = client.newCall(Request.Builder().url("https://example.invalid/").post(body).build())
        try {
            exchange.bind(call)
            exchange.callbackFinished(); exchange.awaitStopped()
            call.cancel() // Real OkHttp event, no enqueue/network. It may arrive after completion.
            val facts = exchange.facts.value
            assertTrue("A cancel event can arrive after callback completion", facts.cancellationRequested)
            assertTrue(facts.stopped); assertTrue(facts.callbackFinished)
            assertFalse(facts.writerRunning); assertFalse(facts.writerStarted)
            assertEquals(1, releases)
        } finally { client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
    }

    @Test fun callbackNotificationCannotReleaseAHeldWriterOrCompleteItsWaiter() = runTest {
        val entered = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val releases = AtomicInteger()
        val exchange = UploadExchange(3) { releases.incrementAndGet() }
        val body = object : RequestBody() {
            override fun contentType() = null
            override fun contentLength() = 3L
            override fun isOneShot() = true
            override fun writeTo(sink: BufferedSink) {
                sink.writeUtf8("END")
                entered.countDown()
                check(finish.await(5, TimeUnit.SECONDS)) { "Fixture writer not released" }
            }
        }
        val wrapped = exchange.wrapMultipart(body)
        val stopped = async { exchange.awaitStopped() }
        val writer = launch(Dispatchers.IO) { wrapped.writeTo(Buffer()) }
        try {
            withContext(Dispatchers.IO) { assertTrue(entered.await(5, TimeUnit.SECONDS)) }
            exchange.callbackFinished(); runCurrent()
            assertFalse(stopped.isCompleted)
            assertTrue(exchange.facts.value.writerRunning)
            assertEquals(0, releases.get())
            finish.countDown(); writer.join(); stopped.await()
            assertEquals(1, releases.get()); assertTrue(exchange.facts.value.stopped)
            exchange.callbackFinished(); assertEquals(1, releases.get())
        } finally { finish.countDown(); writer.join() }
    }

    @Test fun stoppedBeforeWriterEntryCannotReadOrResurrectTheHeldBody() = runTest {
        var writes = 0
        var releases = 0
        val exchange = UploadExchange(1) { releases++ }
        val held = exchange.wrapMultipart(object : RequestBody() {
            override fun contentType() = null
            override fun contentLength() = 1L
            override fun isOneShot() = true
            override fun writeTo(sink: BufferedSink) { writes++; sink.writeByte(1) }
        })
        exchange.callbackFinished(); exchange.awaitStopped()
        try { held.writeTo(Buffer()); fail() } catch (_: IOException) { }
        assertEquals(0, writes); assertEquals(1, releases)
        assertEquals(0L, exchange.facts.value.multipartAttemptedUpperBound)
        assertFalse(exchange.facts.value.writerStarted)
    }

    @Test fun completeFileProgressDoesNotProveTheFinalMultipartOrItsTransportEnd() = runTest {
        val bytes = "prepared".encodeUtf8()
        val upload = AttachmentUpload(PreparedAttachmentPayload.create("private-name", null, bytes), "private-caption")
        val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("caption", upload.caption)
            .addFormDataPart("file", upload.filename, upload.claimBody()).build()
        val sink = Buffer()
        upload.wrapMultipart(multipart).writeTo(sink)
        val facts = upload.transferFacts.value
        assertEquals(bytes.size.toLong(), facts.fileBytesBuffered)
        assertEquals(sink.size, facts.multipartSizeBytes)
        assertEquals(sink.size, facts.multipartAttemptedUpperBound)
        assertTrue(sink.size > bytes.size)
        assertFalse(facts.fullBodyReported); assertFalse(facts.requestBodyEndObserved)
        assertFalse(facts.stopped); assertFalse(facts.writerRunning)
        assertFalse(facts.toString().contains("private")); assertFalse(facts.toString().contains(upload.sha256))
        upload.transportCallbackFinished(); upload.awaitTransportStopped()
        assertTrue(upload.transferFacts.value.stopped)
        assertEquals(bytes.sha256().hex(), upload.sha256) // Evidence remains, not a reusable body.
    }

    @Test fun aThrowingFinalWriteKeepsTheAttemptedUpperBoundEvenIfAllBytesMayHaveGone() = runTest {
        fun multipart(upload: AttachmentUpload) = MultipartBody.Builder("fixture-boundary").setType(MultipartBody.FORM)
            .addFormDataPart("file", upload.filename, upload.claimBody()).build()
        val seed = AttachmentUpload(PreparedAttachmentPayload.create("fixture", null, "p".encodeUtf8()), "")
        val overhead = multipart(seed).contentLength() - 1
        val payloadBytes = 8192 - overhead.toInt()
        assertTrue(payloadBytes > 0)
        val upload = AttachmentUpload(PreparedAttachmentPayload.create("fixture", null, "p".repeat(payloadBytes).encodeUtf8()), "")
        val multipart = multipart(upload)
        assertEquals(8192L, multipart.contentLength())
        val sink = object : ForwardingSink(Buffer()) {
            override fun write(source: Buffer, byteCount: Long) {
                super.write(source, byteCount)
                throw IOException("fixture final write outcome unknown")
            }
        }.buffer()
        // Exactly one full segment forces the final delegate write inside the owned wrapper.
        // The sink consumes that entire segment before throwing: delivery is still unknown.
        try { upload.wrapMultipart(multipart).writeTo(sink); fail() } catch (_: IOException) { }
        val facts = upload.transferFacts.value
        assertEquals(facts.multipartSizeBytes, facts.multipartAttemptedUpperBound)
        assertFalse(facts.fullBodyReported)
        assertFalse(facts.writerRunning)
        upload.transportCallbackFinished(); upload.awaitTransportStopped()
        assertTrue(upload.transferFacts.value.stopped)
    }
}

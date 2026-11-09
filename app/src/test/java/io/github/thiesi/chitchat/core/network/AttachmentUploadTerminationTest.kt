package io.github.thiesi.chitchat.core.network

import io.github.thiesi.chitchat.core.media.AttachmentUpload
import io.github.thiesi.chitchat.core.media.PreparedAttachmentPayload
import io.github.thiesi.chitchat.core.server.AddressResult
import io.github.thiesi.chitchat.core.server.ServerAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.BufferedSink
import okio.ByteString.Companion.encodeUtf8
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AttachmentUploadTerminationTest {
    @Test fun cancellingTheCallerCannotFinishWhileTheActualRequestWriterIsStillHeld() = runTest {
        val entered = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val exited = CountDownLatch(1)
        val cert = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val tls = HandshakeCertificates.Builder().heldCertificate(cert).build()
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        val server = MockWebServer().apply { useHttps(tls.sslSocketFactory()); start() }
        val client = OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager)
            .addNetworkInterceptor { chain ->
                val request = chain.request()
                val original = checkNotNull(request.body)
                val held = object : RequestBody() {
                    override fun contentType() = original.contentType()
                    override fun contentLength() = original.contentLength()
                    override fun isOneShot() = original.isOneShot()
                    override fun writeTo(sink: BufferedSink) {
                        try {
                            original.writeTo(sink)
                            entered.countDown()
                            check(finish.await(5, TimeUnit.SECONDS)) { "Fixture writer not released" }
                        } finally { exited.countDown() }
                    }
                }
                chain.proceed(request.newBuilder().post(held).build())
            }.build()
        val api = SessionHttp((ServerAddress.parse(server.url("/").toString()) as AddressResult.Valid).address, client)
        server.enqueue(MockResponse.Builder().code(201).addHeader("Content-Type", "application/json")
            .body("""{"message":{"id":19,"room_id":42,"sender_id":7,"type":"attachment","deleted":false,"reply_to":null}}""").build())
        val upload = AttachmentUpload(PreparedAttachmentPayload.create("fixture.bin", null, "prepared".encodeUtf8()), "")
        val pending = launch { api.uploadRoomAttachment(42, upload, "a".repeat(64), 7) }
        try {
            runCurrent()
            withContext(Dispatchers.IO) { assertTrue(entered.await(5, TimeUnit.SECONDS)) }
            pending.cancel()
            runCurrent() // Deterministically execute the caller's cancellation continuation.
            assertFalse("Actual request writer still owns its bytes", pending.isCompleted)
        } finally {
            finish.countDown()
            try {
                withContext(NonCancellable) {
                    pending.cancelAndJoin()
                    withContext(Dispatchers.IO) { assertTrue(exited.await(5, TimeUnit.SECONDS)) }
                }
            } finally { api.close(); server.close() }
        }
    }

    @Test fun cancellationAfterTheFullBodyIsReportedNeverTurnsItIntoAnIncompleteBody() = runTest {
        val received = CountDownLatch(1)
        val respond = CountDownLatch(1)
        val cert = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val tls = HandshakeCertificates.Builder().heldCertificate(cert).build()
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        val server = MockWebServer().apply {
            useHttps(tls.sslSocketFactory())
            dispatcher = object : mockwebserver3.Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    received.countDown()
                    check(respond.await(5, TimeUnit.SECONDS)) { "Fixture response not released" }
                    return MockResponse.Builder().code(201).addHeader("Content-Type", "application/json")
                        .body("""{"message":{"id":19,"room_id":42,"sender_id":7,"type":"attachment","deleted":false,"reply_to":null}}""").build()
                }
                override fun close() { respond.countDown() }
            }
            start()
        }
        val client = OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager).build()
        val api = SessionHttp((ServerAddress.parse(server.url("/").toString()) as AddressResult.Valid).address, client)
        val upload = AttachmentUpload(PreparedAttachmentPayload.create("fixture.bin", null, "prepared".encodeUtf8()), "")
        val pending = launch { api.uploadRoomAttachment(42, upload, "a".repeat(64), 7) }
        try {
            runCurrent()
            withContext(Dispatchers.IO) { assertTrue(received.await(5, TimeUnit.SECONDS)) }
            // Observe the actual local event as well as server receipt; no timing guess or replay.
            upload.transferFacts.first { it.fullBodyReported }
            assertFalse(upload.transferFacts.value.stopped)
            pending.cancel(); pending.join()
            val facts = upload.transferFacts.value
            assertTrue(facts.cancellationRequested); assertTrue(facts.fullBodyReported)
            assertTrue(facts.stopped); assertTrue(facts.callbackFinished)
            assertFalse(facts.writerRunning)
            assertEquals(facts.multipartSizeBytes, facts.multipartAttemptedUpperBound)
            assertEquals(1, server.requestCount)
        } finally {
            respond.countDown()
            try { withContext(NonCancellable) { pending.cancelAndJoin() } }
            finally { api.close(); server.close() }
        }
    }
}

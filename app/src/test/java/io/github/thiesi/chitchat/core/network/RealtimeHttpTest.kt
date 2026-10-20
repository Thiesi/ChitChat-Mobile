package io.github.thiesi.chitchat.core.network

import io.github.thiesi.chitchat.core.realtime.RealtimeSignal
import io.github.thiesi.chitchat.core.server.AddressResult
import io.github.thiesi.chitchat.core.server.ServerAddress
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class RealtimeHttpTest {
    private lateinit var server: MockWebServer
    private lateinit var api: SessionHttp
    @Before fun start() {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val tls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        server = MockWebServer().apply { useHttps(tls.sslSocketFactory()); start() }
        api = SessionHttp((ServerAddress.parse(server.url("/").toString()) as AddressResult.Valid).address,
            OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager).build())
    }
    @After fun stop() { api.close(); server.close() }
    private fun stream(body: String) = MockResponse.Builder().addHeader("Content-Type", "text/event-stream; charset=utf-8")
        .body(body).build()
    private fun frame(id: Int = 41) = """id: $id
event: rooms_changed
data: {"id":$id,"type":"rooms_changed","room_id":null,"actor_user_id":7,"payload":[],"created_at":"2026-07-17 08:00:00+00"}

"""
    private suspend fun failure(action: suspend () -> Unit): ApiFailure {
        try { action(); error("Expected failure") } catch (failure: ApiFailure) { return failure }
    }

    @Test fun streamSharesCookieJarAndUsesHeaderCursorWithoutCsrfOrReconnect() = runTest {
        assertTrue(api.restoreCookies(listOf("SITE=fixture; Secure; HttpOnly; Path=/")))
        server.enqueue(stream("retry: 1000\n\n: heartbeat\n\n" + frame() + frame(99)))
        val values = api.events(17).toList()
        assertSame(RealtimeSignal.Opened, values.first())
        assertEquals(listOf(41L, 99L), values.filterIsInstance<RealtimeSignal.Event>().map { it.value.id })
        val request = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("GET", request.method); assertEquals("/api/v1/events/stream.php", request.url.encodedPath)
        assertNull(request.url.encodedQuery); assertEquals("17", request.headers["Last-Event-ID"])
        assertEquals("text/event-stream", request.headers["Accept"]); assertEquals("SITE=fixture", request.headers["Cookie"])
        assertNull(request.headers["X-CSRF-Token"]); assertEquals(1, server.requestCount)
    }
    @Test fun libraryFramingSupportsCrLfMultilineAndUnnumberedInvalidationAfterAnId() = runTest {
        val multiline = frame().replace("\"payload\":[],", "\"payload\":[],\ndata: ")
        server.enqueue(stream((multiline + "event: forced_logout\ndata: {\"reason\":\"session_invalidated\"}\n\n")
            .replace("\n", "\r\n")))
        val values = api.events(0).toList()
        assertEquals(3, values.size); assertTrue(values[1] is RealtimeSignal.Event)
        assertSame(RealtimeSignal.SessionInvalidated, values.last())
    }
    @Test fun unfinishedFrameAtEofIsNotDeliveredOrAcknowledged() = runTest {
        server.enqueue(stream(frame() + "id: 99\nevent: rooms_changed\ndata: {\"private\":\"unfinished\"}"))
        val events = mutableListOf<Long>()
        assertEquals(FailureKind.NETWORK, failure { api.events(0).collect {
            if (it is RealtimeSignal.Event) events.add(it.value.id)
        } }.kind)
        assertEquals(listOf(41L), events)
    }
    @Test fun streamFailureKeepsSafeHttpMetadataAndNeverRetries() = runTest {
        for (status in listOf(401, 403, 429, 503)) {
            server.enqueue(MockResponse.Builder().code(status).addHeader("Content-Type", "application/json")
                .addHeader("Retry-After", "12")
                .body("""{"error":{"code":"fixture_code","message":"Private server error details"}}""").build())
            val error = failure { api.events(0).toList() }
            assertEquals(FailureKind.HTTP, error.kind); assertEquals(status, error.status)
            assertEquals("fixture_code", error.code); assertEquals(12L, error.retryAfterSeconds)
            assertFalse(error.toString().contains("Private server error details"))
        }
        assertEquals(4, server.requestCount)
    }
    @Test fun redirectAndWrongMimeOrStatusAreRejected() = runTest {
        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", server.url("/unexpected")).build())
        assertEquals(FailureKind.REDIRECT, failure { api.events(0).toList() }.kind)
        for (response in listOf(MockResponse.Builder().addHeader("Content-Type", "text/html").body("Private HTML").build(),
            MockResponse.Builder().code(201).addHeader("Content-Type", "text/event-stream").body(frame()).build())) {
            server.enqueue(response)
            assertEquals(FailureKind.INCOMPATIBLE, failure { api.events(0).toList() }.kind)
        }
        assertEquals(3, server.requestCount)
    }
    @Test fun malformedKnownEventAndServerErrorStopInsteadOfSkippingAhead() = runTest {
        server.enqueue(stream("id: 41\nevent: rooms_changed\ndata: {bad private fixture}\n\n" + frame(99)))
        val seen = mutableListOf<RealtimeSignal>()
        assertEquals(FailureKind.INCOMPATIBLE, failure { api.events(0).collect { seen.add(it) } }.kind)
        assertTrue(seen.none { it is RealtimeSignal.Event })
        server.enqueue(stream("event: error\ndata: {\"code\":\"stream_error\"}\n\n" + frame(99)))
        assertEquals(FailureKind.NETWORK, failure { api.events(0).toList() }.kind)
    }
    @Test fun cancellationClosesTheOpenStreamAndDoesNotRetireOrdinaryHttp() = runTest {
        server.enqueue(MockResponse.Builder().addHeader("Content-Type", "text/event-stream")
            .body(": heartbeat\n\n").bodyDelay(300, TimeUnit.MILLISECONDS).build())
        assertSame(RealtimeSignal.Opened, api.events(0).first())
        server.enqueue(MockResponse.Builder().addHeader("Content-Type", "application/json")
            .body("""{"csrf_token":"${"a".repeat(64)}","user":null,"registration_enabled":true,"sign_in_providers":[],"lockdown":{"enabled":false}}""").build())
        assertNull(api.bootstrap().user); assertEquals(2, server.requestCount)
    }
    @Test fun oversizedFramesAndUnterminatedStreamsAreBounded() = runTest {
        server.enqueue(stream("id: 41\nevent: rooms_changed\ndata: " + "x".repeat(1_048_577) + "\n\n"))
        assertEquals(FailureKind.INCOMPATIBLE, failure { api.events(0).toList() }.kind)
        server.enqueue(stream("data: " + "x".repeat(8 * 1024 * 1024 + 1)))
        assertEquals(FailureKind.INCOMPATIBLE, failure { api.events(0).toList() }.kind)
    }
    @Test fun invalidCursorNeverStartsHttp() = runTest {
        assertThrows(IllegalArgumentException::class.java) { api.events(-1) }
        assertEquals(0, server.requestCount)
    }
}

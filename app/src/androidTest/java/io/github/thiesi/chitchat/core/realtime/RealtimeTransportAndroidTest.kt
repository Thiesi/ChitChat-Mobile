package io.github.thiesi.chitchat.core.realtime

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.network.FailureKind
import io.github.thiesi.chitchat.core.network.SessionHttp
import io.github.thiesi.chitchat.core.server.AddressResult
import io.github.thiesi.chitchat.core.server.ServerAddress
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Actual Android networking/framing with a test-only trusted local certificate, not live delivery. */
@RunWith(AndroidJUnit4::class)
class RealtimeTransportAndroidTest {
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
    private fun frame() = "id: 41\nevent: rooms_changed\ndata: " +
        """{"id":41,"type":"rooms_changed","room_id":null,"actor_user_id":7,"payload":[],"created_at":"2026-07-17 08:00:00+00"}""" + "\n\n"
    private fun stream(body: String) = MockResponse.Builder().addHeader("Content-Type", "text/event-stream")
        .body(body).build()

    @Test fun actualTlsCookieStreamHandlesPhpPayloadAndInheritedIdLogoutOnAndroid() = runBlocking {
        assertTrue(api.restoreCookies(listOf("SITE=fixture; Secure; HttpOnly; Path=/")))
        server.enqueue(stream((": heartbeat\n\n" + frame() +
            "event: forced_logout\ndata: {\"reason\":\"session_invalidated\"}\n\n").replace("\n", "\r\n")))
        val signals = api.events(17).toList()
        assertEquals(3, signals.size); assertSame(RealtimeSignal.Opened, signals.first())
        val event = (signals[1] as RealtimeSignal.Event).value
        assertEquals(41L, event.id); assertTrue(event.payload.isEmpty())
        assertEquals("2026-07-17T08:00:00Z", event.createdAt.toString())
        assertSame(RealtimeSignal.SessionInvalidated, signals.last())
        val request = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("/api/v1/events/stream.php", request.url.encodedPath)
        assertEquals("17", request.headers["Last-Event-ID"]); assertEquals("SITE=fixture", request.headers["Cookie"])
        assertNull(request.headers["X-CSRF-Token"])
    }
    @Test fun incompleteAndroidStreamPreservesOnlyCompleteEventsForReconnect() = runBlocking {
        server.enqueue(stream(frame() + "id: 99\nevent: rooms_changed\ndata: {\"private\":\"unfinished fixture\"}"))
        val events = mutableListOf<Long>()
        try { api.events(0).collect { if (it is RealtimeSignal.Event) events.add(it.value.id) }; fail("Expected truncation") }
        catch (failure: ApiFailure) { assertEquals(FailureKind.NETWORK, failure.kind) }
        assertEquals(listOf(41L), events)
    }
    @Test fun androidCancellationDoesNotBreakTheSharedSessionHttpClient() = runBlocking {
        server.enqueue(MockResponse.Builder().addHeader("Content-Type", "text/event-stream")
            .body(": heartbeat\n\n").bodyDelay(100, TimeUnit.MILLISECONDS).build())
        assertSame(RealtimeSignal.Opened, api.events(0).first())
        server.enqueue(MockResponse.Builder().addHeader("Content-Type", "application/json")
            .body("""{"csrf_token":"${"a".repeat(64)}","user":null,"registration_enabled":true,"sign_in_providers":[],"lockdown":{"enabled":false}}""").build())
        assertNull(api.bootstrap().user); assertEquals(2, server.requestCount)
    }
}

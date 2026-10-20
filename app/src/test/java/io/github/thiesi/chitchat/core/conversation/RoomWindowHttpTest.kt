package io.github.thiesi.chitchat.core.conversation

import io.github.thiesi.chitchat.core.network.SessionHttp
import io.github.thiesi.chitchat.core.server.AddressResult
import io.github.thiesi.chitchat.core.server.ServerAddress
import io.github.thiesi.chitchat.core.session.SavedSession
import io.github.thiesi.chitchat.core.session.SessionPhase
import io.github.thiesi.chitchat.core.session.SessionRepository
import io.github.thiesi.chitchat.core.session.SessionVault
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class RoomWindowHttpTest {
    private lateinit var server: MockWebServer
    private lateinit var sessions: SessionRepository
    private lateinit var rooms: ConversationOperations
    private val apis = mutableListOf<SessionHttp>()

    @Before fun start() {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val tls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        server = MockWebServer().apply { useHttps(tls.sslSocketFactory()); start() }
        val client = OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager).build()
        val vault = object : SessionVault {
            override suspend fun read(): SavedSession? = null
            override suspend fun save(session: SavedSession) = Unit
            override suspend fun clear() = Unit
        }
        sessions = SessionRepository(vault, http = { SessionHttp(it, client).also(apis::add) })
        rooms = SessionConversationRepository(sessions)
    }
    @After fun stop() { apis.forEach(SessionHttp::close); server.close() }
    private fun json(body: String) = MockResponse.Builder().addHeader("Content-Type", "application/json").body(body).build()
    private suspend fun login() {
        server.enqueue(MockResponse.Builder().addHeader("Content-Type", "application/json")
            .addHeader("Set-Cookie", "SITE=anon; Secure; HttpOnly; Path=/")
            .body("""{"csrf_token":"${"a".repeat(64)}","user":null,"registration_enabled":true,"sign_in_providers":[],"lockdown":{"enabled":false}}""").build())
        sessions.connect((ServerAddress.parse(server.url("/").toString()) as AddressResult.Valid).address)
        server.enqueue(MockResponse.Builder().addHeader("Content-Type", "application/json")
            .addHeader("Set-Cookie", "SITE=auth; Secure; HttpOnly; Path=/")
            .body("""{"csrf_token":"${"b".repeat(64)}","user":{"id":7,"username":"Viewer","roles":["member"],"session_version":2,"guest":false},"mfa_required":false}""").build())
        sessions.signIn("Viewer", "fixture-password")
        assertEquals(SessionPhase.AUTHENTICATED, sessions.state.value.phase)
        repeat(2) { assertNotNull(server.takeRequest(5, TimeUnit.SECONDS)) }
    }
    private fun message(id: Long, reply: String = "null", attachment: String = "null") =
        """{"id":$id,"room_id":42,"sender_id":7,"username":"Viewer","type":"text","body":"Fresh body","deleted":false,"created_at":"2026-07-17T08:00:00Z","reply_to":$reply,"attachment":$attachment}"""
    private fun mutation(id: Long) =
        """{"id":$id,"body":"Current body","type":"text","edited_at":null,"deleted":false,"deletion_kind":null,"mentions":[],"reactions":[]}"""

    @Test fun freshHistoryUpdatesOffWindowEditedDeletedAndUnavailableQuotesAndFiles() = runTest {
        login()
        val quotes = listOf(
            """{"kind":"room","message_id":5,"available":true,"message":{"id":5,"sender":null,"type":"text","body":"Edited quote","deleted":false}}""",
            """{"kind":"room","message_id":5,"available":true,"message":{"id":5,"sender":null,"type":"text","body":null,"deleted":true}}""",
            """{"kind":"room","message_id":5,"available":false,"message":null}""")
        for ((index, quote) in quotes.withIndex()) {
            val attachment = if (index == 0) """{"id":2,"name":"Current file","mime_type":"text/plain","size_bytes":3,"sha256":"fixture","previewable":false}""" else "null"
            server.enqueue(json("""{"messages":[${message(80, quote, attachment)}]}"""))
            server.enqueue(json("""{"messages":[${mutation(80)}]}"""))
            val result = rooms.window(42, 80, 90)
            val row = result.messages.single()
            val reply = checkNotNull(row.replyTo)
            assertTrue(result.coveredLowerBoundary); assertEquals(80L, result.olderCursor)
            assertEquals("Current body", row.body)
            when (index) {
                0 -> { assertEquals("Edited quote", reply.message!!.body); assertEquals("Current file", row.attachment!!.name) }
                1 -> { val target = checkNotNull(reply.message); assertTrue(target.deleted); assertNull(target.body); assertNull(row.attachment) }
                2 -> { assertFalse(reply.available); assertNull(reply.message); assertNull(row.attachment) }
            }
            val request = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("GET", request.method)
            assertEquals("/api/v1/rooms/messages.php?room_id=42&limit=100&before_id=91", request.url.encodedPath + "?" + request.url.encodedQuery)
            assertNull(request.headers["X-CSRF-Token"])
            assertEquals("/api/v1/rooms/message-mutations.php?room_id=42&message_ids=80",
                server.takeRequest(5, TimeUnit.SECONDS)!!.url.let { it.encodedPath + "?" + it.encodedQuery })
        }
    }

    @Test fun omittedFullPageStillUsesRawCursorForNextCanonicalHistoryGet() = runTest {
        login()
        server.enqueue(json("""{"messages":[${(101L..200).joinToString(",") { message(it) }}]}"""))
        server.enqueue(json("""{"messages":[]}"""))
        server.enqueue(json("""{"messages":[${message(80)},${message(81)}]}"""))
        server.enqueue(json("""{"messages":[${mutation(81)}]}"""))
        val result = rooms.window(42, 80, 200)
        assertEquals(listOf(81L), result.messages.map { it.id }); assertEquals(80L, result.olderCursor)
        assertTrue(result.coveredLowerBoundary); assertFalse(result.mayHaveOlder)
        val requests = List(4) { server.takeRequest(5, TimeUnit.SECONDS)!! }
        assertEquals("101", requests[2].url.queryParameter("before_id"))
        assertTrue(requests.all { it.method == "GET" && it.headers["X-CSRF-Token"] == null })
    }

    @Test fun accountRetirementDuringTraversalCannotReturnPrivateWindowOrReadNextPage() = runTest {
        login()
        server.enqueue(json("""{"messages":[${(101L..200).joinToString(",") { message(it) }}]}"""))
        server.enqueue(MockResponse.Builder().addHeader("Content-Type", "application/json")
            .body("""{"messages":[${(101L..200).joinToString(",") { mutation(it) }}]}""")
            .bodyDelay(400, TimeUnit.MILLISECONDS).build())
        val pending = async { runCatching { rooms.window(42, 1, 200) } }
        withContext(Dispatchers.IO) { repeat(2) { assertNotNull(server.takeRequest(5, TimeUnit.SECONDS)) } }
        server.enqueue(json("""{"status":"logged_out"}""")); sessions.signOut()
        assertTrue(pending.await().exceptionOrNull() is CancellationException)
        assertEquals(SessionPhase.SIGNED_OUT, sessions.state.value.phase)
        assertEquals(5, server.requestCount) // Bootstrap, login, one history/enrichment pair, logout; no second page.
    }
}


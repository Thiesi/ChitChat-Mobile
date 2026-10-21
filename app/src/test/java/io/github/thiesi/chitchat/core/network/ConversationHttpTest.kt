package io.github.thiesi.chitchat.core.network

import io.github.thiesi.chitchat.core.conversation.*
import io.github.thiesi.chitchat.core.server.AddressResult
import io.github.thiesi.chitchat.core.server.ServerAddress
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

class ConversationHttpTest {
    private lateinit var server: MockWebServer
    private lateinit var api: SessionHttp
    private val csrf = "a".repeat(64)
    @Before fun start() {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val tls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        server = MockWebServer().apply { useHttps(tls.sslSocketFactory()); start() }
        api = SessionHttp((ServerAddress.parse(server.url("/").toString()) as AddressResult.Valid).address,
            OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager).build())
    }
    @After fun stop() { api.close(); server.close() }
    private fun json(body: String, status: Int = 200) = MockResponse.Builder().code(status)
        .addHeader("Content-Type", "application/json").body(body).build()
    private fun message(id: Long = 80, room: Long = 42, sender: Long = 7) = """{"id":$id,"room_id":$room,"sender_id":$sender,"username":"Alice","type":"text","body":"Original private fixture","deleted":false,"created_at":"2026-07-17 08:00:00+00"}"""
    private fun mutation(id: Long = 80, body: String = "Corrected private fixture") = """{"id":$id,"body":"$body","type":"text","edited_at":"2026-07-17T09:00:00+00:00","deleted":false,"deletion_kind":null,"mentions":[],"reactions":[],"can_edit":true}"""
    private fun ping(viewer: Long = 7, room: Long = 42) = """{"id":3,"room_id":$room,"sender":{"id":$viewer,"username":"Alice"},"target":{"id":8,"username":"Bob"},"message":"Private nudge","created_at":"2026-07-17T08:00:00+00:00"}"""
    private suspend fun failure(action: suspend () -> Unit): ApiFailure {
        try { action(); error("Expected failure") } catch (error: ApiFailure) { return error }
    }
    @Test fun historyUsesExactExclusiveCursorAndAuthoritativeMutationBodies() = runTest {
        server.enqueue(json("""{"messages":[${message()}]}""")); server.enqueue(json("""{"messages":[${mutation()}]}"""))
        val page = api.roomHistory(42, 90, 1)
        assertEquals("Corrected private fixture", page.messages.single().body)
        assertNotNull(page.messages.single().editedAt); assertEquals(80L, page.olderCursor); assertTrue(page.mayHaveOlder)
        val history = server.takeRequest(); val metadata = server.takeRequest()
        assertEquals("GET", history.method); assertEquals("/api/v1/rooms/messages.php?room_id=42&limit=1&before_id=90", history.url.encodedPath + "?" + history.url.encodedQuery)
        assertEquals("/api/v1/rooms/message-mutations.php?room_id=42&message_ids=80", metadata.url.encodedPath + "?" + metadata.url.encodedQuery)
        assertNull(history.headers["X-CSRF-Token"])
    }
    @Test fun omittedExactIdMetadataDoesNotGuessDeletionOrLoseRawPaginationCursor() = runTest {
        server.enqueue(json("""{"messages":[${message()}]}""")); server.enqueue(json("""{"messages":[]}"""))
        val page = api.roomHistory(42, null, 1)
        assertTrue(page.messages.isEmpty()); assertEquals(80L, page.olderCursor); assertTrue(page.mayHaveOlder)
        server.enqueue(json("""{"messages":[]}"""))
        val empty = api.roomHistory(42, 80, 50)
        assertNull(empty.olderCursor); assertFalse(empty.mayHaveOlder)
        assertEquals(3, server.requestCount)
    }
    @Test fun malformedOrderingIdsRoomOrExclusiveBoundaryNeverReachEnrichment() = runTest {
        val bodies = listOf("${message(81)},${message(80)}", "${message()},${message()}", message(90), message(room = 43), message().replace("\"deleted\":false,", ""))
        for (body in bodies) {
            server.enqueue(json("""{"messages":[$body]}"""))
            assertEquals(FailureKind.INCOMPATIBLE, failure { api.roomHistory(42, 90, 50) }.kind)
        }
        assertEquals(bodies.size, server.requestCount)
    }
    @Test fun foreignOrDuplicateMutationIdsAreRejected() = runTest {
        for (metadata in listOf(mutation(81), "${mutation()},${mutation()}")) {
            server.enqueue(json("""{"messages":[${message()}]}""")); server.enqueue(json("""{"messages":[$metadata]}"""))
            assertEquals(FailureKind.INCOMPATIBLE, failure { api.roomHistory(42, null, 50) }.kind)
        }
    }
    @Test fun deletionClearsBodyAttachmentAndReplyEvenWhenOldHistoryHadContent() = runTest {
        val original = message().dropLast(1) + """, "attachment":{"id":4,"name":"Private file","mime_type":"text/plain","size_bytes":12,"sha256":"fixture","previewable":false},"reply_to":{"kind":"room","message_id":70,"available":true,"message":{"id":70,"sender":null,"type":"text","body":"Private quote","deleted":false}}}"""
        server.enqueue(json("""{"messages":[$original]}"""))
        server.enqueue(json("""{"messages":[{"id":80,"body":"Must never display","type":"text","edited_at":null,"deleted":true,"deletion_kind":"author","mentions":[],"reactions":[]}]}"""))
        val deleted = api.roomHistory(42, null, 50).messages.single()
        assertTrue(deleted.deleted); assertNull(deleted.body); assertNull(deleted.attachment); assertNull(deleted.replyTo)
        assertEquals("author", deleted.deletionKind); assertFalse(deleted.toString().contains("Private"))
    }
    @Test fun sendDistinguishesAcknowledgedMessageAndPrivatePingWithoutOptimisticIds() = runTest {
        server.enqueue(json("""{"message":${message()}}""", 201))
        assertEquals(80L, (api.sendRoomMessage(42, "Hello\nworld", csrf, 7) as SendResult.Message).message.id)
        val request = server.takeRequest()
        assertEquals("/api/v1/rooms/send.php", request.url.encodedPath); assertEquals("POST", request.method)
        assertEquals(csrf, request.headers["X-CSRF-Token"])
        assertEquals("""{"room_id":42,"body":"Hello\nworld"}""", request.body!!.utf8())
        server.enqueue(json("""{"ping":${ping()}}""", 201))
        assertEquals(3L, (api.sendRoomMessage(42, "/ping Bob Private nudge", csrf, 7) as SendResult.Ping).ping.id)
    }
    @Test fun wrongSendSuccessStatusShapeOrActorFailsWithoutPostReplay() = runTest {
        val bad = listOf("""{"message":${message()}}""" to 200, "{}" to 201,
            """{"message":${message()},"ping":${ping()}}""" to 201,
            """{"message":${message(sender = 8)}}""" to 201, """{"message":${message(room = 43)}}""" to 201,
            """{"ping":${ping()}}""" to 201)
        for ((body, status) in bad) {
            server.enqueue(json(body, status))
            assertEquals(FailureKind.INCOMPATIBLE, failure { api.sendRoomMessage(42, "Hello", csrf, 7) }.kind)
        }
        assertEquals(bad.size, server.requestCount)
    }
    private fun replyMessage(target: Long) = message().dropLast(1) +
        """, "reply_to":{"kind":"room","message_id":$target,"available":false,"message":null}}"""
    @Test fun replyUsesTheExistingSendAndPreservesAnUnavailableCanonicalReference() = runTest {
        server.enqueue(json("""{"message":${replyMessage(70)}}""", 201))
        val sent = api.sendRoomMessage(42, "Answer", csrf, 7, 70) as SendResult.Message
        assertEquals(70L, sent.message.replyTo?.messageId); assertFalse(sent.message.replyTo!!.available)
        val request = server.takeRequest()
        assertEquals("/api/v1/rooms/send.php", request.url.encodedPath)
        assertEquals(csrf, request.headers["X-CSRF-Token"])
        assertEquals("""{"room_id":42,"body":"Answer","reply_to_message_id":70}""", request.body!!.utf8())
        assertEquals(1, server.requestCount) // No fallible original/enrichment GET after a confirmed POST.
    }
    @Test fun missingOrDifferentReplyAcknowledgmentIsNotAnOrdinarySendSuccess() = runTest {
        for ((requestId, response) in listOf(70L to message(), 70L to replyMessage(71), null to replyMessage(70))) {
            server.enqueue(json("""{"message":$response}""", 201))
            assertEquals(FailureKind.INCOMPATIBLE, failure { api.sendRoomMessage(42, "Answer", csrf, 7, requestId) }.kind)
        }
        assertEquals(3, server.requestCount)
    }
    @Test fun aSelectedReplyDoesNotChangeTheServersPrivatePingSemantics() = runTest {
        server.enqueue(json("""{"ping":${ping()}}""", 201))
        assertEquals(3L, (api.sendRoomMessage(42, "/ping Bob Private nudge", csrf, 7, 70) as SendResult.Ping).ping.id)
        assertTrue(server.takeRequest().body!!.utf8().contains("\"reply_to_message_id\":70"))
        assertEquals(1, server.requestCount)
    }
    @Test fun nonPositiveReplyIdsNeverReachTheTransport() = runTest {
        for (id in listOf(0L, -1L)) {
            try { api.sendRoomMessage(42, "Answer", csrf, 7, id); fail("Invalid reply accepted") }
            catch (_: IllegalArgumentException) { }
        }
        assertEquals(0, server.requestCount)
    }
    @Test fun anExpiredReplyIsNotReplayedOrSilentlyDowngradedToAnOrdinaryMessage() = runTest {
        server.enqueue(json("""{"error":{"code":"invalid_reply_target","message":"Original unavailable"}}""", 400))
        val error = failure { api.sendRoomMessage(42, "Answer", csrf, 7, 70) }
        assertEquals(400, error.status); assertEquals("invalid_reply_target", error.code)
        assertEquals(1, server.requestCount)
    }
    @Test fun serverErrorsKeepStatusCodeAndNeverReplayMessagePosts() = runTest {
        val errors = listOf("unauthenticated" to 401, "csrf_failed" to 403, "muted" to 403,
            "membership_required" to 403, "guest_read_only" to 403, "ping_target_not_found" to 404,
            "rate_limited" to 429, "maintenance_lockdown" to 503)
        for ((code, status) in errors) {
            server.enqueue(json("""{"error":{"code":"$code","message":"Private error details"}}""", status))
            val error = failure { api.sendRoomMessage(42, "Hello", csrf, 7) }
            assertEquals(status, error.status); assertEquals(code, error.code)
            assertFalse(error.toString().contains("Private error details"))
        }
        assertEquals(errors.size, server.requestCount)
    }
    @Test fun readUsesVisibleIdAndAcceptsActualClampedOrAlreadyAdvancedCursor() = runTest {
        for (cursor in listOf(0L, 80L, 100L)) {
            server.enqueue(json("""{"last_read_message_id":$cursor}"""))
            assertEquals(cursor, api.readRoom(42, 80, csrf))
            val request = server.takeRequest()
            assertEquals("/api/v1/rooms/read.php", request.url.encodedPath)
            assertEquals(csrf, request.headers["X-CSRF-Token"])
            assertEquals("""{"room_id":42,"message_id":80}""", request.body!!.utf8())
        }
        server.enqueue(json("""{"last_read_message_id":-1}"""))
        assertEquals(FailureKind.INCOMPATIBLE, failure { api.readRoom(42, 80, csrf) }.kind)
    }
    @Test fun privatePingHistoryMustBelongToViewerAndRequestedRoom() = runTest {
        server.enqueue(json("""{"pings":[${ping()}]}"""))
        assertEquals(3L, api.roomPings(42, 7).single().id)
        assertEquals("/api/v1/rooms/pings.php?room_id=42", server.takeRequest().url.toString().substringAfter(server.url("/").toString().dropLast(1)))
        for (value in listOf(ping(viewer = 9), ping(room = 43), "${ping()},${ping()}")) {
            server.enqueue(json("""{"pings":[$value]}"""))
            assertEquals(FailureKind.INCOMPATIBLE, failure { api.roomPings(42, 7) }.kind)
        }
    }
    @Test fun invalidCompositionNeverCallsTheServer() = runTest {
        assertEquals("empty_message", failure { api.sendRoomMessage(42, " \n", csrf, 7) }.code)
        assertEquals("unknown_command", failure { api.sendRoomMessage(42, "/dm Bob Hello", csrf, 7) }.code)
        assertEquals(0, server.requestCount)
    }
    @Test fun malformedIgnoreMuteAndClockPolicyCannotBecomeAuthenticatedConversationPolicy() = runTest {
        val base = """{"csrf_token":"$csrf","user":{"id":7,"username":"Alice","roles":[],"session_version":2,"guest":false},"registration_enabled":false,"sign_in_providers":[],"lockdown":{"enabled":false}"""
        for (extra in listOf(""","ignored_user_ids":[-1]}""", ""","ignored_user_ids":[8,8]}""",
            ""","muted_everywhere":{"id":1,"scope":"room","room_id":42,"expires_at":null,"reason":"private"}}""",
            ""","preferences":{"hour_cycle":"invalid"}}""")) {
            server.enqueue(json(base + extra))
            assertEquals(FailureKind.INCOMPATIBLE, failure { api.bootstrap() }.kind)
        }
    }
}

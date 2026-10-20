package io.github.thiesi.chitchat.core.network

import io.github.thiesi.chitchat.core.rooms.CreateRoom
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

class RoomHttpTest {
    private lateinit var server: MockWebServer
    private lateinit var api: SessionHttp
    private val csrf = "a".repeat(64)
    @Before fun start() {
        val cert = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val tls = HandshakeCertificates.Builder().heldCertificate(cert).build()
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        server = MockWebServer().apply { useHttps(tls.sslSocketFactory()); start() }
        val address = (ServerAddress.parse(server.url("/").toString()) as AddressResult.Valid).address
        api = SessionHttp(address, OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager).build())
    }
    @After fun stop() { api.close(); server.close() }
    private fun json(body: String, status: Int = 200) = MockResponse.Builder().code(status)
        .addHeader("Content-Type", "application/json").body(body).build()
    private fun room(role: String = "null", id: Long = 42) = """{"id":$id,"key":"general","name":"General","info_line":"Fixture","visibility":"public","minimum_age":0,"inactivity_timeout_seconds":0,"created_by":7,"member_role":$role,"invited":false,"guest_access":"none"}"""
    private suspend fun failure(action: suspend () -> Unit): ApiFailure {
        try { action(); error("Expected room failure") } catch (failure: ApiFailure) { return failure }
    }

    @Test fun listPreservesMembershipInvitationsAndCappedUnreadWithoutGuessingEnvelopes() = runTest {
        val joined = room("\"member\"").dropLast(1) + """, "unread_count":100,"last_read_message_id":80,"muted":null,"future_field":true}"""
        server.enqueue(json("""{"rooms":[$joined,${room(id = 43).replace("\"invited\":false", "\"invited\":true")}]}"""))
        val rooms = api.rooms()
        assertEquals(2, rooms.size); assertTrue(rooms[0].isMember); assertEquals(100, rooms[0].unreadCount)
        assertEquals(80L, rooms[0].lastReadMessageId); assertTrue(rooms[1].invited); assertFalse(rooms[1].isMember)
        assertEquals("/api/v1/rooms/list.php", server.takeRequest().url.encodedPath)
    }

    @Test fun detailUsesExactNumericIdAndAllowsNullMemberRole() = runTest {
        server.enqueue(json("""{"room":${room()}}"""))
        assertFalse(api.room(42).isMember)
        val request = server.takeRequest()
        assertEquals("GET", request.method); assertEquals("/api/v1/rooms/detail.php?room_id=42", request.url.encodedPath + "?" + request.url.encodedQuery)
        assertNull(request.headers["X-CSRF-Token"])
    }

    @Test fun membershipPostsUseExistingCsrfAndRequireDocumentedSuccess() = runTest {
        server.enqueue(json("""{"room":${room("\"member\"")}}"""))
        assertTrue(api.joinRoom(42, csrf).isMember)
        val join = server.takeRequest()
        assertEquals("/api/v1/rooms/join.php", join.url.encodedPath)
        assertEquals(csrf, join.headers["X-CSRF-Token"]); assertEquals("""{"room_id":42}""", join.body!!.utf8())
        server.enqueue(json("""{"status":"left"}""")); api.leaveRoom(42, csrf)
        val leave = server.takeRequest()
        assertEquals("POST", leave.method); assertEquals("/api/v1/rooms/leave.php", leave.url.encodedPath)
        assertEquals(csrf, leave.headers["X-CSRF-Token"])
    }

    @Test fun creationNormalizesLikeBackendAndRequires201OwnedByCurrentAccount() = runTest {
        server.enqueue(json("""{"room":${room("\"owner\"")}}""", 201))
        val created = api.createRoom(CreateRoom(" GENERAL ", " General ", " Fixture ", "public", 0, 0, "none"), csrf, 7)
        assertEquals("owner", created.memberRole)
        val request = server.takeRequest()
        assertEquals("/api/v1/rooms/create.php", request.url.encodedPath)
        assertEquals("""{"key":"general","name":"General","info_line":"Fixture","visibility":"public","minimum_age":0,"inactivity_timeout_seconds":0,"guest_access":"none"}""", request.body!!.utf8())
        for ((body, status) in listOf(room("\"owner\"") to 200, room("\"member\"") to 201,
            room("\"owner\"").replace("\"created_by\":7", "\"created_by\":8") to 201)) {
            server.enqueue(json("""{"room":$body}""", status))
            assertEquals(FailureKind.INCOMPATIBLE, failure { api.createRoom(CreateRoom("general", "General", "", "public", 0, 0, "none"), csrf, 7) }.kind)
        }
    }

    @Test fun invalidCreationNeverReachesNetwork() = runTest {
        assertEquals("guest_access_scope", failure { api.createRoom(CreateRoom("general", "General", "", "private", 0, 0, "read"), csrf, 7) }.code)
        assertEquals(0, server.requestCount)
    }

    @Test fun creationPreservesHttpErrorsBeforeCheckingSuccessful201WithoutReplay() = runTest {
        val errors = listOf("unauthenticated" to 401, "forbidden" to 403, "csrf_failed" to 403,
            "fixture_conflict" to 409, "fixture_validation" to 422, "rate_limited" to 429)
        for ((code, status) in errors) {
            server.enqueue(MockResponse.Builder().code(status).addHeader("Content-Type", "application/json")
                .addHeader("Retry-After", "30").body("""{"error":{"code":"$code","message":"private fixture detail"}}""").build())
            val failure = failure { api.createRoom(CreateRoom("general", "General", "", "public", 0, 0, "none"), csrf, 7) }
            assertEquals(FailureKind.HTTP, failure.kind)
            assertEquals(status, failure.status); assertEquals(code, failure.code)
            assertEquals(30L, failure.retryAfterSeconds)
            assertFalse(failure.toString().contains("private fixture detail"))
            val request = server.takeRequest()
            assertEquals("/api/v1/rooms/create.php", request.url.encodedPath)
            assertEquals("POST", request.method); assertEquals(csrf, request.headers["X-CSRF-Token"])
        }
        assertEquals(errors.size, server.requestCount)
    }

    @Test fun metadataSchemaFailsClosedWithoutReplayingMutations() = runTest {
        for (body in listOf(room(id = 0), room(id = 43), room().replace("\"member_role\":null,", ""),
            room().replace("\"visibility\":\"public\"", "\"visibility\":\"future\""))) {
            server.enqueue(json("""{"room":$body}"""))
            assertEquals(FailureKind.INCOMPATIBLE, failure { api.room(42) }.kind)
        }
        server.enqueue(json("""{"rooms":[${room()},${room()}]}"""))
        assertEquals(FailureKind.INCOMPATIBLE, failure { api.rooms() }.kind)
        server.enqueue(json("""{"room":${room()}}"""))
        assertEquals(FailureKind.INCOMPATIBLE, failure { api.joinRoom(42, csrf) }.kind)
        server.enqueue(json("""{"status":"ok"}"""))
        assertEquals(FailureKind.INCOMPATIBLE, failure { api.leaveRoom(42, csrf) }.kind)
        assertEquals(7, server.requestCount)
    }

    @Test fun authorizationAndRateErrorsRemainExplicitAndNeverRetryThePost() = runTest {
        for ((code, status) in listOf("birth_date_required" to 403, "minimum_age_not_met" to 403,
            "invitation_required" to 403, "owner_cannot_leave" to 409, "not_a_member" to 409,
            "room_not_found" to 404, "csrf_failed" to 403, "rate_limited" to 429)) {
            server.enqueue(json("""{"error":{"code":"$code","message":"private fixture detail"}}""", status))
            val failure = failure { api.leaveRoom(42, csrf) }
            assertEquals(code, failure.code); assertEquals(status, failure.status)
            assertFalse(failure.toString().contains("private fixture detail"))
        }
        assertEquals(8, server.requestCount)
    }
}

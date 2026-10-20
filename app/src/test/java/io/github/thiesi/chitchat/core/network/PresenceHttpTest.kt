package io.github.thiesi.chitchat.core.network

import io.github.thiesi.chitchat.core.server.AddressResult
import io.github.thiesi.chitchat.core.server.ServerAddress
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class PresenceHttpTest {
    private lateinit var server: MockWebServer
    private lateinit var api: SessionHttp
    private val csrf = "a".repeat(64)
    private val uuid = "11111111-2222-4333-8444-555555555555"
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
    private fun presence(room: String = "42", idle: Int = 75, warning: String = "45", expired: Boolean = false) =
        """{"presence":{"room_id":$room,"idle_seconds":$idle,"warning_seconds":$warning,"expired":$expired,"lease_seconds":45}}"""
    private fun user(id: Long = 7, guest: Boolean = false, connections: Int = 2) =
        """{"id":$id,"username":"Private-fixture-$id","guest":$guest,"idle_seconds":12,"connections":$connections}"""
    private suspend fun failure(action: suspend () -> Unit): ApiFailure {
        try { action(); error("Expected failure") } catch (failure: ApiFailure) { return failure }
    }

    @Test fun heartbeatUsesExactCsrfPostAndSendsExplicitFalseNullCleanup() = runTest {
        server.enqueue(json(presence()))
        val active = api.presenceHeartbeat(uuid, 42, true, csrf)
        assertEquals(42L, active.roomId); assertEquals(75, active.idleSeconds); assertEquals(45, active.warningSeconds)
        val enter = server.takeRequest()
        assertEquals("POST", enter.method); assertEquals("/api/v1/presence/heartbeat.php", enter.url.encodedPath)
        assertEquals(csrf, enter.headers["X-CSRF-Token"])
        val enteredBody = apiJson.parseToJsonElement(checkNotNull(enter.body).utf8()).jsonObject
        assertEquals(setOf("connection_id", "room_id", "interacted"), enteredBody.keys)
        assertEquals(uuid, enteredBody.getValue("connection_id").jsonPrimitive.content)
        assertEquals("42", enteredBody.getValue("room_id").jsonPrimitive.content)
        assertEquals("true", enteredBody.getValue("interacted").jsonPrimitive.content)
        server.enqueue(json(presence(room = "null", idle = 0, warning = "null")))
        val left = api.presenceHeartbeat(uuid, null, false, csrf)
        assertNull(left.roomId); assertFalse(left.expired)
        val leave = server.takeRequest()
        assertEquals("POST", leave.method); assertEquals("/api/v1/presence/heartbeat.php", leave.url.encodedPath)
        assertEquals(csrf, leave.headers["X-CSRF-Token"])
        val leftBody = apiJson.parseToJsonElement(checkNotNull(leave.body).utf8()).jsonObject
        assertEquals(setOf("connection_id", "room_id", "interacted"), leftBody.keys)
        assertEquals("null", leftBody.getValue("room_id").toString())
        assertEquals("false", leftBody.getValue("interacted").jsonPrimitive.content)
        assertEquals(2, server.requestCount)
    }

    @Test fun inactivityExpiryIsReturnedWithoutInventedReentryOrAdditionalPost() = runTest {
        server.enqueue(json(presence(room = "null", idle = 0, warning = "null", expired = true)))
        val expired = api.presenceHeartbeat(uuid, 42, false, csrf)
        assertTrue(expired.expired); assertNull(expired.roomId); assertEquals(0, expired.idleSeconds)
        assertNull(expired.warningSeconds); assertEquals(45, expired.leaseSeconds)
        assertEquals(1, server.requestCount)
    }

    @Test fun aggregateListUsesActualRoomEndpointAndPreservesGuestAndConnectionFields() = runTest {
        server.enqueue(json("""{"users":[${user()},${user(8, guest = true, connections = 1)}]}"""))
        val users = api.roomPresence(42)
        assertEquals(listOf(7L, 8L), users.map { it.id }); assertEquals(2, users.first().connections)
        assertFalse(users.first().guest); assertTrue(users.last().guest); assertEquals(12, users.last().idleSeconds)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/v1/rooms/presence.php?room_id=42", request.url.encodedPath + "?" + request.url.encodedQuery)
        assertNull(request.headers["X-CSRF-Token"])
        assertFalse(users.first().toString().contains("Private-fixture"))
        server.enqueue(json("""{"users":[${(1L..101).joinToString(",") { user(it) }}]}"""))
        assertEquals(101, api.roomPresence(42).size)
        assertEquals(2, server.requestCount)
    }

    @Test fun invalidConnectionAndRoomInputsNeverReachTheNetwork() = runTest {
        for (invalid in listOf("not-a-uuid", uuid.replace("4333", "6333"), uuid.replace("8444", "7444"))) {
            assertEquals(FailureKind.INCOMPATIBLE, failure { api.presenceHeartbeat(invalid, 42, false, csrf) }.kind)
        }
        for (room in listOf(0L, -1L)) {
            assertEquals(FailureKind.INCOMPATIBLE, failure { api.presenceHeartbeat(uuid, room, false, csrf) }.kind)
            try { api.roomPresence(room); fail("Expected invalid room rejection") }
            catch (_: IllegalArgumentException) { /* Same pre-I/O contract as other room GETs. */ }
        }
        assertEquals(0, server.requestCount)
    }

    @Test fun malformedHeartbeatSuccessNeverTriggersAnAutomaticReplay() = runTest {
        val bodies = listOf("{}", """{"presence":{}}""", presence(room = "43"),
            presence().replace("\"warning_seconds\":45,", ""), presence(expired = true),
            presence().replace("\"lease_seconds\":45", "\"lease_seconds\":0"))
        for ((index, body) in bodies.withIndex()) {
            server.enqueue(json(body))
            assertEquals(FailureKind.INCOMPATIBLE, failure { api.presenceHeartbeat(uuid, 42, false, csrf) }.kind)
            assertEquals(index + 1, server.requestCount)
        }
    }

    @Test fun malformedOrDuplicateAggregateRowsCannotMasqueradeAsPresence() = runTest {
        val bodies = listOf("{}", """{"users":[${user()},${user()}]}""",
            """{"users":[${user().replace("\"guest\":false,", "")}]}""",
            """{"users":[${user(connections = 0)}]}""")
        for ((index, body) in bodies.withIndex()) {
            server.enqueue(json(body))
            assertEquals(FailureKind.INCOMPATIBLE, failure { api.roomPresence(42) }.kind)
            assertEquals(index + 1, server.requestCount)
        }
    }

    @Test fun heartbeatPolicyAndAuthenticationFailuresKeepTheirStatusCodeWithoutReplay() = runTest {
        val rejected = listOf(401 to "authentication_required", 403 to "csrf_failed", 403 to "presence_membership_required",
            403 to "birth_date_required", 403 to "minimum_age_not_met", 409 to "presence_connection_conflict",
            429 to "rate_limited", 503 to "service_unavailable")
        for ((index, expected) in rejected.withIndex()) {
            val (status, code) = expected
            server.enqueue(MockResponse.Builder().code(status).addHeader("Content-Type", "application/json")
                .addHeader("Retry-After", "2")
                .body("""{"error":{"code":"$code","message":"Private fixture error detail"}}""").build())
            val failure = failure { api.presenceHeartbeat(uuid, 42, false, csrf) }
            assertEquals(FailureKind.HTTP, failure.kind); assertEquals(status, failure.status); assertEquals(code, failure.code)
            if (status == 429 || status == 503) assertEquals(2L, failure.retryAfterSeconds)
            assertFalse(failure.toString().contains("Private fixture error detail"))
            assertEquals(index + 1, server.requestCount)
        }
    }

    @Test fun presenceListAuthorizationFailureIsNotAnEmptySuccessfulList() = runTest {
        server.enqueue(json("""{"error":{"code":"presence_membership_required","message":"Private fixture error detail"}}""", 403))
        val failure = failure { api.roomPresence(42) }
        assertEquals(FailureKind.HTTP, failure.kind); assertEquals(403, failure.status)
        assertEquals("presence_membership_required", failure.code); assertEquals(1, server.requestCount)
    }
}

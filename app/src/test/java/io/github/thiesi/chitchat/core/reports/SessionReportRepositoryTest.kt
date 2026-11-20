package io.github.thiesi.chitchat.core.reports

import io.github.thiesi.chitchat.core.media.AttachmentUpload
import io.github.thiesi.chitchat.core.media.PreparedAttachmentPayload
import io.github.thiesi.chitchat.core.media.SessionAttachmentRepository
import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.network.SessionHttp
import io.github.thiesi.chitchat.core.profile.changedProfile
import io.github.thiesi.chitchat.core.profile.profileBody
import io.github.thiesi.chitchat.core.server.AddressResult
import io.github.thiesi.chitchat.core.server.ServerAddress
import io.github.thiesi.chitchat.core.session.SessionPhase
import io.github.thiesi.chitchat.core.session.SavedSession
import io.github.thiesi.chitchat.core.session.SessionRepository
import io.github.thiesi.chitchat.core.session.SessionState
import io.github.thiesi.chitchat.core.session.SessionVault
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.ByteString.Companion.toByteString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** Reporting R1: both POSTs through the original session, typed outcomes, and the report budget's 429 kept local (TL). */
class SessionReportRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var sessions: SessionRepository
    private val clients = mutableListOf<SessionHttp>()
    private val reports get() = SessionReportRepository(sessions)
    private val csrf = "b".repeat(64)

    @Before fun start() {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val tls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        server = MockWebServer().apply { useHttps(tls.sslSocketFactory()); start() }
        val client = OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager).build()
        sessions = SessionRepository(Vault()) { SessionHttp(it, client).also(clients::add) }
    }
    @After fun stop() { clients.forEach(SessionHttp::close); server.close() }

    private fun json(body: String, status: Int = 200, cookie: String? = null) = MockResponse.Builder()
        .code(status).addHeader("Content-Type", "application/json").body(body)
        .apply { cookie?.let { addHeader("Set-Cookie", it) } }.build()
    private fun error(code: String, status: Int, extra: String = "") = json("""{"error":{"code":"$code","message":"x"$extra}}""", status)
    private fun take() = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS))
    private fun address() = (ServerAddress.parse(server.url("/").toString()) as AddressResult.Valid).address
    private val created = json("""{"case":{"id":9,"status":"open","report_count":1}}""", 201)

    private suspend fun signedIn(policy: String? = null): SessionState {
        server.enqueue(json("""{"csrf_token":"${"a".repeat(64)}","user":null,"application":{"version":"3.10.0"},"capabilities":{"schema_version":1},"registration_enabled":false,"sign_in_providers":[],"lockdown":{"enabled":false}}""",
            cookie = "SITE=anon; Secure; HttpOnly; Path=/"))
        sessions.connect(address()); take()
        server.enqueue(json("""{"csrf_token":"$csrf","user":{"id":7,"username":"Viewer","roles":["member"],"session_version":1,"guest":false},"mfa_required":false}""",
            cookie = "SITE=auth; Secure; HttpOnly; Path=/"))
        sessions.signIn("Viewer", "fixture-password"); take()
        if (policy != null) {
            server.enqueue(json("""{"csrf_token":"$csrf","user":{"id":7,"username":"Viewer","roles":["member"],"session_version":1,"guest":false},"application":{"version":"3.10.0"},"capabilities":{"schema_version":1},"attachments":$policy,"registration_enabled":false,"sign_in_providers":[],"lockdown":{"enabled":false}}"""))
            sessions.refresh(); take()
        }
        assertEquals(SessionPhase.AUTHENTICATED, sessions.state.value.phase)
        return sessions.state.value
    }

    private fun body(request: mockwebserver3.RecordedRequest) = Json.parseToJsonElement(request.body!!.utf8()).jsonObject

    @Test fun aRoomMessageReportIsOneCsrfPostAndA201IsSent() = runTest {
        val state = signedIn()
        server.enqueue(created)
        assertEquals(ReportResult.Sent(9), reports.send(state, ReportTarget.RoomMessage(148), ReportCategory.HARASSMENT, "  why \n"))
        val request = take()
        assertEquals("POST", request.method)
        assertEquals("/api/v1/reports/message.php", request.target)
        assertEquals(csrf, request.headers["X-CSRF-Token"])
        val sent = body(request)
        assertEquals(JsonPrimitive("room"), sent["message_kind"])
        assertEquals(JsonPrimitive(148), sent["message_id"])
        assertEquals(JsonPrimitive("harassment"), sent["category"])
        assertEquals("Details go as typed", JsonPrimitive("  why \n"), sent["details"])
        assertEquals("Exactly one request", 3, server.requestCount)
    }

    @Test fun aDirectReportSaysDirectAndNoDetailsIsNull() = runTest {
        val state = signedIn()
        server.enqueue(created)
        reports.send(state, ReportTarget.DirectMessage(42), ReportCategory.SPAM, null)
        val sent = body(take())
        assertEquals(JsonPrimitive("direct"), sent["message_kind"])
        assertEquals(JsonNull, sent["details"])
    }

    @Test fun a201WithAnUnreadableBodyIsStillSent() = runTest {
        val state = signedIn()
        server.enqueue(json("not json", 201))
        assertEquals(ReportResult.Sent(null), reports.send(state, ReportTarget.RoomMessage(1), ReportCategory.SPAM, null))
    }

    @Test fun aPersonReportCarriesItsRoomContextOrNone() = runTest {
        val state = signedIn()
        server.enqueue(created); server.enqueue(created)
        reports.send(state, ReportTarget.Person(8, 4), ReportCategory.OFFENSIVE_USERNAME, null)
        val scoped = take()
        assertEquals("/api/v1/reports/user.php", scoped.target)
        assertEquals(JsonPrimitive(8), body(scoped)["user_id"])
        assertEquals(JsonPrimitive(4), body(scoped)["context"]!!.jsonObject["room_id"])
        reports.send(state, ReportTarget.Person(8, null), ReportCategory.SPAM, null)
        assertEquals("No room: a global report", JsonNull, body(take())["context"])
    }

    @Test fun aPersonOnlyCategoryIsNeverSentForAMessage() = runTest {
        val state = signedIn()
        val before = server.requestCount
        assertEquals(ReportResult.Invalid, reports.send(state, ReportTarget.RoomMessage(1), ReportCategory.OFFENSIVE_USERNAME, null))
        assertEquals(before, server.requestCount)
    }

    @Test fun refusalsStayTypedAndNothingIsResent() = runTest {
        val state = signedIn()
        server.enqueue(error("message_already_reported", 409))
        assertEquals(ReportResult.AlreadyReported, reports.send(state, ReportTarget.RoomMessage(1), ReportCategory.SPAM, null))
        server.enqueue(error("report_recipient_required", 403))
        assertEquals(ReportResult.Refused(ReportRefusal.NOT_ALLOWED), reports.send(state, ReportTarget.DirectMessage(2), ReportCategory.SPAM, null))
        server.enqueue(error("report_snapshot_unavailable", 409))
        assertEquals(ReportResult.SnapshotUnavailable, reports.send(state, ReportTarget.Person(8, null), ReportCategory.SPAM, null))
        server.enqueue(error("validation_error", 400))
        assertEquals(ReportResult.PictureGone, reports.send(state, ReportTarget.Person(8, null), ReportCategory.OFFENSIVE_PROFILE_PICTURE, null))
        assertEquals("One request each", 2 + 4, server.requestCount)
    }

    @Test fun theReportBudgets429StaysLocalAndTheSessionKeepsWorking() = runTest {
        val state = signedIn()
        server.enqueue(error("rate_limited", 429, ""","retry_after_seconds":1200"""))
        assertEquals(ReportResult.RateLimited(1200), reports.send(state, ReportTarget.RoomMessage(1), ReportCategory.SPAM, null))
        take()
        assertEquals("The session's wait is untouched", state.retryAtMillis, sessions.state.value.retryAtMillis)
        assertNull("No session error", sessions.state.value.error)
        // A later read goes through the session, not refused locally.
        server.enqueue(json(changedProfile("can_report", JsonPrimitive(true), changedProfile("reported_by_me", JsonPrimitive(false), profileBody()))))
        assertEquals(ReportFacts(true, false), reports.personFacts(sessions.state.value, ReportTarget.Person(8, null)))
        assertEquals("/api/v1/users/profile.php?user_id=8", take().target)
    }

    @Test fun aNonJson429OnTheSamePathStillTakesTheSessionWideLock() = runTest {
        val state = signedIn()
        server.enqueue(MockResponse.Builder().code(429).addHeader("Retry-After", "60").body("busy").build())
        assertEquals(ReportResult.RateLimited(60), reports.send(state, ReportTarget.RoomMessage(1), ReportCategory.SPAM, null))
        take()
        assertTrue("The session-wide wait applies", sessions.state.value.retryAtMillis > state.retryAtMillis)
        val before = server.requestCount
        assertTrue("And the next report is refused locally", reports.send(sessions.state.value, ReportTarget.RoomMessage(2),
            ReportCategory.SPAM, null) is ReportResult.RateLimited)
        assertEquals(before, server.requestCount)
    }

    @Test fun anUpload429IsUnchanged() = runTest {
        val state = signedIn("""{"max_bytes":1000,"allowed_mime_types":["text/plain"],"previewable_mime_types":[],"rate_limits":{"room":{"maximum_attempts":10,"window_seconds":3600},"direct_message":{"maximum_attempts":10,"window_seconds":3600}}}""")
        server.enqueue(error("rate_limited", 429, ""","retry_after_seconds":900"""))
        val upload = AttachmentUpload(PreparedAttachmentPayload.create("fixture.txt", "text/plain", byteArrayOf(1, 2, 3).toByteString()), "")
        try { SessionAttachmentRepository(sessions).room(state, 4, upload); fail() } catch (expected: ApiFailure) { assertEquals(429, expected.status) }
        take()
        assertTrue("An upload's 429 keeps the session-wide wait (#323 decides otherwise later)",
            sessions.state.value.retryAtMillis > state.retryAtMillis)
    }

    @Test fun personFactsReadTheProfileStrictly() = runTest {
        val state = signedIn()
        server.enqueue(json(changedProfile("can_report", JsonPrimitive(true), changedProfile("reported_by_me", JsonPrimitive(true), profileBody()))))
        assertEquals(ReportFacts(true, true), reports.personFacts(state, ReportTarget.Person(8, 4)))
        assertEquals("The frozen room context", "/api/v1/users/profile.php?user_id=8&room_id=4", take().target)
        server.enqueue(json(profileBody()))
        assertNull("Missing facts are unknown", reports.personFacts(state, ReportTarget.Person(8, null)))
        take()
        server.enqueue(json(changedProfile("can_report", JsonPrimitive("true"), changedProfile("reported_by_me", JsonPrimitive(false), profileBody()))))
        assertNull("A quoted Boolean is unknown", reports.personFacts(state, ReportTarget.Person(8, null)))
        take()
        assertFalse("A profile never decides the update state", sessions.state.value.updateRequired)
    }

    private class Vault : SessionVault {
        var saved: SavedSession? = null
        override suspend fun read() = saved
        override suspend fun save(session: SavedSession) { saved = session }
        override suspend fun clear() { saved = null }
    }
}

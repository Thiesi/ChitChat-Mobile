package io.github.thiesi.chitchat.core.search

import io.github.thiesi.chitchat.core.network.*
import io.github.thiesi.chitchat.core.server.AddressResult
import io.github.thiesi.chitchat.core.server.ServerAddress
import io.github.thiesi.chitchat.core.session.*
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Real TLS and the production session capability. No live account, query log or native input action. */
class MessageSearchProtocolTest {
    private lateinit var server: MockWebServer
    private lateinit var sessions: SessionRepository
    private lateinit var repository: SessionMessageSearchRepository
    private lateinit var api: SessionHttp
    private val clients = mutableListOf<SessionHttp>()
    private val vault = Vault()
    private val anonymous = "a".repeat(64)
    private val authenticated = "b".repeat(64)
    private val empty = """{"results":[],"has_more":false,"next_offset":null}"""
    private val query = MessageSearchRequest("private fixture")
    @Before fun start() {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val tls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        server = MockWebServer().apply { useHttps(tls.sslSocketFactory()); start() }
        val client = OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager).build()
        sessions = SessionRepository(vault) { SessionHttp(it, client).also { value -> api = value; clients.add(value) } }
        repository = SessionMessageSearchRepository(sessions)
    }
    @After fun stop() { clients.forEach(SessionHttp::close); server.close() }
    private fun json(body: String, status: Int = 200, cookie: String? = null, delay: Long = 0) = MockResponse.Builder()
        .code(status).addHeader("Content-Type", "application/json").body(body).bodyDelay(delay, TimeUnit.MILLISECONDS)
        .apply { cookie?.let { addHeader("Set-Cookie", it) } }.build()
    private fun take() = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS))
    private suspend fun login(id: Long = 7, version: Int = 1): SessionState {
        server.enqueue(json("""{"csrf_token":"$anonymous","user":null,"registration_enabled":false,"sign_in_providers":[],"lockdown":{"enabled":false}}""",
            cookie = "SITE=anon; Secure; HttpOnly; Path=/"))
        sessions.connect((ServerAddress.parse(server.url("/").toString()) as AddressResult.Valid).address); take()
        server.enqueue(json("""{"csrf_token":"$authenticated","user":{"id":$id,"username":"Viewer","roles":["member"],"session_version":$version,"guest":false},"mfa_required":false}""",
            cookie = "SITE=auth$id; Secure; HttpOnly; Path=/"))
        sessions.signIn("Viewer", "fixture-password"); take()
        assertEquals(SessionPhase.AUTHENTICATED, sessions.state.value.phase)
        return sessions.state.value
    }
    private suspend fun logout() { server.enqueue(json("""{"status":"logged_out"}""")); sessions.signOut(); take() }
    private fun errorResponse(code: String, status: Int) = json("""{"error":{"code":"$code","message":"Private fixture response"}}""", status)
    private suspend fun failure(action: suspend () -> Unit): ApiFailure {
        try { action(); error("Expected typed failure") } catch (failure: ApiFailure) { return failure }
    }

    @Test fun termsStayInTheExactCsrfProtectedPostBodyUsingTheOriginalCookie() = runTest {
        val original = login()
        val terms = "  😀 \"deployment rehearsal\"\t-rollback\n@Alice  "
        for (scope in MessageSearchScope.entries) {
            server.enqueue(json(empty))
            assertTrue(repository.search(original, MessageSearchRequest(terms, scope, 50, 5000)).results.isEmpty())
            val request = take()
            assertEquals("POST", request.method); assertEquals("/api/v1/search/messages.php", request.url.encodedPath)
            assertNull(request.url.encodedQuery); assertEquals(authenticated, request.headers["X-CSRF-Token"])
            assertEquals("SITE=auth7", request.headers["Cookie"])
            val body = apiJson.parseToJsonElement(request.body!!.utf8()).jsonObject
            assertEquals(terms, body.getValue("query").jsonPrimitive.content)
            assertEquals(scope.wireValue, body.getValue("scope").jsonPrimitive.content)
            assertEquals(50, body.getValue("limit").jsonPrimitive.int)
            assertEquals(5000, body.getValue("offset").jsonPrimitive.int)
        }
        assertEquals(5, server.requestCount) // No bootstrap, read, send, metadata or notification request.
    }
    @Test fun invalidLocalPageBoundsNeverReachTheServer() = runTest {
        val original = login(); val count = server.requestCount
        for (request in listOf(MessageSearchRequest("fixture", limit = 0), MessageSearchRequest("fixture", limit = 51),
            MessageSearchRequest("fixture", offset = -1), MessageSearchRequest("fixture", offset = 5001)))
            assertEquals(400, failure { repository.search(original, request) }.status)
        assertEquals(count, server.requestCount)
    }
    @Test fun serverOwnsQueryNormalizationValidationAndSyntaxWithoutAReplay() = runTest {
        val original = login()
        for (terms in listOf("", "-", "a".repeat(201))) {
            server.enqueue(errorResponse("validation_error", 400))
            val bad = failure { repository.search(original, MessageSearchRequest(terms)) }
            assertEquals(400, bad.status); assertEquals("validation_error", bad.code)
            assertFalse(bad.toString().contains("Private fixture"))
            assertEquals(terms, apiJson.parseToJsonElement(take().body!!.utf8()).jsonObject.getValue("query").jsonPrimitive.content)
        }
        assertEquals(5, server.requestCount)
    }
    @Test fun malformedFlagsAndSchemasAreSanitizedAtTheHttpBoundary() = runTest {
        val original = login()
        for (body in listOf("{}", empty.replace("false", "\"false\""), empty.replace("[]", "null"),
            """{"results":[],"has_more":true,"next_offset":25}""")) {
            server.enqueue(json(body))
            assertEquals(FailureKind.INCOMPATIBLE, failure { repository.search(original, query) }.kind); take()
        }
    }
    @Test fun wrongStatusAndOversizedSmallJsonAreRejectedWithoutBroadeningTheTransportLimit() = runTest {
        val original = login(); server.enqueue(json(empty, 201))
        assertEquals(FailureKind.INCOMPATIBLE, failure { repository.search(original, query) }.kind); take()
        server.enqueue(json(" ".repeat(1_048_577)))
        assertEquals(FailureKind.INCOMPATIBLE, failure { repository.search(original, query) }.kind); take()
    }
    @Test fun serverRefusalAndMaintenanceAreTypedAndNeverAutomaticallyReplayed() = runTest {
        val original = login()
        for ((status, code) in listOf(403 to "forbidden", 503 to "maintenance_lockdown")) {
            server.enqueue(errorResponse(code, status))
            val bad = failure { repository.search(original, query) }; take()
            assertEquals(status, bad.status); assertEquals(code, bad.code)
        }
        assertEquals(4, server.requestCount)
    }
    @Test fun csrfFailureRequiresRevalidationAndNeverReplaysTheQuery() = runTest {
        val original = login(); server.enqueue(errorResponse("csrf_failed", 403))
        assertEquals("csrf_failed", failure { repository.search(original, query) }.code); take()
        assertTrue(sessions.state.value.refreshRequired)
        failure { repository.search(original, query) }; assertEquals(3, server.requestCount)
    }
    @Test fun rateLimitUsesTheSharedCooldownRatherThanASecondSearchTransport() = runTest {
        val original = login()
        server.enqueue(MockResponse.Builder().code(429).addHeader("Content-Type", "application/json").addHeader("Retry-After", "30")
            .body("""{"error":{"code":"rate_limited","message":"Private fixture"}}""").build())
        val bad = failure { repository.search(original, query) }; take()
        assertEquals(30L, bad.retryAfterSeconds); assertTrue(sessions.state.value.retryAtMillis > System.currentTimeMillis())
        assertEquals(429, failure { repository.search(original, query) }.status); assertEquals(3, server.requestCount)
    }
    @Test fun unauthorizedRetiresTheSessionAndOldSearchCannotPublish() = runTest {
        val original = login(); server.enqueue(errorResponse("session_invalidated", 401))
        assertEquals(401, failure { repository.search(original, query) }.status); take()
        assertEquals(SessionPhase.INVALIDATED, sessions.state.value.phase); assertNull(vault.saved)
        try { repository.search(original, query); fail("Retired search") } catch (_: CancellationException) { }
        assertEquals(3, server.requestCount)
    }
    @Test fun redirectCannotForwardQueriesCookiesOrCsrfToAnotherOrigin() = runTest {
        val original = login(); server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "https://foreign.example.test/").build())
        assertEquals(FailureKind.REDIRECT, failure { repository.search(original, query) }.kind); take()
        assertEquals(3, server.requestCount)
    }
    @Test fun oldAccountGenerationAndVersionCallbacksCannotAcquireReplacementAccess() = runTest {
        val original = login(); logout(); val replacement = login(version = 2); val count = server.requestCount
        try { repository.search(original, query); fail("Stale version/generation") } catch (_: CancellationException) { }
        assertEquals(count, server.requestCount)
        server.enqueue(json(empty)); repository.search(replacement, query); take()
        logout(); val another = login(9); val later = server.requestCount
        try { repository.search(replacement, query); fail("Stale account") } catch (_: CancellationException) { }
        assertEquals(later, server.requestCount); server.enqueue(json(empty)); repository.search(another, query)
        assertEquals("SITE=auth9", take().headers["Cookie"])
    }
    @Test fun lateResponseIsRejectedAfterLogoutWithoutRetainingPrivateResults() = runTest {
        val original = login(); server.enqueue(json(empty, delay = 500))
        val pending = async { runCatching { repository.search(original, query) } }
        withContext(Dispatchers.IO) { take() }; logout()
        assertTrue(pending.await().exceptionOrNull() is CancellationException)
        assertEquals(SessionPhase.SIGNED_OUT, sessions.state.value.phase); assertNull(vault.saved)
    }
    @Test fun cancelledQueryIsNotReplayedAndNextSearchRequiresAnExplicitNewCall() = runTest {
        val original = login(); server.enqueue(json(empty, delay = 500))
        val pending = async { repository.search(original, query) }; withContext(Dispatchers.IO) { take() }
        pending.cancelAndJoin(); assertEquals(3, server.requestCount)
        server.enqueue(json(empty)); repository.search(original, MessageSearchRequest("new explicit fixture")); take()
        assertEquals(4, server.requestCount)
    }
    @Test fun guestAndAnonymousStatesNeverOpenAMemberSearchCapability() = runTest {
        server.enqueue(json("""{"csrf_token":"$anonymous","user":{"id":10,"username":"Guest","roles":[],"session_version":1,"guest":true},"registration_enabled":false,"sign_in_providers":[],"lockdown":{"enabled":false}}"""))
        sessions.connect((ServerAddress.parse(server.url("/").toString()) as AddressResult.Valid).address); take()
        assertEquals(SessionPhase.GUEST, sessions.state.value.phase)
        failure { repository.search(sessions.state.value, query) }; assertEquals(1, server.requestCount)
        server.enqueue(json("""{"csrf_token":"$anonymous","user":null,"registration_enabled":false,"sign_in_providers":[],"lockdown":{"enabled":false}}"""))
        sessions.connect((ServerAddress.parse(server.url("/").toString()) as AddressResult.Valid).address); take()
        assertEquals(SessionPhase.ANONYMOUS, sessions.state.value.phase)
        failure { repository.search(sessions.state.value, query) }; assertEquals(2, server.requestCount)
    }
    private class Vault : SessionVault {
        var saved: SavedSession? = null
        override suspend fun read() = saved
        override suspend fun save(session: SavedSession) { saved = session }
        override suspend fun clear() { saved = null }
    }
}

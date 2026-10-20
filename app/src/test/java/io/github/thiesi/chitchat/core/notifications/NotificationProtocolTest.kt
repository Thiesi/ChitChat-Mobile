package io.github.thiesi.chitchat.core.notifications

import io.github.thiesi.chitchat.core.network.*
import io.github.thiesi.chitchat.core.server.AddressResult
import io.github.thiesi.chitchat.core.server.ServerAddress
import io.github.thiesi.chitchat.core.session.*
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Real TLS/HTTP plus the production session capability; no installation or private credential. */
class NotificationProtocolTest {
    private lateinit var server: MockWebServer
    private lateinit var sessions: SessionRepository
    private lateinit var repository: SessionNotificationRepository
    private lateinit var api: SessionHttp
    private val clients = mutableListOf<SessionHttp>()
    private val vault = Vault()
    private val anonymous = "a".repeat(64); private val authenticated = "b".repeat(64)
    @Before fun start() {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val tls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        server = MockWebServer().apply { useHttps(tls.sslSocketFactory()); start() }
        val client = OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager).build()
        sessions = SessionRepository(vault) { SessionHttp(it, client).also { value -> api = value; clients.add(value) } }
        repository = SessionNotificationRepository(sessions)
    }
    @After fun stop() { vault.gate?.complete(Unit); clients.forEach(SessionHttp::close); server.close() }
    private fun json(body: String, status: Int = 200, cookie: String? = null, delay: Long = 0) = MockResponse.Builder()
        .code(status).addHeader("Content-Type", "application/json").body(body).bodyDelay(delay, TimeUnit.MILLISECONDS)
        .apply { cookie?.let { addHeader("Set-Cookie", it) } }.build()
    private fun take() = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS))
    private suspend fun login(id: Long = 7): SessionState {
        server.enqueue(json("""{"csrf_token":"$anonymous","user":null,"registration_enabled":false,"sign_in_providers":[],"lockdown":{"enabled":false}}""",
            cookie = "SITE=anon; Secure; HttpOnly; Path=/"))
        sessions.connect((ServerAddress.parse(server.url("/").toString()) as AddressResult.Valid).address); take()
        server.enqueue(json("""{"csrf_token":"$authenticated","user":{"id":$id,"username":"Viewer","roles":["member"],"session_version":1,"guest":false},"mfa_required":false}""",
            cookie = "SITE=auth$id; Secure; HttpOnly; Path=/"))
        sessions.signIn("Viewer", "fixture-password"); take()
        assertEquals(SessionPhase.AUTHENTICATED, sessions.state.value.phase)
        return sessions.state.value
    }
    private suspend fun logout() { server.enqueue(json("""{"status":"logged_out"}""")); sessions.signOut(); take() }
    private fun item(id: Long, link: String? = "/?room_id=42&message_id=91") = AccountNotification(id, "future_kind",
        "Private fixture title", "Private fixture text", listOf("Fixture detail"), link, false, null, "2026-10-20 10:00:00+00")
    private fun page(vararg notices: AccountNotification, unread: Int = 3) =
        apiJson.encodeToString(NotificationListReply(notices.toList(), unread))
    private fun read(updated: Int = 1, unread: Int = 2) = """{"updated":$updated,"unread_count":$unread}"""
    private fun error(code: String, status: Int) = json("""{"error":{"code":"$code","message":"Private fixture failure"}}""", status)
    private suspend fun failure(action: suspend () -> Unit): ApiFailure {
        try { action(); error("Expected typed failure") } catch (failure: ApiFailure) { return failure }
    }

    @Test fun pageUsesExistingCookieAndRawDescendingCursorWithoutMarkingAnything() = runTest {
        val original = login(); server.enqueue(json(page(item(90), item(50))))
        val result = repository.page(original, beforeId = 91, limit = 2)
        assertEquals(listOf(90L, 50L), result.notifications.map { it.id }); assertEquals(50L, result.olderCursor)
        assertTrue(result.mayHaveOlder); assertEquals(3, result.unreadCount)
        val request = take(); assertEquals("GET", request.method); assertEquals("SITE=auth7", request.headers["Cookie"])
        assertNull(request.headers["X-CSRF-Token"]); assertEquals("/api/v1/account/notifications/list.php", request.url.encodedPath)
        assertEquals("91", request.url.queryParameter("before_id")); assertEquals("2", request.url.queryParameter("limit"))
        assertEquals(3, server.requestCount); assertFalse(result.notifications.first().read)
    }
    @Test fun readIdsAndAllUseExactCsrfProtectedBodiesAndDoNotConfuseZeroWithFailure() = runTest {
        val original = login()
        server.enqueue(json(read(0, 5))); val done = repository.markRead(original, listOf(3, 3, 4))
        assertEquals(0, done.updated); assertEquals(5, done.unreadCount)
        val ids = take(); assertEquals("POST", ids.method); assertEquals(authenticated, ids.headers["X-CSRF-Token"])
        assertEquals("SITE=auth7", ids.headers["Cookie"]); assertEquals("/api/v1/account/notifications/read.php", ids.url.encodedPath)
        assertEquals("""{"ids":[3,3,4]}""", ids.body!!.utf8())
        server.enqueue(json(read(1000, 0))); assertEquals(1000, repository.markAllRead(original).updated)
        val all = take(); assertEquals("""{"all":true}""", all.body!!.utf8()); assertEquals(authenticated, all.headers["X-CSRF-Token"])
        server.enqueue(json(read(0, 0))); assertEquals(0, repository.markRead(original, emptyList()).updated)
        assertEquals("""{"ids":[]}""", take().body!!.utf8())
    }
    @Test fun invalidCallerIdsAndLimitsAreRejectedBeforeAnyHttpRequest() = runTest {
        val original = login(); val count = server.requestCount
        for (limit in listOf(0, 101)) assertEquals(400, failure { repository.page(original, limit = limit) }.status)
        for (before in listOf(0L, -1)) assertEquals(400, failure { repository.page(original, beforeId = before) }.status)
        for (ids in listOf(listOf(0L), listOf(-1L), List(101) { 1L }))
            assertEquals(400, failure { repository.markRead(original, ids) }.status)
        assertEquals(count, server.requestCount)
    }
    @Test fun exactHundredRowBoundaryAndMaximumBeforeIdUseExistingRawPageEvidence() = runTest {
        val original = login(); val rows = (100L downTo 1).map { item(it) }
        server.enqueue(json(page(*rows.toTypedArray(), unread = 100)))
        val result = repository.page(original, beforeId = Long.MAX_VALUE, limit = 100)
        assertEquals(100, result.notifications.size); assertEquals(1L, result.olderCursor); assertTrue(result.mayHaveOlder)
        val request = take(); assertEquals(Long.MAX_VALUE.toString(), request.url.queryParameter("before_id"))
        assertEquals("100", request.url.queryParameter("limit"))
        server.enqueue(json(read(100, 0))); assertEquals(100, repository.markRead(original, (1L..100).toList()).updated); take()
    }
    @Test fun emptyPageAndUnknownLinkStillHaveAuthoritativeIndependentUnreadCounts() = runTest {
        val original = login(); server.enqueue(json(page(unread = 500)))
        val empty = repository.page(original); assertNull(empty.olderCursor); assertFalse(empty.mayHaveOlder); assertEquals(500, empty.unreadCount); take()
        server.enqueue(json(page(item(9, "https://foreign.example.test/"), unread = 0)))
        val value = repository.page(original).notifications.single(); take()
        assertEquals("Private fixture text", value.message); assertEquals("future_kind", value.kind)
        assertNull(notificationDestination(checkNotNull(original.server), value.link)) // A bad link does not hide the notice text.
    }
    @Test fun malformedSuccessFlagsOrderingAndSchemaBecomeTypedErrorsWithoutContentExposure() = runTest {
        login()
        val ordinary = page(item(9))
        val bodies = listOf("{}", """{"notifications":null,"unread_count":0}""", page(item(9), item(9)),
            page(item(1), item(2)), page(item(0)), ordinary.replace("\"read\":false", "\"read\":\"false\""),
            ordinary.replace("\"read_at\":null", "\"read_at\":\"2026-10-20T12:00:00Z\""),
            ordinary.replace("2026-10-20 10:00:00+00", "invalid"), page(item(9), unread = -1))
        for (body in bodies) {
            server.enqueue(json(body)); val bad = failure { api.notifications(null, 25) }; take()
            assertEquals(FailureKind.INCOMPATIBLE, bad.kind); assertFalse(bad.toString().contains("Private fixture"))
        }
    }
    @Test fun wrongSuccessStatusAndOversizedSmallJsonAreNotSilentlyAccepted() = runTest {
        login(); server.enqueue(json(page(item(1)), 201))
        assertEquals(FailureKind.INCOMPATIBLE, failure { api.notifications(null, 25) }.kind); take()
        server.enqueue(json(" ".repeat(1_048_577)))
        assertEquals(FailureKind.INCOMPATIBLE, failure { api.notifications(null, 25) }.kind); take()
    }
    @Test fun readResultCannotClaimMoreUpdatesThanDistinctRequestedOrNegativeCounts() = runTest {
        login()
        for (body in listOf(read(2), read(-1), read(0, -1), "{}")) {
            server.enqueue(json(body)); assertEquals(FailureKind.INCOMPATIBLE, failure { api.readNotifications(listOf(3, 3), authenticated) }.kind); take()
        }
    }
    @Test fun serverRefusalDoesNotReplayThePost() = runTest {
        val original = login(); server.enqueue(error("validation_error", 400))
        val bad = failure { repository.markAllRead(original) }; take()
        assertEquals(400, bad.status); assertEquals("validation_error", bad.code); assertEquals(3, server.requestCount)
    }
    @Test fun authenticated401RetiresSessionAndCannotSendASecondRead() = runTest {
        val original = login(); server.enqueue(error("session_invalidated", 401))
        assertEquals(401, failure { repository.page(original) }.status); take()
        assertEquals(SessionPhase.INVALIDATED, sessions.state.value.phase); assertNull(sessions.state.value.user); assertNull(vault.saved)
        try { repository.markRead(original, listOf(3)); fail("Original identity must be retired") } catch (_: CancellationException) { }
        assertEquals(3, server.requestCount)
    }
    @Test fun csrfRefusalRequiresSessionRevalidationAndNeverRetriesTheWrite() = runTest {
        val original = login(); server.enqueue(error("csrf_failed", 403))
        assertEquals("csrf_failed", failure { repository.markRead(original, listOf(3)) }.code); take()
        assertTrue(sessions.state.value.refreshRequired)
        failure { repository.markAllRead(original) }; assertEquals(3, server.requestCount)
    }
    @Test fun rateLimitIsPropagatedAndFurtherCallsRespectSharedCooldown() = runTest {
        val original = login()
        server.enqueue(MockResponse.Builder().code(429).addHeader("Content-Type", "application/json").addHeader("Retry-After", "30")
            .body("""{"error":{"code":"rate_limited","message":"Fixture"}}""").build())
        val bad = failure { repository.markAllRead(original) }; take()
        assertEquals(30L, bad.retryAfterSeconds); assertTrue(sessions.state.value.retryAtMillis > System.currentTimeMillis())
        assertEquals(429, failure { repository.page(original) }.status); assertEquals(3, server.requestCount)
    }
    @Test fun maintenanceIsAVisibleTypedFailureNotAnAutomaticPostReplay() = runTest {
        val original = login(); server.enqueue(error("maintenance_lockdown", 503))
        val bad = failure { repository.markAllRead(original) }; take()
        assertEquals(503, bad.status); assertEquals("maintenance_lockdown", bad.code); assertEquals(3, server.requestCount)
    }
    @Test fun redirectsCannotTurnReadCredentialsIntoAnExternalRequest() = runTest {
        val original = login(); server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "https://foreign.example.test/").build())
        assertEquals(FailureKind.REDIRECT, failure { repository.markAllRead(original) }.kind); take(); assertEquals(3, server.requestCount)
    }
    @Test fun allOldCallbacksAreRejectedBeforeNetworkAfterAnotherAccountSignsIn() = runTest {
        val original = login(); logout(); val replacement = login(9); val count = server.requestCount
        for (action in listOf<suspend () -> Unit>({ repository.page(original) }, { repository.markRead(original, listOf(3)) },
            { repository.markAllRead(original) })) {
            try { action(); fail("Do not upgrade an old notification callback") } catch (_: CancellationException) { }
        }
        assertEquals(count, server.requestCount); assertEquals(9L, sessions.state.value.user?.id)
        server.enqueue(json(page())); repository.page(replacement)
        assertEquals("SITE=auth9", take().headers["Cookie"])
    }
    @Test fun delayedOldPageAndWriteCannotPublishAfterLogout() = runTest {
        for (write in listOf(false, true)) {
            val original = login()
            server.enqueue(json(if (write) read() else page(item(9)), delay = 500))
            val pending = async { runCatching { if (write) repository.markRead(original, listOf(3)) else repository.page(original) } }
            withContext(Dispatchers.IO) { take() }
            logout()
            assertTrue(pending.await().exceptionOrNull() is CancellationException)
            assertEquals(SessionPhase.SIGNED_OUT, sessions.state.value.phase); assertNull(sessions.state.value.user); assertNull(vault.saved)
        }
    }
    @Test fun cancelledDeliveredReadIsNotImplicitlyReplayed() = runTest {
        val original = login(); server.enqueue(json(read(), delay = 500))
        val pending = async { repository.markAllRead(original) }
        val request = withContext(Dispatchers.IO) { take() }; assertEquals("""{"all":true}""", request.body!!.utf8())
        pending.cancelAndJoin(); assertEquals(3, server.requestCount)
        // The native consumer must reconcile unknown outcomes explicitly; the repository does not invent success.
        server.enqueue(json(page())); repository.page(original); assertEquals("GET", take().method); assertEquals(4, server.requestCount)
    }
    @Test fun queuedReadFreezesOriginalIdsBeforeWaitingForTheSessionLock() = runTest {
        val original = login(); val gate = CompletableDeferred<Unit>(); vault.gate = gate
        server.enqueue(json(page())); val holding = async { repository.page(original) }
        withContext(Dispatchers.IO) { take() }; vault.entered.await()
        try {
            val mutableIds = mutableListOf(3L)
            val queued = async { repository.markRead(original, mutableIds) }; yield()
            mutableIds[0] = 4L
            server.enqueue(json(read())); gate.complete(Unit)
            holding.await(); queued.await()
            assertEquals("""{"ids":[3]}""", take().body!!.utf8())
        } finally { gate.complete(Unit) }
    }
    private class Vault : SessionVault {
        var saved: SavedSession? = null
        var gate: CompletableDeferred<Unit>? = null
        val entered = CompletableDeferred<Unit>()
        override suspend fun read() = saved
        override suspend fun save(session: SavedSession) {
            gate?.let { entered.complete(Unit); it.await() }; saved = session
        }
        override suspend fun clear() { saved = null }
    }
}

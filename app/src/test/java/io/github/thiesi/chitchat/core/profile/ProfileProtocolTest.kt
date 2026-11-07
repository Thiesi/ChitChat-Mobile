package io.github.thiesi.chitchat.core.profile

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

/** Production session/transport over fixture TLS. No installation traffic, avatar bytes or mutations. */
class ProfileProtocolTest {
    private lateinit var server: MockWebServer
    private lateinit var sessions: SessionRepository
    private lateinit var repository: SessionProfileRepository
    private val clients = mutableListOf<SessionHttp>()
    private val vault = Vault()
    private val anonymous = "a".repeat(64)
    private val authenticated = "b".repeat(64)
    private val target = ProfileRequest(8, 43)

    @Before fun start() {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val tls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        server = MockWebServer().apply { useHttps(tls.sslSocketFactory()); start() }
        val client = OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager).build()
        sessions = SessionRepository(vault) { SessionHttp(it, client).also(clients::add) }
        repository = SessionProfileRepository(sessions)
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

    @Test fun originalCookieAndExactTargetContextUseOnlyAChosenOriginGet() = runTest {
        val original = login()
        for (room in listOf(43L, null)) {
            server.enqueue(json(profileBody()))
            val result = repository.load(original, ProfileRequest(8, room))
            assertEquals(8L, result.profile.id); assertEquals(room, result.roomId)
            val request = take()
            assertEquals("GET", request.method); assertEquals("/api/v1/users/profile.php", request.url.encodedPath)
            assertEquals("8", request.url.queryParameter("user_id")); assertEquals(room?.toString(), request.url.queryParameter("room_id"))
            assertEquals(if (room == null) setOf("user_id") else setOf("user_id", "room_id"), request.url.queryParameterNames)
            assertEquals(0L, request.body?.size ?: 0L); assertNull(request.headers["X-CSRF-Token"])
            assertEquals("SITE=auth7", request.headers["Cookie"])
        }
        assertEquals(4, server.requestCount) // Two login calls and two explicit profile GETs; no chat read or policy write.
    }
    @Test fun selfOtherAndGuestTargetMetadataDoNotChangeTheViewingAccount() = runTest {
        val original = login()
        for ((id, guest) in listOf(7L to false, 8L to false, 10L to true)) {
            server.enqueue(json(profileBody(id = id, guest = guest)))
            val profile = repository.load(original, ProfileRequest(id)).profile; take()
            assertEquals(id, profile.id); assertEquals(guest, profile.guest)
            assertEquals(7L, sessions.state.value.user?.id); assertEquals(SessionPhase.AUTHENTICATED, sessions.state.value.phase)
        }
        assertEquals(5, server.requestCount)
    }
    @Test fun invalidTargetOrContextNeverReachesTheServer() = runTest {
        val original = login(); val count = server.requestCount
        for (request in listOf(ProfileRequest(0), ProfileRequest(-8), ProfileRequest(8, 0), ProfileRequest(8, -43))) {
            assertEquals(400, failure { repository.load(original, request) }.status)
        }
        assertEquals(count, server.requestCount)
    }
    @Test fun malformedFlagsSchemasAndWrongTargetsAreSanitizedAtTheHttpBoundary() = runTest {
        val original = login()
        for (body in listOf("{}", changedProfile("guest", JsonPrimitive("false")),
            changedProfile("badge", null), profileBody(id = 9), profileBody(since = "Private invalid time"))) {
            server.enqueue(json(body))
            val bad = failure { repository.load(original, target) }; take()
            assertEquals(FailureKind.INCOMPATIBLE, bad.kind); assertFalse(bad.toString().contains("Private"))
        }
        assertEquals(7, server.requestCount)
    }
    @Test fun wrongStatusAndOversizedProfileKeepOrdinarySmallJsonBounds() = runTest {
        val original = login(); server.enqueue(json(profileBody(), 201))
        assertEquals(FailureKind.INCOMPATIBLE, failure { repository.load(original, target) }.kind); take()
        server.enqueue(json(" ".repeat(1_048_577)))
        assertEquals(FailureKind.INCOMPATIBLE, failure { repository.load(original, target) }.kind); take()
    }
    @Test fun resourceDenialAndMaintenanceAreTypedAndNeverAutomaticallyReplayed() = runTest {
        val original = login()
        for ((status, code) in listOf(403 to "forbidden", 404 to "user_not_found", 503 to "maintenance_lockdown")) {
            server.enqueue(errorResponse(code, status))
            val bad = failure { repository.load(original, target) }; take()
            assertEquals(status, bad.status); assertEquals(code, bad.code)
            assertFalse(bad.toString().contains("Private"))
            if (status != 503) assertEquals(SessionPhase.AUTHENTICATED, sessions.state.value.phase)
        }
        assertEquals(5, server.requestCount)
    }
    @Test fun csrfFailureRequiresRevalidationAndDoesNotReplayTheGet() = runTest {
        val original = login(); server.enqueue(errorResponse("csrf_failed", 403))
        assertEquals("csrf_failed", failure { repository.load(original, target) }.code); take()
        assertTrue(sessions.state.value.refreshRequired)
        failure { repository.load(original, target) }; assertEquals(3, server.requestCount)
    }
    @Test fun rateLimitUsesTheExistingSessionCooldown() = runTest {
        val original = login()
        server.enqueue(MockResponse.Builder().code(429).addHeader("Content-Type", "application/json").addHeader("Retry-After", "30")
            .body("""{"error":{"code":"rate_limited","message":"Private fixture"}}""").build())
        val bad = failure { repository.load(original, target) }; take()
        assertEquals(30L, bad.retryAfterSeconds); assertTrue(sessions.state.value.retryAtMillis > System.currentTimeMillis())
        assertEquals(429, failure { repository.load(original, target) }.status); assertEquals(3, server.requestCount)
    }
    @Test fun unauthorizedRetiresTheSessionAndItsOldCapability() = runTest {
        val original = login(); server.enqueue(errorResponse("session_invalidated", 401))
        assertEquals(401, failure { repository.load(original, target) }.status); take()
        assertEquals(SessionPhase.INVALIDATED, sessions.state.value.phase); assertNull(vault.saved)
        try { repository.load(original, target); fail("Retired profile") } catch (_: CancellationException) { }
        assertEquals(3, server.requestCount)
    }
    @Test fun redirectNeverForwardsTargetCookieOrCsrfToAnotherOrigin() = runTest {
        val original = login(); server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "https://foreign.example.test/").build())
        assertEquals(FailureKind.REDIRECT, failure { repository.load(original, target) }.kind); take()
        assertEquals(3, server.requestCount)
    }
    @Test fun originalOwnerGenerationAndVersionCannotUpgradeToReplacementAccess() = runTest {
        val original = login(); val before = server.requestCount
        val wrongVersion = original.copy(user = original.user!!.copy(sessionVersion = 2))
        try { repository.load(wrongVersion, target); fail("Version mismatch") } catch (_: CancellationException) { }
        assertEquals(before, server.requestCount)
        logout(); val replacement = login(9); val count = server.requestCount
        try { repository.load(original, target); fail("Old owner") } catch (_: CancellationException) { }
        assertEquals(count, server.requestCount)
        server.enqueue(json(profileBody())); repository.load(replacement, target)
        assertEquals("SITE=auth9", take().headers["Cookie"])
    }
    @Test fun lateResponseIsRejectedAfterLogoutWithoutPublishingProfileFacts() = runTest {
        val original = login(); server.enqueue(json(profileBody(), delay = 500))
        val pending = async { runCatching { repository.load(original, target) } }
        withContext(Dispatchers.IO) { take() }; logout()
        assertTrue(pending.await().exceptionOrNull() is CancellationException)
        assertEquals(SessionPhase.SIGNED_OUT, sessions.state.value.phase); assertNull(vault.saved)
    }
    @Test fun cancellationDoesNotReplayAndAnotherReadRequiresANewExplicitCall() = runTest {
        val original = login(); server.enqueue(json(profileBody(), delay = 500))
        val pending = async { repository.load(original, target) }; withContext(Dispatchers.IO) { take() }
        pending.cancelAndJoin(); assertEquals(3, server.requestCount)
        server.enqueue(json(profileBody(id = 10))); repository.load(original, ProfileRequest(10)); take()
        assertEquals(4, server.requestCount)
    }
    @Test fun guestAndAnonymousViewersDoNotAcquireThisMemberOnlyNativeCapability() = runTest {
        server.enqueue(json("""{"csrf_token":"$anonymous","user":{"id":10,"username":"Guest","roles":[],"session_version":1,"guest":true},"registration_enabled":false,"sign_in_providers":[],"lockdown":{"enabled":false}}"""))
        sessions.connect((ServerAddress.parse(server.url("/").toString()) as AddressResult.Valid).address); take()
        assertEquals(SessionPhase.GUEST, sessions.state.value.phase)
        failure { repository.load(sessions.state.value, target) }; assertEquals(1, server.requestCount)
        server.enqueue(json("""{"csrf_token":"$anonymous","user":null,"registration_enabled":false,"sign_in_providers":[],"lockdown":{"enabled":false}}"""))
        sessions.connect((ServerAddress.parse(server.url("/").toString()) as AddressResult.Valid).address); take()
        assertEquals(SessionPhase.ANONYMOUS, sessions.state.value.phase)
        failure { repository.load(sessions.state.value, target) }; assertEquals(2, server.requestCount)
    }
    @Test fun enrichmentAndAvatarAvailabilityNeverBecomePolicyOrMutationConsent() = runTest {
        val original = login(); assertNull(original.chatPolicy); assertNull(original.privacy)
        server.enqueue(json(profileBody(avatar = true, version = 0, available = false)))
        val result = repository.load(original, target); take()
        assertEquals(0L, result.profile.avatarVersion); assertTrue(result.profile.canRemoveAvatar)
        assertFalse(result.avatarsAvailable); assertNull(sessions.state.value.chatPolicy); assertNull(sessions.state.value.privacy)
        assertSame(repository.session, sessions.state); assertEquals(3, server.requestCount)
    }
    private class Vault : SessionVault {
        var saved: SavedSession? = null
        override suspend fun read() = saved
        override suspend fun save(session: SavedSession) { saved = session }
        override suspend fun clear() { saved = null }
    }
}

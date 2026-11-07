package io.github.thiesi.chitchat.core.mentions

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

/** Original-session bounded room candidate GET over fixture TLS; no installation traffic or mutations. */
class RoomMentionProtocolTest {
    private lateinit var server: MockWebServer
    private lateinit var sessions: SessionRepository
    private lateinit var repository: SessionRoomMentionRepository
    private val clients = mutableListOf<SessionHttp>()
    private val vault = Vault()
    private val anonymous = "a".repeat(64)
    private val authenticated = "b".repeat(64)
    private val target = RoomMentionRequest(43, "Pr")

    @Before fun start() {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val tls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        server = MockWebServer().apply { useHttps(tls.sslSocketFactory()); start() }
        val client = OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager).build()
        sessions = SessionRepository(vault) { SessionHttp(it, client).also(clients::add) }
        repository = SessionRoomMentionRepository(sessions)
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

    @Test fun originalCookieFrozenRoomAndCanonicalPrefixUseOnlyOneOriginGet() = runTest {
        val original=login()
        val prefixes=listOf("Pr",""," \tAb_.-0\u0000","A".repeat(32))
        for ((index,prefix) in prefixes.withIndex()) {
            val choice=RoomMentionRequest(43,prefix)
            server.enqueue(json(if (index==0) mentionBody() else mentionBody(emptyList())))
            val batch=repository.suggestions(original,choice)
            assertSame(choice,batch.request); assertEquals(normalizedRoomMentionPrefix(43,prefix),batch.prefix)
            val request=take()
            assertEquals("GET",request.method); assertEquals("/api/v1/rooms/mentionable-users.php",request.url.encodedPath)
            assertEquals("43",request.url.queryParameter("room_id")); assertEquals(batch.prefix,request.url.queryParameter("search"))
            assertEquals("8",request.url.queryParameter("limit")); assertEquals(setOf("room_id","search","limit"),request.url.queryParameterNames)
            assertEquals(0L,request.body?.size ?: 0L); assertNull(request.headers["X-CSRF-Token"])
            assertEquals("SITE=auth7",request.headers["Cookie"])
        }
        assertEquals(6,server.requestCount)
    }
    @Test fun candidateHintsNeverBecomePolicyTypingOrSendConsent() = runTest {
        val original=login(); assertNull(original.chatPolicy); assertNull(original.privacy); assertNull(original.shareTyping)
        server.enqueue(json(mentionBody())); val batch=repository.suggestions(original,target); take()
        assertSame(target,batch.request); assertEquals("Pr",batch.prefix); assertEquals("Private suggestion",batch.users.single().username)
        assertSame(repository.session,sessions.state); assertEquals(7L,sessions.state.value.user?.id)
        assertNull(sessions.state.value.chatPolicy); assertNull(sessions.state.value.privacy); assertNull(sessions.state.value.shareTyping)
        assertEquals(3,server.requestCount)
    }
    @Test fun invalidRoomAndPrefixFailBeforeAnyAccessOrHttpWait() = runTest {
        val original=login(); val count=server.requestCount
        val bad=listOf(RoomMentionRequest(0,"a"),RoomMentionRequest(-43,"a"),RoomMentionRequest(43,"x".repeat(33)),
            RoomMentionRequest(43,"a b"),RoomMentionRequest(43,"a%b"),RoomMentionRequest(43,"@a"),RoomMentionRequest(43,"\u00A0ann"))
        for (choice in bad) assertEquals(400,failure { repository.suggestions(original,choice) }.status)
        assertEquals(count,server.requestCount)
    }
    @Test fun malformedSchemasSelfIdsAndDuplicateCandidatesAreSanitized() = runTest {
        val original=login()
        for (body in listOf("{}","{\"users\":null}",mentionBody(listOf(7L to "Private self")),
            mentionBody(listOf(8L to "Private one",8L to "Private two")),mentionBody(listOf(8L to "")))) {
            server.enqueue(json(body)); val bad=failure { repository.suggestions(original,target) }; take()
            assertEquals(FailureKind.INCOMPATIBLE,bad.kind); assertFalse(bad.toString().contains("Private"))
        }
        assertEquals(7,server.requestCount)
    }
    @Test fun wrongStatusAndOversizedCandidatesKeepSmallJsonBounds() = runTest {
        val original=login(); server.enqueue(json(mentionBody(),201))
        assertEquals(FailureKind.INCOMPATIBLE,failure { repository.suggestions(original,target) }.kind); take()
        server.enqueue(json(" ".repeat(1_048_577)))
        assertEquals(FailureKind.INCOMPATIBLE,failure { repository.suggestions(original,target) }.kind); take()
    }
    @Test fun resourceDenialAndMaintenanceAreTypedWithoutReplayOrEmptyListFallback() = runTest {
        val original=login()
        for ((status,code) in listOf(403 to "membership_required",404 to "room_not_found",503 to "maintenance_lockdown")) {
            server.enqueue(errorResponse(code,status)); val bad=failure { repository.suggestions(original,target) }; take()
            assertEquals(status,bad.status); assertEquals(code,bad.code); assertFalse(bad.toString().contains("Private"))
            if (status!=503) assertEquals(SessionPhase.AUTHENTICATED,sessions.state.value.phase)
        }
        assertEquals(5,server.requestCount)
    }
    @Test fun csrfFailureRequiresRevalidationAndDoesNotReplayTheGet() = runTest {
        val original=login(); server.enqueue(errorResponse("csrf_failed",403))
        assertEquals("csrf_failed",failure { repository.suggestions(original,target) }.code); take()
        assertTrue(sessions.state.value.refreshRequired)
        failure { repository.suggestions(original,target) }; assertEquals(3,server.requestCount)
    }
    @Test fun rateLimitConsumesTheExistingSessionCooldownWithoutAnotherQuery() = runTest {
        val original=login()
        server.enqueue(MockResponse.Builder().code(429).addHeader("Content-Type","application/json").addHeader("Retry-After","30")
            .body("{\"error\":{\"code\":\"rate_limited\",\"message\":\"Private fixture\"}}").build())
        val bad=failure { repository.suggestions(original,target) }; take()
        assertEquals(30L,bad.retryAfterSeconds); assertTrue(sessions.state.value.retryAtMillis>System.currentTimeMillis())
        assertEquals(429,failure { repository.suggestions(original,target) }.status); assertEquals(3,server.requestCount)
    }
    @Test fun unauthorizedRetiresTheSessionAndOriginalRequestCapability() = runTest {
        val original=login(); server.enqueue(errorResponse("session_invalidated",401))
        assertEquals(401,failure { repository.suggestions(original,target) }.status); take()
        assertEquals(SessionPhase.INVALIDATED,sessions.state.value.phase); assertNull(vault.saved)
        try { repository.suggestions(original,target); fail("Retired lookup") } catch (_: CancellationException) { }
        assertEquals(3,server.requestCount)
    }
    @Test fun redirectNeverForwardsThePrefixCookieOrCsrfToAnotherOrigin() = runTest {
        val original=login(); server.enqueue(MockResponse.Builder().code(302).addHeader("Location","https://foreign.example.test/").build())
        assertEquals(FailureKind.REDIRECT,failure { repository.suggestions(original,target) }.kind); take()
        assertEquals(3,server.requestCount)
    }
    @Test fun originalOwnerGenerationAndVersionCannotUpgradeToReplacementAccess() = runTest {
        val original=login(); val before=server.requestCount
        val wrong=original.copy(user=original.user!!.copy(sessionVersion=2))
        try { repository.suggestions(wrong,target); fail("Version mismatch") } catch (_: CancellationException) { }
        assertEquals(before,server.requestCount)
        logout(); val replacement=login(9); val count=server.requestCount
        try { repository.suggestions(original,target); fail("Old owner") } catch (_: CancellationException) { }
        assertEquals(count,server.requestCount)
        server.enqueue(json(mentionBody())); repository.suggestions(replacement,target)
        assertEquals("SITE=auth9",take().headers["Cookie"])
    }
    @Test fun lateResponseAfterLogoutCannotPublishCandidateNames() = runTest {
        val original=login(); server.enqueue(json(mentionBody(),delay=500))
        val pending=async { runCatching { repository.suggestions(original,target) } }
        withContext(Dispatchers.IO) { take() }; logout()
        assertTrue(pending.await().exceptionOrNull() is CancellationException)
        assertEquals(SessionPhase.SIGNED_OUT,sessions.state.value.phase); assertNull(vault.saved)
    }
    @Test fun cancellationDoesNotReplayAndNewLookupIsAnExplicitNewCall() = runTest {
        val original=login(); server.enqueue(json(mentionBody(),delay=500))
        val pending=async { repository.suggestions(original,target) }; withContext(Dispatchers.IO) { take() }
        pending.cancelAndJoin(); assertEquals(3,server.requestCount)
        server.enqueue(json(mentionBody())); repository.suggestions(original,RoomMentionRequest(44,"Ne")); take()
        assertEquals(4,server.requestCount)
    }
    @Test fun guestAndAnonymousActorsNeverAcquireThisMemberOnlyLookup() = runTest {
        server.enqueue(json("{\"csrf_token\":\"$anonymous\",\"user\":{\"id\":10,\"username\":\"Guest\",\"roles\":[],\"session_version\":1,\"guest\":true},\"registration_enabled\":false,\"sign_in_providers\":[],\"lockdown\":{\"enabled\":false}}"))
        sessions.connect((ServerAddress.parse(server.url("/").toString()) as AddressResult.Valid).address); take()
        assertEquals(SessionPhase.GUEST,sessions.state.value.phase)
        failure { repository.suggestions(sessions.state.value,target) }; assertEquals(1,server.requestCount)
        server.enqueue(json("{\"csrf_token\":\"$anonymous\",\"user\":null,\"registration_enabled\":false,\"sign_in_providers\":[],\"lockdown\":{\"enabled\":false}}"))
        sessions.connect((ServerAddress.parse(server.url("/").toString()) as AddressResult.Valid).address); take()
        assertEquals(SessionPhase.ANONYMOUS,sessions.state.value.phase)
        failure { repository.suggestions(sessions.state.value,target) }; assertEquals(2,server.requestCount)
    }
    @Test fun boundedUnderscoreResultsStayOrderedAndEachExplicitQueryGetsANewBatch() = runTest {
        val original=login(); var previous: RoomMentionBatch?=null
        repeat(2) {
            val choice=RoomMentionRequest(43,"ann_")
            server.enqueue(json(mentionBody(listOf(9L to "ann_member",8L to "annxmember"))))
            val batch=repository.suggestions(original,choice)
            assertSame(choice,batch.request); assertNotSame(previous,batch)
            assertEquals(listOf(9L,8L),batch.users.map { it.id }); assertEquals("ann_",take().url.queryParameter("search"))
            previous=batch
        }
        assertEquals(4,server.requestCount)
    }
    private class Vault : SessionVault {
        var saved: SavedSession? = null
        override suspend fun read() = saved
        override suspend fun save(session: SavedSession) { saved = session }
        override suspend fun clear() { saved = null }
    }
}

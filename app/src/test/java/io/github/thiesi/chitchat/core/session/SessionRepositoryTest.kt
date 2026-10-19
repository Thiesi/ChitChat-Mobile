package io.github.thiesi.chitchat.core.session

import io.github.thiesi.chitchat.core.network.SessionHttp
import io.github.thiesi.chitchat.core.server.AddressResult
import io.github.thiesi.chitchat.core.server.ServerAddress
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
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

class SessionRepositoryTest {
    private val vault = MemoryVault()
    private val clock = TestClock()
    private val apis = mutableListOf<SessionHttp>()
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var serverTls: HandshakeCertificates
    private lateinit var sessions: SessionRepository
    private val a = "a".repeat(64)
    private val b = "b".repeat(64)
    private val c = "c".repeat(64)
    private val user = """{"id":7,"username":"Alice","roles":["member"],"session_version":2,"guest":false}"""

    @Before fun setUp() {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        server = MockWebServer().apply { useHttps(serverTls.sslSocketFactory()); start() }
        client = OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager).build()
        sessions = repository()
    }
    @After fun tearDown() { apis.forEach(SessionHttp::close); server.close() }
    private fun repository() = SessionRepository(vault, clock) { SessionHttp(it, client).also(apis::add) }
    private fun address() = (ServerAddress.parse(server.url("/").toString()) as AddressResult.Valid).address
    private fun json(body: String, status: Int = 200, cookie: String? = null) = MockResponse.Builder()
        .code(status).body(body).addHeader("Content-Type", "application/json")
        .apply { cookie?.let { addHeader("Set-Cookie", it) } }.build()
    private fun bootstrapBody(user: String = "null", csrf: String = a) =
        """{"csrf_token":"$csrf","user":$user,"registration_enabled":true,"sign_in_providers":[],"lockdown":{"enabled":false}}"""
    private fun signedIn() = json("""{"csrf_token":"$b","user":$user,"mfa_required":false}""", cookie = "SITE=auth; Secure; HttpOnly; Path=/")
    private fun error(code: String, status: Int) = json("""{"error":{"code":"$code","message":"fixture failure"}}""", status)
    private suspend fun anonymous() {
        server.enqueue(json(bootstrapBody(), cookie = "SITE=anon; Secure; HttpOnly; Path=/"))
        sessions.connect(address())
        assertEquals(SessionPhase.ANONYMOUS, sessions.state.value.phase)
        server.takeRequest(5, TimeUnit.SECONDS)
    }
    private suspend fun login() {
        anonymous()
        server.enqueue(signedIn())
        sessions.signIn("Alice", "fixture-password")
        server.takeRequest(5, TimeUnit.SECONDS)
        assertEquals(SessionPhase.AUTHENTICATED, sessions.state.value.phase)
    }

    @Test fun anonymousCookiesAreNotSaved() = runTest {
        anonymous()
        assertNull(vault.saved)
        assertNull(sessions.state.value.user)
    }

    @Test fun logoutUsesRotatedCookiesAndCsrfThenClearsLocally() = runTest {
        login()
        assertNotNull(vault.saved)
        server.enqueue(json("""{"status":"logged_out"}"""))
        sessions.signOut()
        val request = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("SITE=auth", request.headers["Cookie"])
        assertEquals(b, request.headers["X-CSRF-Token"])
        assertEquals(SessionPhase.SIGNED_OUT, sessions.state.value.phase)
        assertNull(vault.saved)
        assertNull(sessions.state.value.user)
        assertFalse(sessions.state.value.logoutUnconfirmed)
    }

    @Test fun coldRestoreRevalidatesBeforeShowingAnAccount() = runTest {
        login()
        apis.forEach(SessionHttp::close)
        val restored = repository()
        assertNull(restored.state.value.user)
        server.enqueue(json(bootstrapBody(user, c)))
        restored.connect(address())
        assertEquals("SITE=auth", server.takeRequest(5, TimeUnit.SECONDS)!!.headers["Cookie"])
        assertEquals(7L, restored.state.value.user!!.id)
        assertEquals(SessionPhase.AUTHENTICATED, restored.state.value.phase)
    }

    @Test fun expiredCredentialsAreNotSent() = runTest {
        vault.saved = SavedSession(address().value, 7, listOf("SITE=old; Secure; HttpOnly; Path=/"),
            clock.millis() - 31L * 86_400_000, clock.millis())
        server.enqueue(json(bootstrapBody()))
        sessions.connect(address())
        assertNull(server.takeRequest(5, TimeUnit.SECONDS)!!.headers["Cookie"])
        assertNull(vault.saved)
        assertEquals(SessionPhase.ANONYMOUS, sessions.state.value.phase)
    }

    @Test fun authenticated401ClearsIdentityAndSavedCredentials() = runTest {
        login()
        val generation = sessions.state.value.generation
        server.enqueue(error("authentication_required", 401))
        sessions.refresh()
        assertEquals(SessionPhase.INVALIDATED, sessions.state.value.phase)
        assertTrue(sessions.state.value.generation > generation)
        assertNull(sessions.state.value.user)
        assertNull(vault.saved)
    }

    @Test fun anonymous200AfterLoginAlsoInvalidatesTheAccount() = runTest {
        login()
        server.enqueue(json(bootstrapBody(csrf = c)))
        sessions.refresh()
        assertEquals(SessionPhase.INVALIDATED, sessions.state.value.phase)
        assertNull(vault.saved)
        assertNull(sessions.state.value.user)
    }

    @Test fun resource403DoesNotPretendTheWholeSessionExpired() = runTest {
        login()
        server.enqueue(error("forbidden", 403))
        sessions.refresh()
        assertEquals(SessionPhase.AUTHENTICATED, sessions.state.value.phase)
        assertNotNull(vault.saved)
        assertEquals("forbidden", sessions.state.value.error!!.code)
    }

    @Test fun csrfFailureRequiresExplicitRefreshAndNeverReplaysTheWrite() = runTest {
        anonymous()
        server.enqueue(error("csrf_failed", 403))
        sessions.signIn("Alice", "fixture-password")
        assertTrue(sessions.state.value.refreshRequired)
        val count = server.requestCount
        sessions.signIn("Alice", "fixture-password")
        assertEquals(count, server.requestCount)
        server.takeRequest(5, TimeUnit.SECONDS)
        server.enqueue(json(bootstrapBody(csrf = c)))
        sessions.refresh()
        server.takeRequest(5, TimeUnit.SECONDS)
        server.enqueue(signedIn())
        sessions.signIn("Alice", "fixture-password")
        assertEquals(c, server.takeRequest(5, TimeUnit.SECONDS)!!.headers["X-CSRF-Token"])
        assertEquals(SessionPhase.AUTHENTICATED, sessions.state.value.phase)
    }

    @Test fun rateLimitBlocksEarlyResubmission() = runTest {
        anonymous()
        server.enqueue(MockResponse.Builder().code(429).addHeader("Retry-After", "45")
            .body("""{"error":{"code":"rate_limited","message":"wait"}}""").build())
        sessions.signIn("Alice", "fixture-password")
        val count = server.requestCount
        sessions.signIn("Alice", "fixture-password")
        assertEquals(count, server.requestCount)
        clock.now += 45_000
        server.enqueue(signedIn())
        sessions.signIn("Alice", "fixture-password")
        assertEquals(SessionPhase.AUTHENTICATED, sessions.state.value.phase)
    }

    @Test fun mfaRemainsTransientUntilRecoveryCompletes() = runTest {
        anonymous()
        server.enqueue(json("""{"csrf_token":"$b","mfa_required":true,"methods":["passkey","recovery_code"]}""", 202,
            "SITE=pending; Secure; HttpOnly; Path=/"))
        sessions.signIn("Alice", "fixture-password")
        server.takeRequest(5, TimeUnit.SECONDS)
        assertEquals(SessionPhase.PENDING_MFA, sessions.state.value.phase)
        assertNull(sessions.state.value.user)
        assertNull(vault.saved)
        server.enqueue(json("""{"csrf_token":"$c","user":$user,"recovery_codes_remaining":6}""", cookie = "SITE=completed; Secure; HttpOnly; Path=/"))
        sessions.completeRecovery("UNUSED-FIXTURE-CODE")
        val recovery = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals(b, recovery.headers["X-CSRF-Token"])
        assertEquals("SITE=pending", recovery.headers["Cookie"])
        assertEquals(SessionPhase.AUTHENTICATED, sessions.state.value.phase)
        assertEquals(6, sessions.state.value.recoveryCodesRemaining)
        assertNotNull(vault.saved)
    }

    @Test fun invalidRecoveryKeepsPendingButExpiredContextClearsIt() = runTest {
        anonymous()
        server.enqueue(json("""{"csrf_token":"$b","mfa_required":true,"methods":["recovery_code"]}""", 202))
        sessions.signIn("Alice", "fixture-password")
        server.enqueue(error("invalid_recovery_code", 403))
        sessions.completeRecovery("WRONG-FIXTURE-CODE")
        assertEquals(SessionPhase.PENDING_MFA, sessions.state.value.phase)
        server.enqueue(error("mfa_login_expired", 401))
        sessions.completeRecovery("ANOTHER-FIXTURE-CODE")
        assertEquals(SessionPhase.INVALIDATED, sessions.state.value.phase)
        assertNull(vault.saved)
    }

    @Test fun logoutWhileLoginIsInFlightCannotBeUndoneByLateResults() = runTest {
        anonymous()
        server.enqueue(MockResponse.Builder().code(200).addHeader("Content-Type", "application/json")
            .addHeader("Set-Cookie", "SITE=late; Secure; HttpOnly; Path=/")
            .body("""{"csrf_token":"$b","user":$user}""").bodyDelay(400, TimeUnit.MILLISECONDS).build())
        val login = async { sessions.signIn("Alice", "fixture-password") }
        // Dispatch the coroutine before waiting for the actual HTTP request.
        kotlinx.coroutines.yield()
        assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        server.enqueue(error("csrf_failed", 403))
        sessions.signOut()
        login.await()
        assertEquals(SessionPhase.SIGNED_OUT, sessions.state.value.phase)
        assertTrue(sessions.state.value.logoutUnconfirmed)
        assertNull(vault.saved)
        assertNull(sessions.state.value.user)
    }

    @Test fun failedSecureSaveDoesNotClaimDurableSignIn() = runTest {
        anonymous()
        vault.failWrites = true
        server.enqueue(signedIn())
        sessions.signIn("Alice", "fixture-password")
        assertEquals(SessionPhase.AUTHENTICATED, sessions.state.value.phase)
        assertTrue(sessions.state.value.storageWarning)
        assertNull(vault.saved)
    }

    @Test fun unreadableStoreHasAnExplicitDiscardAndReconnectPath() = runTest {
        vault.failReads = true
        sessions.connect(address())
        assertEquals(SessionPhase.INVALIDATED, sessions.state.value.phase)
        vault.failReads = false
        server.enqueue(json(bootstrapBody()))
        sessions.connect(address())
        assertEquals(SessionPhase.ANONYMOUS, sessions.state.value.phase)
        assertNull(server.takeRequest(5, TimeUnit.SECONDS)!!.headers["Cookie"])
    }

    @Test fun formStaysBusyUntilAnonymousSecureCleanupFinishes() = runTest {
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        vault.clearStarted = started
        vault.clearGate = finish
        server.enqueue(json(bootstrapBody()))
        val connection = async { sessions.connect(address()) }
        started.await()
        assertEquals(SessionPhase.ANONYMOUS, sessions.state.value.phase)
        assertTrue("The form must not allow a submit discarded by the still-active operation", sessions.state.value.busy)
        finish.complete(Unit)
        connection.await()
        assertFalse(sessions.state.value.busy)
    }

    @Test fun guestsStayTransientAndExpireWithoutBecomingNormalAccounts() = runTest {
        server.enqueue(json(bootstrapBody(user.replace("\"guest\":false", "\"guest\":true")), cookie = "SITE=guest; Secure; Path=/"))
        sessions.connect(address())
        assertEquals(SessionPhase.GUEST, sessions.state.value.phase)
        assertNull(vault.saved)
        assertFalse(sessions.state.value.canChangeServer)
        server.enqueue(json(bootstrapBody()))
        sessions.refresh()
        assertEquals(SessionPhase.INVALIDATED, sessions.state.value.phase)
        assertNull(sessions.state.value.user)
    }

    @Test fun changingServerOriginCannotReuseTheOtherAccountsCookies() = runTest {
        login()
        val other = MockWebServer().apply { useHttps(serverTls.sslSocketFactory()); start() }
        try {
            other.enqueue(json(bootstrapBody()))
            sessions.connect((ServerAddress.parse(other.url("/").toString()) as AddressResult.Valid).address)
            assertNull(other.takeRequest(5, TimeUnit.SECONDS)!!.headers["Cookie"])
            assertNull(vault.saved)
            assertNull(sessions.state.value.user)
            assertEquals(SessionPhase.ANONYMOUS, sessions.state.value.phase)
        } finally { other.close() }
    }

    @Test fun warmProcessAlsoEnforcesSevenDayInactivity() = runTest {
        login()
        val count = server.requestCount
        clock.now += 7L * 86_400_000
        sessions.refresh()
        assertEquals(SessionPhase.INVALIDATED, sessions.state.value.phase)
        assertNull(vault.saved)
        assertEquals("Expired cookies must not be sent", count, server.requestCount)
    }

    @Test fun failedLocalClearingCannotClaimSuccessAndHasARetry() = runTest {
        login()
        vault.failClears = true
        server.enqueue(json("""{"status":"logged_out"}"""))
        sessions.signOut()
        assertTrue(sessions.state.value.storageWarning)
        assertFalse(sessions.state.value.canChangeServer)
        vault.failClears = false
        sessions.signOut()
        assertFalse(sessions.state.value.storageWarning)
        assertTrue(sessions.state.value.canChangeServer)
        assertNull(vault.saved)
        assertFalse("Retrying local cleanup must retain an already-confirmed server logout", sessions.state.value.logoutUnconfirmed)
    }

    private class MemoryVault : SessionVault {
        var saved: SavedSession? = null
        var failWrites = false
        var failReads = false
        var failClears = false
        var clearStarted: CompletableDeferred<Unit>? = null
        var clearGate: CompletableDeferred<Unit>? = null
        override suspend fun read(): SavedSession? { if (failReads) throw IOException("fixture"); return saved }
        override suspend fun save(session: SavedSession) { if (failWrites) throw IOException("fixture"); saved = session }
        override suspend fun clear() {
            clearStarted?.complete(Unit)
            clearGate?.await()
            if (failClears) throw IOException("fixture")
            saved = null
        }
    }
    private class TestClock(var now: Long = 1_790_000_000_000) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = Instant.ofEpochMilli(now)
    }
}

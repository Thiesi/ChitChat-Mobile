package io.github.thiesi.chitchat.core.session

import io.github.thiesi.chitchat.core.network.ApiFailure

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

    private suspend fun registrationReady(challenge: String = "null") {
        sessions.selectAuthFlow(AuthFlow.REGISTER)
        server.enqueue(json("""{"challenge":$challenge}"""))
        sessions.prepareRegistration()
        assertEquals("/api/v1/registration-challenge.php", server.takeRequest(5, TimeUnit.SECONDS)!!.url.encodedPath)
        assertEquals(RegistrationPreparation.READY, sessions.state.value.registration)
    }

    @Test fun registrationAdoptsServerAssignedRolesAndRotatedCredentials() = runTest {
        anonymous()
        registrationReady()
        server.enqueue(json("""{"csrf_token":"$b","user":${user.replace("member", "super_admin")}}""", 201,
            "SITE=registered; Secure; HttpOnly; Path=/"))
        sessions.register("Alice", "fixture-password", null)
        val request = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("/api/v1/register.php", request.url.encodedPath)
        assertEquals(a, request.headers["X-CSRF-Token"])
        assertEquals(SessionPhase.AUTHENTICATED, sessions.state.value.phase)
        assertEquals(listOf("super_admin"), sessions.state.value.user!!.roles)
        assertTrue(vault.saved!!.cookies.single().contains("registered"))
    }

    @Test fun rejectedRegistrationConsumesProofAndRequiresExplicitRefresh() = runTest {
        anonymous()
        registrationReady("""{"nonce":"${"a".repeat(32)}","bits":0}""")
        server.enqueue(error("registration_too_fast", 400))
        sessions.register("Alice", "fixture-password", "1990-05-12")
        assertEquals(RegistrationPreparation.FAILED, sessions.state.value.registration)
        assertEquals("registration_too_fast", sessions.state.value.error!!.code)
        val count = server.requestCount
        sessions.register("Alice", "fixture-password", null)
        assertEquals(count, server.requestCount)
        server.takeRequest()
        registrationReady()
        assertNull(vault.saved)
    }

    @Test fun registrationHonorsRateLimitAcrossChallengeRefresh() = runTest {
        anonymous()
        registrationReady()
        server.enqueue(error("rate_limited", 429))
        sessions.register("Alice", "fixture-password", null)
        val count = server.requestCount
        sessions.prepareRegistration()
        assertEquals(count, server.requestCount)
        clock.now += 31_000
        server.takeRequest()
        registrationReady()
    }

    @Test fun expiredLocalChallengeCannotBeSentOrReused() = runTest {
        anonymous()
        registrationReady()
        clock.now += 1_800_001
        val count = server.requestCount
        sessions.register("Alice", "fixture-password", null)
        assertEquals(count, server.requestCount)
        assertEquals("registration_challenge_expired", sessions.state.value.error!!.code)
    }

    @Test fun registrationDisabledIsEnforcedBelowTheUi() = runTest {
        server.enqueue(json(bootstrapBody().replace("\"registration_enabled\":true", "\"registration_enabled\":false")))
        sessions.connect(address())
        sessions.selectAuthFlow(AuthFlow.REGISTER)
        sessions.prepareRegistration()
        sessions.register("Alice", "fixture-password", null)
        assertEquals(AuthFlow.SIGN_IN, sessions.state.value.authFlow)
        assertEquals(1, server.requestCount)
    }

    @Test fun cancelledFormCannotBeRepopulatedByALateChallenge() = runTest {
        anonymous()
        sessions.selectAuthFlow(AuthFlow.REGISTER)
        server.enqueue(MockResponse.Builder().code(200).addHeader("Content-Type", "application/json")
            .body("""{"challenge":null}""").bodyDelay(200, TimeUnit.MILLISECONDS).build())
        val preparation = async { sessions.prepareRegistration() }
        kotlinx.coroutines.yield()
        assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        sessions.selectAuthFlow(AuthFlow.SIGN_IN)
        preparation.await()
        assertEquals(AuthFlow.SIGN_IN, sessions.state.value.authFlow)
        assertEquals(RegistrationPreparation.IDLE, sessions.state.value.registration)
    }

    @Test fun malformedRegistrationSuccessWarnsThatTheAccountMayExist() = runTest {
        anonymous()
        registrationReady()
        server.enqueue(json("""{"csrf_token":"$b","user":null}""", 201))
        sessions.register("Alice", "fixture-password", null)
        assertEquals(SessionPhase.INVALIDATED, sessions.state.value.phase)
        assertTrue(sessions.state.value.registrationMayHaveSucceeded)
        assertNull(vault.saved)
    }

    @Test fun restorationMfaKeepsClosurePendingUntilServerConfirmedRecovery() = runTest {
        anonymous()
        server.enqueue(json("""{"csrf_token":"$b","restored":false,"restoration_pending":true,"mfa_required":true,"methods":["recovery_code"]}""", 202))
        sessions.signIn("Alice", "fixture-password", restore = true)
        assertEquals("/api/v1/account/restore.php", server.takeRequest(5, TimeUnit.SECONDS)!!.url.encodedPath)
        assertEquals(SessionPhase.PENDING_MFA, sessions.state.value.phase)
        assertEquals(AuthFlow.RESTORE, sessions.state.value.authFlow)
        assertFalse(sessions.state.value.accountRestored)
        assertNull(vault.saved)
        server.enqueue(json("""{"csrf_token":"$c","user":$user,"restored":true,"recovery_codes_remaining":3}""",
            cookie = "SITE=restored; Secure; HttpOnly; Path=/"))
        sessions.completeRecovery("UNUSED-FIXTURE-CODE")
        assertEquals(SessionPhase.AUTHENTICATED, sessions.state.value.phase)
        assertTrue(sessions.state.value.accountRestored)
        assertEquals(3, sessions.state.value.recoveryCodesRemaining)
    }

    @Test fun expiredRestorationRemainsAnonymousAndCannotClaimRestored() = runTest {
        anonymous()
        server.enqueue(error("account_restoration_expired", 410))
        sessions.signIn("Alice", "fixture-password", restore = true)
        assertEquals(SessionPhase.ANONYMOUS, sessions.state.value.phase)
        assertFalse(sessions.state.value.accountRestored)
        assertNull(vault.saved)
    }

    @Test fun pausedChallengeReturnsToIdleAndCanBeExplicitlyPreparedAgain() = runTest {
        anonymous()
        sessions.selectAuthFlow(AuthFlow.REGISTER)
        server.enqueue(MockResponse.Builder().code(200).addHeader("Content-Type", "application/json")
            .body("""{"challenge":null}""").bodyDelay(300, TimeUnit.MILLISECONDS).build())
        val preparation = async { sessions.prepareRegistration() }
        kotlinx.coroutines.yield()
        assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        preparation.cancel()
        preparation.join()
        assertEquals(RegistrationPreparation.IDLE, sessions.state.value.registration)
        assertEquals(SessionPhase.ANONYMOUS, sessions.state.value.phase)
        registrationReady()
    }

    @Test fun serverRegistrationDisableRemovesTheStaleUiCapability() = runTest {
        anonymous()
        registrationReady()
        server.enqueue(error("registration_disabled", 403))
        sessions.register("Alice", "fixture-password", null)
        assertFalse(sessions.state.value.public!!.registrationEnabled)
        val count = server.requestCount
        sessions.prepareRegistration()
        assertEquals(count, server.requestCount)
    }

    @Test fun signOutWhileRegistrationIsInFlightCannotRestoreLateCredentials() = runTest {
        anonymous()
        registrationReady()
        server.enqueue(MockResponse.Builder().code(201).addHeader("Content-Type", "application/json")
            .addHeader("Set-Cookie", "SITE=late; Secure; HttpOnly; Path=/")
            .body("""{"csrf_token":"$b","user":$user}""").bodyDelay(400, TimeUnit.MILLISECONDS).build())
        val registration = async { sessions.register("Alice", "fixture-password", null) }
        kotlinx.coroutines.yield()
        assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        assertFalse(sessions.state.value.canChangeServer)
        server.enqueue(error("csrf_failed", 403))
        sessions.signOut()
        registration.await()
        assertEquals(SessionPhase.SIGNED_OUT, sessions.state.value.phase)
        assertNull(vault.saved)
        assertNull(sessions.state.value.user)
    }

    @Test fun ambiguousRegistrationReconcilesAuthoritativelyWithoutReplayingTheWrite() = runTest {
        anonymous()
        registrationReady()
        server.enqueue(json("""{"error":{"code":"internal_error","message":"fixture failure"}}""", 500,
            "SITE=created; Secure; HttpOnly; Path=/"))
        sessions.register("Alice", "fixture-password", null)
        server.takeRequest()
        assertTrue(sessions.state.value.registrationMayHaveSucceeded)
        assertTrue(sessions.state.value.refreshRequired)
        server.enqueue(json(bootstrapBody(user, c)))
        sessions.refresh()
        val request = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("GET", request.method)
        assertEquals("/api/v1/session.php", request.url.encodedPath)
        assertEquals(SessionPhase.AUTHENTICATED, sessions.state.value.phase)
        assertFalse(sessions.state.value.registrationMayHaveSucceeded)
        assertNotNull(vault.saved)
    }

    @Test fun protectedStepUpRejectionIsNotGlobalLogoutOrAnAutomaticRetry() = runTest {
        login()
        val access = io.github.thiesi.chitchat.core.account.SessionAccountRepository(sessions).open()
        server.enqueue(error("step_up_required", 403))
        val failure = try { access.export(); error("Expected step-up") }
            catch (failure: io.github.thiesi.chitchat.core.network.ApiFailure) { failure }
        assertEquals("step_up_required", failure.code)
        assertEquals(SessionPhase.AUTHENTICATED, sessions.state.value.phase)
        assertNull(sessions.state.value.error)
        assertFalse(sessions.state.value.refreshRequired)
        assertEquals(3, server.requestCount)
    }

    @Test fun authenticatedResource401InvalidatesAllCapturedCapabilities() = runTest {
        login()
        val access = io.github.thiesi.chitchat.core.account.SessionAccountRepository(sessions).open()
        server.enqueue(error("authentication_required", 401))
        try { access.verificationMethods(); fail("Expected invalidation") }
        catch (_: io.github.thiesi.chitchat.core.network.ApiFailure) { }
        assertEquals(SessionPhase.INVALIDATED, sessions.state.value.phase)
        assertNull(vault.saved)
        try { access.ensureCurrent(); fail("Expected stale capability") }
        catch (_: kotlinx.coroutines.CancellationException) { }
    }

    @Test fun capturedCapabilityCannotBypassRateLimitOrLifetime() = runTest {
        login()
        val access = io.github.thiesi.chitchat.core.account.SessionAccountRepository(sessions).open()
        server.enqueue(MockResponse.Builder().code(429).addHeader("Retry-After", "45")
            .body("""{"error":{"code":"rate_limited","message":"fixture"}}""").build())
        repeat(2) {
            try { access.verifyPassword("fixture"); fail("Expected rate limit") }
            catch (failure: io.github.thiesi.chitchat.core.network.ApiFailure) { assertEquals(429, failure.status) }
        }
        assertEquals(3, server.requestCount)
        clock.now += 31L * 24 * 60 * 60 * 1000
        try { access.export(); fail("Expected lifetime expiry") }
        catch (failure: io.github.thiesi.chitchat.core.network.ApiFailure) {
            assertEquals(io.github.thiesi.chitchat.core.network.FailureKind.EXPIRED, failure.kind)
        }
        assertEquals(SessionPhase.INVALIDATED, sessions.state.value.phase)
        assertEquals(3, server.requestCount)
    }

    @Test fun recoveryStepUpUpdatesTheSharedRemainingCountWithoutChangingAccount() = runTest {
        login()
        val access = io.github.thiesi.chitchat.core.account.SessionAccountRepository(sessions).open()
        server.enqueue(json("""{"security":{"privileged_step_up":{"active":true,"method":"recovery_code","verified_at":"2026-10-20T00:00:00Z","expires_at":"2026-10-20T00:10:00Z","max_age_seconds":600}},"recovery_codes_remaining":6}"""))
        assertEquals(6, access.verifyRecovery(" UNUSED-FIXTURE-CODE "))
        val request = server.takeRequest()
        assertEquals("/api/v1/mfa/step-up-recovery.php", request.url.encodedPath)
        assertEquals(b, request.headers["X-CSRF-Token"])
        assertEquals("""{"recovery_code":"UNUSED-FIXTURE-CODE"}""", request.body!!.utf8())
        assertEquals(6, sessions.state.value.recoveryCodesRemaining)
        assertEquals(7L, vault.saved!!.userId)
    }

    @Test fun logoutRetiresCapturedResourceCapabilitiesBeforeAnyFurtherRequest() = runTest {
        login()
        val access = io.github.thiesi.chitchat.core.account.SessionAccountRepository(sessions).open()
        server.enqueue(json("""{"status":"logged_out"}"""))
        sessions.signOut()
        val count = server.requestCount
        try { access.export(); fail("Expected stale capability") }
        catch (_: kotlinx.coroutines.CancellationException) { }
        assertEquals(count, server.requestCount)
        assertNull(vault.saved)
    }

    @Test fun logoutDuringExportDiscardsLatePrivateContent() = runTest {
        login()
        val access = io.github.thiesi.chitchat.core.account.SessionAccountRepository(sessions).open()
        server.enqueue(MockResponse.Builder().code(200).addHeader("Content-Type", "application/json")
            .body("""{"filename":"fixture.json","export":{"format":{"name":"chitchat-personal-data-export","version":1},"account":{"id":7}}}""")
            .bodyDelay(400, TimeUnit.MILLISECONDS).build())
        val pending = async { runCatching { access.export() } }
        kotlinx.coroutines.yield()
        assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        server.enqueue(json("""{"status":"logged_out"}"""))
        sessions.signOut()
        assertTrue(pending.await().exceptionOrNull() is kotlinx.coroutines.CancellationException)
        assertEquals(SessionPhase.SIGNED_OUT, sessions.state.value.phase)
        assertNull(vault.saved)
    }

    private val roomFixture = """{"id":42,"key":"general","name":"Fixture","info_line":"","visibility":"public","minimum_age":0,"inactivity_timeout_seconds":0,"created_by":7,"member_role":"member","invited":false,"guest_access":"none"}"""

    @Test fun roomRepositorySharesCookieCsrfAndResourceErrorsDoNotLogOut() = runTest {
        login()
        val rooms = io.github.thiesi.chitchat.core.rooms.SessionRoomRepository(sessions)
        server.enqueue(json("""{"rooms":[$roomFixture]}"""))
        assertEquals(42L, rooms.list().single().id)
        assertEquals("SITE=auth", server.takeRequest(5, TimeUnit.SECONDS)!!.headers["Cookie"])
        server.enqueue(error("minimum_age_not_met", 403))
        try { rooms.join(42); fail("Expected age restriction") } catch (failure: ApiFailure) { assertEquals("minimum_age_not_met", failure.code) }
        assertEquals(b, server.takeRequest(5, TimeUnit.SECONDS)!!.headers["X-CSRF-Token"])
        assertEquals(SessionPhase.AUTHENTICATED, sessions.state.value.phase)
        assertFalse(sessions.state.value.refreshRequired)
        server.enqueue(error("unauthenticated", 401))
        try { rooms.detail(42); fail("Expected invalidation") } catch (_: ApiFailure) { }
        assertEquals(SessionPhase.INVALIDATED, sessions.state.value.phase)
        assertNull(vault.saved)
    }

    @Test fun lateRoomListCannotRepopulateAfterLogout() = runTest {
        login()
        val rooms = io.github.thiesi.chitchat.core.rooms.SessionRoomRepository(sessions)
        server.enqueue(MockResponse.Builder().code(200).addHeader("Content-Type", "application/json")
            .body("""{"rooms":[$roomFixture]}""").bodyDelay(400, TimeUnit.MILLISECONDS).build())
        val pending = async { runCatching { rooms.list() } }
        kotlinx.coroutines.yield()
        assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        server.enqueue(json("""{"status":"logged_out"}""")); sessions.signOut()
        assertTrue(pending.await().exceptionOrNull() is kotlinx.coroutines.CancellationException)
        assertEquals(SessionPhase.SIGNED_OUT, sessions.state.value.phase)
        assertNull(vault.saved)
    }

    @Test fun roomCsrfFailureRequiresRefreshAndDoesNotReplayJoin() = runTest {
        login()
        val rooms = io.github.thiesi.chitchat.core.rooms.SessionRoomRepository(sessions)
        server.enqueue(error("csrf_failed", 403))
        try { rooms.join(42); fail("Expected CSRF refresh") } catch (_: ApiFailure) { }
        val requests = server.requestCount
        assertTrue(sessions.state.value.refreshRequired)
        try { rooms.join(42); fail("Expected guarded retry") } catch (_: ApiFailure) { }
        assertEquals(requests, server.requestCount)
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

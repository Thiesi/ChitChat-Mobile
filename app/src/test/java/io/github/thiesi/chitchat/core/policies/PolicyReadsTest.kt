package io.github.thiesi.chitchat.core.policies

import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.network.FailureKind
import io.github.thiesi.chitchat.core.network.SessionHttp
import io.github.thiesi.chitchat.core.server.AddressResult
import io.github.thiesi.chitchat.core.server.ServerAddress
import io.github.thiesi.chitchat.core.session.*
import java.util.concurrent.TimeUnit
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

/** SessionRepository's public document read: the server and generation fence and the update-state refusal (O3a2). */
class PolicyReadsTest {
    private lateinit var server: MockWebServer
    private lateinit var sessions: SessionRepository
    private val clients = mutableListOf<SessionHttp>()
    private val vault = Vault()

    @Before fun start() {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val tls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        server = MockWebServer().apply { useHttps(tls.sslSocketFactory()); start() }
        val client = OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager).build()
        sessions = SessionRepository(vault) { SessionHttp(it, client).also(clients::add) }
    }
    @After fun stop() { clients.forEach(SessionHttp::close); server.close() }
    private fun json(body: String, status: Int = 200, cookie: String? = null) = MockResponse.Builder()
        .code(status).addHeader("Content-Type", "application/json").body(body)
        .apply { cookie?.let { addHeader("Set-Cookie", it) } }.build()
    private fun take() = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS))
    private fun address() = (ServerAddress.parse(server.url("/").toString()) as AddressResult.Valid).address
    private suspend fun connect(version: String = "3.10.0"): SessionState {
        server.enqueue(json("""{"csrf_token":"${"a".repeat(64)}","user":null,"application":{"version":"$version"},"capabilities":{"schema_version":1},"registration_enabled":false,"sign_in_providers":[],"lockdown":{"enabled":false}}""",
            cookie = "SITE=anon; Secure; HttpOnly; Path=/"))
        sessions.connect(address()); take()
        return sessions.state.value
    }
    private val hosted = """{"document":{"source":"hosted","url":"https://x/privacy.php","html":"<p>x</p>","markdown":"# Privacy","updated_at":"2026-11-08T12:00:00+00:00"}}"""
    private suspend fun failure(block: suspend () -> Unit) = try { block(); null } catch (failure: ApiFailure) { failure }

    @Test fun aSignedOutSessionReadsThePublicDocument() = runTest {
        val state = connect()
        server.enqueue(json(hosted))
        val document = sessions.policyDocument(PolicyDocumentName.PRIVACY, state)
        assertEquals("# Privacy", (document as PolicyDocument.Hosted).markdown)
        assertEquals("/api/v1/policies/show.php?document=privacy", take().target)
    }

    @Test fun aSignedInSessionReadsItTooThroughTheSameRuntime() = runTest {
        connect()
        server.enqueue(json("""{"csrf_token":"${"b".repeat(64)}","user":{"id":7,"username":"Viewer","roles":["member"],"session_version":1,"guest":false},"mfa_required":false}""",
            cookie = "SITE=auth; Secure; HttpOnly; Path=/"))
        sessions.signIn("Viewer", "fixture-password"); take()
        val state = sessions.state.value
        assertEquals(SessionPhase.AUTHENTICATED, state.phase)
        server.enqueue(json("""{"document":{"source":"external","url":"https://example.org/rules"}}"""))
        assertEquals(PolicyDocument.External("https://example.org/rules"), sessions.policyDocument(PolicyDocumentName.RULES, state))
        take()
    }

    /** Another server's or an earlier generation's rendered session: EXPIRED and no request, never a bare cancellation. */
    @Test fun aStaleRenderedSessionIsATypedExpiredFailureWithoutARequest() = runTest {
        val state = connect()
        val before = server.requestCount
        val older = failure { sessions.policyDocument(PolicyDocumentName.PRIVACY, state.copy(generation = state.generation - 1)) }
        assertEquals(FailureKind.EXPIRED, older?.kind)
        val other = (ServerAddress.parse("https://other.example.test/") as AddressResult.Valid).address
        val elsewhere = failure { sessions.policyDocument(PolicyDocumentName.PRIVACY, state.copy(server = other)) }
        assertEquals(FailureKind.EXPIRED, elsewhere?.kind)
        assertEquals("No read was sent", before, server.requestCount)
    }

    /** Nothing new starts while the server-update state shows (server-update.md). */
    @Test fun theUpdateStateRefusesTheReadWithoutARequest() = runTest {
        val state = connect(version = "3.9.0")
        assertTrue(state.updateRequired)
        val before = server.requestCount
        assertEquals(FailureKind.INCOMPATIBLE, failure { sessions.policyDocument(PolicyDocumentName.RULES, state) }?.kind)
        assertEquals(before, server.requestCount)
    }

    private class Vault : SessionVault {
        var saved: SavedSession? = null
        override suspend fun read() = saved
        override suspend fun save(session: SavedSession) { saved = session }
        override suspend fun clear() { saved = null }
    }
}

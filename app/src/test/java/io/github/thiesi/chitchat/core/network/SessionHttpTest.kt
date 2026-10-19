package io.github.thiesi.chitchat.core.network

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

class SessionHttpTest {
    private lateinit var server: MockWebServer
    private lateinit var api: SessionHttp
    private lateinit var trustedClient: OkHttpClient
    private val token = "a".repeat(64)
    private val rotated = "b".repeat(64)

    @Before fun start() {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        server = MockWebServer().apply { useHttps(serverTls.sslSocketFactory()); start() }
        trustedClient = OkHttpClient.Builder().sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager).build()
        api = SessionHttp(address(), trustedClient)
    }
    @After fun stop() { api.close(); server.close() }
    private fun address() = (ServerAddress.parse(server.url("/").toString()) as AddressResult.Valid).address
    private fun response(body: String, code: Int = 200, cookie: String? = null): MockResponse =
        MockResponse.Builder().code(code).body(body).addHeader("Content-Type", "application/json")
            .apply { cookie?.let { addHeader("Set-Cookie", it) } }.build()
    private fun anonymous(csrf: String = token) = """{"csrf_token":"$csrf","user":null,"registration_enabled":true,"sign_in_providers":[],"lockdown":{"enabled":false},"future_field":true}"""
    private fun user() = """{"id":7,"username":"Alice","roles":["member"],"session_version":2,"guest":false}"""

    @Test fun adoptsCustomCookieAndCsrfRotationWithoutAssumingPhpName() = runTest {
        server.enqueue(response(anonymous(), cookie = "COMMUNITY=anonymous; Secure; HttpOnly; Path=/"))
        api.bootstrap()
        server.enqueue(response("""{"csrf_token":"$rotated","user":${user()},"mfa_required":false}""", cookie = "COMMUNITY=authenticated; Secure; HttpOnly; Path=/"))
        val signedIn = api.password("Alice", "private-test-password", token)
        assertEquals(7L, signedIn.user!!.id)
        server.takeRequest()
        val login = server.takeRequest()
        assertEquals("/api/v1/login.php", login.url.encodedPath)
        assertEquals(token, login.headers["X-CSRF-Token"])
        assertEquals("COMMUNITY=anonymous", login.headers["Cookie"])
        server.enqueue(response(anonymous(rotated)))
        api.bootstrap()
        assertEquals("COMMUNITY=authenticated", server.takeRequest().headers["Cookie"])
        assertFalse(signedIn.toString().contains(rotated))
    }

    @Test fun registrationUses201WithOptionalDateDecoyAndExactChallengeFields() = runTest {
        server.enqueue(response("""{"challenge":{"nonce":"${"a".repeat(32)}","bits":16}}"""))
        assertEquals(16, api.registrationChallenge()!!.bits)
        assertEquals("/api/v1/registration-challenge.php", server.takeRequest().url.encodedPath)
        server.enqueue(response("""{"csrf_token":"$rotated","user":${user()}}""", 201))
        assertEquals(7L, api.register("Alice", "fixture-password", "1990-05-12", "a".repeat(32), "123", token).user!!.id)
        val request = server.takeRequest()
        val body = apiJson.parseToJsonElement(request.body!!.utf8()).toString()
        assertEquals("/api/v1/register.php", request.url.encodedPath)
        assertTrue(body.contains("\"birth_date\":\"1990-05-12\""))
        assertTrue(body.contains("\"website\":\"\""))
        assertTrue(body.contains("\"challenge_solution\":\"123\""))
        assertEquals(token, request.headers["X-CSRF-Token"])
    }

    @Test fun registrationDoesNotAcceptInvented200SuccessOrPendingMfa() = runTest {
        for (code in listOf(200, 202)) {
            server.enqueue(response("""{"csrf_token":"$rotated","user":${user()}}""", code))
            val failure = try { api.register("Alice", "fixture-password", null, null, null, token); error("Expected rejection") }
                catch (failure: ApiFailure) { failure }
            assertEquals(FailureKind.INCOMPATIBLE, failure.kind)
        }
    }

    @Test fun restorationMustExplicitlyReportCompletionOrPendingState() = runTest {
        server.enqueue(response("""{"csrf_token":"$rotated","user":${user()}}"""))
        val invalid = try { api.password("Alice", "fixture-password", token, true); error("Expected rejection") }
            catch (failure: ApiFailure) { failure }
        assertEquals(FailureKind.INCOMPATIBLE, invalid.kind)
        server.enqueue(response("""{"csrf_token":"$rotated","user":${user()},"restored":true,"restoration_pending":false}"""))
        assertTrue(api.password("Alice", "fixture-password", token, true).restored)
        server.takeRequest()
        assertEquals("/api/v1/account/restore.php", server.takeRequest().url.encodedPath)
    }

    @Test fun pendingMfaIsNotAnAuthenticatedUser() = runTest {
        server.enqueue(response("""{"csrf_token":"$rotated","mfa_required":true,"methods":["passkey","recovery_code"]}""", 202))
        val pending = api.password("Alice", "private-test-password", token)
        assertTrue(pending.mfaRequired)
        assertNull(pending.user)
    }

    @Test fun malformedSuccessFailsClosedWithoutIncludingBodyInException() = runTest {
        server.enqueue(response("""{"csrf_token":"secret-in-invalid-payload","user":${user()}}"""))
        val failure = try { api.bootstrap(); error("Expected rejection") } catch (failure: ApiFailure) { failure }
        assertEquals(FailureKind.INCOMPATIBLE, failure.kind)
        assertFalse(failure.toString().contains("secret-in-invalid-payload"))
    }

    @Test fun redirectsAreNotFollowed() = runTest {
        server.enqueue(MockResponse.Builder().code(307).addHeader("Location", "https://other.invalid/api/v1/login.php").build())
        val failure = try { api.password("Alice", "private-test-password", token); error("Expected rejection") } catch (failure: ApiFailure) { failure }
        assertEquals(FailureKind.REDIRECT, failure.kind)
        assertEquals(1, server.requestCount)
    }

    @Test fun rateLimitRetainsStatusCodeAndRetryAfterWithoutReplayingPost() = runTest {
        server.enqueue(MockResponse.Builder().code(429).addHeader("Retry-After", "45")
            .body("""{"error":{"code":"rate_limited","message":"private server explanation"}}""").build())
        val failure = try { api.password("Alice", "private-test-password", token); error("Expected rejection") } catch (failure: ApiFailure) { failure }
        assertEquals(429, failure.status)
        assertEquals("rate_limited", failure.code)
        assertEquals(45L, failure.retryAfterSeconds)
        assertEquals(1, server.requestCount)
        assertFalse(failure.toString().contains("private server explanation"))
    }

    @Test fun defaultTrustRejectsUnknownCertificate() = runTest {
        val untrusted = SessionHttp(address())
        try {
            val failure = try { untrusted.bootstrap(); error("Expected TLS rejection") } catch (failure: ApiFailure) { failure }
            assertEquals(FailureKind.TLS, failure.kind)
        } finally { untrusted.close() }
    }

    @Test fun trustingACertificateDoesNotDisableHostnameVerification() = runTest {
        val mismatched = (ServerAddress.parse(server.url("/").newBuilder().host("127.0.0.1").build().toString()) as AddressResult.Valid).address
        val wrongHost = SessionHttp(mismatched, trustedClient)
        try {
            val failure = try { wrongHost.bootstrap(); error("Expected hostname rejection") } catch (failure: ApiFailure) { failure }
            assertEquals(FailureKind.TLS, failure.kind)
        } finally { wrongHost.close() }
    }

    @Test fun unavailableServerRetainsRetryMetadataWithoutInventingPayloadFields() = runTest {
        server.enqueue(MockResponse.Builder().code(503).addHeader("Retry-After", "60").body("Temporarily unavailable").build())
        val failure = try { api.bootstrap(); error("Expected unavailable response") } catch (failure: ApiFailure) { failure }
        assertEquals(503, failure.status)
        assertEquals(60L, failure.retryAfterSeconds)
        assertNull(failure.code)
    }
}

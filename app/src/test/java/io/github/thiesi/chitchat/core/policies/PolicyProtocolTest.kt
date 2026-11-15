package io.github.thiesi.chitchat.core.policies

import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.network.FailureKind
import io.github.thiesi.chitchat.core.network.SessionHttp
import io.github.thiesi.chitchat.core.network.policyDocumentReply
import io.github.thiesi.chitchat.core.server.AddressResult
import io.github.thiesi.chitchat.core.server.ServerAddress
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Released ChitChat v3.10.0 docs/api/policies.md: discovery and `policies/show.php`. */
class PolicyProtocolTest {
    private lateinit var server: MockWebServer
    private lateinit var api: SessionHttp

    @Before fun start() {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        server = MockWebServer().apply { useHttps(serverTls.sslSocketFactory()); start() }
        val client = OkHttpClient.Builder().sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager).build()
        api = SessionHttp((ServerAddress.parse(server.url("/").toString()) as AddressResult.Valid).address, client)
    }
    @After fun stop() { api.close(); server.close() }
    private fun json(body: String, code: Int = 200) =
        MockResponse.Builder().code(code).body(body).addHeader("Content-Type", "application/json").build()
    private fun discovery(text: String) = policyDiscovery(Json.parseToJsonElement(text))

    @Test fun discoveryDistinguishesUnknownFromNoneAndKeepsEachEntry() {
        assertNull(policyDiscovery(null)) // The key is missing: an older server, unknown.
        val none = discovery("""{"rules":null,"privacy":null,"legal_notice":null}""")!!
        assertEquals(PolicyDiscovery(null, null, null), none)
        val all = discovery("""{"rules":{"url":"https://chat.example/rules.php","version":3,"updated_at":"2026-11-08T12:00:00+00:00"},
            "privacy":{"url":"https://chat.example/privacy.php","updated_at":"2026-11-08T12:00:00+00:00"},
            "legal_notice":{"url":"https://example.org/imprint"}}""")!!
        assertEquals(PolicyLink("https://chat.example/rules.php", 3, "2026-11-08T12:00:00+00:00"), all.rules)
        assertEquals(PolicyLink("https://chat.example/privacy.php", null, "2026-11-08T12:00:00+00:00"), all.privacy)
        assertEquals("https://example.org/imprint", all.legalNotice?.url)
    }

    @Test fun malformedEntriesAreNoneNeverAGuess() {
        val odd = discovery("""{"rules":{"url":"https://x.example/r","version":"3"},"privacy":{"url":42},"legal_notice":{"url":"https://a b"}}""")!!
        assertNull("A quoted version isn't a version", odd.rules?.version)
        assertNull(odd.privacy); assertNull(odd.legalNotice)
        assertNull(discovery("""[]"""))
        assertNull(discovery(""""policies""""))
    }

    @Test fun hostedDocumentsCarryMarkdownAndNeverTheServerHtml() = runTest {
        server.enqueue(json("""{"document":{"source":"hosted","url":"https://chat.example/rules.php","html":"<h2>Rules</h2><script>x()</script>",
            "markdown":"# Community rules","version":1,"updated_at":"2026-11-08T12:00:00+00:00","change_summary":"Be kind."}}"""))
        val document = api.policyDocument(PolicyDocumentName.RULES)
        assertEquals(PolicyDocument.Hosted("# Community rules", 1, "2026-11-08T12:00:00+00:00", "Be kind."), document)
        assertEquals("/api/v1/policies/show.php?document=rules", server.takeRequest().target)
    }

    @Test fun externalDocumentsCarryOnlyTheirUrl() = runTest {
        server.enqueue(json("""{"document":{"source":"external","url":"https://example.org/privacy","html":null,"markdown":null}}"""))
        assertEquals(PolicyDocument.External("https://example.org/privacy"), api.policyDocument(PolicyDocumentName.PRIVACY))
        assertEquals("/api/v1/policies/show.php?document=privacy", server.takeRequest().target)
    }

    @Test fun notFoundIsNotPublishedNotAnError() = runTest {
        server.enqueue(json("""{"error":{"code":"policy_document_not_found","message":"Not found"}}""", 404))
        assertEquals(PolicyDocument.NotPublished, api.policyDocument(PolicyDocumentName.PRIVACY))
    }

    @Test fun otherRefusalsStayTyped() = runTest {
        server.enqueue(json("""{"error":{"code":"validation_error","message":"Bad"}}""", 400))
        val failure = runCatching { api.policyDocument(PolicyDocumentName.RULES) }.exceptionOrNull() as ApiFailure
        assertEquals(400, failure.status); assertEquals("validation_error", failure.code)
        server.enqueue(json("""{"error":{"code":"not_found","message":"Other"}}""", 404))
        val other = runCatching { api.policyDocument(PolicyDocumentName.RULES) }.exceptionOrNull() as ApiFailure
        assertEquals("A different 404 isn't NotPublished", 404, other.status)
    }

    @Test fun anythingOutsideTheDocumentedShapeIsIncompatible() {
        for (body in listOf("""{"document":{"source":"hosted","markdown":null}}""", """{"document":{"source":"external"}}""",
            """{"document":{"source":"inline","markdown":"x"}}""", """{"doc":{}}""", "not json")) {
            val failure = runCatching { policyDocumentReply(body) }.exceptionOrNull() as? ApiFailure
            assertEquals(body, FailureKind.INCOMPATIBLE, failure?.kind)
        }
    }
}

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

class AttachmentPreviewMetadataHttpTest {
    private lateinit var server: MockWebServer
    private lateinit var api: SessionHttp
    private val sha = "a".repeat(64)
    @Before fun start() {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val tls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        server = MockWebServer().apply { useHttps(tls.sslSocketFactory()); start() }
        api = SessionHttp((ServerAddress.parse(server.url("/").toString()) as AddressResult.Valid).address,
            OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(),trust.trustManager).build())
    }
    @After fun stop() { api.close(); server.close() }
    private fun json(body: String) = MockResponse.Builder().addHeader("Content-Type","application/json").body(body).build()
    private fun attachment(direct: Boolean = false, urlDirect: Boolean = direct) = """{"id":12,${if(direct) "\"message_id\":80," else ""}"name":"private.png","mime_type":"image/png","size_bytes":1250000,"sha256":"$sha","previewable":true,"width":2048,"height":1024,"preview":{"url":"/api/v1/${if(urlDirect) "direct-messages/" else ""}attachments/download.php?id=12&variant=preview","width":1024,"height":512,"mime_type":"image/webp","size_bytes":42000,"sha256":"$sha"}}"""
    private fun room(file: String = attachment()) = """{"messages":[{"id":80,"room_id":42,"sender_id":7,"username":"Viewer","type":"attachment","body":"caption","deleted":false,"created_at":"2026-11-10T00:00:00Z","attachment":$file}]}"""
    private fun mutations(direct: Boolean = false) = """{"messages":[{"id":80,"body":"caption",${if(direct) "" else "\"type\":\"attachment\",\"deletion_kind\":null,"}"edited_at":null,"deleted":false,"can_edit":true,"can_delete":true,"mentions":[],"reactions":[]}]}"""
    private fun history() = """{"messages":[{"id":80,"sender":{"id":7,"username":"Viewer"},"recipient":{"id":8,"username":"Peer"},"body":"caption","read_at":null,"created_at":"2026-11-10T00:00:00Z","outgoing":true,"reply_to":null,"mentions":[],"reactions":[]}]}"""
    private fun assertOnlyMetadataGets(count: Int) {
        assertEquals(count,server.requestCount)
        repeat(count) {
            val request=server.takeRequest()
            assertEquals("GET",request.method); assertNull(request.headers["X-CSRF-Token"])
            assertFalse(request.url.encodedPath.contains("download.php"))
        }
    }
    private suspend fun incompatible(action: suspend () -> Unit) {
        try { action(); fail("Cross-namespace metadata accepted") }
        catch (failure: ApiFailure) { assertEquals(FailureKind.INCOMPATIBLE,failure.kind) }
    }

    @Test fun roomHistoryKeepsOrientedOriginalAndPreviewThroughCanonicalMutation() = runTest {
        server.enqueue(json(room())); server.enqueue(json(mutations()))
        val value=api.roomHistory(42,null,50).messages.single().attachment!!
        val preview=checkNotNull(value.preview)
        assertTrue(value.dimensionsAdvertised); assertEquals(2048,value.displayDimensions!!.width)
        assertEquals(1024,preview.width); assertEquals(42000L,preview.sizeBytes)
        assertEquals("/api/v1/attachments/download.php?id=12&variant=preview",preview.url)
        assertOnlyMetadataGets(2)
    }

    @Test fun directHistoryKeepsTypedPreviewThroughItsSeparateMetadataEndpoint() = runTest {
        server.enqueue(json(history())); server.enqueue(json(mutations(true)))
        server.enqueue(json("""{"attachments":[${attachment(true)}]}"""))
        val value=api.directHistory(8,null,50,7).messages.single().attachment!!
        assertTrue(value.dimensionsAdvertised); assertEquals(2048,value.displayDimensions!!.width)
        assertEquals("/api/v1/direct-messages/attachments/download.php?id=12&variant=preview",value.preview!!.url)
        assertOnlyMetadataGets(3)
    }

    @Test fun directInboxEnrichmentPreservesPreviewWithoutFetchingIt() = runTest {
        server.enqueue(json("""{"conversations":[{"user":{"id":8,"username":"Peer"},"last_message":{"id":80,"sender_id":7,"body":"caption","created_at":"2026-11-10T00:00:00Z","outgoing":true},"unread_count":0}]}"""))
        server.enqueue(json(mutations(true))); server.enqueue(json("""{"attachments":[${attachment(true)}]}"""))
        val value=api.directConversations(7).single().lastMessage.attachment!!
        assertTrue(value.dimensionsAdvertised); assertEquals(1024,value.displayDimensions!!.height)
        assertEquals(42000L,value.preview!!.sizeBytes); assertOnlyMetadataGets(3)
    }

    @Test fun roomRejectsADirectPreviewBeforeCanonicalEnrichment() = runTest {
        server.enqueue(json(room(attachment(urlDirect=true))))
        incompatible { api.roomHistory(42,null,50) }; assertOnlyMetadataGets(1)
    }

    @Test fun directRejectsARoomPreviewWithoutAnyBinaryRequest() = runTest {
        server.enqueue(json(history())); server.enqueue(json(mutations(true)))
        server.enqueue(json("""{"attachments":[${attachment(true,urlDirect=false)}]}"""))
        incompatible { api.directHistory(8,null,50,7) }; assertOnlyMetadataGets(3)
    }
}

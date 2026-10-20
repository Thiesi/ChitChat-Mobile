package io.github.thiesi.chitchat.core.direct

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.thiesi.chitchat.core.network.SessionHttp
import io.github.thiesi.chitchat.core.server.AddressResult
import io.github.thiesi.chitchat.core.server.ServerAddress
import io.github.thiesi.chitchat.core.session.*
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual Android TLS/serialization/session integration with local HTTP fixtures, not a live DM/UI claim. */
@RunWith(AndroidJUnit4::class)
class DirectSessionJourneyTest {
    @Test fun authenticatedDmEnrichmentUsesFreshSessionAndClearsPolicyOnLogout() = runBlocking {
        val cert = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val tls = HandshakeCertificates.Builder().heldCertificate(cert).build()
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        val server = MockWebServer().apply { useHttps(tls.sslSocketFactory()); start() }
        val transport = OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager).build()
        val vault = Vault()
        var api: SessionHttp? = null
        val sessions = SessionRepository(vault) { SessionHttp(it, transport).also { value -> api = value } }
        val user = """{"id":7,"username":"Viewer","roles":["member"],"session_version":1,"guest":false}"""
        val a = "a".repeat(64); val b = "b".repeat(64); val c = "c".repeat(64)
        fun response(body: String, cookie: String? = null) = MockResponse.Builder().addHeader("Content-Type", "application/json")
            .body(body).apply { cookie?.let { addHeader("Set-Cookie", it) } }.build()
        fun take() = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        try {
            server.enqueue(response("""{"csrf_token":"$a","user":null,"registration_enabled":true,"sign_in_providers":[],"lockdown":{"enabled":false}}""",
                "SITE=anon; Secure; HttpOnly; Path=/"))
            sessions.connect((ServerAddress.parse(server.url("/").toString()) as AddressResult.Valid).address); take()
            server.enqueue(response("""{"csrf_token":"$b","user":$user,"mfa_required":false}""", "SITE=auth; Secure; HttpOnly; Path=/"))
            sessions.signIn("Viewer", "fixture-password"); take()
            server.enqueue(response("""{"csrf_token":"$c","user":$user,"registration_enabled":true,"sign_in_providers":[],"lockdown":{"enabled":false},"ignored_user_ids":[],"preferences":{},"privacy":{"direct_messages":{"end_to_end_encrypted":false,"admin_inspection_enabled":false,"admin_inspection_role":"super_admin","retention":"for up to 30 days","retention_days":30},"message_revisions":{"admin_review_enabled":true,"admin_review_role":"admin","reason_required":true,"audit_each_review":true,"participant_notification":false}}}"""))
            server.enqueue(response("""{"user":{"id":8,"username":"Peer"},"relationship":{"blocked_by_me":false,"messaging_available":true}}"""))
            val direct = SessionDirectRepository(sessions)
            val context = direct.context(8); take(); take()
            assertEquals(30, context.privacy.direct.retentionDays); assertFalse(context.privacy.direct.inspectionEnabled)
            assertTrue(context.privacy.revisions.reviewEnabled); assertFalse(context.privacy.revisions.participantNotification)
            server.enqueue(response("""{"messages":[{"id":18,"sender":{"id":8,"username":"Peer"},"recipient":{"id":7,"username":"Viewer"},"body":"Old body","read_at":null,"created_at":"2026-10-20T10:00:00Z","outgoing":false,"reply_to":{"kind":"direct","message_id":9,"available":true,"message":{"id":9,"sender":{"id":7,"username":"Viewer"},"body":"Fresh quote","deleted":false}},"mentions":[],"reactions":[]}]}"""))
            server.enqueue(response("""{"messages":[{"id":18,"body":"Canonical @Viewer","edited_at":"2026-10-20T10:01:00Z","deleted":false,"can_edit":false,"can_delete":false,"mentions":[{"user_id":7,"username":"Viewer"}],"reactions":[]}]}"""))
            server.enqueue(response("""{"attachments":[{"id":3,"message_id":18,"name":"note.txt","mime_type":"text/plain","size_bytes":3,"sha256":"${"f".repeat(64)}","previewable":false}]}"""))
            val message = direct.history(8).messages.single(); repeat(3) { take() }
            assertEquals("Canonical @Viewer", message.body); assertEquals("Fresh quote", message.replyTo?.message?.body)
            assertEquals(7L, message.mentions.single().userId); assertEquals("note.txt", message.attachment?.name)
            server.enqueue(response("""{"updated":0}""")); assertEquals(0, direct.read(8))
            val read = take(); assertEquals(c, read.headers["X-CSRF-Token"]); assertEquals("SITE=auth", read.headers["Cookie"])
            assertEquals("""{"user_id":8}""", read.body!!.utf8())
            server.enqueue(response("""{"status":"logged_out"}""")); sessions.signOut(); take()
            assertNull(sessions.state.value.privacy); assertNull(vault.saved)
        } finally { api?.close(); server.close() }
    }
    private class Vault : SessionVault {
        var saved: SavedSession? = null
        override suspend fun read() = saved
        override suspend fun save(session: SavedSession) { saved = session }
        override suspend fun clear() { saved = null }
    }
}

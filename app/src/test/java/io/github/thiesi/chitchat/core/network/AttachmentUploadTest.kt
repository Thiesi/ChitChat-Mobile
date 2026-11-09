package io.github.thiesi.chitchat.core.network

import io.github.thiesi.chitchat.core.media.AttachmentUpload
import io.github.thiesi.chitchat.core.media.PreparedAttachmentPayload
import io.github.thiesi.chitchat.core.server.AddressResult
import io.github.thiesi.chitchat.core.server.ServerAddress
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class AttachmentUploadTest {
    private lateinit var server: MockWebServer
    private lateinit var api: SessionHttp
    private val token = "a".repeat(64)
    private suspend fun attempt(caption: String = "", reply: Long? = null) =
        AttachmentUpload(PreparedAttachmentPayload.create("fixture.bin", null, byteArrayOf(0, 1, 127, -1).toByteString()), caption, reply)
    private fun roomAck(room: Long = 42, sender: Long = 7, id: Long = 19, reply: String = "null") =
        """{"message":{"id":$id,"room_id":$room,"sender_id":$sender,"type":"attachment","deleted":false,"reply_to":$reply}}"""
    private fun directAck(sender: Long = 7, recipient: Long = 8, outgoing: String = "true", reply: String = "null") =
        """{"message":{"id":23,"sender":{"id":$sender},"recipient":{"id":$recipient},"outgoing":$outgoing,"reply_to":$reply}}"""
    private fun json(body: String, status: Int = 201) = MockResponse.Builder().code(status)
        .addHeader("Content-Type", "application/json").body(body).build()

    @Before fun start() {
        val cert = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val tls = HandshakeCertificates.Builder().heldCertificate(cert).build()
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        server = MockWebServer().apply { useHttps(tls.sslSocketFactory()); start() }
        val client = OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager).build()
        api = SessionHttp((ServerAddress.parse(server.url("/").toString()) as AddressResult.Valid).address, client)
    }
    @After fun stop() { api.close(); server.close() }

    @Test fun roomAndDirectUseLiteralMultipartFieldsOriginalCsrfAndFrozenBytes() = runTest {
        api.restoreCookies(listOf("SITE=fixture; Path=/; Secure; HttpOnly"))
        for (direct in listOf(false, true)) {
            val kind = if (direct) "direct" else "room"
            val pointer = """{"kind":"$kind","message_id":11}"""
            server.enqueue(json(if (direct) directAck(reply = pointer) else roomAck(reply = pointer)))
            val upload = attempt(" \t/ping literal\n", 11)
            if (direct) assertEquals(23L, api.uploadDirectAttachment(8, upload, token, 7).messageId)
            else assertEquals(19L, api.uploadRoomAttachment(42, upload, token, 7).messageId)
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals(if (direct) "/api/v1/direct-messages/attachments/upload.php" else "/api/v1/attachments/upload.php",
                request.url.encodedPath)
            assertEquals(token, request.headers["X-CSRF-Token"])
            assertEquals("application/json", request.headers["Accept"])
            assertTrue(request.headers["Cookie"]!!.contains("SITE=fixture"))
            assertTrue(request.headers["Content-Type"]!!.startsWith("multipart/form-data; boundary="))
            val bytes = request.body!!
            val body = bytes.utf8()
            assertTrue(body.contains("name=\"${if (direct) "recipient_user_id" else "room_id"}\"\r\n\r\n${if (direct) 8 else 42}\r\n"))
            assertFalse(body.contains("name=\"${if (direct) "room_id" else "recipient_user_id"}\""))
            assertTrue(body.contains("name=\"caption\"\r\n\r\n/ping literal\r\n"))
            assertTrue(body.contains("name=\"reply_to_message_id\"\r\n\r\n11\r\n"))
            assertTrue(body.contains("name=\"file\"; filename=\"fixture.bin\""))
            assertTrue(body.contains("Content-Type: application/octet-stream"))
            assertTrue(bytes.indexOf(byteArrayOf(0, 1, 127, -1).toByteString()) >= 0)
            assertFalse(upload.toString().contains("fixture.bin"))
        }
        assertEquals(2, server.requestCount) // No automatic policy or enrichment GET.
    }

    @Test fun committedIdentityIsAcknowledgedWithoutDecodingOptionalDisplayMetadata() = runTest {
        server.enqueue(json(roomAck().dropLast(2) + """, "attachment":{"private":"invalid"},"body":null}}"""))
        assertEquals(42L, api.uploadRoomAttachment(42, attempt(), token, 7).roomId)
        val multipart = server.takeRequest().body!!.utf8()
        assertTrue(multipart.contains("name=\"caption\"\r\n\r\n\r\n"))
        assertFalse(multipart.contains("reply_to_message_id"))
        server.enqueue(json(directAck().dropLast(2) + """, "mentions":"bad","reactions":null}}"""))
        assertEquals(8L, api.uploadDirectAttachment(8, attempt(), token, 7).peerId)
        assertEquals(2, server.requestCount)
    }

    @Test fun wrongNamespaceViewerPeerReplyOrStatusIsNeverConfirmed() = runTest {
        val badRooms = listOf(roomAck(room = 43), roomAck(sender = 8), roomAck(id = 0),
            roomAck().replace("\"attachment\"", "\"message\""), roomAck().replace("false", "true"),
            roomAck().replace("false", "\"false\""),
            roomAck(reply = """{"kind":"direct","message_id":11}"""), "{}")
        for (body in badRooms) {
            server.enqueue(json(body))
            try { api.uploadRoomAttachment(42, attempt(reply = if (body.contains("message_id")) 11 else null), token, 7); fail() }
            catch (e: ApiFailure) { assertEquals(FailureKind.INCOMPATIBLE, e.kind) }
        }
        for (body in listOf(directAck(sender = 8, recipient = 7), directAck(recipient = 9),
            directAck(outgoing = "false"), directAck(outgoing = "\"true\""),
            directAck(reply = """{"kind":"room","message_id":11}"""))) {
            server.enqueue(json(body))
            try { api.uploadDirectAttachment(8, attempt(reply = if (body.contains("message_id")) 11 else null), token, 7); fail() }
            catch (e: ApiFailure) { assertEquals(FailureKind.INCOMPATIBLE, e.kind) }
        }
        server.enqueue(json(roomAck(), 200))
        try { api.uploadRoomAttachment(42, attempt(), token, 7); fail() }
        catch (e: ApiFailure) { assertEquals(FailureKind.INCOMPATIBLE, e.kind) }
        assertEquals(badRooms.size + 6, server.requestCount)
    }

    @Test fun refusalsRetainSafeHintsAndAnAttemptCannotReplayEvenOnTheOtherRoute() = runTest {
        for (status in listOf(413, 429)) {
            server.enqueue(json("""{"error":{"code":"fixture_refusal","message":"private","max_bytes":0,"retry_after_seconds":19}}""", status))
            val upload = attempt()
            val before = server.requestCount
            try { api.uploadRoomAttachment(42, upload, token, 7); fail() }
            catch (e: ApiFailure) {
                assertEquals(status, e.status)
                assertEquals(if (status == 413) 0L else null, e.maxBytes)
                assertEquals(if (status == 429) 19L else null, e.retryAfterSeconds)
                assertFalse(e.toString().contains("private"))
            }
            try { api.uploadDirectAttachment(8, upload, token, 7); fail() }
            catch (_: IllegalStateException) { }
            assertEquals(before + 1, server.requestCount)
        }
        server.enqueue(MockResponse.Builder().code(413).addHeader("Content-Type", "text/html").body("private proxy").build())
        try { api.uploadDirectAttachment(8, attempt(), token, 7); fail() }
        catch (e: ApiFailure) { assertEquals(413, e.status); assertNull(e.maxBytes); assertNull(e.code) }
        assertEquals(3, server.requestCount)
    }

    @Test fun redirectIsNotFollowedAndDoesNotAuthorizeAnotherAttempt() = runTest {
        server.enqueue(MockResponse.Builder().code(307).addHeader("Location", server.url("/api/v1/elsewhere.php")).build())
        val upload = attempt()
        try { api.uploadDirectAttachment(8, upload, token, 7); fail() }
        catch (e: ApiFailure) { assertEquals(FailureKind.REDIRECT, e.kind) }
        try { api.uploadDirectAttachment(8, upload, token, 7); fail() }
        catch (_: IllegalStateException) { }
        assertEquals(1, server.requestCount)
    }

    @Test fun bodyIsOneShotAndValidationNeverPublishesFileOrCaptionInAnError() = runTest {
        val bytes = "prepared".encodeUtf8()
        val upload = AttachmentUpload(PreparedAttachmentPayload.create("private-name.txt", "text/plain", bytes), "private-caption")
        val body = upload.claimBody()
        assertTrue(body.isOneShot())
        val buffer = Buffer(); body.writeTo(buffer)
        val written = buffer.readByteString()
        assertEquals(bytes, written)
        assertEquals(written.sha256().hex(), upload.sha256)
        assertEquals(written.size.toLong(), upload.sizeBytes)
        try { body.writeTo(Buffer()); fail() } catch (_: IllegalStateException) { }
        for (name in listOf("../private.txt", "private\r\nname", "..", "private/name")) {
            try { AttachmentUpload(PreparedAttachmentPayload.create(name, null, bytes), "private-caption"); fail() }
            catch (e: IllegalArgumentException) { assertFalse(e.toString().contains(name)) }
        }
        try { AttachmentUpload(PreparedAttachmentPayload.create("private", null, bytes), "x".repeat(4001)); fail() }
        catch (_: IllegalArgumentException) { }
        // Supplementary Unicode counts as one server character, not two UTF-16 code units.
        AttachmentUpload(PreparedAttachmentPayload.create("private", null, bytes), "\uD83D\uDE00".repeat(4000))
    }
}

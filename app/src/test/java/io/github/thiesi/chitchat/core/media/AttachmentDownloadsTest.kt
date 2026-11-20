package io.github.thiesi.chitchat.core.media

import io.github.thiesi.chitchat.core.conversation.MessageAttachment
import io.github.thiesi.chitchat.core.network.FailureKind
import io.github.thiesi.chitchat.core.network.SessionHttp
import io.github.thiesi.chitchat.core.server.AddressResult
import io.github.thiesi.chitchat.core.server.ServerAddress
import io.github.thiesi.chitchat.core.session.*
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Received files F1: one authenticated GET per request, bound to its session, verified, typed, never replayed. */
class AttachmentDownloadsTest {
    private lateinit var server: MockWebServer
    private lateinit var sessions: SessionRepository
    private val clients = mutableListOf<SessionHttp>()
    private var budget = AttachmentMemoryBudget(8L * 1024 * 1024)
    private val downloads get() = AttachmentDownloads(sessions, budget)
    private val bytes = ByteArray(5_000) { (it % 251).toByte() }
    private val preview = ByteArray(1_200) { (it % 7).toByte() }

    @Before fun start() {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val tls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        server = MockWebServer().apply { useHttps(tls.sslSocketFactory()); start() }
        val client = OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager).build()
        sessions = SessionRepository(Vault()) { SessionHttp(it, client).also(clients::add) }
    }
    @After fun stop() { clients.forEach(SessionHttp::close); server.close() }

    private fun sha(value: ByteArray) = MessageDigest.getInstance("SHA-256").digest(value).joinToString("") { "%02x".format(it) }
    private fun json(body: String, status: Int = 200, cookie: String? = null) = MockResponse.Builder()
        .code(status).addHeader("Content-Type", "application/json").body(body)
        .apply { cookie?.let { addHeader("Set-Cookie", it) } }.build()
    private fun file(value: ByteArray, type: String = "image/png", etag: String? = null) = MockResponse.Builder().code(200)
        .addHeader("Content-Type", type).addHeader("Cache-Control", "private, no-store").body(Buffer().write(value))
        .apply { etag?.let { addHeader("ETag", it) } }.build()
    private fun error(code: String, status: Int, extra: String = "") = json("""{"error":{"code":"$code","message":"x"$extra}}""", status)
    private fun take() = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS))
    private fun address() = (ServerAddress.parse(server.url("/").toString()) as AddressResult.Valid).address

    /** Signed in; [policy] is the bootstrap's upload-policy block (downloads must not depend on it). */
    private suspend fun signedIn(policy: String = "null"): SessionState {
        server.enqueue(json("""{"csrf_token":"${"a".repeat(64)}","user":null,"application":{"version":"3.10.0"},"capabilities":{"schema_version":1},"registration_enabled":false,"sign_in_providers":[],"lockdown":{"enabled":false}}""",
            cookie = "SITE=anon; Secure; HttpOnly; Path=/"))
        sessions.connect(address()); take()
        server.enqueue(json("""{"csrf_token":"${"b".repeat(64)}","user":{"id":7,"username":"Viewer","roles":["member"],"session_version":1,"guest":false},"mfa_required":false}""",
            cookie = "SITE=auth; Secure; HttpOnly; Path=/"))
        sessions.signIn("Viewer", "fixture-password"); take()
        if (policy != "null") {
            // The upload policy arrives with a bootstrap: an explicit refresh carrying it.
            server.enqueue(json("""{"csrf_token":"${"b".repeat(64)}","user":{"id":7,"username":"Viewer","roles":["member"],"session_version":1,"guest":false},"application":{"version":"3.10.0"},"capabilities":{"schema_version":1},"attachments":$policy,"registration_enabled":false,"sign_in_providers":[],"lockdown":{"enabled":false}}"""))
            sessions.refresh(); take()
        }
        assertEquals(SessionPhase.AUTHENTICATED, sessions.state.value.phase)
        assertFalse(sessions.state.value.busy)
        return sessions.state.value
    }

    private fun attachment(id: Long = 12, size: Long = bytes.size.toLong(), hash: String = sha(bytes), width: Int? = 400, height: Int? = 300,
        direct: Boolean = false, withPreview: Boolean = true) = AttachmentRef(MessageAttachment(id, "diagram.png", "image/png", size, hash,
        previewable = true, width = width, height = height,
        preview = if (!withPreview) null else AttachmentPreview(
            (if (direct) "/api/v1/direct-messages/attachments/download.php" else "/api/v1/attachments/download.php") + "?id=$id&variant=preview",
            400, 300, "image/webp", preview.size.toLong(), sha(preview))), direct)

    @Test fun aPreviewLoadsFromItsOwnUrlVerifiedAndChargedUntilReleased() = runTest {
        val state = signedIn()
        server.enqueue(file(preview, "image/webp", etag = "\"p1\""))
        val result = downloads.load(attachment(), AttachmentVariant.PREVIEW, state) as DownloadResult.Bytes
        val request = take()
        assertEquals("/api/v1/attachments/download.php?id=12&variant=preview", request.target)
        assertNull("No validator on a first load", request.headers["If-None-Match"])
        assertArrayEquals(preview, result.bytes)
        assertEquals("\"p1\"", result.etag)
        assertEquals("Charged while held", preview.size.toLong(), budget.reservedBytes)
        result.release()
        assertEquals(0L, budget.reservedBytes)
    }

    @Test fun aHeldPreviewRevalidatesAndA304KeepsIt() = runTest {
        val state = signedIn()
        server.enqueue(MockResponse.Builder().code(304).addHeader("ETag", "\"p1\"").build())
        assertEquals(DownloadResult.NotModified, downloads.load(attachment(), AttachmentVariant.PREVIEW, state, ifNoneMatch = "\"p1\""))
        assertEquals("\"p1\"", take().headers["If-None-Match"])
        assertEquals("Nothing stays charged", 0L, budget.reservedBytes)
    }

    @Test fun originalsUseTheirOwnNamespaceAndNoValidator() = runTest {
        val state = signedIn()
        server.enqueue(file(bytes)); server.enqueue(file(bytes))
        (downloads.load(attachment(), AttachmentVariant.ORIGINAL, state, ifNoneMatch = "\"x\"") as DownloadResult.Bytes).release()
        val room = take()
        assertEquals("/api/v1/attachments/download.php?id=12", room.target)
        assertNull("Originals don't use preview validators", room.headers["If-None-Match"])
        (downloads.load(attachment(id = 14, direct = true), AttachmentVariant.ORIGINAL, state) as DownloadResult.Bytes).release()
        assertEquals("/api/v1/direct-messages/attachments/download.php?id=14", take().target)
    }

    @Test fun aMismatchingFileIsNeverReturned() = runTest {
        val state = signedIn()
        val other = bytes.copyOf().also { it[0] = 99 }
        server.enqueue(file(other))
        assertEquals("Same length, wrong bytes", DownloadResult.IntegrityFailed, downloads.load(attachment(), AttachmentVariant.ORIGINAL, state))
        server.enqueue(file(bytes.copyOf(bytes.size - 1)))
        assertEquals("A different length", DownloadResult.IntegrityFailed, downloads.load(attachment(), AttachmentVariant.ORIGINAL, state))
        assertEquals("Nothing stays charged", 0L, budget.reservedBytes)
    }

    @Test fun refusalsAreTypedAndNeverRetried() = runTest {
        val state = signedIn()
        val cases = listOf(
            error("attachment_deleted", 410) to DownloadResult.Gone,
            error("attachment_not_found", 404) to DownloadResult.NotAllowed,
            error("forbidden", 403) to DownloadResult.NotAllowed,
            error("attachment_preview_unavailable", 404) to DownloadResult.PreviewUnavailable)
        for ((reply, expected) in cases) {
            server.enqueue(reply)
            assertEquals(expected, downloads.load(attachment(), AttachmentVariant.PREVIEW, state)); take()
        }
        val before = server.requestCount
        server.enqueue(error("rate_limited", 429, ""","retry_after_seconds":30"""))
        assertEquals(DownloadResult.RateLimited(30), downloads.load(attachment(), AttachmentVariant.PREVIEW, state)); take()
        assertEquals("One request, never replayed", before + 1, server.requestCount)
        assertEquals(0L, budget.reservedBytes)
    }

    @Test fun beyondTheDisplayCapsNothingIsFetched() = runTest {
        val state = signedIn()
        val before = server.requestCount
        assertEquals(DownloadResult.TooLarge, downloads.load(attachment(size = 33L * 1024 * 1024), AttachmentVariant.ORIGINAL, state))
        assertEquals(DownloadResult.TooLarge, downloads.load(attachment(width = 5000, height = 4000), AttachmentVariant.ORIGINAL, state))
        assertEquals("Decided from metadata, before any request", before, server.requestCount)
    }

    @Test fun withoutABudgetLeaseNothingIsFetched() = runTest {
        val state = signedIn()
        budget = AttachmentMemoryBudget(1_000)
        val before = server.requestCount
        assertEquals(DownloadResult.NoMemory, downloads.load(attachment(), AttachmentVariant.ORIGINAL, state))
        assertEquals(before, server.requestCount)
    }

    @Test fun anInvalidPreviewUrlIsNeverRequested() = runTest {
        val state = signedIn()
        val forged = AttachmentRef(MessageAttachment(12, "x.png", "image/png", 10, sha(bytes), true, 1, 1,
            AttachmentPreview("https://evil.example/x?id=12&variant=preview", 1, 1, "image/webp", 10, sha(preview))), false)
        val before = server.requestCount
        val result = downloads.load(forged, AttachmentVariant.PREVIEW, state)
        assertEquals(FailureKind.INCOMPATIBLE, (result as DownloadResult.Failed).failure.kind)
        assertEquals(before, server.requestCount)
    }

    @Test fun aStaleRenderedSessionGetsNothing() = runTest {
        val state = signedIn()
        val before = server.requestCount
        val stale = try { downloads.load(attachment(), AttachmentVariant.PREVIEW, state.copy(generation = state.generation - 1)); null }
            catch (cancelled: CancellationException) { cancelled }
        assertNotNull("Never upgraded into the current session", stale)
        assertEquals(before, server.requestCount)
    }

    @Test fun theUploadPolicyDoesntGovernDownloads() = runTest {
        // max_bytes 0 means uploads are unavailable here; received files stay readable.
        val state = signedIn("""{"max_bytes":0,"allowed_mime_types":[],"previewable_mime_types":[],"rate_limits":{"room":{"maximum_attempts":10,"window_seconds":3600},"direct_message":{"maximum_attempts":10,"window_seconds":3600}}}""")
        server.enqueue(file(preview, "image/webp"))
        assertEquals("The upload policy really says no uploads", 0L,
            (state.attachmentPolicy as? io.github.thiesi.chitchat.core.media.AttachmentPolicy.Known)?.maxBytes)
        (downloads.load(attachment(), AttachmentVariant.PREVIEW, state) as DownloadResult.Bytes).release()
    }

    @Test fun streamingWritesEveryByteAndReportsAMismatch() = runTest {
        val state = signedIn()
        server.enqueue(file(bytes))
        val out = ByteArrayOutputStream()
        assertEquals(DownloadResult.Saved, downloads.stream(attachment(), state, { out }))
        assertArrayEquals(bytes, out.toByteArray())
        assertEquals("No memory charge for streaming", 0L, budget.reservedBytes)
        server.enqueue(file(bytes.copyOf().also { it[1] = 42 }))
        val partial = ByteArrayOutputStream()
        assertEquals("The caller decides what to do with the written file", DownloadResult.IntegrityFailed,
            downloads.stream(attachment(), state, { partial }))
        // Beyond the display caps it still streams.
        val big = ByteArray(40_000) { 3 }
        server.enqueue(file(big))
        assertEquals(DownloadResult.Saved, downloads.stream(AttachmentRef(MessageAttachment(20, "b.bin", "application/zip", big.size.toLong(),
            sha(big), false), false), state, { ByteArrayOutputStream() }))
    }

    @Test fun aFailureBeforeTheBodyNeverOpensTheDestination() = runTest {
        // TL (F5): a picked document may be an existing file, so it's opened only once body bytes arrive.
        val state = signedIn()
        var opened = 0
        server.enqueue(error("attachment_deleted", 410))
        assertEquals(DownloadResult.Gone, downloads.stream(attachment(), state, { opened++; ByteArrayOutputStream() }))
        server.enqueue(error("rate_limited", 429, ""","retry_after_seconds":30"""))
        assertTrue(downloads.stream(attachment(), state, { opened++; ByteArrayOutputStream() }) is DownloadResult.RateLimited)
        assertEquals("Nothing opened, created or truncated", 0, opened)
        take(); take()
    }

    @Test fun aFailureMidBodyKeepsWhatWasWrittenAndClosesTheDestination() = runTest {
        // F5: a mid-way failure leaves the partial file to the caller (a chosen document is never deleted).
        val state = signedIn()
        val large = ByteArray(200_000) { (it % 251).toByte() }
        server.enqueue(file(large, "application/pdf"))
        var opened = 0
        var closed = false
        val written = ByteArrayOutputStream()
        val destination = object : OutputStream() {
            override fun write(b: Int) = Unit
            override fun write(b: ByteArray, off: Int, len: Int) {
                if (written.size() >= 64 * 1024) throw IOException("full")
                written.write(b, off, len)
            }
            override fun close() { closed = true }
        }
        val ref = AttachmentRef(MessageAttachment(21, "big.pdf", "application/pdf", large.size.toLong(), sha(large), false), false)
        val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            downloads.stream(ref, state, { opened++; destination })
        }
        assertEquals(FailureKind.STORAGE, (result as DownloadResult.Failed).failure.kind)
        assertEquals(1, opened)
        assertEquals("The first chunk stays written", 64 * 1024, written.size())
        assertTrue("Closed, never deleted", closed)
        take()
    }

    @Test fun aStaleSessionStopsBeforeTheNextChunk() = runTest {
        // F5: the caller's fence runs before every chunk; stopping leaves no further writes.
        val state = signedIn()
        server.enqueue(file(bytes))
        var opened = 0
        val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            downloads.stream(attachment(), state, { opened++; ByteArrayOutputStream() },
                ensureCurrent = { throw io.github.thiesi.chitchat.core.network.ApiFailure(FailureKind.EXPIRED) })
        }
        assertEquals(FailureKind.EXPIRED, (result as DownloadResult.Failed).failure.kind)
        assertEquals("Stopped before anything was opened", 0, opened)
        take()
    }

    @Test fun aFailingDestinationIsAStorageFailure() = runTest {
        val state = signedIn()
        // Checked and unchecked failures alike end the request typed, never hanging (review, #306).
        for (broken in listOf(object : OutputStream() { override fun write(b: Int) = throw IOException("full") },
            object : OutputStream() { override fun write(b: Int) = throw IllegalStateException("closed") })) {
            server.enqueue(file(bytes))
            val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                kotlinx.coroutines.withTimeout(10_000) { downloads.stream(attachment(), state, { broken }) } }
            assertEquals(FailureKind.STORAGE, (result as DownloadResult.Failed).failure.kind)
            take()
        }
    }

    @Test fun aCancelledDownloadReturnsOnlyAfterItsProducerStops() = runTest {
        // Review (#306): never free memory or a destination while the callback may still write into it (M5g3h).
        val state = signedIn()
        server.enqueue(file(bytes))
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val slow = object : OutputStream() {
            override fun write(b: Int) = Unit
            override fun write(b: ByteArray, off: Int, len: Int) { entered.countDown(); release.await(5, TimeUnit.SECONDS) }
        }
        val job = launch(kotlinx.coroutines.Dispatchers.IO) { downloads.stream(attachment(), state, { slow }) }
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { assertTrue(entered.await(5, TimeUnit.SECONDS)) }
        job.cancel()
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Thread.sleep(300) }
        assertFalse("Still waiting for the producer to stop", job.isCompleted)
        release.countDown()
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { kotlinx.coroutines.withTimeout(5_000) { job.join() } }
        assertTrue(job.isCompleted)
    }

    @Test fun aCancelAgainstAStalledServerReturnsPromptly() = runTest {
        // TL (#306): the non-cancellable wait for the producer never stretches toward the call timeout.
        val state = signedIn()
        val stalls = listOf(
            "before the headers" to MockResponse.Builder().code(200).addHeader("Content-Type", "image/png")
                .body(Buffer().write(bytes)).headersDelay(60, TimeUnit.SECONDS).build(),
            "mid-body" to MockResponse.Builder().code(200).addHeader("Content-Type", "image/png")
                .body(Buffer().write(bytes)).bodyDelay(60, TimeUnit.SECONDS).build())
        for ((where, stall) in stalls) {
            server.enqueue(stall)
            val job = launch(kotlinx.coroutines.Dispatchers.IO) { downloads.load(attachment(), AttachmentVariant.ORIGINAL, state) }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { take() }
            val started = System.nanoTime()
            job.cancel()
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { kotlinx.coroutines.withTimeout(5_000) { job.join() } }
            val millis = (System.nanoTime() - started) / 1_000_000
            assertTrue("$where: returned in ${millis}ms", millis < 3_000)
            assertEquals("$where: the lease is freed once the producer stopped", 0L, budget.reservedBytes)
        }
    }

    @Test fun downloadsAskForTheStoredBytesNotACompressedForm() = runTest {
        val state = signedIn()
        server.enqueue(file(bytes))
        (downloads.load(attachment(), AttachmentVariant.ORIGINAL, state) as DownloadResult.Bytes).release()
        assertEquals("identity", take().headers["Accept-Encoding"])
        // A server that compresses anyway can't be verified against the stored file's length and hash.
        val gzipped = Buffer().write(ByteArrayOutputStream().also { out -> java.util.zip.GZIPOutputStream(out).use { it.write(bytes) } }.toByteArray())
        server.enqueue(MockResponse.Builder().code(200).addHeader("Content-Type", "image/png").addHeader("Content-Encoding", "gzip")
            .body(gzipped).build())
        assertEquals(DownloadResult.IntegrityFailed, downloads.load(attachment(), AttachmentVariant.ORIGINAL, state))
        assertEquals(0L, budget.reservedBytes)
    }

    private class Vault : SessionVault {
        var saved: SavedSession? = null
        override suspend fun read() = saved
        override suspend fun save(session: SavedSession) { saved = session }
        override suspend fun clear() { saved = null }
    }
}

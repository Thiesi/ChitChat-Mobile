package io.github.thiesi.chitchat.core.direct

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
import org.junit.*
import org.junit.Assert.*

class DirectProtocolTest {
    private lateinit var server: MockWebServer
    private lateinit var sessions: SessionRepository
    private lateinit var api: SessionHttp
    private lateinit var direct: SessionDirectRepository
    private val vault = Vault()
    private val a = "a".repeat(64); private val b = "b".repeat(64); private val c = "c".repeat(64)
    private val viewer = """{"id":7,"username":"Viewer","roles":["member"],"session_version":1,"guest":false}"""
    private val privacy = """{"direct_messages":{"end_to_end_encrypted":false,"admin_inspection_enabled":true,"admin_inspection_role":"admin","retention":"permanently","retention_days":0},"message_revisions":{"admin_review_enabled":false,"admin_review_role":"super_admin","reason_required":true,"audit_each_review":true,"participant_notification":false}}"""
    @Before fun start() {
        val cert = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val tls = HandshakeCertificates.Builder().heldCertificate(cert).build()
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        server = MockWebServer().apply { useHttps(tls.sslSocketFactory()); start() }
        val client = OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager).build()
        sessions = SessionRepository(vault) { SessionHttp(it, client).also { value -> api = value } }
        direct = SessionDirectRepository(sessions)
    }
    @After fun stop() { if (::api.isInitialized) api.close(); server.close() }
    private fun json(body: String, status: Int = 200, cookie: String? = null) = MockResponse.Builder().code(status)
        .addHeader("Content-Type", "application/json").body(body).apply { cookie?.let { addHeader("Set-Cookie", it) } }.build()
    private fun bootstrap(user: String = viewer, policy: String? = privacy): String {
        val extra = policy?.let { """, "privacy":$it""" } ?: ""
        return """{"csrf_token":"$c","user":$user,"registration_enabled":true,"sign_in_providers":[],"lockdown":{"enabled":false},"ignored_user_ids":[],"preferences":{"hour_cycle":"h23"}$extra}"""
    }
    private suspend fun login() {
        server.enqueue(json("""{"csrf_token":"$a","user":null,"registration_enabled":true,"sign_in_providers":[],"lockdown":{"enabled":false}}""",
            cookie = "SITE=anon; Secure; HttpOnly; Path=/"))
        sessions.connect((ServerAddress.parse(server.url("/").toString()) as AddressResult.Valid).address); take()
        server.enqueue(json("""{"csrf_token":"$b","user":$viewer,"mfa_required":false}""", cookie = "SITE=auth; Secure; HttpOnly; Path=/"))
        sessions.signIn("Viewer", "fixture-password"); take(); assertNull(sessions.state.value.privacy)
    }
    private suspend fun context(): DirectContext {
        server.enqueue(json(bootstrap()))
        server.enqueue(json("""{"user":{"id":8,"username":"Peer"},"relationship":{"blocked_by_me":false,"messaging_available":true}}"""))
        return direct.context(8).also { take(); take() }
    }
    private fun take() = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS))
    private fun wire(id: Long = 18, body: String = "Hello", outgoing: Boolean = false, read: String? = null,
        reply: String = "null", peer: Long = 8) =
        """{"id":$id,"sender":{"id":${if (outgoing) 7 else peer},"username":"Sender"},"recipient":{"id":${if (outgoing) peer else 7},"username":"Recipient"},"body":${JsonPrimitive(body)},"read_at":${read?.let(::JsonPrimitive) ?: JsonNull},"created_at":"2026-10-20T10:00:00Z","outgoing":$outgoing,"reply_to":$reply,"mentions":[],"reactions":[]}"""
    private fun mutation(id: Long = 18, body: String? = "Fresh", deleted: Boolean = false, owned: Boolean = false,
        mentions: String = "[]") =
        """{"id":$id,"body":${body?.let(::JsonPrimitive) ?: JsonNull},"edited_at":null,"deleted":$deleted,"can_edit":${owned && !deleted},"can_delete":${owned && !deleted},"mentions":$mentions,"reactions":[]}"""
    private fun file(id: Long = 18, hash: String = "f".repeat(64)) =
        """{"id":3,"message_id":$id,"name":"note.txt","mime_type":"text/plain","size_bytes":3,"sha256":"$hash","previewable":false}"""
    private fun page(raw: String, metadata: String, files: String = "") {
        server.enqueue(json("""{"messages":[$raw]}""")); server.enqueue(json("""{"messages":[$metadata]}"""))
        server.enqueue(json("""{"attachments":[$files]}"""))
    }

    @Test fun contextReauthorizesPrivacyAndFreshCsrfWithoutASecondSession() = runTest {
        login(); val value = context()
        assertFalse(value.privacy.direct.endToEndEncrypted); assertEquals(0, value.privacy.direct.retentionDays)
        assertEquals("admin", value.privacy.direct.inspectionRole); assertFalse(value.privacy.revisions.reviewEnabled)
        assertFalse(value.privacy.revisions.participantNotification); assertEquals("h23", value.display.hourCycle)
        server.enqueue(json("""{"updated":0}""")); assertEquals(0, direct.read(8))
        val request = take(); assertEquals("SITE=auth", request.headers["Cookie"]); assertEquals(c, request.headers["X-CSRF-Token"])
        assertEquals("""{"user_id":8}""", request.body!!.utf8())
    }

    @Test fun historyUsesActualDmMentionsAndReplyShapePlusCanonicalDeletedAndFileMetadata() = runTest {
        login(); context()
        val reply = """{"kind":"direct","message_id":9,"available":true,"message":{"id":9,"sender":{"id":8,"username":"Peer"},"body":"Edited quote","deleted":false}}"""
        page("${wire(reply = reply)},${wire(19, "Message deleted.")}",
            "${mutation(body = "Message deleted.", mentions = """[{"user_id":7,"username":"Viewer"}]""")},${mutation(19, null, true)}", file())
        val value = direct.history(8)
        assertEquals(listOf(18L, 19L), value.messages.map { it.id }); assertEquals(18L, value.olderCursor)
        val first = value.messages.first(); assertFalse(first.deleted); assertEquals("Message deleted.", first.body)
        assertEquals("Edited quote", first.replyTo?.message?.body); assertEquals(7L, first.mentions.single().userId)
        assertEquals("note.txt", first.attachment?.name); assertFalse(first.toString().contains("Message deleted."))
        assertTrue(value.messages.last().deleted); assertNull(value.messages.last().body)
        assertEquals(listOf("/api/v1/direct-messages/history.php", "/api/v1/direct-messages/message-mutations.php",
            "/api/v1/direct-messages/attachments/metadata.php"), List(3) { take().url.encodedPath })
    }

    @Test fun whollyOmittedEnrichedPageKeepsRawCursorAndPageCountEvidence() = runTest {
        login(); context(); page("${wire(10)},${wire(11)}", "")
        val value = direct.history(8, beforeId = 12, limit = 2)
        assertTrue(value.messages.isEmpty()); assertEquals(10L, value.olderCursor); assertTrue(value.mayHaveOlder)
        assertEquals("12", take().url.queryParameter("before_id")); take(); take()
        val count = server.requestCount; server.enqueue(json("""{"messages":[]}"""))
        assertNull(direct.history(8).olderCursor); assertEquals(count + 1, server.requestCount)
    }

    @Test fun inboxPreviewsAreEnrichedAndNeverGuessDeletionFromLiteralText() = runTest {
        login(); server.enqueue(json(bootstrap()))
        server.enqueue(json("""{"conversations":[{"user":{"id":8,"username":"Peer"},"last_message":{"id":18,"sender_id":7,"body":"Stale","created_at":"2026-10-20T10:00:00Z","outgoing":true},"unread_count":3}]}"""))
        server.enqueue(json("""{"messages":[${mutation(body = "Message deleted.", owned = true)}]}"""))
        server.enqueue(json("""{"attachments":[${file()}]}"""))
        val value = direct.inbox().conversations.single()
        assertEquals(3, value.unreadCount); assertEquals("Message deleted.", value.lastMessage.body)
        assertFalse(value.lastMessage.deleted); assertEquals("note.txt", value.lastMessage.attachment?.name)
        assertEquals("/api/v1/session.php", take().url.encodedPath)
        assertEquals("100", take().url.queryParameter("limit")); take(); take()
    }

    @Test fun confirmedSendReturnsItsIdWithoutFalliblePostAcknowledgmentEnrichmentOrReplay() = runTest {
        login(); context(); val body = "/me literal\nSecond line"
        server.enqueue(json("""{"message":${wire(20, body, true)}}""", 201))
        val count = server.requestCount; assertEquals(20L, direct.send(8, body))
        assertEquals(count + 1, server.requestCount); val request = take()
        val payload = apiJson.parseToJsonElement(request.body!!.utf8()).jsonObject
        assertEquals(body, payload["body"]!!.jsonPrimitive.content)
        assertEquals(8L, payload["recipient_user_id"]!!.jsonPrimitive.long); assertEquals(c, request.headers["X-CSRF-Token"])
    }

    @Test fun wholePeerReadCanAcknowledgeAnArrivalNotInTheRenderedHistory() = runTest {
        login(); context(); page(wire(), mutation()); val rendered = direct.history(8).messages.map { it.id }.toSet()
        repeat(3) { take() }; server.enqueue(json("""{"updated":2}""")); assertEquals(2, direct.read(8)); take()
        page("${wire(read = "2026-10-20T10:01:00Z")},${wire(19, read = "2026-10-20T10:01:00Z")}", "${mutation()},${mutation(19)}")
        val fresh = direct.history(8)
        assertEquals(listOf(19L), fresh.messages.filter { it.id !in rendered }.map { it.id })
        assertTrue(fresh.messages.all { it.readAt != null }) // M4 UI must preserve separate local unseen attention.
    }

    @Test fun ownBlockAndUnblockNeverInventAnOtherPersonsBlockFlag() = runTest {
        login(); context(); server.enqueue(json("""{"relationship":{"blocked_by_me":true,"messaging_available":false}}"""))
        assertTrue(direct.setBlocked(8, true).blockedByMe); assertEquals("/api/v1/direct-messages/block.php", take().url.encodedPath)
        server.enqueue(json("""{"relationship":{"blocked_by_me":false,"messaging_available":false}}"""))
        val value = direct.setBlocked(8, false); assertFalse(value.blockedByMe); assertFalse(value.messagingAvailable)
        assertEquals("/api/v1/direct-messages/unblock.php", take().url.encodedPath)
        server.enqueue(json("""{"updated":0}""")); assertEquals(0, direct.read(8)) // History/read remain available when blocked.
    }

    @Test fun missingPrivacyDisablesOnlyDmWithoutInventingDefaultsOrLoggingOut() = runTest {
        login(); server.enqueue(json(bootstrap(policy = null)))
        assertEquals(FailureKind.INCOMPATIBLE, failure { direct.context(8) }.kind); take()
        val count = server.requestCount; failure { direct.history(8) }
        assertEquals(count, server.requestCount); assertEquals(SessionPhase.AUTHENTICATED, sessions.state.value.phase)
    }

    @Test fun unsupportedE2eePolicyIsNeverPresentedAsClientEncryption() = runTest {
        login(); server.enqueue(json(bootstrap(policy = privacy.replace("\"end_to_end_encrypted\":false", "\"end_to_end_encrypted\":true"))))
        assertEquals(FailureKind.INCOMPATIBLE, failure { direct.context(8) }.kind)
        assertEquals(SessionPhase.AUTHENTICATED, sessions.state.value.phase)
    }

    @Test fun prefixAndSelfValidationNeverRequestAnInventedRouteOrEscapeTheOrigin() = runTest {
        login(); context(); val count = server.requestCount
        for (prefix in listOf("a", "bad space", "ab&host=x", "a".repeat(33))) failure { direct.search(prefix) }
        assertEquals("direct_message_self_forbidden", failure { direct.send(7, "Hello") }.code)
        assertEquals(count, server.requestCount)
        server.enqueue(json("""{"users":[{"id":8,"username":"Alex"}]}"""))
        assertEquals(8L, direct.search("Al_").single().id)
        assertEquals("Al_", take().url.queryParameter("search")) // Existing SQL LIKE underscore is not a literal-prefix filter.
    }

    @Test fun malformedPerspectiveAndUnsolicitedMutationIdsFailBeforeContentCanEscape() = runTest {
        login(); context()
        server.enqueue(json("""{"messages":[${wire(peer = 9)}]}"""))
        assertEquals(FailureKind.INCOMPATIBLE, failure { api.directHistory(8, null, 50, 7) }.kind); take()
        server.enqueue(json("""{"messages":[${wire()}]}""")); server.enqueue(json("""{"messages":[${mutation(99)}]}"""))
        val count = server.requestCount
        assertEquals(FailureKind.INCOMPATIBLE, failure { api.directHistory(8, null, 50, 7) }.kind)
        assertEquals(count + 2, server.requestCount) // No attachment request after an unsolicited metadata ID.
    }

    @Test fun unauthorizedResponseClearsPrivacyAndVaultButResourceDenialDoesNot() = runTest {
        login(); context(); server.enqueue(json("""{"error":{"code":"direct_message_unavailable","message":"Private server detail"}}""", 403))
        val denied = failure { direct.send(8, "Hello") }
        assertEquals(SessionPhase.AUTHENTICATED, sessions.state.value.phase); assertNotNull(sessions.state.value.privacy)
        assertFalse(denied.toString().contains("Private server detail")); take()
        server.enqueue(json("""{"error":{"code":"unauthenticated","message":"Private server detail"}}""", 401))
        assertEquals(401, failure { direct.read(8) }.status)
        assertEquals(SessionPhase.INVALIDATED, sessions.state.value.phase); assertNull(sessions.state.value.privacy); assertNull(vault.saved)
    }

    @Test fun logoutDuringMutationEnrichmentCannotReturnOldContentOrStartAttachmentRead() = runTest {
        login(); context(); server.enqueue(json("""{"messages":[${wire()}]}"""))
        server.enqueue(MockResponse.Builder().addHeader("Content-Type", "application/json")
            .body("""{"messages":[${mutation()}]}""").bodyDelay(400, TimeUnit.MILLISECONDS).build())
        val pending = async { runCatching { direct.history(8) } }
        withContext(Dispatchers.IO) { repeat(2) { take() } }
        val count = server.requestCount; server.enqueue(json("""{"status":"logged_out"}""")); sessions.signOut()
        assertTrue(pending.await().exceptionOrNull() is CancellationException)
        assertEquals(count + 1, server.requestCount); assertNull(sessions.state.value.privacy)
    }

    @Test fun unicodeLengthAndSlashVocabularyMatchDmNotRoomCommands() {
        assertNull(validateDirectBody("/any-command remains text")); assertEquals("empty_message", validateDirectBody("\u0000\t\r\n"))
        assertNull(validateDirectBody("😀".repeat(4_000))); assertEquals("message_too_long", validateDirectBody("😀".repeat(4_001)))
    }

    @Test fun csrfFailureRequiresExplicitSessionRefreshAndNeverReplaysTheSend() = runTest {
        login(); context(); server.enqueue(json("""{"error":{"code":"csrf_failed","message":"Private fixture"}}""", 403))
        assertEquals("csrf_failed", failure { direct.send(8, "Unknown draft") }.code); take()
        assertTrue(sessions.state.value.refreshRequired); val count = server.requestCount
        failure { direct.send(8, "Unknown draft") }; assertEquals(count, server.requestCount)
    }

    @Test fun retryAfterGatesFurtherRequestsWithoutAutomaticPosting() = runTest {
        login(); context()
        server.enqueue(MockResponse.Builder().code(429).addHeader("Content-Type", "application/json").addHeader("Retry-After", "120")
            .body("""{"error":{"code":"rate_limited","message":"Private fixture"}}""").build())
        assertEquals(120L, failure { direct.search("Al") }.retryAfterSeconds); take()
        assertTrue(sessions.state.value.retryAtMillis > System.currentTimeMillis()); val count = server.requestCount
        assertEquals(429, failure { direct.read(8) }.status); assertEquals(count, server.requestCount)
    }

    @Test fun duplicateOrDescendingHistoryAndContradictoryRelationshipAreIncompatible() = runTest {
        login(); context()
        for (rows in listOf("${wire()},${wire()}", "${wire(19)},${wire(18)}")) {
            server.enqueue(json("""{"messages":[$rows]}""")); val count = server.requestCount
            assertEquals(FailureKind.INCOMPATIBLE, failure { api.directHistory(8, null, 50, 7) }.kind)
            assertEquals(count + 1, server.requestCount); take()
        }
        server.enqueue(json("""{"user":{"id":8,"username":"Peer"},"relationship":{"blocked_by_me":true,"messaging_available":true}}"""))
        assertEquals(FailureKind.INCOMPATIBLE, failure { api.directStatus(8, 7) }.kind)
    }

    @Test fun foreignDuplicateAndMalformedAttachmentMetadataNeverBecomeCanonicalContent() = runTest {
        login(); context()
        for (files in listOf(file(99), "${file()},${file()}", file(hash = "bad"))) {
            page(wire(), mutation(), files)
            assertEquals(FailureKind.INCOMPATIBLE, failure { api.directHistory(8, null, 50, 7) }.kind)
            repeat(3) { take() }
        }
    }

    @Test fun largeEscapedUnicodeDmPageAndQuotesFitTheActualHundredMessageContract() = runTest {
        login(); context()
        val body = "😀".repeat(4_000)
        val quote = """{"kind":"direct","message_id":999,"available":true,"message":{"id":999,"sender":{"id":7,"username":"Viewer"},"body":${JsonPrimitive(body)},"deleted":false}}"""
        val rows = (1_001L..1_100L).joinToString(",") { wire(it, body, reply = quote) }.replace("😀", "\\ud83d\\ude00")
        val metadata = (1_001L..1_100L).joinToString(",") { mutation(it, body) }.replace("😀", "\\ud83d\\ude00")
        assertTrue(rows.length > 9_000_000); page(rows, metadata)
        val result = direct.history(8, limit = 100)
        assertEquals(100, result.messages.size); assertEquals(body, result.messages.last().body)
        assertEquals(body, result.messages.first().replyTo?.message?.body)
    }

    @Test fun largeEscapedUnicodeRoomPageAlsoUsesTheSharedBulkReadBound() = runTest {
        login(); val body = "😀".repeat(1_000)
        val rows = (1L..100L).joinToString(",") { id ->
            """{"id":$id,"room_id":42,"sender_id":8,"username":"Peer","type":"text","body":${JsonPrimitive(body)},"deleted":false,"created_at":"2026-10-20T10:00:00Z"}"""
        }.replace("😀", "\\ud83d\\ude00")
        val metadata = (1L..100L).joinToString(",") { id ->
            """{"id":$id,"type":"text","body":${JsonPrimitive(body)},"edited_at":null,"deleted":false,"deletion_kind":null,"mentions":[],"reactions":[]}"""
        }.replace("😀", "\\ud83d\\ude00")
        server.enqueue(json("""{"messages":[$rows]}""")); server.enqueue(json("""{"messages":[$metadata]}"""))
        val result = api.roomHistory(42, null, 100)
        assertEquals(100, result.messages.size); assertEquals(body, result.messages.last().body)
    }

    @Test fun oversizedBulkReadIsBoundedAndDoesNotInvalidateTheSharedSession() = runTest {
        login(); context()
        server.enqueue(json(" ".repeat(SessionHttp.BULK_MESSAGE_MAX_BYTES.toInt() + 1)))
        assertEquals(FailureKind.TOO_LARGE, failure { direct.history(8) }.kind); take()
        assertEquals(SessionPhase.AUTHENTICATED, sessions.state.value.phase)
        assertNotNull(sessions.state.value.privacy); assertNotNull(vault.saved)
    }

    @Test fun authenticationJsonStillHasTheOneMiBResponseBound() = runTest {
        login()
        server.enqueue(json(bootstrap().dropLast(1) + ",\"extra\":\"" + "a".repeat(1_048_576) + "\"}"))
        assertEquals(FailureKind.INCOMPATIBLE, failure { api.bootstrap() }.kind); take()
    }

    @Test fun bulkErrorResponsesStillHaveTheOneMiBResponseBound() = runTest {
        login(); context()
        server.enqueue(json(" ".repeat(1_048_577), 403))
        assertEquals(FailureKind.INCOMPATIBLE, failure { api.directHistory(8, null, 50, 7) }.kind); take()
    }

    @Test fun cancelledPolicyBootstrapReleasesBusyAndRequiresFreshCsrfBeforeFurtherAccess() = runTest {
        login()
        server.enqueue(MockResponse.Builder().addHeader("Content-Type", "application/json")
            .body(bootstrap()).bodyDelay(500, TimeUnit.MILLISECONDS).build())
        val pending = async { direct.context(8) }
        withContext(Dispatchers.IO) { take() }
        assertTrue(sessions.state.value.busy)
        pending.cancelAndJoin()
        assertFalse(sessions.state.value.busy)
        assertTrue(sessions.state.value.refreshRequired)
        assertEquals(SessionPhase.AUTHENTICATED, sessions.state.value.phase)
        val count = server.requestCount
        failure { direct.history(8) }; assertEquals(count, server.requestCount)
        server.enqueue(json(bootstrap())); sessions.refresh(); take()
        assertFalse(sessions.state.value.busy); assertFalse(sessions.state.value.refreshRequired)
        server.enqueue(json("""{"messages":[]}""")); assertTrue(direct.history(8).messages.isEmpty())
        assertEquals("SITE=auth", take().headers["Cookie"])
    }

    @Test fun cancelledInitialBootstrapCanBeRevalidatedInsteadOfStayingConnecting() = runTest {
        server.enqueue(MockResponse.Builder().addHeader("Content-Type", "application/json")
            .body(bootstrap(user = "null")).bodyDelay(500, TimeUnit.MILLISECONDS).build())
        val pending = async { sessions.connect((ServerAddress.parse(server.url("/").toString()) as AddressResult.Valid).address) }
        withContext(Dispatchers.IO) { take() }; pending.cancelAndJoin()
        assertFalse(sessions.state.value.busy); assertEquals(SessionPhase.INVALIDATED, sessions.state.value.phase)
        server.enqueue(json(bootstrap(user = "null"))); sessions.refresh(); take()
        assertEquals(SessionPhase.ANONYMOUS, sessions.state.value.phase); assertFalse(sessions.state.value.refreshRequired)
    }

    @Test fun cancelledOldBootstrapCannotChangeTheSignedOutGeneration() = runTest {
        login()
        server.enqueue(MockResponse.Builder().addHeader("Content-Type", "application/json")
            .body(bootstrap()).bodyDelay(500, TimeUnit.MILLISECONDS).build())
        val pending = async { direct.context(8) }; withContext(Dispatchers.IO) { take() }
        server.enqueue(json("""{"status":"logged_out"}""")); sessions.signOut()
        pending.cancelAndJoin()
        assertEquals(SessionPhase.SIGNED_OUT, sessions.state.value.phase); assertFalse(sessions.state.value.busy)
        assertFalse(sessions.state.value.refreshRequired); assertNull(sessions.state.value.privacy); assertNull(vault.saved)
    }

    private suspend fun failure(action: suspend () -> Unit): ApiFailure {
        try { action(); error("Expected safe failure") } catch (failure: ApiFailure) { return failure }
    }
    private class Vault : SessionVault {
        var saved: SavedSession? = null
        override suspend fun read() = saved
        override suspend fun save(session: SavedSession) { saved = session }
        override suspend fun clear() { saved = null }
    }
}

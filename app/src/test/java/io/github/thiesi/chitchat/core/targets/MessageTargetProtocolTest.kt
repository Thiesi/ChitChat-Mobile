package io.github.thiesi.chitchat.core.targets

import io.github.thiesi.chitchat.core.conversation.*
import io.github.thiesi.chitchat.core.network.*
import io.github.thiesi.chitchat.core.rooms.RoomInfo
import io.github.thiesi.chitchat.core.server.AddressResult
import io.github.thiesi.chitchat.core.server.ServerAddress
import io.github.thiesi.chitchat.core.session.*
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Real production TLS/session/history enrichment. Every target lookup request is an existing GET. */
class MessageTargetProtocolTest {
    private lateinit var server: MockWebServer
    private lateinit var sessions: SessionRepository
    private lateinit var repository: SessionMessageTargetRepository
    private val apis = mutableListOf<SessionHttp>()
    private val vault = Vault()
    private val a = "a".repeat(64); private val b = "b".repeat(64); private val c = "c".repeat(64)
    private val user = """{"id":7,"username":"Viewer","roles":["member"],"session_version":1,"guest":false}"""
    private val privacy = """{"direct_messages":{"end_to_end_encrypted":false,"admin_inspection_enabled":true,"admin_inspection_role":"admin","retention":"permanently","retention_days":0},"message_revisions":{"admin_review_enabled":false,"admin_review_role":"super_admin","reason_required":true,"audit_each_review":true,"participant_notification":false}}"""
    private val room = RoomInfo(42, "room42", "Room fixture", "", "public", 0, 0, 9, "member", false, "none", 2, 1)
    @Before fun start() {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val tls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        server = MockWebServer().apply { useHttps(tls.sslSocketFactory()); start() }
        val client = OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager).build()
        sessions = SessionRepository(vault) { SessionHttp(it, client).also(apis::add) }
        repository = SessionMessageTargetRepository(sessions)
    }
    @After fun stop() { apis.forEach(SessionHttp::close); server.close() }
    private fun json(body: String, status: Int = 200, delay: Long = 0, cookie: String? = null) = MockResponse.Builder()
        .code(status).addHeader("Content-Type", "application/json").body(body).bodyDelay(delay, TimeUnit.MILLISECONDS)
        .apply { cookie?.let { addHeader("Set-Cookie", it) } }.build()
    private fun take() = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS))
    private suspend fun login(): SessionState {
        server.enqueue(json("""{"csrf_token":"$a","user":null,"registration_enabled":false,"sign_in_providers":[],"lockdown":{"enabled":false}}""",
            cookie = "SITE=anon; Secure; HttpOnly; Path=/"))
        sessions.connect((ServerAddress.parse(server.url("/").toString()) as AddressResult.Valid).address); take()
        server.enqueue(json("""{"csrf_token":"$b","user":$user,"mfa_required":false}""", cookie = "SITE=auth; Secure; HttpOnly; Path=/"))
        sessions.signIn("Viewer", "fixture-password"); take(); return sessions.state.value
    }
    private fun bootstrap(disclosure: String? = privacy, ignored: String? = "[]") =
        """{"csrf_token":"$c","user":$user,"registration_enabled":false,"sign_in_providers":[],"lockdown":{"enabled":false},"preferences":{"hour_cycle":"h23"}${ignored?.let { ",\"ignored_user_ids\":$it" } ?: ""}${disclosure?.let { ",\"privacy\":$it" } ?: ""}}"""
    private fun rows(vararg ids: Long, roomId: Long = 42) = apiJson.encodeToString(MessagesReply(ids.map {
        RoomMessage(it, roomId, 8, "Peer", "text", "Stale raw fixture", false, "2026-10-20T12:00:00Z")
    }))
    private fun mutations(vararg ids: Long, deleted: Boolean = false) = apiJson.encodeToString(MutationsReply(ids.map {
        MessageMutation(it, if (deleted) null else "Fresh fixture $it", "text", null, deleted,
            if (deleted) "moderator" else null, emptyList(), listOf(MessageReaction("👍", listOf(MessagePerson(7, "Viewer")), true)))
    }))
    private fun pings(vararg ids: Long) = apiJson.encodeToString(PingsReply(ids.map {
        RoomPing(it, 42, MessagePerson(8, "Peer"), MessagePerson(7, "Viewer"), "Private ping fixture", "2026-10-20T12:00:00Z")
    }))
    private fun roomContext(disclosure: String? = privacy, ignored: String? = "[]", listed: Boolean = true) {
        server.enqueue(json(bootstrap(disclosure, ignored)))
        server.enqueue(json("""{"rooms":[${if (listed) apiJson.encodeToString(room) else ""}]}"""))
        if (!listed) server.enqueue(json("""{"room":${apiJson.encodeToString(room)}}"""))
    }
    private fun roomPage(raw: String = rows(17, 18), metadata: String = mutations(17, 18), pingRows: String = pings()) {
        server.enqueue(json(raw)); server.enqueue(json(metadata)); server.enqueue(json(pingRows))
    }
    private fun directStatus(peer: Long = 8, available: Boolean = true) =
        """{"user":{"id":$peer,"username":"Peer"},"relationship":{"blocked_by_me":false,"messaging_available":$available}}"""
    private fun directRows(vararg ids: Long) = """{"messages":[${ids.joinToString(",") {
        """{"id":$it,"sender":{"id":8,"username":"Peer"},"recipient":{"id":7,"username":"Viewer"},"body":"Stale raw fixture","read_at":null,"created_at":"2026-10-20T12:00:00Z","outgoing":false,"reply_to":null,"mentions":[],"reactions":[]}"""
    }}]}"""
    private fun directMutations(vararg ids: Long) = """{"messages":[${ids.joinToString(",") {
        """{"id":$it,"body":"Fresh direct fixture $it","edited_at":null,"deleted":false,"can_edit":false,"can_delete":false,"mentions":[],"reactions":[]}"""
    }}]}"""
    private fun directContext(disclosure: String? = privacy, ignored: String = "[]", available: Boolean = true) {
        server.enqueue(json(bootstrap(disclosure, ignored))); server.enqueue(json(directStatus(available = available)))
    }
    private fun directPage(raw: String = directRows(17, 18), metadata: String = directMutations(17, 18)) {
        server.enqueue(json(raw)); server.enqueue(json(metadata)); server.enqueue(json("""{"attachments":[]}"""))
    }
    private suspend fun failure(action: suspend () -> Unit): ApiFailure {
        try { action(); error("Expected typed failure") } catch (failure: ApiFailure) { return failure }
    }
    private fun assertGets(count: Int) = List(count) { take().also { request ->
        assertEquals("GET", request.method); assertEquals("SITE=auth", request.headers["Cookie"]); assertNull(request.headers["X-CSRF-Token"])
    } }

    @Test fun roomTargetUsesOneBoundedHistoryPageAndCanonicalMetadataWithoutAnyReadOrPresenceWrite() = runTest {
        val request = TargetRequest(login(), MessageTarget.RoomMessage(42, 18)); roomContext(); roomPage()
        val result = repository.lookup(request) as TargetSnapshot.RoomMessage
        assertTrue(result.available); assertSame(request, result.request); assertEquals("Fresh fixture 18", result.page.messages.last().body)
        assertEquals(17L, result.page.olderCursor); assertFalse(result.page.mayHaveOlder); assertEquals("h23", result.context.policy.display.hourCycle)
        val calls = assertGets(5)
        assertEquals(listOf("/api/v1/session.php", "/api/v1/rooms/list.php", "/api/v1/rooms/messages.php",
            "/api/v1/rooms/message-mutations.php", "/api/v1/rooms/pings.php"), calls.map { it.url.encodedPath })
        assertEquals("19", calls[2].url.queryParameter("before_id")); assertEquals("50", calls[2].url.queryParameter("limit"))
    }
    @Test fun nearestEarlierRowsNeverPretendTheRequestedMissingMessageWasFound() = runTest {
        val request = TargetRequest(login(), MessageTarget.RoomMessage(42, 18)); roomContext(); roomPage(rows(17), mutations(17))
        val result = repository.lookup(request) as TargetSnapshot.RoomMessage
        assertFalse(result.available); assertEquals(listOf(17L), result.page.messages.map { it.id }); assertGets(5)
    }
    @Test fun omittedTargetMutationKeepsRawBoundaryButCannotExposeTheStaleUnenrichedBody() = runTest {
        val request = TargetRequest(login(), MessageTarget.RoomMessage(42, 18)); roomContext(); roomPage(metadata = mutations(17))
        val result = repository.lookup(request) as TargetSnapshot.RoomMessage
        assertFalse(result.available); assertEquals(17L, result.page.olderCursor)
        assertTrue(result.page.messages.none { it.id == 18L || it.body == "Stale raw fixture" }); assertGets(5)
    }
    @Test fun deletedTargetRemainsItsExactTombstoneWithPreservedReactionMetadata() = runTest {
        val request = TargetRequest(login(), MessageTarget.RoomMessage(42, 18)); roomContext(); roomPage(rows(18), mutations(18, deleted = true))
        val result = repository.lookup(request) as TargetSnapshot.RoomMessage
        assertTrue(result.available); val row = result.page.messages.single(); assertTrue(row.deleted); assertNull(row.body)
        assertEquals("moderator", row.deletionKind); assertEquals("👍", row.reactions.single().emoji); assertGets(5)
    }
    @Test fun maximumTargetOmitsBeforeInsteadOfOverflowingAndDoesNotScanFurtherPages() = runTest {
        val request = TargetRequest(login(), MessageTarget.RoomMessage(42, Long.MAX_VALUE)); roomContext()
        roomPage(rows(Long.MAX_VALUE), mutations(Long.MAX_VALUE))
        val result = repository.lookup(request); assertTrue(result.available)
        val calls = assertGets(5); assertNull(calls[2].url.queryParameter("before_id")); assertEquals(7, server.requestCount)
    }
    @Test fun roomNotInDirectoryUsesAuthorizedExistingDetailWithoutJoining() = runTest {
        val request = TargetRequest(login(), MessageTarget.RoomMessage(42, 18)); roomContext(listed = false); roomPage()
        assertTrue(repository.lookup(request).available)
        val calls = assertGets(6); assertEquals("/api/v1/rooms/detail.php", calls[2].url.encodedPath)
        assertEquals("42", calls[2].url.queryParameter("room_id"))
    }
    @Test fun wrongRoomMessageNamespaceIsIncompatibleBeforeMetadataOrPingCalls() = runTest {
        val request = TargetRequest(login(), MessageTarget.RoomMessage(42, 18)); roomContext(); server.enqueue(json(rows(18, roomId = 43)))
        assertEquals(FailureKind.INCOMPATIBLE, failure { repository.lookup(request) }.kind); assertGets(3); assertEquals(5, server.requestCount)
    }
    @Test fun unknownRoomPolicyFailsBeforeFetchingAnyConversationContent() = runTest {
        val request = TargetRequest(login(), MessageTarget.RoomMessage(42, 18)); server.enqueue(json(bootstrap(ignored = null)))
        assertEquals(FailureKind.INCOMPATIBLE, failure { repository.lookup(request) }.kind); assertGets(1); assertEquals(3, server.requestCount)
    }
    @Test fun privatePingUsesOnlyExistingBoundedPingEndpointAndHonorsIgnorePolicy() = runTest {
        val original = login(); val request = TargetRequest(original, MessageTarget.RoomPing(42, 5))
        roomContext(); server.enqueue(json(pings(5)))
        val visible = repository.lookup(request) as TargetSnapshot.RoomPing; assertTrue(visible.available); assertGets(3)
        roomContext(ignored = "[8]"); server.enqueue(json(pings(5)))
        val ignored = repository.lookup(request) as TargetSnapshot.RoomPing; assertFalse(ignored.available); assertTrue(ignored.pings.isEmpty())
        assertGets(3); assertEquals(8, server.requestCount) // two login + two three-GET lookups, no history/write.
    }
    @Test fun missingPrivatePingDoesNotInventAnOlderLookupOrTreatAnotherPingAsTheTarget() = runTest {
        val request = TargetRequest(login(), MessageTarget.RoomPing(42, 200)); roomContext(); server.enqueue(json(pings(5)))
        assertFalse(repository.lookup(request).available); val calls = assertGets(3)
        assertEquals("/api/v1/rooms/pings.php", calls.last().url.encodedPath); assertEquals(5, server.requestCount)
    }
    @Test fun directTargetIncludesMandatoryDisclosureAndCanonicalEnrichmentButNeverWholePeerRead() = runTest {
        val request = TargetRequest(login(), MessageTarget.DirectMessage(8, 18)); directContext(ignored = "[8]", available = false); directPage()
        val result = repository.lookup(request) as TargetSnapshot.DirectMessage
        assertTrue(result.available); assertFalse(result.context.privacy.direct.endToEndEncrypted)
        assertFalse(result.context.relationship.messagingAvailable); assertFalse(result.context.relationship.blockedByMe)
        assertEquals("Fresh direct fixture 18", result.page.messages.last().body)
        val calls = assertGets(5); assertEquals("19", calls[2].url.queryParameter("before_id")); assertEquals("50", calls[2].url.queryParameter("limit"))
        assertEquals(listOf("/api/v1/session.php", "/api/v1/direct-messages/block-status.php", "/api/v1/direct-messages/history.php",
            "/api/v1/direct-messages/message-mutations.php", "/api/v1/direct-messages/attachments/metadata.php"), calls.map { it.url.encodedPath })
    }
    @Test fun omittedDirectMutationDoesNotReturnAnUnverifiedTarget() = runTest {
        val request = TargetRequest(login(), MessageTarget.DirectMessage(8, 18)); directContext(); directPage(metadata = directMutations(17))
        val result = repository.lookup(request) as TargetSnapshot.DirectMessage
        assertFalse(result.available); assertEquals(17L, result.page.olderCursor); assertGets(5)
    }
    @Test fun missingOrUnsupportedDirectDisclosureFailsBeforeAnyDmEndpoint() = runTest {
        val request = TargetRequest(login(), MessageTarget.DirectMessage(8, 18))
        for (disclosure in listOf(null, privacy.replace("\"end_to_end_encrypted\":false", "\"end_to_end_encrypted\":true"))) {
            server.enqueue(json(bootstrap(disclosure)))
            assertEquals(FailureKind.INCOMPATIBLE, failure { repository.lookup(request) }.kind); assertGets(1)
        }
        assertEquals(4, server.requestCount)
    }
    @Test fun wrongPeerStatusCannotSelectAnotherConversation() = runTest {
        val request = TargetRequest(login(), MessageTarget.DirectMessage(8, 18))
        server.enqueue(json(bootstrap())); server.enqueue(json(directStatus(peer = 9)))
        assertEquals(FailureKind.INCOMPATIBLE, failure { repository.lookup(request) }.kind); assertGets(2)
    }
    @Test fun invalidOrSelfSelectorsAndOldSessionRequestsNeverStartHttp() = runTest {
        val original = login(); val count = server.requestCount
        for (target in listOf(MessageTarget.RoomMessage(0, 18), MessageTarget.RoomPing(42, 0), MessageTarget.DirectMessage(7, 18)))
            assertEquals(400, failure { repository.lookup(TargetRequest(original, target)) }.status)
        assertEquals(count, server.requestCount)
        server.enqueue(json("""{"status":"logged_out"}""")); sessions.signOut(); take()
        try { repository.lookup(TargetRequest(original, MessageTarget.RoomMessage(42, 18))); fail("Retired identity") } catch (_: CancellationException) { }
        assertEquals(count + 1, server.requestCount)
    }
    @Test fun deniedHistoryIsAnExplicitPermissionFailureNotANewestWindowFallback() = runTest {
        val request = TargetRequest(login(), MessageTarget.RoomMessage(42, 18)); roomContext()
        server.enqueue(json("""{"error":{"code":"room_access_denied","message":"Fixture"}}""", 403))
        val error = failure { repository.lookup(request) }; assertEquals(403, error.status); assertEquals("room_access_denied", error.code)
        assertGets(3); assertEquals(5, server.requestCount)
    }
    @Test fun logoutDuringMetadataEnrichmentRetiresTheWholeOriginalLookup() = runTest {
        val request = TargetRequest(login(), MessageTarget.RoomMessage(42, 18)); roomContext()
        server.enqueue(json(rows(17, 18))); server.enqueue(json(mutations(17, 18), delay = 500))
        val pending = async { runCatching { repository.lookup(request) } }
        withContext(Dispatchers.IO) { assertGets(4) }
        server.enqueue(json("""{"status":"logged_out"}""")); sessions.signOut(); take()
        assertTrue(pending.await().exceptionOrNull() is CancellationException)
        assertEquals(SessionPhase.SIGNED_OUT, sessions.state.value.phase); assertNull(vault.saved); assertEquals(7, server.requestCount)
    }
    @Test fun cancelledBootstrapReleasesBusyAndExplicitSessionGetCanRecoverWithoutAWrite() = runTest {
        val request = TargetRequest(login(), MessageTarget.RoomMessage(42, 18))
        server.enqueue(json(bootstrap(), delay = 500)); val pending = async { repository.lookup(request) }
        withContext(Dispatchers.IO) { take() }; pending.cancelAndJoin()
        assertFalse(sessions.state.value.busy); assertTrue(sessions.state.value.refreshRequired)
        server.enqueue(json(bootstrap())); sessions.refresh(); take()
        roomContext(); roomPage(); assertTrue(repository.lookup(request).available); assertGets(5)
    }
    @Test fun confirmedIgnoreDuringTheLastGetCannotBeOverwrittenByTheOlderLookup() = runTest {
        val request = TargetRequest(login(), MessageTarget.RoomMessage(42, 18)); roomContext()
        server.enqueue(json(rows(17, 18))); server.enqueue(json(mutations(17, 18))); server.enqueue(json(pings(5), delay = 500))
        val pending = async { repository.lookup(request) }; withContext(Dispatchers.IO) { assertGets(5) }
        server.enqueue(json("""{"ignored_user_ids":[8]}""")); sessions.setIgnored(8, true, sessions.state.value)
        assertEquals("/api/v1/users/ignore.php", take().url.encodedPath) // Explicit independent fixture policy write.
        val result = pending.await() as TargetSnapshot.RoomMessage
        assertEquals(setOf(8L), result.context.policy.ignoredUserIds); assertTrue(result.pings.isEmpty())
    }
    private class Vault : SessionVault {
        var saved: SavedSession? = null
        override suspend fun read() = saved
        override suspend fun save(session: SavedSession) { saved = session }
        override suspend fun clear() { saved = null }
    }
}

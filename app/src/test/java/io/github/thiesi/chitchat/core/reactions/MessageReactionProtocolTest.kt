package io.github.thiesi.chitchat.core.reactions

import io.github.thiesi.chitchat.core.conversation.*
import io.github.thiesi.chitchat.core.direct.*
import io.github.thiesi.chitchat.core.network.*
import io.github.thiesi.chitchat.core.server.*
import io.github.thiesi.chitchat.core.session.*
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.*
import org.junit.Assert.*

/** Actual session/TLS/HTTP with isolated server-shaped fixtures, never a live account. */
class MessageReactionProtocolTest {
    private lateinit var server: MockWebServer
    private lateinit var sessions: SessionRepository
    private lateinit var repository: SessionMessageReactionRepository
    private lateinit var api: SessionHttp
    private val clients = mutableListOf<SessionHttp>()
    private val vault = Vault()
    private val csrf = "b".repeat(64)
    private val privacy = """{"direct_messages":{"end_to_end_encrypted":false,"admin_inspection_enabled":false,"admin_inspection_role":"admin","retention":"permanently","retention_days":0},"message_revisions":{"admin_review_enabled":false,"admin_review_role":"super_admin","reason_required":true,"audit_each_review":true,"participant_notification":false}}"""

    @Before fun start() {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val tls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        server = MockWebServer().apply { useHttps(tls.sslSocketFactory()); start() }
        val client = OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager).build()
        sessions = SessionRepository(vault) { SessionHttp(it, client).also { value -> api = value; clients += value } }
        repository = SessionMessageReactionRepository(sessions)
    }
    @After fun stop() { clients.forEach(SessionHttp::close); server.close() }
    private fun json(body: String, status: Int = 200, delay: Long = 0, cookie: String? = null) = MockResponse.Builder()
        .code(status).addHeader("Content-Type", "application/json").body(body).bodyDelay(delay, TimeUnit.MILLISECONDS)
        .apply { cookie?.let { addHeader("Set-Cookie", it) } }.build()
    private fun take() = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS))
    private suspend fun login(id: Long = 7, withPrivacy: Boolean = true, muted: Boolean = false): SessionState {
        server.enqueue(json("""{"csrf_token":"${"a".repeat(64)}","user":null,"registration_enabled":false,"sign_in_providers":[],"lockdown":{"enabled":false}}"""))
        sessions.connect((ServerAddress.parse(server.url("/").toString()) as AddressResult.Valid).address); take()
        val user = """{"id":$id,"username":"Viewer","roles":["member"],"session_version":1,"guest":false}"""
        server.enqueue(json("""{"csrf_token":"$csrf","user":$user,"mfa_required":false}""",
            cookie = "SITE=auth$id; Secure; HttpOnly; Path=/"))
        sessions.signIn("Viewer", "fixture-password"); take()
        if (withPrivacy) {
            val mute = if (muted) """{"id":90,"scope":"everywhere","room_id":null,"expires_at":null,"reason":"Fixture"}""" else "null"
            server.enqueue(json("""{"csrf_token":"$csrf","user":$user,"registration_enabled":false,"sign_in_providers":[],"lockdown":{"enabled":false},"privacy":$privacy,"muted_everywhere":$mute}"""))
            sessions.refresh(); take()
        }
        return sessions.state.value
    }
    private suspend fun logout() { server.enqueue(json("""{"status":"logged_out"}""")); sessions.signOut(); take() }
    private fun room(id: Long = 80, roomId: Long = 42, sender: Long? = 8, deleted: Boolean = false,
        type: String = "text", reactions: List<MessageReaction> = emptyList()) =
        RoomMessage(id, roomId, sender, "Peer", type, if (deleted) null else "Private original", deleted,
            "2026-10-20T10:00:00Z", reactions = reactions)
    private fun direct(id: Long = 80, sender: Long = 8, recipient: Long = 7, outgoing: Boolean = false,
        deleted: Boolean = false) = DirectMessage(id, MessagePerson(sender, "Peer"), MessagePerson(recipient, "Viewer"),
            if (deleted) null else "Private original", null, "2026-10-20T10:00:00Z", outgoing, deleted,
            null, false, false, null, emptyList(), emptyList(), null)
    private fun summary(emoji: String = ReactionEmoji.THUMBS_UP.value, ids: List<Long> = listOf(7, 8),
        flag: Boolean = false): String {
        val users = ids.joinToString(",") { """{"id":$it,"username":"Reactor$it"}""" }
        return """{"reactions":[{"emoji":${JsonPrimitive(emoji)},"users":[$users],"reacted_by_me":$flag}]}"""
    }
    private suspend fun failure(action: suspend () -> Unit): ApiFailure = try { action(); error("Expected typed error") }
        catch (failure: ApiFailure) { failure }
    private suspend fun invalid(action: suspend () -> Unit) {
        try { action(); fail("Invalid original accepted") } catch (_: IllegalArgumentException) { }
    }
    private fun assertWrite(path: String, emoji: ReactionEmoji = ReactionEmoji.THUMBS_UP) {
        val request = take()
        assertEquals("POST", request.method); assertEquals("/api/v1/$path", request.url.encodedPath)
        assertEquals(csrf, request.headers["X-CSRF-Token"]); assertEquals("SITE=auth7", request.headers["Cookie"])
        assertEquals("""{"message_id":80,"emoji":${JsonPrimitive(emoji.value)}}""", request.body!!.utf8())
    }

    @Test fun vocabularyKeepsTheExactSixChoicesAndRejectsBareHeartNormalizationOrWhitespace() {
        val values = listOf("\uD83D\uDC4D", "\u2764\uFE0F", "\uD83D\uDE02", "\uD83D\uDE2E", "\uD83D\uDE22", "\uD83C\uDF89")
        assertEquals(values, ReactionEmoji.entries.map { it.value })
        for (value in values) assertEquals(value, ReactionEmoji.fromValue(value)?.value)
        for (value in listOf("", "\u2764", "\uD83D\uDE21", " ${values.first()}", "${values.first()} "))
            assertNull(ReactionEmoji.fromValue(value))
    }
    @Test fun roomAddUsesExistingSessionAndOnlyMessageIdAndEmojiWithoutAnotherGet() = runTest {
        val expected = login(); server.enqueue(json(summary()))
        val result = repository.setRoom(expected, room(), ReactionEmoji.THUMBS_UP, true)
        assertEquals(1, result.size); assertEquals(listOf(7L, 8L), result.single().users.map { it.id })
        assertTrue(result.single().reactedByMe)
        assertWrite("rooms/react.php"); assertEquals(4, server.requestCount)
    }
    @Test fun heartPreservesVariationSelectorOnTheWire() = runTest {
        val expected = login(); server.enqueue(json(summary(ReactionEmoji.HEART.value)))
        val result = repository.setRoom(expected, room(), ReactionEmoji.HEART, true)
        assertEquals(ReactionEmoji.HEART.value, result.single().emoji)
        assertWrite("rooms/react.php", ReactionEmoji.HEART)
    }
    @Test fun roomRemoveCanUseAnotherAuthorsDeletedMessageWithoutAnOldSelectionOrAuthorHint() = runTest {
        val expected = login(); val original = room(deleted = true); server.enqueue(json(summary(ids = listOf(8))))
        val result = repository.setRoom(expected, original, ReactionEmoji.THUMBS_UP, false)
        assertFalse(result.single().reactedByMe); assertEquals(8L, result.single().users.single().id)
        assertNull(original.body); assertTrue(original.deleted)
        assertWrite("rooms/unreact.php"); assertEquals(4, server.requestCount)
    }
    @Test fun roomReactionIsNotAnAuthorMutationAndDoesNotInventASystemMessageRestriction() = runTest {
        val expected = login(); server.enqueue(json(summary()))
        repository.setRoom(expected, room(sender = null, type = "system"), ReactionEmoji.THUMBS_UP, true)
        assertWrite("rooms/react.php"); assertEquals(4, server.requestCount)
    }
    @Test fun incomingAndOutgoingDirectMessagesUseParticipantScopeNotAuthorOrSendPermissions() = runTest {
        val expected = login()
        for (original in listOf(direct(), direct(sender = 7, recipient = 8, outgoing = true))) {
            assertFalse(original.canEdit); assertFalse(original.canDelete)
            server.enqueue(json(summary()))
            assertTrue(repository.setDirect(expected, original, ReactionEmoji.THUMBS_UP, true).single().reactedByMe)
            assertWrite("direct-messages/react.php")
        }
        assertEquals(5, server.requestCount) // No relationship/author-hint GET or send endpoint.
    }
    @Test fun directRemoveUsesExplicitEndpointEvenWhenAlreadyAbsentOrDeleted() = runTest {
        val expected = login()
        repeat(2) {
            server.enqueue(json("""{"reactions":[]}"""))
            assertTrue(repository.setDirect(expected, direct(deleted = true), ReactionEmoji.THUMBS_UP, false).isEmpty())
            assertWrite("direct-messages/unreact.php")
        }
        assertEquals(5, server.requestCount)
    }
    @Test fun requestedDirectionIsExplicitInsteadOfTogglingTheOldActorFlag() = runTest {
        val expected = login()
        val old = room(reactions = listOf(MessageReaction(ReactionEmoji.THUMBS_UP.value, listOf(MessagePerson(7, "Viewer")), true)))
        server.enqueue(json(summary())); repository.setRoom(expected, old, ReactionEmoji.THUMBS_UP, true)
        assertWrite("rooms/react.php")
        server.enqueue(json("""{"reactions":[]}""")); repository.setRoom(expected, room(), ReactionEmoji.THUMBS_UP, false)
        assertWrite("rooms/unreact.php"); assertEquals(5, server.requestCount)
    }
    @Test fun selectionComesFromViewerIdsRatherThanTheSharedActorFlag() = runTest {
        val expected = login(); server.enqueue(json(summary(ids = listOf(8), flag = true)))
        val result = repository.setRoom(expected, room(), ReactionEmoji.THUMBS_UP, true).single()
        assertFalse(result.reactedByMe); assertFalse(result.selectedBy(7)); assertTrue(result.selectedBy(8))
        assertFalse(result.selectedBy(0)); assertWrite("rooms/react.php")
    }
    @Test fun aConcurrentChoiceInTheReturnedSummaryDoesNotBecomeAnInventedOptimisticResult() = runTest {
        val expected = login(); server.enqueue(json("""{"reactions":[]}"""))
        assertTrue(repository.setRoom(expected, room(), ReactionEmoji.THUMBS_UP, true).isEmpty()); assertWrite("rooms/react.php")
        server.enqueue(json(summary()))
        assertTrue(repository.setDirect(expected, direct(), ReactionEmoji.THUMBS_UP, false).single().reactedByMe)
        assertWrite("direct-messages/unreact.php")
    }
    @Test fun additionsOnKnownTombstonesFailLocallyWithoutRemovingExistingReactionMetadata() = runTest {
        val expected = login(); val count = server.requestCount
        invalid { repository.setRoom(expected, room(deleted = true), ReactionEmoji.THUMBS_UP, true) }
        invalid { repository.setDirect(expected, direct(deleted = true), ReactionEmoji.THUMBS_UP, true) }
        assertEquals(count, server.requestCount)
    }
    @Test fun invalidRoomAndDirectIdentityOrPerspectiveNeverReachHttp() = runTest {
        val expected = login(); val count = server.requestCount
        for (original in listOf(room(id = 0), room(roomId = -1)))
            invalid { repository.setRoom(expected, original, ReactionEmoji.THUMBS_UP, false) }
        for (original in listOf(direct(id = -1), direct(sender = 0), direct(recipient = 0),
            direct(sender = 8, recipient = 9), direct(sender = 7, recipient = 7), direct(outgoing = true)))
            invalid { repository.setDirect(expected, original, ReactionEmoji.THUMBS_UP, false) }
        assertEquals(count, server.requestCount)
    }
    @Test fun malformedOrDuplicateReactionGroupsAndReactorsFailClosedWithoutReplay() = runTest {
        login(); val valid = summary(); val count = server.requestCount
        val duplicate = valid.substringAfter("[{").substringBeforeLast("}]")
        val bad = listOf("{}", """{"reactions":null}""", """{"reactions":{}}""",
            summary("\u2764"), summary("future"), summary(ids = emptyList()), summary(ids = listOf(7, 7)),
            summary(ids = listOf(0)), valid.replace("\"username\":\"Reactor7\"", "\"username\":null"),
            valid.replace("\"username\":\"Reactor7\"", "\"username\":\"\""),
            valid.replace("\"reacted_by_me\":false", "\"reacted_by_me\":\"false\""),
            valid.replace(",\"reacted_by_me\":false", ""), """{"reactions":[{$duplicate},{$duplicate}]}""")
        for (body in bad) {
            server.enqueue(json(body))
            assertEquals(FailureKind.INCOMPATIBLE, failure { api.setRoomReaction(room(), ReactionEmoji.THUMBS_UP, true, csrf, 7) }.kind)
            take()
        }
        server.enqueue(json(valid, 201))
        assertEquals(FailureKind.INCOMPATIBLE, failure { api.setDirectReaction(direct(), ReactionEmoji.THUMBS_UP, true, csrf, 7) }.kind)
        take(); assertEquals(count + bad.size + 1, server.requestCount)
    }
    @Test fun globallyMutedRemovalIsNotMistakenForSendingAndAddRefusalsStayServerOwned() = runTest {
        val expected = login(muted = true); server.enqueue(json("""{"reactions":[]}"""))
        repository.setDirect(expected, direct(deleted = true), ReactionEmoji.THUMBS_UP, false)
        assertWrite("direct-messages/unreact.php")
        server.enqueue(json("""{"error":{"code":"muted","message":"Fixture"}}""", 403))
        assertEquals("muted", failure { repository.setRoom(expected, room(), ReactionEmoji.THUMBS_UP, true) }.code)
        assertWrite("rooms/react.php"); assertEquals(5, server.requestCount)
    }
    @Test fun errorCodesAndRateLimitsRemainTypedAndNoServerDetailsAreLoggedOrRetried() = runTest {
        login(); val count = server.requestCount
        val errors = listOf(400 to "invalid_reaction_emoji", 401 to "unauthenticated", 403 to "muted",
            404 to "message_not_found", 409 to "message_already_deleted", 429 to "rate_limited", 500 to "internal_error")
        for ((status, code) in errors) {
            server.enqueue(json("""{"error":{"code":"$code","message":"Private server details"}}""", status))
            val result = failure { api.setDirectReaction(direct(), ReactionEmoji.THUMBS_UP, true, csrf, 7) }; take()
            assertEquals(status, result.status); assertEquals(code, result.code)
            assertFalse(result.toString().contains("Private"))
        }
        assertEquals(count + errors.size, server.requestCount)
    }
    @Test fun postCommitFailureDoesNotReplayOrClaimTheRequestedSelection() = runTest {
        val expected = login(); server.enqueue(json("""{"error":{"code":"internal_error","message":"Fixture"}}""", 500))
        assertEquals(500, failure { repository.setRoom(expected, room(), ReactionEmoji.THUMBS_UP, true) }.status)
        assertWrite("rooms/react.php"); assertEquals(4, server.requestCount)
        // The consumer must retain ID-only uncertainty and explicitly GET/review before another choice.
    }
    @Test fun csrfRefusalRequiresRevalidationAndNeverReplaysTheWrite() = runTest {
        val expected = login(); server.enqueue(json("""{"error":{"code":"csrf_failed","message":"Fixture"}}""", 403))
        assertEquals("csrf_failed", failure { repository.setRoom(expected, room(), ReactionEmoji.THUMBS_UP, false) }.code)
        assertWrite("rooms/unreact.php"); assertTrue(sessions.state.value.refreshRequired)
        failure { repository.setRoom(expected, room(), ReactionEmoji.THUMBS_UP, false) }
        assertEquals(4, server.requestCount)
    }
    @Test fun anAuthenticatedRefusalRetiresTheSessionAndOriginalCallbacks() = runTest {
        val expected = login(); server.enqueue(json("""{"error":{"code":"unauthenticated","message":"Fixture"}}""", 401))
        assertEquals(401, failure { repository.setDirect(expected, direct(), ReactionEmoji.THUMBS_UP, false) }.status)
        assertWrite("direct-messages/unreact.php"); assertEquals(SessionPhase.INVALIDATED, sessions.state.value.phase)
        assertNull(vault.saved)
        try { repository.setRoom(expected, room(), ReactionEmoji.THUMBS_UP, true); fail("Old identity survived") }
        catch (_: CancellationException) { }
        assertEquals(4, server.requestCount)
    }
    @Test fun staleCallbacksCannotUpgradeToAReplacementAccountOrANewGenerationOfTheSameAccount() = runTest {
        val original = login(); logout(); login(); val count = server.requestCount
        for (action in listOf<suspend () -> Unit>(
            { repository.setRoom(original, room(), ReactionEmoji.THUMBS_UP, true) },
            { repository.setDirect(original, direct(), ReactionEmoji.THUMBS_UP, false) })) {
            try { action(); fail("Old session upgraded") } catch (_: CancellationException) { }
        }
        assertEquals(count, server.requestCount)
        logout(); login(9); val replacementCount = server.requestCount
        try { repository.setRoom(original, room(), ReactionEmoji.THUMBS_UP, true); fail("Old account upgraded") }
        catch (_: CancellationException) { }
        assertEquals(replacementCount, server.requestCount); assertEquals(9L, sessions.state.value.user?.id)
    }
    @Test fun cancellationAfterTheRequestIsDeliveredNeverReplaysOrInventsSuccess() = runTest {
        val expected = login(); server.enqueue(json(summary(), delay = 500))
        val pending = async { repository.setRoom(expected, room(), ReactionEmoji.THUMBS_UP, true) }
        withContext(Dispatchers.IO) { take() }; pending.cancelAndJoin()
        assertTrue(pending.isCancelled); assertEquals(4, server.requestCount)
    }
    @Test fun delayedResultsCannotPublishAfterLogout() = runTest {
        val expected = login(); server.enqueue(json(summary(), delay = 500))
        val pending = async { runCatching { repository.setDirect(expected, direct(), ReactionEmoji.THUMBS_UP, true) } }
        withContext(Dispatchers.IO) { take() }; logout()
        assertTrue(pending.await().exceptionOrNull() is CancellationException)
        assertEquals(SessionPhase.SIGNED_OUT, sessions.state.value.phase); assertNull(vault.saved)
    }
    @Test fun missingDirectPrivacyBlocksOnlyDirectWritesWithoutGuessingThePolicy() = runTest {
        val expected = login(withPrivacy = false)
        assertEquals(FailureKind.INCOMPATIBLE, failure { repository.setDirect(expected, direct(), ReactionEmoji.THUMBS_UP, false) }.kind)
        assertEquals(2, server.requestCount)
        server.enqueue(json("""{"reactions":[]}""")); repository.setRoom(expected, room(), ReactionEmoji.THUMBS_UP, false)
        assertWrite("rooms/unreact.php"); assertEquals(3, server.requestCount)
    }
    @Test fun redirectNeverForwardsTheWriteOrSessionOutsideItsOrigin() = runTest {
        val expected = login(); server.enqueue(MockResponse.Builder().code(307).addHeader("Location", "https://foreign.example.test/").build())
        assertEquals(FailureKind.REDIRECT, failure { repository.setRoom(expected, room(), ReactionEmoji.THUMBS_UP, true) }.kind)
        assertWrite("rooms/react.php"); assertEquals(4, server.requestCount)
    }
    @Test fun reactionRepliesKeepTheSmallJsonBoundInsteadOfUsingBulkHistoryAllowance() = runTest {
        login(); val count = server.requestCount
        server.enqueue(json("""{"reactions":[],"padding":"${"x".repeat(1_048_576)}"}"""))
        assertEquals(FailureKind.INCOMPATIBLE, failure { api.setRoomReaction(room(), ReactionEmoji.THUMBS_UP, true, csrf, 7) }.kind)
        take(); assertEquals(count + 1, server.requestCount)
    }
    private class Vault : SessionVault {
        var saved: SavedSession? = null
        override suspend fun read() = saved
        override suspend fun save(session: SavedSession) { saved = session }
        override suspend fun clear() { saved = null }
    }
}

package io.github.thiesi.chitchat.core.mutations

import io.github.thiesi.chitchat.core.conversation.*
import io.github.thiesi.chitchat.core.direct.*
import io.github.thiesi.chitchat.core.network.*
import io.github.thiesi.chitchat.core.server.*
import io.github.thiesi.chitchat.core.session.*
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.*
import org.junit.Assert.*

/** Production TLS/HTTP/session capabilities with isolated contract responses, never a live account. */
class MessageMutationProtocolTest {
    private lateinit var server: MockWebServer
    private lateinit var sessions: SessionRepository
    private lateinit var repository: SessionMessageMutationRepository
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
        repository = SessionMessageMutationRepository(sessions)
    }
    @After fun stop() { clients.forEach(SessionHttp::close); server.close() }
    private fun json(body: String, status: Int = 200, delay: Long = 0, cookie: String? = null) = MockResponse.Builder()
        .code(status).addHeader("Content-Type", "application/json").body(body).bodyDelay(delay, TimeUnit.MILLISECONDS)
        .apply { cookie?.let { addHeader("Set-Cookie", it) } }.build()
    private fun take() = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS))
    private suspend fun login(id: Long = 7, withPrivacy: Boolean = true): SessionState {
        server.enqueue(json("""{"csrf_token":"${"a".repeat(64)}","user":null,"registration_enabled":false,"sign_in_providers":[],"lockdown":{"enabled":false}}"""))
        sessions.connect((ServerAddress.parse(server.url("/").toString()) as AddressResult.Valid).address); take()
        val user = """{"id":$id,"username":"Viewer","roles":["member"],"session_version":1,"guest":false}"""
        server.enqueue(json("""{"csrf_token":"$csrf","user":$user,"mfa_required":false}""",
            cookie = "SITE=auth$id; Secure; HttpOnly; Path=/"))
        sessions.signIn("Viewer", "fixture-password"); take()
        if (withPrivacy) {
            server.enqueue(json("""{"csrf_token":"$csrf","user":$user,"registration_enabled":false,"sign_in_providers":[],"lockdown":{"enabled":false},"privacy":$privacy}"""))
            sessions.refresh(); take()
        }
        return sessions.state.value
    }
    private suspend fun logout() { server.enqueue(json("""{"status":"logged_out"}""")); sessions.signOut(); take() }
    private fun room(id: Long = 80, sender: Long = 7, type: String = "text", deleted: Boolean = false) =
        RoomMessage(id, 42, sender, "Viewer", type, "Private original", deleted, "2026-10-20T10:00:00Z",
            MessageAttachment(3, "private.txt", "text/plain", 4, "f".repeat(64), false),
            MessageReply("room", 70, false, null))
    private fun direct(sender: Long = 7, outgoing: Boolean = true, deleted: Boolean = false) =
        DirectMessage(80, MessagePerson(sender, "Viewer"), MessagePerson(8, "Peer"), "Private original", null,
            "2026-10-20T10:00:00Z", outgoing, deleted, null, false, true,
            DirectReply("direct", 70, false, null), emptyList(), emptyList(),
            MessageAttachment(3, "private.txt", "text/plain", 4, "f".repeat(64), false))
    private fun metadata(room: Boolean, deleted: Boolean = false, id: Long = 80, body: String = "Corrected private body"): String {
        val extra = if (room) """, "type":"text","deletion_kind":${if (deleted) "\"author\"" else "null"}""" else ""
        return """{"message":{"id":$id,"body":${if (deleted) "null" else JsonPrimitive(body)},"edited_at":"2026-10-20T11:00:00Z","deleted":$deleted,"can_edit":${!deleted},"can_delete":${!deleted},"mentions":[],"reactions":[{"emoji":"👍","users":[{"id":8,"username":"Peer"}],"reacted_by_me":false}]$extra}}"""
    }
    private suspend fun failure(action: suspend () -> Unit): ApiFailure = try { action(); error("Expected typed error") }
        catch (failure: ApiFailure) { failure }
    private suspend fun invalid(action: suspend () -> Unit) {
        try { action(); fail("Invalid original accepted") } catch (_: IllegalArgumentException) { }
    }
    private fun assertWrite(path: String, body: String) {
        val request = take()
        assertEquals("POST", request.method); assertEquals("/api/v1/$path", request.url.encodedPath)
        assertEquals(csrf, request.headers["X-CSRF-Token"]); assertEquals("SITE=auth7", request.headers["Cookie"])
        assertEquals(body, request.body!!.utf8())
    }

    @Test fun roomEditUsesExistingAuthorEndpointAndLiteralBodyWithoutAnotherGet() = runTest {
        val expected = login(); val original = room(); val body = "/ping Peer\nCorrected caption"
        server.enqueue(json(metadata(true, body = body)))
        val result = repository.editRoom(expected, original, body)
        assertEquals(body, result.body); assertEquals(80L, result.id); assertEquals(42L, result.roomId)
        assertSame(original.attachment, result.attachment); assertSame(original.replyTo, result.replyTo)
        val actions = checkNotNull(result.authorActions)
        assertTrue(actions.canEdit); assertTrue(actions.canDelete)
        assertWrite("rooms/edit-message.php", """{"message_id":80,"body":${JsonPrimitive(body)}}""")
        assertEquals(4, server.requestCount)
    }
    @Test fun roomAuthorDeleteUsesDistinctEndpointAndClearsContentWithoutErasingReactions() = runTest {
        val expected = login(); server.enqueue(json(metadata(true, deleted = true)))
        val result = repository.deleteRoom(expected, room())
        assertTrue(result.deleted); assertNull(result.body); assertNull(result.attachment); assertNull(result.replyTo)
        assertTrue(result.mentions.isEmpty()); assertEquals(1, result.reactions.size); assertEquals("author", result.deletionKind)
        val actions = checkNotNull(result.authorActions)
        assertFalse(actions.canEdit); assertFalse(actions.canDelete)
        assertWrite("rooms/delete-own-message.php", """{"message_id":80}""")
        assertEquals(4, server.requestCount)
    }
    @Test fun directEditPreservesCanonicalIdentityAndQuoteWithoutSendingPeerOrAuthorFields() = runTest {
        val expected = login(); val original = direct(); server.enqueue(json(metadata(false)))
        val result = repository.editDirect(expected, original, "Corrected private body")
        assertEquals("Corrected private body", result.body); assertSame(original.sender, result.sender)
        assertSame(original.recipient, result.recipient); assertSame(original.replyTo, result.replyTo)
        assertSame(original.attachment, result.attachment); assertTrue(result.canEdit)
        assertWrite("direct-messages/edit.php", """{"message_id":80,"body":"Corrected private body"}""")
        assertEquals(4, server.requestCount)
    }
    @Test fun roomEditsKeepEmoteAndAttachmentTypesAndNeverReplaceTheOriginalBinary() = runTest {
        val expected = login()
        for (type in listOf("emote", "attachment")) {
            val original = room(type = type)
            server.enqueue(json(metadata(true, body = "/me is literal").replace("\"type\":\"text\"", "\"type\":\"$type\"")))
            val result = repository.editRoom(expected, original, "/me is literal")
            assertEquals(type, result.type); assertEquals("/me is literal", result.body)
            assertSame(original.attachment, result.attachment)
            assertWrite("rooms/edit-message.php", """{"message_id":80,"body":"/me is literal"}""")
        }
        assertEquals(5, server.requestCount)
    }
    @Test fun directDeleteDoesNotRequireTheOldEditHintOrMessagingAvailability() = runTest {
        val expected = login(); val original = direct(); assertFalse(original.canEdit); assertTrue(original.canDelete)
        server.enqueue(json(metadata(false, deleted = true)))
        val result = repository.deleteDirect(expected, original)
        assertTrue(result.deleted); assertNull(result.body); assertNull(result.replyTo); assertNull(result.attachment)
        assertFalse(result.canEdit); assertFalse(result.canDelete); assertEquals(1, result.reactions.size)
        assertWrite("direct-messages/delete.php", """{"message_id":80}""")
        assertEquals(4, server.requestCount)
    }
    @Test fun literalEditValidationUsesPhpTrimAndUnicodeCodePointsNotRoomCommands() {
        for (body in listOf("/me", "/ping", "/anything", "\u00a0", "😀".repeat(4000))) assertNull(validateEditedBody(body))
        assertEquals("empty_message", validateEditedBody(" \t\r\n\u0000\u000B"))
        assertEquals("message_too_long", validateEditedBody("😀".repeat(4001)))
    }
    @Test fun invalidBodiesAndNonOwnedDeletedSystemOrInvalidOriginalsNeverReachHttp() = runTest {
        val expected = login(); val count = server.requestCount
        for (body in listOf(" \u0000\t", "😀".repeat(4001))) {
            assertEquals(400, failure { repository.editRoom(expected, room(), body) }.status)
            assertEquals(400, failure { repository.editDirect(expected, direct(), body) }.status)
        }
        for (original in listOf(room(0), room(sender = 8), room(type = "system"), room(deleted = true))) {
            invalid { repository.editRoom(expected, original, "Changed") }; invalid { repository.deleteRoom(expected, original) }
        }
        for (original in listOf(direct(sender = 8), direct(outgoing = false), direct(deleted = true))) {
            invalid { repository.editDirect(expected, original, "Changed") }; invalid { repository.deleteDirect(expected, original) }
        }
        assertEquals(count, server.requestCount)
    }
    @Test fun metadataHintsAreUnknownUnlessBothActualBooleanFieldsExistAndAreNotHistoryWireFields() = runTest {
        login(); val original = room()
        val withHints = applyMutation(original, apiJson.decodeFromString<MessageMutation>(
            metadata(true).substringAfter("{\"message\":").dropLast(1)))
        assertTrue(withHints.authorActions!!.canEdit)
        assertFalse(apiJson.encodeToString(withHints).contains("authorActions"))
        assertFalse(apiJson.encodeToString(withHints).contains("can_edit"))
        val unknown = applyMutation(original, MessageMutation(80, "Body", "text", null, false, null, emptyList(), emptyList()))
        assertNull(unknown.authorActions)
        assertFalse(withHints.toString().contains("Corrected private body"))
    }
    @Test fun roomEditRejectsWrongIdsTypesStatusMissingFlagsAndQuotedFlagsWithoutReplay() = runTest {
        login(); val valid = metadata(true)
        val bad = listOf(valid.replace("\"id\":80", "\"id\":81"), valid.replace("\"type\":\"text\"", "\"type\":\"emote\""),
            valid.replace("\"can_delete\":true,", ""), valid.replace("\"can_edit\":true", "\"can_edit\":\"true\""),
            valid.replace("\"deleted\":false", "\"deleted\":\"false\""), valid.replace("2026-10-20T11:00:00Z", "invalid"), "{}")
        val count = server.requestCount
        for (body in bad) { server.enqueue(json(body)); assertEquals(FailureKind.INCOMPATIBLE,
            failure { api.editRoomMessage(room(), "Changed", csrf, 7) }.kind); take() }
        server.enqueue(json(valid, 201)); assertEquals(FailureKind.INCOMPATIBLE,
            failure { api.editRoomMessage(room(), "Changed", csrf, 7) }.kind); take()
        assertEquals(count + bad.size + 1, server.requestCount)
    }
    @Test fun directEditRejectsWrongIdsQuotedFlagsAndMalformedMetadataWithoutReplay() = runTest {
        login(); val valid = metadata(false); val count = server.requestCount
        val bad = listOf(valid.replace("\"id\":80", "\"id\":81"), valid.replace("\"can_delete\":true", "\"can_delete\":\"true\""),
            valid.replace("\"body\":\"Corrected private body\"", "\"body\":null"), valid.replace("2026-10-20T11:00:00Z", "invalid"), "{}")
        for (body in bad) { server.enqueue(json(body)); assertEquals(FailureKind.INCOMPATIBLE,
            failure { api.editDirectMessage(direct(), "Changed", csrf, 7) }.kind); take() }
        assertEquals(count + bad.size, server.requestCount)
    }
    @Test fun activeDeleteResponsesNeverCountAsDeletionAndDeletedControlsMustBeFalse() = runTest {
        login(); val count = server.requestCount
        for (isRoom in listOf(true, false)) for (body in listOf(metadata(isRoom),
            metadata(isRoom, deleted = true).replace("\"can_edit\":false", "\"can_edit\":true"))) {
            server.enqueue(json(body)); assertEquals(FailureKind.INCOMPATIBLE, failure {
                if (isRoom) api.deleteOwnRoomMessage(room(), csrf, 7) else api.deleteOwnDirectMessage(direct(), csrf, 7)
            }.kind); take()
        }
        assertEquals(count + 4, server.requestCount)
    }
    @Test fun roomTombstonesRequireARealDeletionKindRatherThanInventingAnAuthorLabel() = runTest {
        login(); val count = server.requestCount
        for (kind in listOf("null", "\"future_kind\"")) {
            server.enqueue(json(metadata(true, deleted = true).replace("\"deletion_kind\":\"author\"", "\"deletion_kind\":$kind")))
            assertEquals(FailureKind.INCOMPATIBLE, failure { api.deleteOwnRoomMessage(room(), csrf, 7) }.kind); take()
        }
        assertEquals(count + 2, server.requestCount)
    }
    @Test fun successfulEditMayAlreadyHaveBeenDeletedByTheTimePostCommitMetadataWasRead() = runTest {
        val expected = login()
        server.enqueue(json(metadata(true, deleted = true))); assertTrue(repository.editRoom(expected, room(), "Changed").deleted); take()
        server.enqueue(json(metadata(false, deleted = true))); assertTrue(repository.editDirect(expected, direct(), "Changed").deleted); take()
        assertEquals(5, server.requestCount)
    }
    @Test fun existingValidationAuthorizationConflictRetentionAndServerErrorsKeepCodesWithoutReplay() = runTest {
        login(); val count = server.requestCount
        val errors = listOf(400 to "empty_message", 401 to "unauthenticated", 403 to "membership_required",
            403 to "muted", 404 to "message_not_found", 409 to "message_unchanged", 429 to "rate_limited", 500 to "internal_error")
        for ((status, code) in errors) {
            server.enqueue(json("""{"error":{"code":"$code","message":"Private server details"}}""", status))
            val result = failure { api.editRoomMessage(room(), "Changed", csrf, 7) }; take()
            assertEquals(status, result.status); assertEquals(code, result.code)
            assertFalse(result.toString().contains("Private"))
        }
        assertEquals(count + errors.size, server.requestCount)
    }
    @Test fun csrfRefusalRequiresRevalidationInsteadOfReplayingAnAuthorWrite() = runTest {
        val expected = login(); server.enqueue(json("""{"error":{"code":"csrf_failed","message":"Fixture"}}""", 403))
        assertEquals("csrf_failed", failure { repository.deleteRoom(expected, room()) }.code); take()
        assertTrue(sessions.state.value.refreshRequired)
        failure { repository.deleteRoom(expected, room()) }; assertEquals(4, server.requestCount)
    }
    @Test fun absentDmDisclosureBlocksOnlyDmWritesWithoutPreventingSupportedRoomWrites() = runTest {
        val expected = login(withPrivacy = false)
        assertEquals(FailureKind.INCOMPATIBLE, failure { repository.deleteDirect(expected, direct()) }.kind)
        assertEquals(2, server.requestCount)
        server.enqueue(json(metadata(true, deleted = true))); assertTrue(repository.deleteRoom(expected, room()).deleted); take()
        assertEquals(3, server.requestCount)
    }
    @Test fun authenticatedRefusalRetiresTheSessionAndStoredCredentialsWithoutRetry() = runTest {
        val expected = login(); server.enqueue(json("""{"error":{"code":"unauthenticated","message":"Fixture"}}""", 401))
        assertEquals(401, failure { repository.deleteDirect(expected, direct()) }.status); take()
        assertEquals(SessionPhase.INVALIDATED, sessions.state.value.phase); assertNull(vault.saved)
        try { repository.deleteRoom(expected, room()); fail("Old identity survived") } catch (_: CancellationException) { }
        assertEquals(4, server.requestCount)
    }
    @Test fun allOriginalCallbacksFailBeforeHttpAfterAnotherAccountSignsIn() = runTest {
        val original = login(); logout(); login(9); val count = server.requestCount
        for (action in listOf<suspend () -> Unit>({ repository.editRoom(original, room(), "Changed") },
            { repository.deleteRoom(original, room()) }, { repository.editDirect(original, direct(), "Changed") },
            { repository.deleteDirect(original, direct()) })) {
            try { action(); fail("Old session upgraded") } catch (_: CancellationException) { }
        }
        assertEquals(count, server.requestCount); assertEquals(9L, sessions.state.value.user?.id)
    }
    @Test fun deliveredWriteCancellationDoesNotRetryOrInventSuccess() = runTest {
        val original = login(); server.enqueue(json(metadata(true), delay = 500))
        val pending = async { repository.editRoom(original, room(), "Changed") }
        withContext(Dispatchers.IO) { take() }; pending.cancelAndJoin()
        assertEquals(4, server.requestCount)
        // Explicit GET reconciliation belongs to the native state actor, not an automatic POST here.
    }
    @Test fun delayedAuthorResultCannotPublishAfterLogout() = runTest {
        val original = login(); server.enqueue(json(metadata(false), delay = 500))
        val pending = async { runCatching { repository.editDirect(original, direct(), "Changed") } }
        withContext(Dispatchers.IO) { take() }; logout()
        assertTrue(pending.await().exceptionOrNull() is CancellationException)
        assertEquals(SessionPhase.SIGNED_OUT, sessions.state.value.phase); assertNull(vault.saved)
    }
    @Test fun redirectsNeverForwardTheAuthorWriteOrSessionToAnotherOrigin() = runTest {
        val expected = login(); server.enqueue(MockResponse.Builder().code(307).addHeader("Location", "https://foreign.example.test/").build())
        assertEquals(FailureKind.REDIRECT, failure { repository.deleteRoom(expected, room()) }.kind); take()
        assertEquals(4, server.requestCount)
    }
    private class Vault : SessionVault {
        var saved: SavedSession? = null
        override suspend fun read() = saved
        override suspend fun save(session: SavedSession) { saved = session }
        override suspend fun clear() { saved = null }
    }
}

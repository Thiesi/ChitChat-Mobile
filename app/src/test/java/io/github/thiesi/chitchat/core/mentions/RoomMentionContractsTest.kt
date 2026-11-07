package io.github.thiesi.chitchat.core.mentions

import io.github.thiesi.chitchat.core.network.*
import kotlinx.serialization.SerializationException
import org.junit.Assert.*
import org.junit.Test

class RoomMentionContractsTest {
    private fun parse(body: String) = apiJson.decodeFromString<RoomMentionReply>(body).validUsers(7)
    private fun invalid(body: String) {
        try { parse(body); fail("Expected incompatible candidate data") }
        catch (_: SerializationException) { }
        catch (failure: ApiFailure) { assertEquals(FailureKind.INCOMPATIBLE,failure.kind) }
    }
    @Test fun emptyAndOrderedCandidateListsDoNotInventAnAccountOrSortById() {
        assertTrue(parse(mentionBody(emptyList())).isEmpty())
        val users=parse(mentionBody(listOf(9L to "Alpha",8L to "Zed")))
        assertEquals(listOf(9L,8L),users.map { it.id }); assertEquals(listOf("Alpha","Zed"),users.map { it.username })
    }
    @Test fun eightCandidatesRemainCompleteWithinTheExplicitQueryBound() {
        val rows=(8L..15L).map { it to "User$it" }
        assertEquals(rows,parse(mentionBody(rows)).map { it.id to it.username })
    }
    @Test fun responsesLargerThanTheRequestedEightAreNotSilentlyTruncated() {
        invalid(mentionBody((8L..16L).map { it to "User$it" }))
    }
    @Test fun missingNullOrWrongRootListsAreNotAnEmptyRoster() {
        for (body in listOf("{}","[]","""{"users":null}""","""{"users":{}}""")) invalid(body)
    }
    @Test fun missingOrNullIdentityFieldsAreNotFallbackNamesOrIds() {
        for (body in listOf("""{"users":[{"id":8}]}""","""{"users":[{"username":"Peer"}]}""",
            """{"users":[{"id":null,"username":"Peer"}]}""","""{"users":[{"id":8,"username":null}]}""")) invalid(body)
    }
    @Test fun nonPositiveAndOwnIdsCannotBecomeServerSuggestedPeople() {
        for (id in listOf(0L,-1L,7L)) invalid(mentionBody(listOf(id to "Peer")))
    }
    @Test fun duplicateIdsCannotProduceTwoAmbiguousChoices() {
        invalid(mentionBody(listOf(8L to "Before",8L to "After")))
    }
    @Test fun blankOrOverlongAccountNamesAreNotPublishableCandidates() {
        for (name in listOf(""," \t","x".repeat(81))) invalid(mentionBody(listOf(8L to name)))
    }
    @Test fun prefixUsesPhpTrimAndPreservesCaseAsciiPunctuationAndEmptyQuery() {
        assertEquals("Ab_.-0",normalizedRoomMentionPrefix(43,"\u0000 \tAb_.-0\u000B\r\n"))
        assertEquals("",normalizedRoomMentionPrefix(43," \t\n\u0000"))
        assertEquals("A".repeat(32),normalizedRoomMentionPrefix(43,"A".repeat(32)))
    }
    @Test fun invalidRoomOrPrefixFailsLocalValidationWithoutAQuery() {
        val bad=listOf(0L to "a",-1L to "a",43L to "x".repeat(33),43L to "@a",43L to "a b",
            43L to "a%b",43L to "a/b",43L to "\u00A0ann",43L to "\u000CAnn",43L to "\uD83D\uDE42")
        for ((room,prefix) in bad) {
            try { normalizedRoomMentionPrefix(room,prefix); fail("Invalid query") }
            catch (failure: ApiFailure) { assertEquals(400,failure.status); assertEquals("validation_error",failure.code) }
        }
    }
    @Test fun existingUnderscoreWildcardMatchesRemainValidOrderedServerHints() {
        val request=RoomMentionRequest(43,"ann_")
        val batch=RoomMentionBatch(request,"ann_",parse(mentionBody(listOf(9L to "ann_member",8L to "annxmember"))))
        assertSame(request,batch.request); assertEquals(listOf("ann_member","annxmember"),batch.users.map { it.username })
    }
    @Test fun requestCandidateBatchAndWireStringsNeverGeneratePrivateValues() {
        val request=RoomMentionRequest(43,"Private_prefix")
        val wire=apiJson.decodeFromString<RoomMentionReply>(mentionBody())
        val users=wire.validUsers(7); val batch=RoomMentionBatch(request,"Private_prefix",users)
        for (value in listOf(request,wire,wire.users.single(),users.single(),batch))
            assertFalse(value.toString().contains("Private"))
    }
}

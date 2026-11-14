package io.github.thiesi.chitchat.core.search

import io.github.thiesi.chitchat.core.network.*
import kotlinx.serialization.SerializationException
import org.junit.Assert.*
import org.junit.Test

class MessageSearchContractsTest {
    private val request = MessageSearchRequest("fixture")
    private val time = "2026-07-18T10:45:00+00:00"
    private val room = """{"kind":"room","message_id":148,"excerpt":"<b>Private fixture</b> @Alice","sender":null,"room":{"id":4,"key":"operations","name":"Operations"},"peer":null,"outgoing":null,"created_at":"$time","edited_at":null}"""
    private val direct = """{"kind":"direct","message_id":92,"excerpt":"Private fixture","sender":{"id":7,"username":"Viewer"},"room":null,"peer":{"id":12,"username":"Peer"},"outgoing":true,"created_at":"$time","edited_at":"$time"}"""
    private fun page(rows: String = room, more: String = "false", next: String = "null") =
        """{"results":[$rows],"has_more":$more,"next_offset":$next}"""
    private fun decode(body: String, query: MessageSearchRequest = request) =
        apiJson.decodeFromString<MessageSearchReply>(body).page(query, 7)
    private fun incompatible(body: String, query: MessageSearchRequest = request) {
        try { decode(body, query); fail("Expected incompatible response") }
        catch (failure: ApiFailure) { assertEquals(FailureKind.INCOMPATIBLE, failure.kind); assertFalse(failure.toString().contains("Private fixture")) }
        catch (failure: SerializationException) { /* SessionHttp strips serializer details. */ }
    }

    @Test fun namespaceAndRelevanceOrderArePreservedWithNullableRoomSenderAndLiteralExcerpt() {
        val result = decode(page("$room,$direct"))
        assertEquals(listOf(148L, 92L), result.results.map { it.messageId })
        val first = result.results.first() as MessageSearchHit.Room
        assertNull(first.sender); assertEquals(4L, first.room.id)
        assertEquals("<b>Private fixture</b> @Alice", first.excerpt)
        val dm = result.results.last() as MessageSearchHit.Direct
        assertTrue(dm.outgoing); assertEquals(7L, dm.sender.id); assertEquals(12L, dm.peer.id)
        assertFalse(result.hasMore); assertFalse(result.canLoadMore)
    }
    // excerpt_matches (v3.10.0, #283): validated all or nothing per row, never failing the response.
    private fun withMatches(excerpt: String, matches: String) = room.replace(""""excerpt":"<b>Private fixture</b> @Alice"""",
        """"excerpt":"$excerpt","excerpt_matches":$matches""")
    private fun ranges(excerpt: String, matches: String) =
        decode(page(withMatches(excerpt, matches))).results.single().excerptMatches.map { it.start to it.length }

    @Test fun validMatchRangesAreKeptInUtf16UnitsAsTheDocumentationExample() {
        // The contract's own example: "Party " is 6 units, the emoji 2, the space 1, so "deployment" starts at 9.
        val hit = decode(page(withMatches("Party 🎉 deployment done", """[{"start":9,"length":10}]"""))).results.single()
        assertEquals(listOf(9 to 10), hit.excerptMatches.map { it.start to it.length })
        assertEquals("deployment", hit.excerpt.substring(9, 19))
        assertEquals(listOf(0 to 5, 9 to 10), ranges("Party 🎉 deployment done", """[{"start":0,"length":5},{"start":9,"length":10}]"""))
        assertEquals("Absent means no highlight", emptyList<Pair<Int, Int>>(), decode(page()).results.single().excerptMatches.map { it.start to it.length })
        assertEquals(emptyList<Pair<Int, Int>>(), ranges("Party", "[]"))
    }

    @Test fun anyInvalidRangeDropsEveryRangeOfThatRowButNeverTheResult() {
        val excerpt = "Party 🎉 deployment done"
        for (bad in listOf(
            """[{"start":9,"length":0}]""",                              // empty
            """[{"start":-1,"length":3}]""",                             // negative
            """[{"start":20,"length":10}]""",                            // past the end
            """[{"start":9,"length":10},{"start":0,"length":5}]""",      // out of order
            """[{"start":9,"length":10},{"start":12,"length":2}]""",     // overlapping
            """[{"start":7,"length":3}]""",                              // starts inside the emoji's surrogate pair
            """[{"start":0,"length":7}]""",                              // ends inside it
            """[{"start":"9","length":10}]""",                           // quoted number
            """[{"start":9.5,"length":10}]""",                           // not an integer
            """[{"start":9}]""",                                         // missing length
            """[[9,10]]""",                                              // wrong item shape
            """{"start":9,"length":10}""",                               // not an array
            "\"9-19\"",                                                // wrong type
            "null")) {
            val result = decode(page(withMatches(excerpt, bad)))
            val hit = result.results.single()
            assertEquals(bad, emptyList<Pair<Int, Int>>(), hit.excerptMatches.map { it.start to it.length })
            assertEquals("The row and its excerpt survive", excerpt, hit.excerpt)
        }
        // One bad row doesn't touch another row's valid ranges.
        val good = withMatches("Party deployment", """[{"start":6,"length":10}]""")
        val mixed = decode(page("$good,${withMatches("Party", """[{"start":3,"length":9}]""").replace("148", "149")}"))
        assertEquals(listOf(6 to 10), mixed.results[0].excerptMatches.map { it.start to it.length })
        assertTrue(mixed.results[1].excerptMatches.isEmpty())
    }

    @Test fun aRoomFilteredSearchAcceptsOnlyThatRoomsMessages() {
        val filtered = MessageSearchRequest("fixture", MessageSearchScope.ROOMS, roomId = 4)
        assertEquals(4L, (decode(page(room), filtered).results.single() as MessageSearchHit.Room).room.id)
        incompatible(page(room.replace(""""id":4,"key"""", """"id":5,"key"""")), filtered)
        incompatible(page(direct), MessageSearchRequest("fixture", MessageSearchScope.ALL, roomId = 4))
    }

    @Test fun incomingDirectRequiresThePeerAsSender() {
        val incoming = direct.replace(""""id":7,"username":"Viewer"""", """"id":12,"username":"Peer"""").replace(""""outgoing":true""", """"outgoing":false""")
        assertFalse((decode(page(incoming)).results.single() as MessageSearchHit.Direct).outgoing)
        incompatible(page(incoming.replaceFirst(""""id":12,"username":"Peer"""", """"id":9,"username":"Other"""")))
        incompatible(page(direct.replace(""""outgoing":true""", """"outgoing":false""")))
    }
    @Test fun paginationRetainsTheRealOffsetAndExplicitRequestCeiling() {
        val next = decode(page(room, "true", "50"), MessageSearchRequest("fixture", limit = 1, offset = 49))
        assertEquals(50, next.nextOffset); assertTrue(next.canLoadMore)
        val capped = decode(page(room, "true", "5001"), MessageSearchRequest("fixture", limit = 1, offset = 5000))
        assertTrue(capped.hasMore); assertEquals(5001, capped.nextOffset); assertFalse(capped.canLoadMore)
        assertTrue(decode(page(rows = "")).results.isEmpty())
    }
    @Test fun malformedPaginationIsNotInventedOrClamped() {
        incompatible(page(room, "true", "null"))
        incompatible(page(room, "true", "1")) // A full requested page is required before continuation.
        incompatible(page(room, "true", "2"), MessageSearchRequest("fixture", limit = 1))
        incompatible(page(room, "false", "1"))
        incompatible(page("$room,$direct"), MessageSearchRequest("fixture", limit = 1))
    }
    @Test fun unknownKindsNamespacesDuplicateIdsAndWrongScopeFailClosed() {
        incompatible(page(room.replace(""""kind":"room"""", """"kind":"future"""")))
        incompatible(page("$room,$room"))
        incompatible(page(room), MessageSearchRequest("fixture", MessageSearchScope.DIRECT))
        incompatible(page(direct), MessageSearchRequest("fixture", MessageSearchScope.ROOMS))
        incompatible(page(room.replace(""""outgoing":null""", """"outgoing":false""")))
        incompatible(page(direct.replace(""""room":null""", """"room":{"id":4,"key":"r","name":"Room"}""")))
        incompatible(page(direct.replace(""""peer":{"id":12,"username":"Peer"}""", """"peer":null""")))
        incompatible(page(direct.replace(""""sender":{"id":7,"username":"Viewer"}""", """"sender":null""")))
        incompatible(page(direct.replace(""""id":12""", """"id":7""")))
    }
    @Test fun requiredFlagsAreActualJsonBooleansAndNullableFieldsAreStillRequired() {
        for (flag in listOf("\"false\"", "0", "null")) incompatible(page(more = flag))
        incompatible(page(direct.replace(""""outgoing":true""", """"outgoing":"true"""")))
        incompatible(page(direct.replace(""""outgoing":true,""", "")))
        incompatible(page(room.replace(""""sender":null,""", "")))
        incompatible("{}"); incompatible("""{"results":null,"has_more":false,"next_offset":null}""")
    }
    @Test fun positiveIdentitiesDatesAndUnicodeExcerptBoundAreValidated() {
        for (body in listOf(room.replace(""""message_id":148""", """"message_id":0"""),
            room.replace(""""id":4""", """"id":0"""), room.replace(time, "invalid"),
            room.replace(""""key":"operations"""", """"key":""""""),
            direct.replace(""""username":"Peer"""", """"username":""""""),
            direct.replace(""""edited_at":"$time"""", """"edited_at":"invalid""""))) incompatible(page(body))
        val unicode = "😀".repeat(320)
        assertEquals(unicode, decode(page(room.replace("<b>Private fixture</b> @Alice", unicode))).results.single().excerpt)
        incompatible(page(room.replace("<b>Private fixture</b> @Alice", unicode + "a")))
    }
    @Test fun debugStringsCannotExposeTermsExcerptsOrParticipants() {
        val privateRequest = MessageSearchRequest("SensitiveQuery")
        val reply = apiJson.decodeFromString<MessageSearchReply>(page("$room,$direct"))
        val result = reply.page(request, 7)
        for (value in listOf(privateRequest, reply, reply.results.first(), reply.results.last().sender!!,
            result, result.results.first(), result.results.last(), (result.results.first() as MessageSearchHit.Room).room)) {
            val text = value.toString()
            for (privateText in listOf("SensitiveQuery", "Private fixture", "Operations", "Viewer", "Peer"))
                assertFalse(text, text.contains(privateText))
        }
    }
}

package io.github.thiesi.chitchat.core.conversation

import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.network.FailureKind
import io.github.thiesi.chitchat.core.session.SessionState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CanonicalRoomWindowTest {
    @Test fun fixedOlderFiveHundredRowsStartDirectlyAtCapturedUpperBoundary() = runTest {
        val requests = mutableListOf<Pair<Long?, Int>>()
        val result = readCanonicalRoomWindow(42, 1_501, 2_000) { before, limit ->
            requests += before to limit
            val top = before!! - 1
            MessagePage((top - 99..top).map(::message), top - 99, true)
        }
        assertEquals(listOf(2_001L, 1_901L, 1_801L, 1_701L, 1_601L), requests.map { it.first })
        assertTrue(requests.all { it.second == 100 }); assertEquals(500, result.messages.size)
        assertEquals(1_501L, result.messages.first().id); assertEquals(2_000L, result.messages.last().id)
        assertTrue(result.coveredLowerBoundary); assertEquals(1_501L, result.olderCursor)
    }

    @Test fun omittedEnrichedPagesKeepFollowingRawCursorAndRetainCapturedBoundary() = runTest {
        val requests = mutableListOf<Long?>()
        val pages = ArrayDeque(listOf(MessagePage(emptyList(), 201, true), MessagePage(emptyList(), 101, true),
            MessagePage(listOf(message(50), message(100)), 1, false)))
        val result = readCanonicalRoomWindow(42, 50, 300) { before, _ -> requests += before; pages.removeFirst() }
        assertEquals(listOf(301L, 201L, 101L), requests)
        assertEquals(listOf(50L, 100L), result.messages.map { it.id })
        assertEquals(50L, result.olderCursor); assertTrue(result.coveredLowerBoundary); assertTrue(result.mayHaveOlder)
    }

    @Test fun shortPageCrossingBelowLowerStillAllowsLoadingFilteredOlderRows() = runTest {
        val result = readCanonicalRoomWindow(42, 10, 11) { _, _ ->
            MessagePage(listOf(message(9), message(10), message(11)), 9, false)
        }
        assertEquals(listOf(10L, 11L), result.messages.map { it.id })
        assertEquals(10L, result.olderCursor); assertTrue(result.mayHaveOlder)
        val exact = readCanonicalRoomWindow(42, 10, 11) { _, _ -> MessagePage(listOf(message(10), message(11)), 10, false) }
        assertFalse(exact.mayHaveOlder)
    }

    @Test fun exhaustedHistoryProvesCoverageEvenIfRetentionRemovedCapturedLower() = runTest {
        val result = readCanonicalRoomWindow(42, 10, 90) { _, _ -> MessagePage(listOf(message(80)), 80, false) }
        assertTrue(result.coveredLowerBoundary); assertEquals(10L, result.olderCursor); assertFalse(result.mayHaveOlder)
        val empty = readCanonicalRoomWindow(42, 10, 90) { _, _ -> MessagePage(emptyList(), null, false) }
        assertTrue(empty.coveredLowerBoundary); assertTrue(empty.messages.isEmpty()); assertFalse(empty.mayHaveOlder)
    }

    @Test fun latestTraversalStopsAfterSixHundredRawRowsWithoutClaimingCoverage() = runTest {
        var calls = 0
        val result = readCanonicalRoomWindow(42, 1, null) { before, limit ->
            calls++; assertEquals(100, limit)
            val top = (before ?: 1_001L) - 1
            MessagePage((top - 99..top).map(::message), top - 99, true)
        }
        assertEquals(6, calls); assertEquals(600, result.messages.size)
        assertFalse(result.coveredLowerBoundary); assertTrue(result.mayHaveOlder); assertEquals(1L, result.olderCursor)
    }

    @Test fun maximumUpperIdDoesNotOverflowAndFreshNestedContentIsPreserved() = runTest {
        val quote = MessageReply("room", 5, true, ReplyMessage(5, null, "text", "Edited quote", false))
        val file = MessageAttachment(2, "Refreshed file", "text/plain", 3, "fixture", false)
        val fresh = RoomMessage(Long.MAX_VALUE, 42, 7, "Viewer", "attachment", "Fresh body", false,
            "2026-07-17T08:00:00Z", file, quote)
        val result = readCanonicalRoomWindow(42, Long.MAX_VALUE, Long.MAX_VALUE) { before, _ ->
            assertNull(before); MessagePage(listOf(fresh), Long.MAX_VALUE, false)
        }
        assertSame(fresh, result.messages.single()); assertSame(quote, result.messages.single().replyTo)
        assertSame(file, result.messages.single().attachment)
        assertFalse(result.toString().contains("Fresh body"))
    }

    @Test fun malformedCursorOrderingOrPageShapeFailsBeforeAnotherRead() = runTest {
        val malformed = listOf(MessagePage(emptyList(), null, true), MessagePage(emptyList(), 0, true),
            MessagePage(listOf(message(80)), null, false), MessagePage(listOf(message(80)), 81, false),
            MessagePage(listOf(message(80), message(79)), 79, false), MessagePage(listOf(message(80), message(80)), 80, false),
            MessagePage(listOf(message(91)), 91, false), MessagePage((1L..101).map(::message), 1, true),
            MessagePage((1L..100).map(::message), 1, false))
        for (page in malformed) {
            var calls = 0
            assertEquals(FailureKind.INCOMPATIBLE, failure {
                readCanonicalRoomWindow(42, 1, 90) { _, _ -> calls++; page }
            }.kind)
            assertEquals(1, calls)
        }
        var calls = 0
        assertEquals(FailureKind.INCOMPATIBLE, failure {
            readCanonicalRoomWindow(42, 1, 200) { _, _ -> calls++
                if (calls == 1) MessagePage((101L..200).map(::message), 101, true)
                else MessagePage(emptyList(), 101, true)
            }
        }.kind)
        assertEquals(2, calls)
    }

    @Test fun invalidBoundsNeverInvokeReaderAndServerErrorsAreNotRetried() = runTest {
        for ((room, lower, upper) in listOf(Triple(0L, 1L, 2L), Triple(42L, 0L, 2L), Triple(42L, 3L, 2L))) {
            try { readCanonicalRoomWindow(room, lower, upper) { _, _ -> error("Unexpected read") }; fail("Expected invalid bounds") }
            catch (_: IllegalArgumentException) { }
        }
        for (status in listOf(401, 403, 404, 429, 503)) {
            var calls = 0
            val expected = ApiFailure(FailureKind.HTTP, status, "fixture")
            assertSame(expected, failure { readCanonicalRoomWindow(42, 1, 90) { _, _ -> calls++; throw expected } })
            assertEquals(1, calls)
        }
    }

    @Test fun cancellationAfterAnUncooperativeReaderPreventsFurtherTraversal() = runTest {
        var calls = 0; val gate = CompletableDeferred<Unit>()
        val pending = async { readCanonicalRoomWindow(42, 1, 200) { _, _ ->
            calls++; withContext(NonCancellable) { gate.await(); MessagePage((101L..200).map(::message), 101, true) }
        } }
        runCurrent(); pending.cancel(); gate.complete(Unit); runCurrent()
        assertTrue(pending.isCancelled); assertEquals(1, calls)
    }

    @Test fun interfaceDefaultPerformsRealBoundedHistoryReads() = runTest {
        var reads = 0
        val operations = object : ConversationOperations {
            override val session = MutableStateFlow(SessionState())
            override suspend fun history(roomId: Long, beforeId: Long?, limit: Int): MessagePage {
                reads++; assertEquals(42L, roomId); assertEquals(91L, beforeId); assertEquals(100, limit)
                return MessagePage(listOf(message(80)), 80, false)
            }
            override suspend fun context(roomId: Long): ConversationContext = error("Unused")
            override suspend fun pings(roomId: Long): List<RoomPing> = error("Unused")
            override suspend fun send(roomId: Long, body: String, replyId: Long?): SendResult = error("Unused")
            override suspend fun read(roomId: Long, messageId: Long): Long = error("Unused")
        }
        assertEquals(80L, operations.window(42, 80, 90).messages.single().id); assertEquals(1, reads)
    }

    private suspend fun failure(action: suspend () -> Unit): ApiFailure {
        try { action(); error("Expected failure") } catch (failure: ApiFailure) { return failure }
    }
    private fun message(id: Long) = RoomMessage(id, 42, 7, "Viewer", "text", "Private body", false, "2026-07-17T08:00:00Z")
}


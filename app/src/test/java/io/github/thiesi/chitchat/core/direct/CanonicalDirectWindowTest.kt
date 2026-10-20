package io.github.thiesi.chitchat.core.direct

import io.github.thiesi.chitchat.core.conversation.MessagePerson
import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.network.FailureKind
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class CanonicalDirectWindowTest {
    @Test fun emptyEnrichedPageAdvancesRawCursorAndKeepsCapturedLowerBoundary() = runTest {
        val cursors = mutableListOf<Long?>()
        val result = readCanonicalDirectWindow(8, 7, 5, null) { before, _ ->
            cursors += before
            if (before == null) DirectPage(emptyList(), 20, true) else DirectPage((5L..19L).map(::row), 5, true)
        }
        assertEquals(listOf(null, 20L), cursors); assertTrue(result.covered)
        assertEquals(5L, result.olderCursor); assertTrue(result.mayHaveOlder)
    }
    @Test fun fixedUpperStartsDirectlyAtExclusiveNextIdAndExcludesOutsideRows() = runTest {
        val result = readCanonicalDirectWindow(8, 7, 8, 10) { before, limit ->
            assertEquals(11L, before); assertEquals(100, limit)
            DirectPage((1L..10L).map(::row), 1, false)
        }
        assertEquals(listOf(8L, 9L, 10L), result.messages.map { it.id }); assertTrue(result.mayHaveOlder)
    }
    @Test fun maxIdDoesNotOverflowAndSixPageLimitDoesNotPretendFullCoverage() = runTest {
        var count = 0
        val result = readCanonicalDirectWindow(8, 7, 1, Long.MAX_VALUE) { before, _ ->
            if (count == 0) assertNull(before)
            val upper = 1000L - count++ * 100
            DirectPage((upper - 99..upper).map(::row), upper - 99, true)
        }
        assertEquals(6, count); assertEquals(600, result.messages.size); assertFalse(result.covered)
    }
    @Test fun repeatedCursorAndForeignPerspectiveCannotBecomeCanonicalRows() = runTest {
        for (foreign in listOf(false, true)) {
            val failure = try {
                readCanonicalDirectWindow(8, 7, 1, null) { before, _ ->
                    if (foreign) DirectPage(listOf(row(5, 9)), 5, false) else DirectPage(emptyList(), before ?: 20, true)
                }; error("Expected bounded rejection")
            } catch (failure: ApiFailure) { failure }
            assertEquals(FailureKind.INCOMPATIBLE, failure.kind)
        }
    }
    private fun row(id: Long, peer: Long = 8) = DirectMessage(id, MessagePerson(peer, "Peer"), MessagePerson(7, "Viewer"),
        "Fixture", null, "2026-10-20T10:00:00Z", false, false, null, false, false, null, emptyList(), emptyList(), null)
}

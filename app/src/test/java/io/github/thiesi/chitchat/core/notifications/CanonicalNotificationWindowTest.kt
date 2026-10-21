package io.github.thiesi.chitchat.core.notifications

import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.network.FailureKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CanonicalNotificationWindowTest {
    private val stamp = "2026-10-21T00:00:00Z"
    private fun notice(id: Long) = AccountNotification(id, "security", "Fixture title", "Fixture message",
        emptyList(), null, false, null, stamp)
    private fun page(ids: List<Long>, unread: Int = 9000) = NotificationPage(ids.map(::notice), unread,
        ids.lastOrNull(), ids.size == 100)

    @Test fun historicalWindowUsesDirectBeforeBoundsAndIndependentUnreadCount() = runTest {
        val queries = mutableListOf<Long?>()
        val result = readCanonicalNotificationWindow(1501, 2000) { before, limit ->
            queries += before; assertEquals(100, limit)
            page((1L..3000).reversed().filter { before == null || it < before }.take(limit))
        }
        assertEquals(listOf(2001L, 1901L, 1801L, 1701L, 1601L), queries)
        assertTrue(result.coveredLowerBoundary); assertEquals(500, result.notifications.size)
        assertEquals(2000L, result.notifications.first().id); assertEquals(1501L, result.notifications.last().id)
        assertEquals(9000, result.unreadCount); assertEquals(1501L, result.olderCursor); assertTrue(result.mayHaveOlder)
    }
    @Test fun fetchedButExcludedOlderRowsAreNotSkippedByTheNextCursor() = runTest {
        val result = readCanonicalNotificationWindow(20, 80) { before, _ ->
            assertEquals(81L, before); page((1L..80).reversed().toList())
        }
        assertEquals(61, result.notifications.size); assertEquals(20L, result.olderCursor)
        assertTrue(result.mayHaveOlder); assertTrue(result.coveredLowerBoundary)
    }
    @Test fun completeRetentionLossDoesNotSubstituteLatestOrInventAVisibleRow() = runTest {
        val queries = mutableListOf<Long?>()
        val result = readCanonicalNotificationWindow(18, 22) { before, _ -> queries += before; page(emptyList(), 7) }
        assertEquals(listOf(23L), queries); assertTrue(result.notifications.isEmpty())
        assertTrue(result.coveredLowerBoundary); assertFalse(result.mayHaveOlder)
        assertEquals(18L, result.olderCursor); assertEquals(7, result.unreadCount)
    }
    @Test fun latestReaderStopsAfterSixPagesAndReportsIncompleteCoverage() = runTest {
        var calls = 0
        val result = readCanonicalNotificationWindow(1, null) { before, limit ->
            calls++; page((1L..10000).reversed().filter { before == null || it < before }.take(limit), calls)
        }
        assertEquals(6, calls); assertEquals(600, result.notifications.size)
        assertFalse(result.coveredLowerBoundary); assertTrue(result.mayHaveOlder)
        assertEquals(1L, result.olderCursor); assertEquals(6, result.unreadCount)
    }
    @Test fun maximumIdDoesNotOverflowTheExclusiveBeforeParameter() = runTest {
        val result = readCanonicalNotificationWindow(Long.MAX_VALUE, Long.MAX_VALUE) { before, _ ->
            assertNull(before); page(listOf(Long.MAX_VALUE), 0)
        }
        assertEquals(Long.MAX_VALUE, result.notifications.single().id); assertEquals(0, result.unreadCount)
    }
    @Test fun rejectsInvalidBoundsBeforeAnyOperation() = runTest {
        listOf(0L to null, -1L to 8L, 9L to 8L).forEach { (lower, upper) ->
            try { readCanonicalNotificationWindow(lower, upper) { _, _ -> error("Unexpected read") }; fail("Expected invalid bounds") }
            catch (_: IllegalArgumentException) { /* No networking. */ }
        }
    }
    @Test fun rejectsPoisonedRowsAndNoncanonicalPaginationMetadata() = runTest {
        val invalid = listOf(page(listOf(20, 21)), page(listOf(20, 20)), page(listOf(0)), page(listOf(30)),
            page(listOf(20), -1), NotificationPage(listOf(notice(20)), 0, 19, false),
            NotificationPage(listOf(notice(20)), 0, 20, true))
        invalid.forEach { value ->
            var calls = 0
            try { readCanonicalNotificationWindow(1, 22) { _, _ -> calls++; value }; fail("Expected incompatible page") }
            catch (failure: ApiFailure) { assertEquals(FailureKind.INCOMPATIBLE, failure.kind) }
            assertEquals(1, calls)
        }
    }
    @Test fun errorsPropagateWithoutRetryOrPartialSuccess() = runTest {
        var calls = 0; val denied = ApiFailure(FailureKind.HTTP, 403, "forbidden")
        try { readCanonicalNotificationWindow(1, 22) { _, _ -> calls++; throw denied }; fail("Expected failure") }
        catch (failure: ApiFailure) { assertSame(denied, failure) }
        assertEquals(1, calls)
    }
    @Test fun cancellationAfterAnUncooperativeAwaitCannotFetchOrPublishMore() = runTest {
        var calls = 0; val gate = CompletableDeferred<Unit>()
        val job = async { readCanonicalNotificationWindow(1, 300) { _, _ ->
            calls++; withContext(NonCancellable) { gate.await() }; page((201L..300).reversed().toList())
        } }
        runCurrent(); assertEquals(1, calls); job.cancel(); gate.complete(Unit); runCurrent()
        assertTrue(job.isCancelled); assertEquals(1, calls)
    }
}

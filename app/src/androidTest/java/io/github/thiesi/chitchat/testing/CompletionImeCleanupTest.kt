package io.github.thiesi.chitchat.testing

import org.junit.Assert.*
import org.junit.Test

/** Deterministic scalar probes, no Activity, input/hide/focus action or real-time claim. */
class CompletionImeCleanupTest {
    @Test fun aVisibleConfirmationKeepsWaitingWithinTheOriginalDeadline() {
        val wait = Polls(2)
        val native = Probe(listOf(false, true, false, false))
        awaitCompletionImeHidden(wait::until, { 0 }, native::next)
        assertEquals(1, wait.calls)
        assertEquals(2, wait.polls)
        assertEquals(4, native.reads)
        assertEquals(false, native.last)
        assertTrue(native.remaining.isEmpty())
    }

    @Test fun composeAndBothNativeObservationsMustAllBeHidden() {
        val wait = Polls(4)
        val native = Probe(listOf(null, false, true, false, false))
        var composeReads = 0
        awaitCompletionImeHidden(wait::until, { if (++composeReads == 1) 1 else 0 }, native::next)
        assertEquals(1, wait.calls)
        assertEquals(4, wait.polls)
        assertEquals(4, composeReads)
        assertEquals(5, native.reads)
        assertEquals(false, native.last)
    }

    @Test fun unknownOrVisibleNativeStateCannotCompleteOrResetTheWait() {
        for (visible in listOf(null, true)) {
            val wait = Polls(3)
            var nativeReads = 0
            try {
                awaitCompletionImeHidden(wait::until, { 0 }, { nativeReads++; visible })
                fail("Unproven cleanup must not complete")
            } catch (failure: IllegalStateException) {
                assertSame(wait.timeout, failure)
            }
            assertEquals(1, wait.calls)
            assertEquals(3, wait.polls)
            assertEquals(3, nativeReads)
        }
    }

    @Test fun stableCleanupKeepsBothChecksWithoutAnUnboundedLaterProbe() {
        val wait = Polls(1)
        val native = Probe(listOf(false, false, true))
        awaitCompletionImeHidden(wait::until, { 0 }, native::next)
        assertEquals(1, wait.calls)
        assertEquals(1, wait.polls)
        assertEquals(2, native.reads)
        assertEquals(false, native.last)
        // The contract is hidden at completion, not a promise about a later re-show.
        assertEquals(listOf(true), native.remaining)
    }

    private class Polls(private val maximum: Int) {
        val timeout = IllegalStateException("Deterministic test poll budget exhausted")
        var calls = 0
        var polls = 0
        fun until(timeoutMillis: Long, condition: () -> Boolean) {
            assertEquals(5_000L, timeoutMillis)
            calls++
            repeat(maximum) {
                polls++
                if (condition()) return
            }
            throw timeout
        }
    }
    private class Probe(values: List<Boolean?>) {
        val remaining = values.toMutableList()
        var reads = 0
        var last: Boolean? = null
        fun next(): Boolean? {
            check(remaining.isNotEmpty()) { "Unexpected extra native probe" }
            reads++
            return remaining.removeAt(0).also { last = it }
        }
    }
}

package io.github.thiesi.chitchat.testing

import androidx.compose.ui.test.ComposeTimeoutException
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

    @Test fun timeoutRecordsOnlyTheLastShortCircuitedEvaluation() {
        val failure = ComposeTimeoutException("fixture timeout")
        var polls = 0
        var nativeReads = 0
        val records = mutableListOf<String>()
        try {
            awaitCompletionImeHidden({ deadline, condition ->
                assertEquals(5_000L, deadline)
                repeat(2) { assertFalse(condition()) }
                throw failure
            }, { if (++polls == 1) 0 else 9 }, { ++nativeReads == 2 }, records::add)
            fail()
        } catch (actual: ComposeTimeoutException) { assertSame(failure, actual) }
        assertEquals(2, nativeReads) // No stale first/second values or post-timeout probes.
        assertEquals(listOf("sample=last_wait_predicate compose=9 native1=unobserved native2=unobserved"), records)
    }

    @Test fun firstProbeVisibleOrUnknownKeepsSecondUnobserved() {
        for (visible in listOf(true, null)) {
            val failure = ComposeTimeoutException("fixture timeout")
            var reads = 0
            val records = mutableListOf<String>()
            try {
                awaitCompletionImeHidden({ _, condition -> assertFalse(condition()); throw failure },
                    { 0 }, { reads++; visible }, records::add)
                fail()
            } catch (actual: ComposeTimeoutException) { assertSame(failure, actual) }
            assertEquals(1, reads)
            val first = if (visible == true) "visible" else "unknown"
            assertEquals(listOf("sample=last_wait_predicate compose=0 native1=$first native2=unobserved"), records)
        }
    }

    @Test fun secondProbeFailureIsRecordedWithoutAnotherStateRead() {
        val failure = ComposeTimeoutException("fixture timeout")
        val native = Probe(listOf(false, true))
        val records = mutableListOf<String>()
        try {
            awaitCompletionImeHidden({ _, condition -> assertFalse(condition()); throw failure },
                { 0 }, native::next, records::add)
            fail()
        } catch (actual: ComposeTimeoutException) { assertSame(failure, actual) }
        assertEquals(2, native.reads)
        assertEquals(listOf("sample=last_wait_predicate compose=0 native1=hidden native2=visible"), records)
    }

    @Test fun timeoutWithoutEvaluationIsUnobservedAndEmitterFailureCannotReplaceIt() {
        val failure = ComposeTimeoutException("fixture timeout")
        var emissions = 0
        var emitted: String? = null
        try {
            awaitCompletionImeHidden({ _, _ -> throw failure }, { error("Unexpected Compose read") },
                { error("Unexpected native read") }) { record ->
                emissions++
                emitted = record
                error("fixture emitter failure")
            }
            fail()
        } catch (actual: ComposeTimeoutException) {
            assertSame(failure, actual); assertNull(actual.cause); assertTrue(actual.suppressed.isEmpty())
        }
        assertEquals(1, emissions)
        assertEquals("sample=last_wait_predicate compose=unobserved native1=unobserved native2=unobserved", emitted)
    }

    @Test fun successfulCleanupEmitsNothingAndUsesOnlyItsTwoNativeProbes() {
        val wait = Polls(1)
        val native = Probe(listOf(false, false))
        var emissions = 0
        awaitCompletionImeHidden(wait::until, { 0 }, native::next) { emissions++ }
        assertEquals(0, emissions); assertEquals(2, native.reads); assertEquals(1, wait.calls)
    }

    @Test fun nonTimeoutFailureIsPreservedWithoutAnEmission() {
        val failure = IllegalArgumentException("fixture failure")
        var emissions = 0
        try {
            awaitCompletionImeHidden({ _, _ -> throw failure }, { 0 }, { false }) { emissions++ }
            fail()
        } catch (actual: IllegalArgumentException) { assertSame(failure, actual) }
        assertEquals(0, emissions)
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

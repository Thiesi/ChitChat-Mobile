package io.github.thiesi.chitchat.feature.reports

import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.network.FailureKind
import io.github.thiesi.chitchat.core.network.SessionUser
import io.github.thiesi.chitchat.core.reports.ReportCategory
import io.github.thiesi.chitchat.core.reports.ReportFacts
import io.github.thiesi.chitchat.core.reports.ReportOperations
import io.github.thiesi.chitchat.core.reports.ReportRefusal
import io.github.thiesi.chitchat.core.reports.ReportResult
import io.github.thiesi.chitchat.core.reports.ReportTarget
import io.github.thiesi.chitchat.core.server.AddressResult
import io.github.thiesi.chitchat.core.server.ServerAddress
import io.github.thiesi.chitchat.core.session.SessionPhase
import io.github.thiesi.chitchat.core.session.SessionState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Reporting R1: the report state (reporting.md, P-162/P-164/P-167; TL and PL rulings of 2026-11-20). */
@OptIn(ExperimentalCoroutinesApi::class)
class ReportViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val fake = Reports()
    private lateinit var model: ReportViewModel
    private val room = ReportTarget.RoomMessage(148)
    private val person = ReportTarget.Person(8, 4)

    @Before fun setUp() { Dispatchers.setMain(dispatcher); model = ReportViewModel(fake) }
    @After fun tearDown() { model.close(); Dispatchers.resetMain() }

    private fun state() = model.state.value!!
    private fun filled(target: ReportTarget = room, category: ReportCategory = ReportCategory.HARASSMENT, details: String = "private why") {
        model.open(target, pictureAvailable = true)
        model.choose(category)
        model.edit(details)
    }

    @Test fun aCategoryIsRequiredAndA201Closes() = runTest(dispatcher) {
        advanceUntilIdle()
        model.open(room)
        model.send(); advanceUntilIdle()
        assertEquals("No category, no send", 0, fake.sends.size)
        model.choose(ReportCategory.SPAM); model.edit("  details \n")
        model.send(); advanceUntilIdle()
        assertEquals(listOf(Send(room, ReportCategory.SPAM, "  details \n")), fake.sends)
        assertEquals(ReportPhase.Done(ReportOutcome.Sent), state().phase)
        assertNull("Category and details are gone once done", state().category)
        assertEquals("", state().details)
    }

    @Test fun tooLongDetailsBlockSendWithoutTruncation() = runTest(dispatcher) {
        advanceUntilIdle()
        filled(details = "a".repeat(1_001))
        assertFalse(state().canSend)
        model.send(); advanceUntilIdle()
        assertEquals(0, fake.sends.size)
        assertEquals("Never cut", 1_001, state().details.length)
        model.edit("a".repeat(1_000))
        assertTrue(state().canSend)
    }

    @Test fun aFirstAlreadyReportedIsItsOwnSuccess() = runTest(dispatcher) {
        advanceUntilIdle()
        fake.results += ReportResult.AlreadyReported
        filled(); model.send(); advanceUntilIdle()
        assertEquals(ReportPhase.Done(ReportOutcome.AlreadyReported), state().phase)
    }

    @Test fun unknownFreezesTheFormAndSendAgainResendsTheSameFrozenReport() = runTest(dispatcher) {
        advanceUntilIdle()
        fake.results += ReportResult.Unknown(ApiFailure(FailureKind.TIMEOUT))
        filled(); model.send(); advanceUntilIdle()
        assertEquals(ReportPhase.Unknown(), state().phase)
        assertFalse(state().afterUnknown)
        // Read-only: nothing changes between attempts (TL).
        model.choose(ReportCategory.SPAM); model.edit("changed")
        assertEquals(ReportCategory.HARASSMENT, state().category)
        assertEquals("private why", state().details)
        model.send(); advanceUntilIdle()
        assertEquals("Send report isn't available in the summary", 1, fake.sends.size)
        // Send again, answered already-reported: the first attempt arrived, so it reads as sent (PL).
        fake.results += ReportResult.AlreadyReported
        model.sendAgain(); advanceUntilIdle()
        assertEquals(2, fake.sends.size)
        assertEquals("The same frozen report", fake.sends[0], fake.sends[1])
        assertEquals(ReportPhase.Done(ReportOutcome.Sent), state().phase)
    }

    @Test fun nothingIsResentAutomatically() = runTest(dispatcher) {
        advanceUntilIdle()
        fake.results += ReportResult.Unknown(ApiFailure(FailureKind.NETWORK))
        filled(); model.send(); advanceUntilIdle()
        // A reconnect, a refresh or time passing sends nothing.
        fake.session.value = fake.session.value.copy(busy = true); advanceUntilIdle()
        fake.session.value = fake.session.value.copy(busy = false); advanceUntilIdle()
        assertEquals(1, fake.sends.size)
        assertEquals(ReportPhase.Unknown(), state().phase)
    }

    @Test fun aSendAgainThatHitsTheBudgetKeepsTheSummaryWithTheWait() = runTest(dispatcher) {
        advanceUntilIdle()
        fake.results += ReportResult.Unknown(ApiFailure(FailureKind.TIMEOUT))
        fake.results += ReportResult.RateLimited(900)
        filled(); model.send(); advanceUntilIdle()
        model.sendAgain(); advanceUntilIdle()
        assertEquals(ReportPhase.Unknown(ReportNotice.RateLimited(900)), state().phase)
        assertEquals("Still frozen", ReportCategory.HARASSMENT, state().category)
    }

    @Test fun aFirstAttemptsWaitKeepsTheEditableForm() = runTest(dispatcher) {
        advanceUntilIdle()
        fake.results += ReportResult.RateLimited(null)
        filled(); model.send(); advanceUntilIdle()
        assertEquals(ReportPhase.Editing(ReportNotice.RateLimited(null)), state().phase)
        assertEquals("private why", state().details)
        model.edit("still mine")
        assertEquals("still mine", state().details)
    }

    @Test fun definiteRefusalsCloseWithTheirReason() = runTest(dispatcher) {
        advanceUntilIdle()
        for (reason in ReportRefusal.entries) {
            fake.results += ReportResult.Refused(reason)
            filled(); model.send(); advanceUntilIdle()
            assertEquals(ReportPhase.Done(ReportOutcome.Refused(reason)), state().phase)
        }
    }

    @Test fun pictureGoneRemovesTheCategoryAndClearsTheChoice() = runTest(dispatcher) {
        advanceUntilIdle()
        fake.results += ReportResult.PictureGone
        filled(target = person, category = ReportCategory.OFFENSIVE_PROFILE_PICTURE); model.send(); advanceUntilIdle()
        assertEquals(ReportPhase.Editing(ReportNotice.PictureGone), state().phase)
        assertNull(state().category)
        assertFalse(ReportCategory.OFFENSIVE_PROFILE_PICTURE in state().categories)
        model.choose(ReportCategory.OFFENSIVE_PROFILE_PICTURE)
        assertNull("No longer offered", state().category)
    }

    @Test fun personCheckFindsTheReportOrSaysItDidNotArrive() = runTest(dispatcher) {
        advanceUntilIdle()
        fake.results += ReportResult.Unknown(ApiFailure(FailureKind.TIMEOUT))
        filled(target = person); model.send(); advanceUntilIdle()
        model.sendAgain(); advanceUntilIdle()
        assertEquals("No Send again for a person: Check first (P-167)", 1, fake.sends.size)
        // A failed read stays Unknown, with Check still offered.
        fake.facts = null; fake.factsFailure = ApiFailure(FailureKind.NETWORK)
        model.check(); advanceUntilIdle()
        assertEquals(ReportPhase.Unknown(), state().phase)
        assertEquals("The frozen room context", listOf(person), fake.checks)
        // It didn't arrive: editable again, and sending is an explicit choice.
        fake.factsFailure = null; fake.facts = ReportFacts(canReport = true, reportedByMe = false)
        model.check(); advanceUntilIdle()
        assertEquals(ReportPhase.Editing(ReportNotice.DidNotArrive), state().phase)
        assertEquals(1, fake.sends.size)
        model.send(); advanceUntilIdle()
        assertEquals(2, fake.sends.size)
    }

    @Test fun aPersonCheckThatFindsTheReportReadsAsSent() = runTest(dispatcher) {
        advanceUntilIdle()
        fake.results += ReportResult.Unknown(ApiFailure(FailureKind.TIMEOUT))
        filled(target = person); model.send(); advanceUntilIdle()
        fake.facts = ReportFacts(canReport = true, reportedByMe = true)
        model.check(); advanceUntilIdle()
        assertEquals(ReportPhase.Done(ReportOutcome.Sent), state().phase)
        assertEquals("Check never POSTs", 1, fake.sends.size)
    }

    @Test fun closeAndASessionChangeDropEverythingAndCancelTheAttempt() = runTest(dispatcher) {
        advanceUntilIdle()
        fake.hold = CompletableDeferred()
        filled(); model.send(); advanceUntilIdle()
        model.close(); advanceUntilIdle()
        assertNull(model.state.value)
        assertTrue("The attempt in flight was cancelled", fake.cancelled)
        fake.hold = null
        filled(); advanceUntilIdle()
        fake.session.value = fake.session.value.copy(generation = 2); advanceUntilIdle()
        assertNull("A session change drops the form", model.state.value)
    }

    @Test fun detailsAndCategoryNeverReachAString() = runTest(dispatcher) {
        advanceUntilIdle()
        filled(details = "very private words")
        val text = state().toString()
        assertFalse(text.contains("very private words"))
        assertFalse(text.contains("HARASSMENT"))
    }

    private data class Send(val target: ReportTarget, val category: ReportCategory, val details: String?)

    private class Reports : ReportOperations {
        override val session = MutableStateFlow(SessionState(
            server = (ServerAddress.parse("https://chat.example") as AddressResult.Valid).address,
            generation = 1, phase = SessionPhase.AUTHENTICATED, user = SessionUser(7, "Alice", listOf("member"), 1, false)))
        val sends = mutableListOf<Send>()
        val results = ArrayDeque<ReportResult>()
        val checks = mutableListOf<ReportTarget.Person>()
        var facts: ReportFacts? = null
        var factsFailure: ApiFailure? = null
        var hold: CompletableDeferred<Unit>? = null
        var cancelled = false

        override suspend fun send(expected: SessionState, target: ReportTarget, category: ReportCategory, details: String?): ReportResult {
            sends += Send(target, category, details)
            try { hold?.await() } catch (e: kotlinx.coroutines.CancellationException) { cancelled = true; throw e }
            return results.removeFirstOrNull() ?: ReportResult.Sent(1)
        }

        override suspend fun personFacts(expected: SessionState, target: ReportTarget.Person): ReportFacts? {
            checks += target
            factsFailure?.let { throw it }
            return facts
        }
    }
}

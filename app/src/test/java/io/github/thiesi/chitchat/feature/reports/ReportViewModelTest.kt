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
    private val origin = ReportOrigin.Room(4)

    @Before fun setUp() { Dispatchers.setMain(dispatcher); model = ReportViewModel(fake) }
    @After fun tearDown() { model.close(); Dispatchers.resetMain() }

    private fun state() = model.state.value!!
    private fun filled(target: ReportTarget = room, category: ReportCategory = ReportCategory.HARASSMENT, details: String = "private why") {
        model.open(target, origin, pictureAvailable = true)
        model.choose(category)
        model.edit(details)
    }

    @Test fun aCategoryIsRequiredAndA201Closes() = runTest(dispatcher) {
        advanceUntilIdle()
        model.open(room, origin)
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

    @Test fun closeIsRefusedWhileSendingAndTheAttemptKeepsRunning() = runTest(dispatcher) {
        advanceUntilIdle()
        val hold = CompletableDeferred<Unit>().also { fake.hold = it }
        filled(); model.send(); advanceUntilIdle()
        model.close(); advanceUntilIdle()
        assertEquals("Still sending (TL, #324)", ReportPhase.Sending, state().phase)
        assertFalse(fake.cancelled)
        hold.complete(Unit); advanceUntilIdle()
        assertEquals(ReportPhase.Done(ReportOutcome.Sent), state().phase)
        model.close()
        assertNull("Outside sending, close drops it", model.state.value)
    }

    @Test fun navigationAwayLetsTheReportFinishAndKeepsItsOutcome() = runTest(dispatcher) {
        advanceUntilIdle()
        val hold = CompletableDeferred<Unit>().also { fake.hold = it }
        filled(); model.send(); advanceUntilIdle()
        model.departed(); advanceUntilIdle()
        assertFalse("Navigation never cancels the POST", fake.cancelled)
        hold.complete(Unit); advanceUntilIdle()
        assertEquals("The outcome is kept for the UI", ReportPhase.Done(ReportOutcome.Sent), state().phase)
        // Outside an attempt, navigation away drops the form.
        model.close(); filled(); model.departed()
        assertNull("An open form is dropped", model.state.value)
    }

    @Test fun navigationAwayKeepsAPendingUnknownAndAFinishedOutcomeForTheirOrigin() = runTest(dispatcher) {
        advanceUntilIdle()
        fake.results += ReportResult.Unknown(ApiFailure(FailureKind.TIMEOUT))
        filled(); model.send(); advanceUntilIdle()
        model.departed()
        assertEquals("The Unknown waits for its Review (#324 review)", ReportPhase.Unknown(), state().phase)
        assertEquals("Still frozen", "private why", state().details)
        fake.results += ReportResult.AlreadyReported
        model.sendAgain(); advanceUntilIdle()
        assertEquals(ReportPhase.Done(ReportOutcome.Sent), state().phase)
        model.departed()
        assertEquals("The finished outcome waits for its line", ReportPhase.Done(ReportOutcome.Sent), state().phase)
        model.consume()
        assertNull(model.state.value)
    }

    @Test fun aGenuineBackgroundWhileSendingLandsInUnknownWithTheFrozenReport() = runTest(dispatcher) {
        advanceUntilIdle()
        fake.hold = CompletableDeferred()
        filled(details = "kept why"); model.send(); advanceUntilIdle()
        model.backgrounded(); advanceUntilIdle()
        assertTrue("The call is cancelled", fake.cancelled)
        assertEquals("Kept, never dropped", ReportPhase.Unknown(), state().phase)
        assertEquals(ReportCategory.HARASSMENT, state().category)
        assertEquals("kept why", state().details)
        // Send again is available, as an explicit choice with the same report.
        fake.hold = null; fake.cancelled = false
        fake.results += ReportResult.AlreadyReported
        model.sendAgain(); advanceUntilIdle()
        assertEquals(fake.sends[0], fake.sends[1])
        assertEquals(ReportPhase.Done(ReportOutcome.Sent), state().phase)
    }

    @Test fun aGenuineBackgroundWhileSendingAPersonReportOffersCheck() = runTest(dispatcher) {
        advanceUntilIdle()
        fake.hold = CompletableDeferred()
        filled(target = person); model.send(); advanceUntilIdle()
        model.backgrounded(); advanceUntilIdle()
        assertEquals(ReportPhase.Unknown(), state().phase)
        model.sendAgain(); advanceUntilIdle()
        assertEquals("No resend for a person", 1, fake.sends.size)
        fake.hold = null
        fake.facts = ReportFacts(canReport = true, reportedByMe = true)
        model.check(); advanceUntilIdle()
        assertEquals(ReportPhase.Done(ReportOutcome.Sent), state().phase)
    }

    @Test fun aGenuineBackgroundWhileEditingDropsTheFormAndKeepsAnUnknown() = runTest(dispatcher) {
        advanceUntilIdle()
        filled(); model.backgrounded()
        assertNull("An open form is memory only, never kept as a draft", model.state.value)
        fake.results += ReportResult.Unknown(ApiFailure(FailureKind.TIMEOUT))
        filled(); model.send(); advanceUntilIdle()
        model.backgrounded()
        assertEquals("An Unknown stays", ReportPhase.Unknown(), state().phase)
    }

    @Test fun aSessionChangeWhileSendingDropsEverything() = runTest(dispatcher) {
        advanceUntilIdle()
        fake.hold = CompletableDeferred()
        filled(); model.send(); advanceUntilIdle()
        fake.session.value = fake.session.value.copy(generation = 2); advanceUntilIdle()
        assertNull("The identity fence drops even an attempt in flight", model.state.value)
        assertTrue(fake.cancelled)
    }

    @Test fun theStateKeepsItsOriginAndAShownOutcomeIsConsumedOnce() = runTest(dispatcher) {
        advanceUntilIdle()
        model.open(ReportTarget.DirectMessage(9), ReportOrigin.Direct(12)); model.choose(ReportCategory.SPAM)
        model.send(); advanceUntilIdle()
        assertEquals(ReportOrigin.Direct(12), state().origin)
        assertEquals(ReportPhase.Done(ReportOutcome.Sent), state().phase)
        model.consume()
        assertNull("Shown once, then gone", model.state.value)
    }

    @Test fun aFinishedOutcomeWaitingToBeShownNeverBlocksANewReport() = runTest(dispatcher) {
        advanceUntilIdle()
        filled(); model.send(); advanceUntilIdle()
        assertEquals(ReportPhase.Done(ReportOutcome.Sent), state().phase)
        assertEquals(ReportOpen.OPENED, model.open(ReportTarget.RoomMessage(200), ReportOrigin.Room(5)))
        assertEquals(ReportPhase.Editing(), state().phase)
        assertEquals(ReportTarget.RoomMessage(200), state().target)
    }

    @Test fun aPendingUnknownIsDroppedByAReportElsewhereWithNothingSent() = runTest(dispatcher) {
        advanceUntilIdle()
        fake.results += ReportResult.Unknown(ApiFailure(FailureKind.TIMEOUT))
        filled(); model.send(); advanceUntilIdle()
        assertEquals("Reopening the same message keeps its Unknown (Review)", ReportOpen.OPENED, model.open(room, origin))
        assertEquals(ReportPhase.Unknown(), state().phase)
        assertEquals(ReportOpen.OPENED, model.open(ReportTarget.RoomMessage(200), ReportOrigin.Room(5)))
        assertEquals(ReportPhase.Editing(), state().phase)
        assertEquals("", state().details)
        assertEquals("No POST for the dropped Unknown", 1, fake.sends.size)
    }

    @Test fun onlyAReportSendingOrCheckingBlocksANewOne() = runTest(dispatcher) {
        advanceUntilIdle()
        val hold = CompletableDeferred<Unit>().also { fake.hold = it }
        filled(); model.send(); advanceUntilIdle()
        assertEquals(ReportOpen.BUSY, model.open(ReportTarget.RoomMessage(200), ReportOrigin.Room(5)))
        assertEquals(room, state().target)
        hold.complete(Unit); advanceUntilIdle()
        fake.hold = null
        // A person Check blocks too.
        fake.results += ReportResult.Unknown(ApiFailure(FailureKind.TIMEOUT))
        filled(target = person); model.send(); advanceUntilIdle()
        val read = CompletableDeferred<Unit>().also { fake.factsHold = it }
        model.check(); advanceUntilIdle()
        assertEquals(ReportOpen.BUSY, model.open(room, origin))
        read.complete(Unit); advanceUntilIdle()
    }

    @Test fun aSuccessInPlaceOffersTheSheetOnceAndNotOnReturn() = runTest(dispatcher) {
        advanceUntilIdle()
        filled(); assertTrue("Open presents the dialog", state().presented)
        model.send(); advanceUntilIdle()
        assertFalse("The dialog closes on the outcome", state().presented)
        assertTrue("The follow-up sheet, in place (PL)", state().sheet)
        model.dismissSheet()
        assertFalse(state().sheet)
        assertEquals("The line stays until leaving", ReportPhase.Done(ReportOutcome.Sent), state().phase)
        // Left mid-send: the outcome lands without a sheet.
        model.leaveConversation(origin)
        val hold = CompletableDeferred<Unit>().also { fake.hold = it }
        filled(); model.send(); advanceUntilIdle()
        model.leaveConversation(origin)
        assertFalse(state().presented)
        hold.complete(Unit); advanceUntilIdle()
        assertEquals(ReportPhase.Done(ReportOutcome.Sent), state().phase)
        assertFalse("No sheet on return (PL)", state().sheet)
    }

    @Test fun aRefusalNeverOffersTheSheet() = runTest(dispatcher) {
        advanceUntilIdle()
        fake.results += ReportResult.Refused(ReportRefusal.NOT_ALLOWED)
        filled(); model.send(); advanceUntilIdle()
        assertFalse(state().sheet)
    }

    @Test fun leavingItsOwnConversationConsumesAShownOutcomeAndLeavesOthersAlone() = runTest(dispatcher) {
        advanceUntilIdle()
        filled(); model.send(); advanceUntilIdle()
        model.leaveConversation(ReportOrigin.Room(99))
        assertEquals("Another conversation's departure doesn't touch it", ReportPhase.Done(ReportOutcome.Sent), state().phase)
        model.leaveConversation(origin)
        assertNull("Leaving its own conversation clears the shown line", model.state.value)
        filled(); model.leaveConversation(origin)
        assertNull("An open form is dropped", model.state.value)
    }

    @Test fun reviewReopensAPendingUnknownsSummary() = runTest(dispatcher) {
        advanceUntilIdle()
        fake.hold = CompletableDeferred()
        filled(); model.send(); advanceUntilIdle()
        model.backgrounded(); advanceUntilIdle()
        assertFalse("The dialog goes with the background", state().presented)
        model.review()
        assertTrue(state().presented)
        assertEquals(ReportPhase.Unknown(), state().phase)
        model.close()
        assertNull("Close also dismisses the notice", model.state.value)
    }

    @Test fun theBusyLineShowsWhereItWasRefusedAndClearsOnSettleOrLeaving() = runTest(dispatcher) {
        advanceUntilIdle()
        val hold = CompletableDeferred<Unit>().also { fake.hold = it }
        filled(); model.send(); advanceUntilIdle()
        assertEquals(ReportOpen.BUSY, model.request(ReportTarget.RoomMessage(200), ReportOrigin.Room(5)))
        assertEquals(ReportOrigin.Room(5), model.busyNotice.value)
        assertEquals(ReportOpen.BUSY, model.request(ReportTarget.RoomMessage(201), ReportOrigin.Room(5)))
        assertEquals("Never twice: one line", ReportOrigin.Room(5), model.busyNotice.value)
        model.leaveConversation(ReportOrigin.Room(5))
        assertNull("Leaving clears it", model.busyNotice.value)
        model.request(ReportTarget.RoomMessage(200), ReportOrigin.Room(5))
        hold.complete(Unit); advanceUntilIdle()
        assertNull("Settling clears it", model.busyNotice.value)
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
        var factsHold: CompletableDeferred<Unit>? = null
        var hold: CompletableDeferred<Unit>? = null
        var cancelled = false

        override suspend fun send(expected: SessionState, target: ReportTarget, category: ReportCategory, details: String?): ReportResult {
            sends += Send(target, category, details)
            try { hold?.await() } catch (e: kotlinx.coroutines.CancellationException) { cancelled = true; throw e }
            return results.removeFirstOrNull() ?: ReportResult.Sent(1)
        }

        override suspend fun personFacts(expected: SessionState, target: ReportTarget.Person): ReportFacts? {
            checks += target
            factsHold?.await()
            factsFailure?.let { throw it }
            return facts
        }
    }
}

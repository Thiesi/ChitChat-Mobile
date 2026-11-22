package io.github.thiesi.chitchat.feature.reports

import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.network.FailureKind
import io.github.thiesi.chitchat.core.network.SessionUser
import io.github.thiesi.chitchat.core.profile.BasicProfile
import io.github.thiesi.chitchat.core.profile.ProfileSnapshot
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

/** Reporting R3: a person reported from their profile card (reporting.md, P-165–P-167; TL conditions of 2026-11-22). */
@OptIn(ExperimentalCoroutinesApi::class)
class PersonReportTest {
    private val dispatcher = StandardTestDispatcher()
    private val fake = Reports()
    private lateinit var model: ReportViewModel
    private val room = ReportOrigin.Room(4)
    private val bob = ReportTarget.Person(8, 4)

    @Before fun setUp() { Dispatchers.setMain(dispatcher); model = ReportViewModel(fake) }
    @After fun tearDown() { model.close(); Dispatchers.resetMain() }

    private fun state() = model.state.value!!
    private fun snapshot(roomId: Long?, hasAvatar: Boolean = true, avatars: Boolean = true) = ProfileSnapshot(
        BasicProfile(8, "Bob", "2026-01-02T12:00:00Z", null, false, hasAvatar, if (hasAvatar) 3 else null, false), roomId, avatars,
        ReportFacts(canReport = true, reportedByMe = false))

    @Test fun theTargetIsTheRenderedProfilesOwnRoomContextAndADmCardIsGlobal() {
        val fromRoom = personReport(snapshot(roomId = 4))
        assertEquals(ReportTarget.Person(8, 4), fromRoom.target)
        assertEquals("Bob", fromRoom.name)
        assertTrue(fromRoom.pictureAvailable)
        // TL condition 3: a DM card loads without a room, so its report is global; never the conversation's room.
        assertEquals(ReportTarget.Person(8, null), personReport(snapshot(roomId = null)).target)
    }

    @Test fun aRoomCardWithoutARoomContextIsGlobalWithTheGlobalCopy() {
        // A room card whose profile came back without a room context: null, and the copy is global (Direct), never the
        // conversation's own room filled in.
        val report = personReport(snapshot(roomId = null))
        assertNull(report.target.roomId)
        model.open(report.target, room, report.pictureAvailable, report.name)
        assertEquals(ReportTarget.Person(8, null), state().target)
        assertEquals(room, state().origin)
    }

    @Test fun thePictureCategoryNeedsACurrentPicture() {
        assertFalse(personReport(snapshot(4, hasAvatar = false)).pictureAvailable)
        assertFalse("Avatars off on the server", personReport(snapshot(4, avatars = false)).pictureAvailable)
        model.open(bob, room, pictureAvailable = false, name = "Bob")
        assertFalse(ReportCategory.OFFENSIVE_PROFILE_PICTURE in state().categories)
        assertEquals("Person-only categories first", ReportCategory.OFFENSIVE_USERNAME, state().categories.first())
    }

    @Test fun cardsOutsideARoomOrDmThreadHaveNoReportOrigin() {
        assertEquals(ReportOrigin.Room(4), profileReportOrigin(room = true, direct = false, roomId = 4, peerId = -1))
        assertEquals(ReportOrigin.Direct(8), profileReportOrigin(room = false, direct = true, roomId = -1, peerId = 8))
        // TL condition 6: any other source shows no Report entry and never crashes.
        assertNull(profileReportOrigin(room = false, direct = false, roomId = 4, peerId = 8))
        assertNull(profileReportOrigin(room = true, direct = false, roomId = -1, peerId = null))
        assertNull(profileReportOrigin(room = false, direct = true, roomId = null, peerId = 0))
        assertNull(profileReportOrigin(room = true, direct = true, roomId = 4, peerId = 8))
    }

    @Test fun whileAReportIsSendingTheTapShowsTheBusyLineAndOpensNothing() = runTest(dispatcher) {
        advanceUntilIdle()
        val hold = CompletableDeferred<Unit>().also { fake.hold = it }
        model.open(ReportTarget.RoomMessage(148), room); model.choose(ReportCategory.SPAM); model.send(); advanceUntilIdle()
        assertEquals(ReportOpen.BUSY, model.requestPerson(bob, room, true, "Bob", model.settled))
        assertEquals("The still-sending line, in this conversation", room, model.busyNotice.value)
        assertEquals("The message report is untouched", ReportTarget.RoomMessage(148), state().target)
        assertEquals(ReportPhase.Sending, state().phase)
        hold.complete(Unit); advanceUntilIdle()
        assertEquals(1, fake.sends.size)
    }

    @Test fun aHeldUnknownForAnotherTargetShowsItsSummaryAndIsNeverReplaced() = runTest(dispatcher) {
        advanceUntilIdle()
        fake.results += ReportResult.Unknown(ApiFailure(FailureKind.TIMEOUT))
        model.open(ReportTarget.RoomMessage(148), room); model.choose(ReportCategory.SPAM); model.edit("kept why"); model.send(); advanceUntilIdle()
        model.departed()
        assertFalse(state().presented)
        assertEquals(ReportOpen.HELD, model.requestPerson(bob, room, true, "Bob", model.settled))
        assertEquals("The message's Unknown stays", ReportTarget.RoomMessage(148), state().target)
        assertTrue("Its summary shows instead", state().presented)
        assertEquals("kept why", state().details)
        assertEquals(1, fake.sends.size)
        // Closed explicitly, the person report opens next time.
        model.close()
        assertEquals(ReportOpen.OPENED, model.requestPerson(bob, room, true, "Bob", model.settled))
        assertEquals(bob, state().target)
    }

    @Test fun theSamePersonsUnknownReopensItsSummaryWithCheck() = runTest(dispatcher) {
        advanceUntilIdle()
        fake.results += ReportResult.Unknown(ApiFailure(FailureKind.NETWORK))
        model.requestPerson(bob, room, true, "Bob", model.settled); model.choose(ReportCategory.OFFENSIVE_USERNAME); model.send(); advanceUntilIdle()
        model.departed()
        assertEquals(ReportOpen.OPENED, model.requestPerson(bob, room, true, "Bob", model.settled))
        assertTrue(state().phase is ReportPhase.Unknown)
        assertEquals(ReportCategory.OFFENSIVE_USERNAME, state().category)
        model.sendAgain(); advanceUntilIdle()
        assertEquals("A person's Unknown never sends again", 1, fake.sends.size)
        fake.facts = ReportFacts(canReport = true, reportedByMe = false)
        model.check(); advanceUntilIdle()
        assertEquals("Check is a GET with the frozen room", listOf(bob), fake.checks)
        assertEquals(ReportPhase.Editing(ReportNotice.DidNotArrive), state().phase)
        assertEquals("Still no POST until Send report is tapped", 1, fake.sends.size)
    }

    @Test fun anOutcomeThatSettledUnderTheCardIsSeenBeforeANewForm() = runTest(dispatcher) {
        advanceUntilIdle()
        val hold = CompletableDeferred<Unit>().also { fake.hold = it }
        model.open(ReportTarget.RoomMessage(148), room); model.choose(ReportCategory.SPAM); model.send(); advanceUntilIdle()
        model.departed()
        val cardOpenedAt = model.settled
        hold.complete(Unit); advanceUntilIdle()
        assertEquals(ReportPhase.Done(ReportOutcome.Sent), state().phase)
        assertEquals("It settled while the card was up", ReportOpen.HELD, model.requestPerson(bob, room, true, "Bob", cardOpenedAt))
        assertEquals(ReportPhase.Done(ReportOutcome.Sent), state().phase)
        // The card closed and the line was on screen; a card opened since replaces it, as R2 does.
        assertEquals(ReportOpen.OPENED, model.requestPerson(bob, room, true, "Bob", model.settled))
        assertEquals(bob, state().target)
    }

    @Test fun theFrozenNameCarriesToTheOutcomeAndNeverReachesAString() = runTest(dispatcher) {
        advanceUntilIdle()
        fake.results += ReportResult.Refused(ReportRefusal.NOT_ALLOWED)
        model.requestPerson(bob, room, false, "Bob", model.settled); model.choose(ReportCategory.OTHER); model.edit("private words")
        assertFalse(state().toString().contains("Bob")); assertFalse(state().toString().contains("private words"))
        model.send(); advanceUntilIdle()
        assertEquals(ReportPhase.Done(ReportOutcome.Refused(ReportRefusal.NOT_ALLOWED)), state().phase)
        assertEquals("Bob", state().name)
        assertFalse("A refusal offers no follow-up", state().sheet)
    }

    @Test fun theSameNoticeTwiceIsANewOccurrence() = runTest(dispatcher) {
        advanceUntilIdle()
        fake.results += ReportResult.SnapshotUnavailable
        fake.results += ReportResult.SnapshotUnavailable
        model.requestPerson(bob, room, true, "Bob", model.settled); model.choose(ReportCategory.OFFENSIVE_PROFILE_PICTURE)
        model.send(); advanceUntilIdle()
        val first = state().noticeSeq
        assertEquals(ReportPhase.Editing(ReportNotice.SnapshotUnavailable), state().phase)
        model.edit("more"); assertEquals("Typing keeps the occurrence", first, state().noticeSeq)
        model.send(); advanceUntilIdle()
        assertEquals(ReportPhase.Editing(ReportNotice.SnapshotUnavailable), state().phase)
        assertTrue("A second identical refusal is a new occurrence (TL)", state().noticeSeq > first)
    }

    /** PL and TL: a new notice holds the view; each of (a)-(d), the person's next interaction, lets it go. */
    @Test fun aNoticeHoldsUntilTheNextInteraction() = runTest(dispatcher) {
        advanceUntilIdle()
        repeat(4) { fake.results += ReportResult.SnapshotUnavailable }
        model.requestPerson(bob, room, true, "Bob", model.settled); model.choose(ReportCategory.OFFENSIVE_PROFILE_PICTURE)
        assertFalse("No notice, no hold", state().noticeHeld)
        val releases = listOf<Pair<String, () -> Unit>>(
            "(a) keyboard shown" to { assertTrue(model.keyboardShown()); assertFalse("Only once", model.keyboardShown()) },
            "(b) field focused" to { model.fieldFocused() },
            "(c) typing" to { model.edit("more") },
            "(d) a category" to { model.choose(ReportCategory.OFFENSIVE_USERNAME); model.choose(ReportCategory.OFFENSIVE_PROFILE_PICTURE) })
        for ((name, release) in releases) {
            model.send(); advanceUntilIdle()
            assertTrue("$name: a new notice holds", state().noticeHeld)
            release()
            assertFalse("$name lets it go", state().noticeHeld)
        }
        assertFalse("The hold never reaches a string", state().toString().contains("Held"))
    }

    @Test fun aMessageReportCarriesNoName() {
        model.open(ReportTarget.RoomMessage(148), room, name = "Bob")
        assertNull(state().name)
    }

    @Test fun reportingIsNeverGatedByRulesAcceptance() = runTest(dispatcher) {
        fake.session.value = fake.session.value.copy(rulesRefused = true)
        advanceUntilIdle()
        assertEquals(ReportOpen.OPENED, model.requestPerson(bob, room, true, "Bob", model.settled))
        model.choose(ReportCategory.OFFENSIVE_PROFILE_PICTURE); model.send(); advanceUntilIdle()
        assertEquals(ReportPhase.Done(ReportOutcome.Sent), state().phase)
        assertEquals(1, fake.sends.size)
    }

    private class Reports : ReportOperations {
        override val session = MutableStateFlow(SessionState(
            server = (ServerAddress.parse("https://chat.example") as AddressResult.Valid).address,
            generation = 1, phase = SessionPhase.AUTHENTICATED, user = SessionUser(7, "Alice", listOf("member"), 1, false)))
        val sends = mutableListOf<ReportTarget>()
        val results = ArrayDeque<ReportResult>()
        val checks = mutableListOf<ReportTarget.Person>()
        var facts: ReportFacts? = null
        var hold: CompletableDeferred<Unit>? = null

        override suspend fun send(expected: SessionState, target: ReportTarget, category: ReportCategory, details: String?): ReportResult {
            sends += target
            hold?.await()
            return results.removeFirstOrNull() ?: ReportResult.Sent(1)
        }

        override suspend fun personFacts(expected: SessionState, target: ReportTarget.Person): ReportFacts? {
            checks += target
            return facts
        }
    }
}

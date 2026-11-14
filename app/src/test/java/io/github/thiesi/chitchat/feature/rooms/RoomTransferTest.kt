package io.github.thiesi.chitchat.feature.rooms

import io.github.thiesi.chitchat.core.account.AccountAccess
import io.github.thiesi.chitchat.core.account.AccountOperations
import io.github.thiesi.chitchat.core.account.PersonalExport
import io.github.thiesi.chitchat.core.account.VerificationMethods
import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.network.FailureKind
import io.github.thiesi.chitchat.core.network.SessionUser
import io.github.thiesi.chitchat.core.rooms.CreateRoom
import io.github.thiesi.chitchat.core.rooms.RoomInfo
import io.github.thiesi.chitchat.core.rooms.RoomOperations
import io.github.thiesi.chitchat.core.rooms.RoomOwnershipOperations
import io.github.thiesi.chitchat.core.rooms.RoomTransferMember
import io.github.thiesi.chitchat.core.rooms.RoomTransferSnapshot
import io.github.thiesi.chitchat.core.session.SessionPhase
import io.github.thiesi.chitchat.core.session.SessionState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Room ownership transfer (P-217, B2) through the Rooms view model. */
@OptIn(ExperimentalCoroutinesApi::class)
class RoomTransferTest {
    private val dispatcher = StandardTestDispatcher()
    private val rooms = Rooms()
    private val ownership = Ownership()
    private val account = Account()
    private lateinit var model: RoomsViewModel
    @Before fun setUp() { Dispatchers.setMain(dispatcher); model = RoomsViewModel(rooms, null, ownership, account) }
    @After fun tearDown() { model.cancel(); Dispatchers.resetMain() }
    private fun TestScope.open() { model.refresh(); runCurrent(); model.select(42); runCurrent() }
    private val transfer get() = model.transfer.value
    private fun TestScope.pick(id: Long = 8) {
        model.openTransferPicker(); runCurrent()
        model.chooseTransferTarget(transfer!!.candidates.first { it.id == id }); runCurrent()
    }

    @Test fun anOrdinaryMemberNeverTriggersTheSnapshot() = runTest(dispatcher) {
        rooms.room = room("member"); open()
        assertEquals(0, ownership.snapshots); assertNull(transfer)
    }

    @Test fun anOwnerLoadsTheFlagsAndSeesTheRowOnlyWithACandidate() = runTest(dispatcher) {
        open()
        assertEquals(1, ownership.snapshots)
        assertEquals(TransferLoad.LOADED, transfer?.load); assertTrue(transfer!!.ownerActing)
        assertEquals(listOf(8L, 9L), transfer!!.candidates.map { it.id })
        model.closeDetail(); runCurrent(); assertNull("Closing drops the private facts", transfer)
        ownership.candidates = emptyList(); model.select(42); runCurrent()
        assertTrue("No one can take over: no row", transfer!!.candidates.isEmpty())
    }

    @Test fun aGlobalRoomManagerLoadsItButAModeratorRoleNeverInfersIt() = runTest(dispatcher) {
        rooms.room = room("member"); rooms.session.value = rooms.session.value.copy(user = user(listOf("member", "chat_admin")))
        open()
        assertEquals(1, ownership.snapshots); assertFalse(transfer!!.ownerActing)
        model.closeDetail(); runCurrent()
        rooms.session.value = rooms.session.value.copy(user = user(listOf("member", "global_moderator")))
        runCurrent(); open()
        assertEquals("Global moderators don't transfer", 1, ownership.snapshots)
    }

    @Test fun aNetworkFailureOffersOneExplicitTryAgainAndNeverRetriesItself() = runTest(dispatcher) {
        ownership.snapshotFailure = ApiFailure(FailureKind.NETWORK); open()
        assertEquals(TransferLoad.FAILED, transfer?.load)
        runCurrent(); assertEquals(1, ownership.snapshots)
        ownership.snapshotFailure = null; model.retryTransferCheck(); runCurrent()
        assertEquals(2, ownership.snapshots); assertEquals(TransferLoad.LOADED, transfer?.load)
    }

    @Test fun a403HidesTheRowAndRereadsTheRoomOnce() = runTest(dispatcher) {
        ownership.snapshotFailure = ApiFailure(FailureKind.HTTP, 403, "forbidden")
        open(); val details = rooms.details
        assertEquals(TransferLoad.HIDDEN, transfer?.load)
        model.retryTransferCheck(); runCurrent()
        assertEquals("No Try again after a 403", 1, ownership.snapshots)
        assertTrue(rooms.details >= details)
    }

    @Test fun a404RoomNotFoundTakesTheRoomUnavailablePath() = runTest(dispatcher) {
        ownership.snapshotFailure = ApiFailure(FailureKind.HTTP, 404, "room_not_found"); open()
        assertNull(transfer); assertNull(model.state.value.selected)
        assertEquals("room_not_found", model.state.value.error?.code)
    }

    @Test fun aSuccessfulTransferAdoptsTheRoomRereadsAndShowsTheLine() = runTest(dispatcher) {
        open(); pick()
        assertTrue(transfer!!.confirming)
        rooms.room = room("moderator") // What the server will report after the transfer.
        model.confirmTransfer(); runCurrent()
        assertEquals(listOf(8L), ownership.transfers)
        assertEquals(TransferOutcome.SUCCESS, transfer?.outcome); assertEquals("Fixture 8", transfer?.outcomeName)
        assertFalse(transfer!!.pickerOpen)
        assertEquals("moderator", model.state.value.selected?.memberRole)
        assertEquals("No longer an owner: no row", TransferLoad.HIDDEN, transfer?.load)
        assertTrue("Directory re-read", rooms.lists >= 2)
    }

    @Test fun stepUpIsAskedOnceThenTheTransferIsSentOnceMore() = runTest(dispatcher) {
        ownership.stepUpFirst = true; open(); pick()
        model.confirmTransfer(); runCurrent()
        assertNotNull("The shared step-up prompt shows", transfer?.methods)
        model.verifyTransfer("fixture-password", false); runCurrent()
        assertEquals("One definite step-up refusal, then exactly one more POST", listOf(8L, 8L), ownership.transfers)
        assertEquals(TransferOutcome.SUCCESS, transfer?.outcome)
    }

    @Test fun cancellingStepUpSendsNothingMoreAndKeepsTheChoice() = runTest(dispatcher) {
        ownership.stepUpFirst = true; open(); pick()
        model.confirmTransfer(); runCurrent()
        model.cancelTransferVerification(); runCurrent()
        assertEquals(1, ownership.transfers.size); assertNull(transfer?.methods); assertNull(transfer?.outcome)
        assertTrue(transfer!!.pickerOpen); assertEquals(8L, transfer?.chosen?.id)
    }

    @Test fun eachRefusalShowsItsOwnLineAndNothingIsResent() = runTest(dispatcher) {
        val cases = listOf(ApiFailure(FailureKind.HTTP, 409, "already_owner") to TransferOutcome.ALREADY_OWNER,
            ApiFailure(FailureKind.HTTP, 409, "transfer_target_not_eligible") to TransferOutcome.NOT_ELIGIBLE,
            ApiFailure(FailureKind.HTTP, 404, "membership_not_found") to TransferOutcome.NOT_MEMBER,
            ApiFailure(FailureKind.HTTP, 409, "ownership_changed") to TransferOutcome.CHANGED,
            ApiFailure(FailureKind.HTTP, 403, "forbidden") to TransferOutcome.FORBIDDEN)
        open()
        for ((failure, outcome) in cases) {
            ownership.transferFailure = failure
            if (transfer?.pickerOpen != true) model.openTransferPicker()
            runCurrent(); model.chooseTransferTarget(transfer!!.candidates.first()); runCurrent()
            val sent = ownership.transfers.size
            model.confirmTransfer(); runCurrent()
            assertEquals(failure.code, outcome, transfer?.outcome)
            assertEquals("One POST per choice", sent + 1, ownership.transfers.size)
        }
    }

    @Test fun notEligibleKeepsThePersonListedAndGivesNoReason() = runTest(dispatcher) {
        open(); pick(9)
        ownership.transferFailure = ApiFailure(FailureKind.HTTP, 409, "transfer_target_not_eligible")
        model.confirmTransfer(); runCurrent()
        assertEquals(TransferOutcome.NOT_ELIGIBLE, transfer?.outcome)
        assertTrue("Still listed: hiding them would hint at the cause", transfer!!.candidates.any { it.id == 9L })
        assertTrue(transfer!!.pickerOpen)
    }

    @Test fun anAmbiguousReplyIsUnknownNeverRetriedAndOnlyCheckRoomResolvesIt() = runTest(dispatcher) {
        open(); pick()
        ownership.transferFailure = ApiFailure(FailureKind.TIMEOUT)
        model.confirmTransfer(); runCurrent()
        assertEquals(TransferOutcome.UNKNOWN, transfer?.outcome)
        model.closeTransferPicker(); model.chooseTransferTarget(transfer!!.candidates.first()); runCurrent()
        assertTrue("Locked until checked", transfer!!.pickerOpen); assertEquals(1, ownership.transfers.size)
        rooms.room = room("moderator"); ownership.transferFailure = null
        model.checkTransferRoom(); runCurrent()
        assertNull(transfer?.outcome); assertFalse(transfer!!.pickerOpen)
        assertEquals("The actual state, never a resend", 1, ownership.transfers.size)
        assertEquals("moderator", model.state.value.selected?.memberRole)
    }

    @Test fun leavingMidPostForcesCheckRoomOnTheNextOpenBeforeAnyNewChoice() = runTest(dispatcher) {
        open(); pick()
        val hold = CompletableDeferred<Unit>(); ownership.transferGate = hold
        model.confirmTransfer(); runCurrent()
        model.background(); runCurrent() // Genuine background retires the flow while the POST's result is open.
        model.foreground(); model.select(42); runCurrent()
        assertEquals("The possible commit is remembered", TransferOutcome.UNKNOWN, transfer?.outcome)
        model.openTransferPicker(); runCurrent()
        assertFalse("No new choice before Check room", transfer!!.pickerOpen)
        model.checkTransferRoom(); runCurrent()
        assertNull(transfer?.outcome)
        model.closeDetail(); runCurrent(); model.select(42); runCurrent()
        assertNull("Checked: the marker is gone", transfer?.outcome)
        assertEquals("Never resent", 1, ownership.transfers.size)
        hold.complete(Unit)
    }

    @Test fun leavingDuringThePostSuccessRereadIsNotAnUnknown() = runTest(dispatcher) {
        open(); pick()
        rooms.room = room("moderator")
        val hold = CompletableDeferred<Unit>(); rooms.detailGate = hold
        model.confirmTransfer(); runCurrent()
        assertEquals("Confirmed", TransferOutcome.SUCCESS, transfer?.outcome)
        model.background(); runCurrent() // Leaves while the success re-read is still running.
        hold.complete(Unit); rooms.detailGate = null
        model.foreground(); runCurrent(); rooms.room = room("owner"); model.select(42); runCurrent()
        assertNotEquals("A confirmed transfer is never recorded as unknown", TransferOutcome.UNKNOWN, transfer?.outcome)
        assertEquals(1, ownership.transfers.size)
    }

    @Test fun aFailedCheckRoomKeepsTheUnknownAndTheLock() = runTest(dispatcher) {
        open(); pick()
        ownership.transferFailure = ApiFailure(FailureKind.TIMEOUT)
        model.confirmTransfer(); runCurrent()
        assertEquals(TransferOutcome.UNKNOWN, transfer?.outcome)
        rooms.detailFailure = ApiFailure(FailureKind.NETWORK)
        model.checkTransferRoom(); runCurrent()
        assertEquals("Nothing was learned: still unknown", TransferOutcome.UNKNOWN, transfer?.outcome)
        assertFalse(transfer!!.checking); assertTrue("Still locked", transfer!!.pickerOpen)
        model.closeTransferPicker(); runCurrent(); assertTrue(transfer!!.pickerOpen)
        rooms.detailFailure = null // Reopening needs a readable room; the marker must still be there.
        model.closeDetail(); runCurrent(); model.select(42); runCurrent()
        assertEquals("The marker survives a failed check", TransferOutcome.UNKNOWN, transfer?.outcome)
        model.checkTransferRoom(); runCurrent()
        assertNull(transfer?.outcome)
        assertEquals("Never resent", 1, ownership.transfers.size)
    }

    @Test fun aRetiredFlowsLateResultNeverReachesASuccessor() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>(); ownership.snapshotGate = gate
        open()
        model.closeDetail(); runCurrent()
        rooms.session.value = rooms.session.value.copy(generation = 2); runCurrent()
        gate.complete(Unit); runCurrent()
        assertNull("The old snapshot can't repopulate", transfer)
    }

    private class Rooms : RoomOperations {
        override val session = MutableStateFlow(SessionState(generation = 1, phase = SessionPhase.AUTHENTICATED, user = user()))
        var room = room("owner"); var lists = 0; var details = 0
        var detailGate: CompletableDeferred<Unit>? = null; var detailFailure: ApiFailure? = null
        override suspend fun list(): List<RoomInfo> { lists++; return listOf(room) }
        override suspend fun detail(roomId: Long): RoomInfo { details++; detailGate?.await(); detailFailure?.let { throw it }; return room }
        override suspend fun join(roomId: Long) = room
        override suspend fun leave(roomId: Long) = Unit
        override suspend fun create(input: CreateRoom) = room
    }

    private inner class Ownership : RoomOwnershipOperations {
        override val session get() = rooms.session
        var snapshots = 0; val transfers = mutableListOf<Long>()
        var candidates = listOf(member(8, "moderator"), member(9, "member"))
        var snapshotFailure: ApiFailure? = null; var transferFailure: ApiFailure? = null
        var snapshotGate: CompletableDeferred<Unit>? = null; var transferGate: CompletableDeferred<Unit>? = null
        var stepUpFirst = false
        override suspend fun transferSnapshot(roomId: Long): RoomTransferSnapshot {
            snapshots++; snapshotGate?.await(); snapshotFailure?.let { throw it }
            return RoomTransferSnapshot(roomId, member(7, "owner"), candidates)
        }
        override suspend fun transferOwnership(roomId: Long, targetUserId: Long): RoomInfo {
            transfers += targetUserId
            transferGate?.await()
            if (stepUpFirst) { stepUpFirst = false; throw ApiFailure(FailureKind.HTTP, 403, "step_up_required") }
            transferFailure?.let { throw it }
            return rooms.room
        }
    }

    private class Account : AccountOperations {
        override val session = MutableStateFlow(SessionState())
        override suspend fun open(): AccountAccess = object : AccountAccess {
            override fun ensureCurrent() = Unit
            override suspend fun export() = PersonalExport(ByteArray(0))
            override suspend fun verificationMethods() = VerificationMethods(false, true, 0)
            override suspend fun verifyPassword(password: String) = Unit
            override suspend fun verifyRecovery(code: String) = 0
        }
    }

    companion object {
        private fun user(roles: List<String> = listOf("member")) = SessionUser(7, "Alice", roles, 1, false)
        private fun member(id: Long, role: String) = RoomTransferMember(id, "Fixture $id", role)
        private fun room(role: String?) = RoomInfo(42, "lounge", "Lounge", "Information", "public", 0, 0, 7, role, false, "none")
    }
}

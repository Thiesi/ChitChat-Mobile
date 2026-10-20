package io.github.thiesi.chitchat.feature.rooms

import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.network.FailureKind
import io.github.thiesi.chitchat.core.network.SessionUser
import io.github.thiesi.chitchat.core.rooms.CreateRoom
import io.github.thiesi.chitchat.core.rooms.RoomInfo
import io.github.thiesi.chitchat.core.rooms.RoomOperations
import io.github.thiesi.chitchat.core.session.SessionPhase
import io.github.thiesi.chitchat.core.session.SessionState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RoomsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val fake = Rooms()
    private lateinit var model: RoomsViewModel
    @Before fun setUp() { Dispatchers.setMain(dispatcher); model = RoomsViewModel(fake) }
    @After fun tearDown() { model.cancel(); Dispatchers.resetMain() }
    private fun TestScope.load() { model.refresh(); runCurrent() }
    private fun TestScope.select() { model.select(42); runCurrent() }

    @Test fun directoryAndDetailLoadWithoutJoiningOrCreatingPresence() = runTest(dispatcher) {
        load(); select()
        assertEquals(1, fake.lists); assertEquals(1, fake.details)
        assertEquals("Lounge", model.state.value.selected?.name)
        assertFalse(model.state.value.stale); assertEquals(0, fake.joins)
    }

    @Test fun joinReconcilesDirectoryAndDetailExactlyOnce() = runTest(dispatcher) {
        load(); select(); model.join(); advanceUntilIdle()
        assertEquals(1, fake.joins); assertEquals(2, fake.lists); assertEquals(2, fake.details)
        assertEquals("member", model.state.value.selected?.memberRole)
        assertEquals("joined", model.state.value.notice)
    }

    @Test fun leaveRequiresConfirmationAndOwnerCanNeverLeave() = runTest(dispatcher) {
        fake.room = room("member"); load(); select()
        model.leave(); runCurrent(); assertEquals(0, fake.leaves)
        model.confirmLeave(true); model.leave(); advanceUntilIdle()
        assertEquals(1, fake.leaves); assertFalse(model.state.value.detailOpen)
        fake.room = room("owner"); select(); model.confirmLeave(true); model.leave(); advanceUntilIdle()
        assertFalse(model.state.value.leaveConfirmation); assertEquals(1, fake.leaves)
    }

    @Test fun roleGatePreventsMemberAndGuestCreation() = runTest(dispatcher) {
        load(); model.showCreate(); assertNull(model.state.value.draft)
        fake.session.value = fake.session.value.copy(user = user(listOf("chat_admin"), guest = true))
        runCurrent(); model.showCreate(); model.create(); advanceUntilIdle()
        assertNull(model.state.value.draft); assertEquals(0, fake.creates)
    }

    @Test fun createUsesAllExplicitChoicesAndShowsOwnedResult() = runTest(dispatcher) {
        fake.session.value = fake.session.value.copy(user = user(listOf("chat_admin")))
        load(); model.showCreate()
        model.updateDraft(RoomDraft("  New_ROOM  ", "New room", "Information", "unlisted", "18", "120", "none"))
        model.create(); advanceUntilIdle()
        assertEquals(1, fake.creates)
        assertEquals("  New_ROOM  ", fake.created?.key); assertEquals(18, fake.created?.minimumAge)
        assertEquals("unlisted", fake.created?.visibility); assertEquals(120, fake.created?.inactivityTimeoutSeconds)
        assertEquals("owner", model.state.value.selected?.memberRole); assertNull(model.state.value.draft)
    }

    @Test fun invalidGuestCombinationPreservesDraftAndNeverPosts() = runTest(dispatcher) {
        fake.session.value = fake.session.value.copy(user = user(listOf("admin")))
        load(); model.showCreate()
        val draft = RoomDraft("new_room", "Room", visibility = "private", guestAccess = "write")
        model.updateDraft(draft); model.create(); advanceUntilIdle()
        assertEquals(draft, model.state.value.draft)
        assertEquals("guest_access_scope", model.state.value.validation); assertEquals(0, fake.creates)
        model.updateDraft(draft.copy(guestAccess = "none", inactivitySeconds = "119")); model.create()
        assertEquals("invalid_inactivity_timeout", model.state.value.validation)
        model.updateDraft(draft.copy(guestAccess = "none", minimumAge = "1.5")); model.create()
        assertEquals("invalid_room_numbers", model.state.value.validation)
    }

    @Test fun failedCreationKeepsDraftAndNeverReplaysOnRefresh() = runTest(dispatcher) {
        fake.session.value = fake.session.value.copy(user = user(listOf("admin")))
        load(); model.showCreate(); model.updateDraft(RoomDraft("new_room", "Room"))
        fake.failure = ApiFailure(FailureKind.HTTP, 409, "room_key_taken")
        model.create(); advanceUntilIdle()
        assertEquals("room_key_taken", model.state.value.error?.code)
        assertNotNull(model.state.value.draft); assertFalse(model.state.value.mutationUncertain)
        fake.failure = null; model.refresh(); advanceUntilIdle(); assertEquals(1, fake.creates)
    }

    @Test fun failedLeaveRemainsVisibleAndDoesNotReplay() = runTest(dispatcher) {
        fake.room = room("member"); load(); select()
        fake.failure = ApiFailure(FailureKind.TIMEOUT)
        model.confirmLeave(true); model.leave(); advanceUntilIdle()
        assertEquals("member", model.state.value.selected?.memberRole)
        assertTrue(model.state.value.mutationUncertain)
        model.confirmLeave(true); model.leave(); advanceUntilIdle(); assertEquals(1, fake.leaves)
    }

    @Test fun policyAndConflictFailuresAreExposedWithoutReplayingMutation() = runTest(dispatcher) {
        load(); select()
        for (code in listOf("invitation_required", "birth_date_required", "minimum_age_not_met")) {
            fake.failure = ApiFailure(FailureKind.HTTP, 403, code)
            model.join(); advanceUntilIdle()
            assertEquals(code, model.state.value.error?.code)
            assertFalse(model.state.value.mutationUncertain)
            fake.failure = null; model.refresh(); advanceUntilIdle()
        }
        assertEquals(3, fake.joins)
    }

    @Test fun ambiguousMutationRequiresSuccessfulReconciliationAndNeverAutoReplays() = runTest(dispatcher) {
        load(); select(); fake.failure = ApiFailure(FailureKind.NETWORK)
        model.join(); advanceUntilIdle(); model.join(); advanceUntilIdle()
        assertEquals(1, fake.joins); assertTrue(model.state.value.mutationUncertain)
        model.refresh(); advanceUntilIdle(); assertTrue(model.state.value.mutationUncertain)
        fake.failure = null; model.refresh(); advanceUntilIdle()
        assertFalse(model.state.value.mutationUncertain); assertEquals(1, fake.joins)
        model.join(); advanceUntilIdle(); assertEquals(2, fake.joins)
    }

    @Test fun cancellationIgnoresLateSameSessionResponseAndBlocksNewMutation() = runTest(dispatcher) {
        load(); select(); fake.gate = CompletableDeferred()
        model.join(); runCurrent(); model.cancelRequest()
        assertTrue(model.state.value.mutationUncertain)
        fake.gate!!.complete(Unit); advanceUntilIdle()
        assertNull(model.state.value.selected?.memberRole)
        model.join(); advanceUntilIdle(); assertEquals(1, fake.joins)
    }

    @Test fun logoutScrubsDirectoryDraftLookupAndLateResponse() = runTest(dispatcher) {
        fake.session.value = fake.session.value.copy(user = user(listOf("admin")))
        load(); model.updateLookup("42"); model.showCreate(); model.updateDraft(RoomDraft(name = "Private draft"))
        fake.gate = CompletableDeferred(); model.select(42); runCurrent()
        fake.session.value = SessionState(generation = 2, phase = SessionPhase.SIGNED_OUT)
        runCurrent(); fake.gate!!.complete(Unit); advanceUntilIdle()
        assertEquals(RoomsUiState(), model.state.value)
    }

    @Test fun accountAndSessionVersionChangeNeverPublishesOldRooms() = runTest(dispatcher) {
        fake.gate = CompletableDeferred(); model.refresh(); runCurrent()
        fake.session.value = fake.session.value.copy(user = SessionUser(8, "Bob", listOf("member"), 2, false))
        runCurrent(); fake.gate!!.complete(Unit); advanceUntilIdle()
        assertTrue(model.state.value.rooms.isEmpty()); assertFalse(model.state.value.loaded)
    }

    @Test fun foregroundWaitsForAuthValidationAndCoalescesRefreshes() = runTest(dispatcher) {
        fake.session.value = fake.session.value.copy(busy = true)
        model.foreground(); model.refresh(); runCurrent(); assertEquals(0, fake.lists)
        fake.session.value = fake.session.value.copy(busy = false); advanceUntilIdle()
        assertEquals(1, fake.lists); assertFalse(model.state.value.busy)
    }

    @Test fun cancelledReadAndUnavailableDetailDoNotRetainFalseAuthoritativeState() = runTest(dispatcher) {
        load(); select(); fake.failure = ApiFailure(FailureKind.HTTP, 404, "room_not_found")
        model.refresh(); advanceUntilIdle()
        assertTrue(model.state.value.stale)
        fake.failure = null; fake.missingDetail = true; model.refresh(); advanceUntilIdle()
        assertFalse(model.state.value.detailOpen); assertFalse(model.state.value.stale)
    }

    @Test fun invalidLookupNeverCallsServer() = runTest(dispatcher) {
        for (id in listOf("", "0", "-1", "word", "99999999999999999999999")) {
            model.updateLookup(id); model.lookup(); advanceUntilIdle()
            assertEquals("invalid_room_id", model.state.value.validation)
        }
        assertEquals(0, fake.details)
    }

    private class Rooms : RoomOperations {
        override val session = MutableStateFlow(SessionState(generation = 1, phase = SessionPhase.AUTHENTICATED, user = user()))
        var room = room(); var lists = 0; var details = 0; var joins = 0; var leaves = 0; var creates = 0
        var gate: CompletableDeferred<Unit>? = null
        var failure: ApiFailure? = null
        var created: CreateRoom? = null
        var missingDetail = false
        private suspend fun request() { gate?.let { withContext(NonCancellable) { it.await() } }; failure?.let { throw it } }
        override suspend fun list(): List<RoomInfo> { lists++; request(); return listOf(room) }
        override suspend fun detail(roomId: Long): RoomInfo {
            details++; request(); if (missingDetail) throw ApiFailure(FailureKind.HTTP, 404, "room_not_found"); return room
        }
        override suspend fun join(roomId: Long): RoomInfo { joins++; request(); room = room("member"); return room }
        override suspend fun leave(roomId: Long) { leaves++; request(); room = room() }
        override suspend fun create(input: CreateRoom): RoomInfo { creates++; created = input; request(); room = room("owner"); return room }
    }

    companion object {
        private fun user(roles: List<String> = listOf("member"), guest: Boolean = false) = SessionUser(7, "Alice", roles, 1, guest)
        private fun room(role: String? = null) = RoomInfo(42, "lounge", "Lounge", "Information", "public", 0, 0, 7, role, false, "none")
    }
}

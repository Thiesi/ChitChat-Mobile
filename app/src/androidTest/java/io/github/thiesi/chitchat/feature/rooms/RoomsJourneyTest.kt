package io.github.thiesi.chitchat.feature.rooms

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.network.FailureKind
import io.github.thiesi.chitchat.core.network.SessionUser
import io.github.thiesi.chitchat.core.rooms.CreateRoom
import io.github.thiesi.chitchat.core.rooms.RoomInfo
import io.github.thiesi.chitchat.core.rooms.RoomOperations
import io.github.thiesi.chitchat.core.session.SessionPhase
import io.github.thiesi.chitchat.core.session.SessionState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class RoomsJourneyTest {
    @get:Rule val compose = createComposeRule()
    private val fake = TestRooms()
    private val model = RoomsViewModel(fake)
    @After fun tearDown() { model.cancel(); fake.gate?.complete(Unit) }
    private fun content(onRefresh: () -> Unit = {}) {
        compose.setContent { MaterialTheme { Box(Modifier.size(320.dp, 480.dp)) { RoomsScreen(model, onRefresh, {}) } } }
        compose.waitUntil(5_000) { model.state.value.loaded && !model.state.value.busy }
    }
    private fun detail() {
        compose.onNodeWithTag("rooms_directory").performScrollToNode(hasText("Lounge"))
        compose.onNodeWithText("Lounge").performClick()
        compose.waitUntil(5_000) { model.state.value.selected != null && !model.state.value.busy }
    }

    @Test fun compactDirectoryShowsMembershipCappedUnreadAndRealMetadata() {
        fake.room = room(role = "member", unread = 100)
        content()
        compose.onNodeWithText("Lounge").assertIsDisplayed()
        compose.onNodeWithText("Joined").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("99+ unread").performScrollTo().assertIsDisplayed()
        detail()
        compose.onNodeWithText("Room #42 · lounge").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Your room role: member").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Leave room").performScrollTo().performClick()
        compose.onNodeWithText("Leave this room?").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertEquals(0, fake.leaves) }
    }

    @Test fun privateInvitationExplainsHistoryAndJoinsOnlyOnExplicitAction() {
        fake.room = room(visibility = "private", invited = true)
        content()
        compose.onNodeWithText("Invited").performScrollTo().assertIsDisplayed()
        detail()
        compose.onNodeWithText("An invitation reveals room details", substring = true).performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, fake.joins) }
        compose.onNodeWithText("Join room").performScrollTo().performClick()
        compose.waitUntil(5_000) { model.state.value.selected?.isMember == true && !model.state.value.busy }
        compose.onNodeWithText("You joined the room.").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, fake.joins) }
    }

    @Test fun ownerCannotLeaveAndOrdinaryMemberCannotCreate() {
        fake.room = room(role = "owner")
        content()
        compose.onNodeWithText("Create room").assertDoesNotExist()
        detail()
        compose.onNodeWithText("Room ownership cannot be transferred here", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Leave room").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, fake.leaves); assertEquals(0, fake.creates) }
    }

    @Test fun creationKeepsIncompatibleChoicesAndRequiresExplicitCorrection() {
        fake.session.value = fake.session.value.copy(user = SessionUser(7, "Alice", listOf("chat_admin"), 1, false))
        content()
        compose.onNodeWithText("Create room").performScrollTo().performClick()
        compose.onNodeWithText("Room key").performScrollTo().performTextInput("native_room")
        compose.onNodeWithText("Room name").performScrollTo().performTextInput("Native room")
        compose.onNodeWithText("Read and write").performScrollTo().performClick()
        compose.onNodeWithText("Private").performScrollTo().performClick()
        compose.onNodeWithText("Create").performClick()
        compose.waitUntil(5_000) { model.state.value.validation == "guest_access_scope" }
        compose.onNodeWithText("Read and write").performScrollTo().assertIsSelected()
        compose.runOnIdle { assertEquals("private", model.state.value.draft?.visibility); assertEquals(0, fake.creates) }
        compose.onNodeWithText("No guests").performScrollTo().performClick()
        compose.onNodeWithText("Create").performClick()
        compose.waitUntil(5_000) { model.state.value.selected?.memberRole == "owner" && !model.state.value.busy }
        compose.onNodeWithText("Room created.").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, fake.creates); assertEquals("private", fake.created?.visibility) }
    }

    @Test fun policyErrorIsReadableAndAmbiguousFailureRequiresReconciliation() {
        content(); detail()
        fake.failure = ApiFailure(FailureKind.HTTP, 403, "minimum_age_not_met")
        compose.onNodeWithText("Join room").performScrollTo().performClick()
        compose.onNodeWithText("Your account does not meet this room’s minimum age.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Join room").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { fake.failure = null }
        compose.onNodeWithText("Check current room state").performScrollTo().performClick()
        compose.waitUntil(5_000) { !model.state.value.stale && !model.state.value.busy }
        compose.runOnIdle { fake.failure = ApiFailure(FailureKind.TIMEOUT) }
        compose.onNodeWithText("Join room").performScrollTo().performClick()
        compose.waitUntil(5_000) { model.state.value.mutationUncertain }
        compose.onNodeWithText("Join room").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { assertEquals(2, fake.joins) }
    }

    @Test fun cancelledMutationCannotPublishLateCompletion() {
        content(); detail()
        compose.runOnIdle { fake.gate = CompletableDeferred() }
        compose.onNodeWithText("Join room").performScrollTo().performClick()
        compose.waitUntil(5_000) { fake.joins == 1 }
        compose.onNode(hasText("Cancel") and hasAnyAncestor(isDialog())).performScrollTo().performClick()
        compose.runOnIdle { fake.gate!!.complete(Unit) }
        compose.waitUntil(5_000) { !model.state.value.busy }
        compose.onNodeWithText("Join room").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { assertTrue(model.state.value.mutationUncertain); assertNull(model.state.value.selected?.memberRole) }
    }

    @Test fun restoredCompositionUsesVmDraftButLogoutScrubsIt() {
        fake.session.value = fake.session.value.copy(user = SessionUser(7, "Alice", listOf("admin"), 1, false))
        val restoration = StateRestorationTester(compose)
        restoration.setContent { MaterialTheme { Box(Modifier.size(320.dp, 480.dp)) { RoomsScreen(model, {}, {}) } } }
        compose.waitUntil(5_000) { model.state.value.loaded && !model.state.value.busy }
        compose.onNodeWithText("Create room").performScrollTo().performClick()
        compose.onNodeWithText("Room name").performScrollTo().performTextInput("Account-scoped draft")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Account-scoped draft").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { fake.session.value = SessionState(generation = 2, phase = SessionPhase.SIGNED_OUT) }
        compose.onNodeWithText("Account-scoped draft").assertDoesNotExist()
        compose.runOnIdle { assertNull(model.state.value.draft); assertTrue(model.state.value.rooms.isEmpty()) }
    }

    @Test fun createDialogOffersSessionRefreshWithoutDiscardingDraftOrReplayingPost() {
        fake.session.value = fake.session.value.copy(user = SessionUser(7, "Alice", listOf("admin"), 1, false))
        var refreshes = 0
        content { refreshes++; fake.session.value = fake.session.value.copy(refreshRequired = false) }
        compose.onNodeWithText("Create room").performScrollTo().performClick()
        compose.onNodeWithText("Room name").performScrollTo().performTextInput("Retained draft")
        compose.runOnIdle { fake.session.value = fake.session.value.copy(refreshRequired = true) }
        compose.onNodeWithText("Create").assertIsNotEnabled()
        compose.onNode(hasText("Refresh session") and hasAnyAncestor(isDialog())).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, refreshes); assertEquals("Retained draft", model.state.value.draft?.name); assertEquals(0, fake.creates) }
    }

    private class TestRooms : RoomOperations {
        override val session = MutableStateFlow(SessionState(generation = 1, phase = SessionPhase.AUTHENTICATED,
            user = SessionUser(7, "Alice", listOf("member"), 1, false)))
        var room = room(); var joins = 0; var leaves = 0; var creates = 0
        var failure: ApiFailure? = null; var gate: CompletableDeferred<Unit>? = null; var created: CreateRoom? = null
        override suspend fun list() = listOf(room)
        override suspend fun detail(roomId: Long): RoomInfo = room
        override suspend fun join(roomId: Long): RoomInfo {
            joins++; gate?.let { withContext(NonCancellable) { it.await() } }
            failure?.let { throw it }; room = room("member"); return room
        }
        override suspend fun leave(roomId: Long) { leaves++; room = room() }
        override suspend fun create(input: CreateRoom): RoomInfo { creates++; created = input; room = room("owner"); return room }
    }

    companion object {
        private fun room(role: String? = null, unread: Int = 0, visibility: String = "public", invited: Boolean = false) =
            RoomInfo(42, "lounge", "Lounge", "Information", visibility, 0, 0, 7, role, invited, "none", unread)
    }
}

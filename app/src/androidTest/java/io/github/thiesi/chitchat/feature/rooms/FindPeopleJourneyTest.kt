package io.github.thiesi.chitchat.feature.rooms

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import io.github.thiesi.chitchat.R
import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.network.FailureKind
import io.github.thiesi.chitchat.core.rooms.CreateRoom
import io.github.thiesi.chitchat.core.rooms.RoomInfo
import io.github.thiesi.chitchat.core.rooms.RoomOperations
import io.github.thiesi.chitchat.core.server.ServerAddress
import io.github.thiesi.chitchat.core.session.SessionState
import io.github.thiesi.chitchat.core.settings.*
import io.github.thiesi.chitchat.app.SettingsViewModel
import io.github.thiesi.chitchat.feature.account.TestChitChatApp
import io.github.thiesi.chitchat.feature.auth.AuthViewModel
import io.github.thiesi.chitchat.feature.auth.TestSessions
import io.github.thiesi.chitchat.testing.openTab
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Find your people (onboarding.md step 5, P-54, O2b): new accounts only; each Join confirmed, never replayed. */
class FindPeopleJourneyTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val sessions = TestSessions().apply { registrationEnabled = true }
    private val auth = AuthViewModel(sessions)
    private val api = FakeRooms(sessions.state)
    private val rooms = RoomsViewModel(api)
    private val settings = SettingsViewModel(object : SettingsStore {
        override val settings = MutableStateFlow(AppSettings(server = ServerAddress.parse("chat.example.test")
            .let { (it as io.github.thiesi.chitchat.core.server.AddressResult.Valid).address }))
        override suspend fun saveServer(server: ServerAddress?) = Unit
        override suspend fun savePureBlack(enabled: Boolean) = Unit
        override suspend fun saveTheme(theme: ThemePreference) = Unit
    })
    private fun text(id: Int, vararg args: Any) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id, *args)

    @After fun release() { api.gate?.complete(Unit) }

    private fun install() = compose.runOnUiThread { compose.activity.setContent { TestChitChatApp(settings, auth, roomModel = rooms) } }
    private fun createAccount() {
        install()
        compose.onNodeWithTag("onboarding_create").performScrollTo().performClick()
        compose.onNodeWithText("Username").performScrollTo().performTextInput("Alice")
        for (label in listOf("Password", "Confirm password")) compose.onNodeWithText(label).performScrollTo().performTextInput("fixture-password")
        Espresso.closeSoftKeyboard()
        compose.onNodeWithText("Create account").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("find_people").fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(5_000) { rooms.state.value.loaded && !rooms.state.value.busy }
    }
    private fun join(id: Long) = compose.onNodeWithTag("find_people_join_$id").performScrollTo()

    @Test fun aNewAccountSeesFindPeopleWithOpenRoomsOnlyAndNoIconBack() {
        createAccount()
        compose.onNodeWithText(text(R.string.find_people_heading, "Alice")).assertExists()
        compose.onNodeWithTag("find_people_row_1").assertExists(); compose.onNodeWithTag("find_people_row_2").assertExists()
        compose.onNodeWithTag("find_people_row_3").assertDoesNotExist() // Private: not listed.
        compose.onNodeWithContentDescription(text(R.string.back)).assertDoesNotExist() // P-76 exception (O2b).
        compose.onAllNodesWithTag("nav_tab_rooms").assertCountEquals(0) // Pushed: no bar.
    }

    @Test fun aReturningSignInLandsOnRoomsNotFindPeople() {
        install()
        compose.onNodeWithTag("onboarding_sign_in").performScrollTo().performClick()
        compose.onNodeWithText("Username").performScrollTo().performTextInput("Alice")
        compose.onNodeWithText("Password").performScrollTo().performTextInput("fixture-password")
        Espresso.closeSoftKeyboard()
        compose.onNodeWithText("Sign in").performScrollTo().performClick()
        compose.openTab("rooms").assertIsSelected()
        compose.onNodeWithTag("find_people").assertDoesNotExist()
    }

    /** "Joined" only after the server confirms; one join at a time, so a second Join can't fire during the first. */
    @Test fun joinIsConfirmedByTheServerAndASecondJoinCannotFireMeanwhile() {
        createAccount()
        val gate = CompletableDeferred<Unit>().also { api.gate = it }
        join(1).performClick()
        compose.onNodeWithText(text(R.string.find_people_joining)).assertExists()
        compose.onNodeWithTag("find_people_joined_1").assertDoesNotExist()
        join(2).assertIsNotEnabled().performClick()
        compose.runOnIdle { assertEquals(1, api.joins) }
        compose.runOnIdle { gate.complete(Unit) }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("find_people_joined_1").fetchSemanticsNodes().isNotEmpty() }
        join(2).assertIsEnabled()
        compose.runOnIdle { assertEquals(1, api.joins) }
    }

    /** An ambiguous failure: inline on that row, Check room is a GET only, never a resend. */
    @Test fun anUncertainJoinOffersCheckRoomWhichNeverResends() {
        createAccount()
        api.failNext = ApiFailure(FailureKind.TIMEOUT); api.commitDespiteFailure = true
        join(1).performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("find_people_check_1").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(text(R.string.find_people_uncertain)).assertExists()
        join(2).assertIsNotEnabled() // No other write until the explicit check, and the row says why.
        compose.onNode(hasText(text(R.string.find_people_check_first)) and hasAnyAncestor(hasTestTag("find_people_row_1"))).assertExists()
        val lists = api.lists
        compose.onNodeWithTag("find_people_check_1").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("find_people_joined_1").fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle { assertEquals(1, api.joins); assertTrue(api.lists > lists) }
    }

    /** Shown once, for the new account only: a sign-out and a later sign-in land on Rooms. */
    @Test fun aLaterSignInAfterSignOutDoesNotShowItAgain() {
        createAccount()
        compose.onNodeWithTag("find_people_start").performClick()
        compose.openTab("rooms").assertIsSelected()
        compose.runOnIdle { auth.signOut() }
        compose.onNodeWithText(text(R.string.back_to_sign_in)).performScrollTo().performClick()
        compose.onNodeWithTag("onboarding_sign_in").performScrollTo().performClick()
        compose.onNodeWithText("Username").performScrollTo().performTextInput("Alice")
        compose.onNodeWithText("Password").performScrollTo().performTextInput("fixture-password")
        Espresso.closeSoftKeyboard()
        compose.onNodeWithText("Sign in").performScrollTo().performClick()
        compose.openTab("rooms").assertIsSelected()
        compose.onNodeWithTag("find_people").assertDoesNotExist()
    }

    /** A definite refusal has a known outcome: shown inline, reconciled by the list GET, and other rooms stay joinable. */
    @Test fun aDefiniteRefusalShowsInlineAndBlocksNothing() {
        createAccount()
        api.failNext = ApiFailure(FailureKind.HTTP, 403, "minimum_age_not_met")
        val lists = api.lists
        join(1).performClick()
        compose.waitUntil(5_000) { rooms.state.value.joinRefusal != null && !rooms.state.value.busy }
        compose.onNodeWithText(text(R.string.rooms_minimum_age_not_met)).assertExists()
        compose.onNodeWithTag("find_people_check_1").assertDoesNotExist()
        compose.onNodeWithText(text(R.string.find_people_check_first)).assertDoesNotExist()
        join(1).assertIsEnabled(); join(2).assertIsEnabled()
        compose.runOnIdle { assertEquals(1, api.joins); assertTrue("Reconciled by a list GET", api.lists > lists) }
        join(2).performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("find_people_joined_2").fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle { assertEquals(2, api.joins) }
    }

    @Test fun startChattingGoesToTheRoomsRoot() {
        createAccount()
        compose.onNodeWithTag("find_people_start").performClick()
        compose.openTab("rooms").assertIsSelected(); compose.onNodeWithTag("find_people").assertDoesNotExist()
    }

    @Test fun systemBackDoesWhatStartChattingDoes() {
        createAccount()
        Espresso.pressBack()
        compose.openTab("rooms").assertIsSelected(); compose.onNodeWithTag("find_people").assertDoesNotExist()
        assertFalse(compose.activity.isFinishing)
    }

    /** Leaving the screen doesn't cancel an in-flight join; its result reconciles through the Rooms list. */
    @Test fun aJoinInFlightSurvivesStartChatting() {
        createAccount()
        val gate = CompletableDeferred<Unit>().also { api.gate = it }
        join(1).performClick()
        compose.onNodeWithTag("find_people_start").performClick()
        compose.openTab("rooms").assertIsSelected()
        compose.runOnIdle { gate.complete(Unit) }
        compose.waitUntil(5_000) { rooms.state.value.rooms.firstOrNull { it.id == 1L }?.isMember == true && !rooms.state.value.busy }
        compose.runOnIdle { assertEquals(1, api.joins); assertFalse(rooms.state.value.mutationUncertain) }
    }

    /**
     * A genuine app background still cancels (M3a) and marks the join uncertain. Returning runs the ordinary foreground
     * list GET, which settles it; the join is never resent, and Join comes back only as a fresh explicit choice.
     */
    @Test fun aGenuineBackgroundCancelsTheJoinAndMarksItUncertain() {
        createAccount()
        api.gate = CompletableDeferred()
        join(1).performClick()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.runOnIdle { assertTrue(rooms.state.value.mutationUncertain); assertFalse(rooms.state.value.busy) }
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitUntil(5_000) { !rooms.state.value.busy && !rooms.state.value.stale }
        join(1).assertIsEnabled()
        compose.onNodeWithTag("find_people_joined_1").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, api.joins) }
    }

    @Test fun noOpenRoomsShowsTheEmptyLineAndStartChatting() {
        api.rooms.removeAll { it.visibility == "public" }
        createAccount()
        compose.onNodeWithTag("find_people_empty").assertExists()
        compose.onNodeWithTag("find_people_start").assertIsDisplayed()
    }

    private class FakeRooms(override val session: StateFlow<SessionState>) : RoomOperations {
        val rooms = mutableListOf(room(1, "Lounge", "Say hello"), room(2, "Garden", ""), room(3, "Staff", "Team", "private"))
        @Volatile var gate: CompletableDeferred<Unit>? = null
        @Volatile var failNext: ApiFailure? = null
        @Volatile var commitDespiteFailure = false
        @Volatile var joins = 0
        @Volatile var lists = 0
        override suspend fun list(): List<RoomInfo> { lists++; return rooms.toList() }
        override suspend fun detail(roomId: Long) = rooms.first { it.id == roomId }
        override suspend fun join(roomId: Long): RoomInfo {
            joins++
            gate?.await()
            failNext?.let { failure ->
                failNext = null
                if (commitDespiteFailure) member(roomId) // Committed, but the response was lost.
                throw failure
            }
            return member(roomId)
        }
        private fun member(id: Long): RoomInfo {
            val i = rooms.indexOfFirst { it.id == id }
            rooms[i] = room(id, rooms[i].name, rooms[i].infoLine, rooms[i].visibility, "member")
            return rooms[i]
        }
        override suspend fun leave(roomId: Long) = error("Unexpected leave")
        override suspend fun create(input: CreateRoom): RoomInfo = error("Unexpected create")
        companion object {
            fun room(id: Long, name: String, info: String, visibility: String = "public", role: String? = null) =
                RoomInfo(id, "room$id", name, info, visibility, 0, 0, 7, role, false, "none")
        }
    }
}

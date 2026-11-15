package io.github.thiesi.chitchat.app

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import io.github.thiesi.chitchat.R
import io.github.thiesi.chitchat.core.conversation.ConversationOperations
import io.github.thiesi.chitchat.core.push.PendingPushTap
import io.github.thiesi.chitchat.core.push.PushTap
import io.github.thiesi.chitchat.core.rooms.*
import io.github.thiesi.chitchat.core.server.AddressResult
import io.github.thiesi.chitchat.core.server.ServerAddress
import io.github.thiesi.chitchat.core.session.SessionPhase
import io.github.thiesi.chitchat.core.network.SessionUser
import io.github.thiesi.chitchat.core.settings.*
import io.github.thiesi.chitchat.feature.account.TestChitChatApp
import io.github.thiesi.chitchat.feature.auth.AuthViewModel
import io.github.thiesi.chitchat.feature.auth.TestSessions
import io.github.thiesi.chitchat.feature.conversation.ConversationViewModel
import io.github.thiesi.chitchat.feature.direct.DirectInboxViewModel
import io.github.thiesi.chitchat.feature.direct.DirectThreadViewModel
import io.github.thiesi.chitchat.feature.notifications.NotificationCenterViewModel
import io.github.thiesi.chitchat.feature.rooms.RoomsViewModel
import io.github.thiesi.chitchat.testing.NativeChatFixtures
import io.github.thiesi.chitchat.testing.openTab
import io.github.thiesi.chitchat.testing.tapNativeInput
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Navigation shell N1 (navigation-shell.md, P-70–P-75): the bar, tab switches as departures, saved stacks and
 * stale scopes, Back and reselection, recreation, sign-out and the You hub. Typed fixtures, not live acceptance.
 */
class NavShellJourneyTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val sessions = TestSessions()
    private val api = NativeChatFixtures(sessions)
    private val auth = AuthViewModel(sessions)
    private val log = CopyOnWriteArrayList<String>()
    @Volatile private var historyGate: CompletableDeferred<Unit>? = null
    private val roomOps = object : ConversationOperations by api.rooms {
        override suspend fun history(roomId: Long, beforeId: Long?, limit: Int) =
            api.rooms.history(roomId, beforeId, limit).also { historyGate?.await(); log += "history" }
        override suspend fun read(roomId: Long, messageId: Long) = api.rooms.read(roomId, messageId).also { log += "read" }
    }
    private val directoryOps = object : RoomOperations {
        override val session = sessions.state
        private val rows = listOf(RoomInfo(43, "studio", "Room 43", "A shared room", "private", 0, 0, 8, "member", false, "none", 12, 88))
        override suspend fun list() = rows
        override suspend fun detail(roomId: Long) = rows.single { it.id == roomId }
        override suspend fun join(roomId: Long): RoomInfo = error("No membership mutation")
        override suspend fun leave(roomId: Long) { error("No membership mutation") }
        override suspend fun create(input: CreateRoom): RoomInfo = error("No room creation")
    }
    private val directory = RoomsViewModel(directoryOps)
    private val room = ConversationViewModel(roomOps)
    private val inbox = DirectInboxViewModel(api.directs)
    private val thread = DirectThreadViewModel(api.directs)
    private val notices = NotificationCenterViewModel(api.notices)
    private val settings = SettingsViewModel(object : SettingsStore {
        override val settings = MutableStateFlow(AppSettings(server = (ServerAddress.parse("chat.example.test") as AddressResult.Valid).address))
        override suspend fun saveServer(server: ServerAddress?) { settings.value = settings.value.copy(server = server) }
        override suspend fun savePureBlack(enabled: Boolean) = Unit
        override suspend fun saveTheme(theme: ThemePreference) = Unit
    })
    @Volatile private var ime = 0
    private var taps: PendingPushTap? = null
    private var targets: io.github.thiesi.chitchat.feature.targets.MessageTargetViewModel? = null

    @After fun close() {
        Espresso.closeSoftKeyboard()
        compose.runOnIdle { api.rooms.sendGate?.complete(Unit); historyGate?.complete(Unit)
            room.cancel(); directory.cancel(); inbox.background(); thread.background(); notices.background() }
    }
    private fun install(activity: ComponentActivity) = activity.setContent {
        val bottom = WindowInsets.ime.getBottom(LocalDensity.current)
        SideEffect { ime = bottom }
        Box(Modifier.fillMaxSize()) { TestChitChatApp(settings, auth, roomModel = directory, conversationModel = room,
            directInbox = inbox, directThread = thread, notifications = notices, messageTargets = targets, pushTaps = taps) }
    }
    private fun signedIn() {
        compose.runOnUiThread { install(compose.activity) }
        compose.waitUntil(5_000) { sessions.state.value.phase == SessionPhase.ANONYMOUS }
        compose.onNodeWithTag("nav_tab_rooms").assertDoesNotExist()
        compose.runOnIdle { runBlocking { sessions.signIn("Viewer", "fixture-password") }
            sessions.state.value = sessions.state.value.copy(chatPolicy = api.policy, privacy = api.privacy) }
        compose.openTab("rooms").assertIsDisplayed().assertIsSelected()
        compose.waitUntil(5_000) { directory.state.value.loaded && !directory.state.value.busy }
    }
    private fun text(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)
    private fun back() = compose.onNodeWithText(text(R.string.back)).assertIsDisplayed().performClick()
    private fun openRoom() {
        compose.onNodeWithTag("rooms_directory").performScrollToNode(hasText("Room 43"))
        compose.onNodeWithText("Room 43").assertIsDisplayed().performClick()
        compose.onNode(hasText(text(R.string.rooms_open_conversation)) and hasAnyAncestor(isDialog())).performScrollTo().performClick()
        compose.waitUntil(5_000) { room.state.value.roomId == 43L && room.state.value.loaded && !room.state.value.busy }
    }
    private fun bar(present: Boolean) = ShellTab.entries.forEach {
        if (present) compose.onNodeWithTag(it.tag).assertIsDisplayed() else compose.onNodeWithTag(it.tag).assertDoesNotExist()
    }

    @Test fun barIsOnTheFourRootsOnlyAndNeverSignedOut() {
        signedIn(); bar(true)
        compose.onNodeWithText(text(R.string.back)).assertDoesNotExist() // The Rooms root has no Back.
        openRoom(); bar(false); back(); bar(true)
        compose.openTab("direct").performClick(); compose.onNodeWithTag("dm_inbox").assertExists(); bar(true)
        compose.openTab("activity").performClick(); compose.onNodeWithTag("notification_list").assertExists(); bar(true)
        compose.openTab("you").performClick(); compose.onNodeWithTag("you_session").assertExists(); bar(true)
        compose.onNodeWithTag("you_account").performScrollTo().performClick(); bar(false); back(); bar(true)
        compose.runOnIdle { auth.signOut() }
        compose.waitUntil(5_000) { sessions.state.value.phase != SessionPhase.AUTHENTICATED }
        bar(false)
    }

    @Test fun aTabSwitchIsADepartureAndAPendingSendSurvivesIt() {
        signedIn(); openRoom()
        val gate = CompletableDeferred<Unit>()
        compose.runOnIdle { api.rooms.sendGate = gate; room.updateDraft("Survives the tab switch"); room.send() }
        compose.waitUntil(5_000) { api.rooms.sends == 1 }
        back() // Leaving the pane keeps the authorized write (today's rule); the tab switches below are departures too.
        compose.openTab("direct").performClick()
        compose.waitUntil(5_000) { inbox.state.value.inbox != null && !inbox.state.value.busy }
        compose.runOnIdle { assertFalse("Leaving Rooms cancels directory work", directory.state.value.busy) }
        compose.openTab("activity").performClick()
        compose.runOnIdle { assertTrue("Leaving Direct backgrounds the inbox", inbox.state.value.stale) }
        compose.runOnIdle { gate.complete(Unit) }
        compose.waitUntil(5_000) { !room.state.value.sending }
        compose.runOnIdle {
            assertEquals("Navigation never cancels or replays a POST", 1, api.rooms.sends)
            assertEquals(listOf("Survives the tab switch"), api.rooms.sent.map { it.second })
        }
    }

    @Test fun aStaleScopeGoesToTheRoomsRootAndNothingIsRestoredAcrossScopes() {
        signedIn()
        compose.openTab("activity").performClick(); compose.onNodeWithTag("notification_list").assertExists()
        compose.openTab("direct").performClick(); compose.onNodeWithTag("dm_inbox").assertExists()
        compose.runOnIdle { sessions.state.value = sessions.state.value.copy(generation = sessions.state.value.generation + 1,
            user = SessionUser(9, "Second fixture", listOf("member"), 1, false)) }
        compose.waitUntil(5_000) { runCatching { compose.onNodeWithTag("rooms_directory").assertExists() }.isSuccess }
        compose.onNodeWithTag("dm_inbox").assertDoesNotExist(); compose.openTab("rooms").assertIsSelected()
        // The Activity stack saved under the old scope is discarded: its root is rebuilt for the current one.
        compose.openTab("activity").performClick()
        compose.onNodeWithTag("notification_list").assertExists(); compose.openTab("activity").assertIsSelected()
    }

    @Test fun backAtEachRootAndReselection() {
        signedIn()
        compose.runOnIdle { assertFalse("At the Rooms root the system leaves the app", compose.activity.onBackPressedDispatcher.hasEnabledCallbacks()) }
        for (tab in listOf("direct", "activity", "you")) {
            compose.openTab(tab).performClick(); compose.openTab(tab).assertIsSelected()
            Espresso.pressBack()
            compose.openTab("rooms").assertIsSelected(); compose.onNodeWithTag("rooms_directory").assertExists()
        }
        compose.openTab("you").performClick(); compose.onNodeWithTag("you_account").performScrollTo().performClick()
        Espresso.pressBack(); compose.onNodeWithTag("you_session").assertExists() // A pushed destination pops.
        compose.onNodeWithTag("you_account").performScrollTo().performClick(); back()
        compose.openTab("you").performClick(); compose.onNodeWithTag("you_session").assertExists() // Reselect at the root: no change.
    }

    @Test fun aRestoredConversationIsAReentryAndReadsOnlyAfterAFreshHistory() {
        val taps = PendingPushTap().also { this.taps = it }
        signedIn()
        openRoom()
        // A push tap lands on Activity from inside the conversation, saving the Rooms stack with it.
        compose.runOnIdle { taps.offer(PushTap(sessions.state.value.server!!.value, sessions.state.value.user!!.id, null)) }
        compose.waitUntil(5_000) { taps.state.value == null }
        compose.onNodeWithTag("notification_list").assertExists()
        val gate = CompletableDeferred<Unit>()
        compose.runOnIdle { historyGate = gate; log.clear() }
        compose.openTab("rooms").performClick()
        compose.waitUntil(5_000) { room.state.value.roomId == 43L }
        compose.waitForIdle()
        compose.runOnIdle { assertFalse("No read before the restored pane's fresh history", "read" in log) }
        compose.runOnIdle { gate.complete(Unit) }
        compose.waitUntil(5_000) { "history" in log && room.state.value.loaded && !room.state.value.busy }
        compose.runOnIdle { val first = log.indexOf("read"); assertTrue(first == -1 || first > log.indexOf("history")) }
        compose.onNodeWithTag("conversation_composer").assertExists()
    }

    /** Why a tab root is "a tab route with nothing below it": an Activity notice can push the DM inbox route. */
    @Test fun theBarIsAbsentOnTheInboxPushedFromActivityAndPresentOnEveryRoot() {
        val taps = PendingPushTap().also { this.taps = it }
        compose.runOnUiThread { targets = androidx.lifecycle.ViewModelProvider(compose.activity,
            io.github.thiesi.chitchat.feature.targets.MessageTargetViewModel.factory(api.lookups))[
            io.github.thiesi.chitchat.feature.targets.MessageTargetViewModel::class.java] }
        signedIn()
        for (tab in listOf("direct", "activity", "you", "rooms")) {
            compose.openTab(tab).performClick(); compose.openTab(tab).assertIsSelected(); bar(true)
        }
        compose.openTab("activity").performClick(); compose.onNodeWithTag("notification_list").assertExists()
        compose.runOnIdle { taps.offer(PushTap(sessions.state.value.server!!.value, sessions.state.value.user!!.id, "/messages.php")) }
        compose.waitUntil(5_000) { taps.state.value == null && runCatching { compose.onNodeWithTag("dm_inbox").assertExists() }.isSuccess }
        bar(false) // The inbox route, pushed on Activity, is not a tab root.
        back(); compose.onNodeWithTag("notification_list").assertExists(); bar(true)
    }

    @Test fun theSelectedTabAndSavedStacksSurviveRecreation() {
        signedIn()
        compose.openTab("you").performClick(); compose.onNodeWithTag("you_account").performScrollTo().performClick(); back()
        compose.openTab("activity").performClick(); compose.onNodeWithTag("notification_list").assertExists()
        compose.activityRule.scenario.recreate()
        compose.activityRule.scenario.onActivity { install(it) }
        compose.openTab("activity").assertIsSelected(); compose.onNodeWithTag("notification_list").assertExists()
        compose.openTab("you").performClick(); compose.onNodeWithTag("you_session").assertExists()
    }

    @Test fun signOutFromAnyTabReturnsToOnboardingAndRetiresEverySavedStack() {
        signedIn(); compose.openTab("direct").performClick(); compose.onNodeWithTag("dm_inbox").assertExists()
        compose.runOnIdle { auth.signOut() }
        compose.waitUntil(5_000) { runCatching { compose.onNodeWithText("Back to sign-in").assertExists() }.isSuccess }
        bar(false); compose.onNodeWithTag("dm_inbox").assertDoesNotExist()
        compose.onNodeWithText("Back to sign-in").performScrollTo().performClick()
        compose.runOnIdle { runBlocking { sessions.signIn("Viewer", "fixture-password") } }
        compose.openTab("rooms").assertIsSelected(); compose.onNodeWithTag("rooms_directory").assertExists()
        compose.onNodeWithTag("dm_inbox").assertDoesNotExist()
    }

    /** The bar leaves layout and semantics while the IME shows on the two roots with a field; the field doesn't move. */
    @Test fun onRootsWithAFieldTheBarLeavesWithTheImeAndTheFieldStaysPut() {
        signedIn()
        for ((tab, field) in listOf("rooms" to "rooms_lookup", "direct" to "dm_search_input")) {
            compose.openTab(tab).performClick()
            if (tab == "rooms") compose.onNodeWithTag("rooms_directory").performScrollToNode(hasTestTag(field))
            else compose.onNodeWithTag("dm_inbox").performScrollToNode(hasTestTag(field))
            val before = compose.onNodeWithTag(field).assertIsDisplayed().fetchSemanticsNode().boundsInWindow
            compose.openTab(tab).assertIsDisplayed()
            compose.tapNativeInput(field, { ime })
            if (InstrumentationRegistry.getArguments().getString("expectSoftKeyboard") == "true") {
                bar(false)
                val now = compose.onNodeWithTag(field).assertIsFocused().assertIsDisplayed().fetchSemanticsNode()
                // DM search is at the top: it must not move. The Rooms lookup sits below the room list, so the IME's own
                // bring-into-view scroll moves it; there it must stay focused and wholly above the IME.
                if (tab == "direct") assertEquals("direct: the focused field does not move when the bar leaves", before, now.boundsInWindow)
                else assertTrue("rooms: the focused field stays above the IME",
                    now.boundsInWindow.bottom <= compose.activity.window.decorView.height - ime + 0.5f)
            }
            Espresso.closeSoftKeyboard(); compose.waitUntil(5_000) { ime == 0 }
            compose.openTab(tab).assertIsDisplayed()
        }
    }

    @Test fun youHoldsEveryOldHubAccountAndSettingsControl() {
        signedIn(); compose.openTab("you").performClick()
        for (tag in listOf("you_appearance", "you_account", "you_session", "you_about", "licences_open"))
            compose.onNodeWithTag(tag).performScrollTo().assertExists()
        compose.onNodeWithTag("notification_settings_open").assertDoesNotExist() // No push in this host: no row, no placeholder.
        compose.onNode(hasText(text(R.string.sign_out)) and hasAnyAncestor(hasTestTag("you_session"))).performScrollTo().assertIsEnabled()
        compose.onNode(hasText(text(R.string.refresh_session)) and hasAnyAncestor(hasTestTag("you_session"))).assertExists()
        compose.onNodeWithText(text(R.string.change_server)).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.sign_out_to_change_server)).assertExists()
        compose.onNode(hasAnyAncestor(hasTestTag("you_about")) and hasText(text(R.string.you_app_version).substringBefore("%"), substring = true)).assertExists()
        compose.onNodeWithTag("you_appearance").performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.appearance)).assertExists(); compose.onNodeWithTag("theme_pure_black").assertExists()
        back(); compose.onNodeWithTag("you_account").performScrollTo().performClick()
        compose.onNodeWithTag("licences_open").assertDoesNotExist() // Moved from account to You.
        back(); compose.onNodeWithTag("licences_open").performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.oss_licences_title)).assertExists()
    }
}

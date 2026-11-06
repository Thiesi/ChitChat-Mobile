package io.github.thiesi.chitchat.feature.search

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import io.github.thiesi.chitchat.R
import io.github.thiesi.chitchat.app.*
import io.github.thiesi.chitchat.core.network.*
import io.github.thiesi.chitchat.core.search.*
import io.github.thiesi.chitchat.core.server.*
import io.github.thiesi.chitchat.core.session.*
import io.github.thiesi.chitchat.core.settings.*
import io.github.thiesi.chitchat.core.targets.*
import io.github.thiesi.chitchat.feature.account.*
import io.github.thiesi.chitchat.feature.auth.*
import io.github.thiesi.chitchat.feature.conversation.ConversationViewModel
import io.github.thiesi.chitchat.feature.direct.*
import io.github.thiesi.chitchat.feature.realtime.NativeTypingHarness
import io.github.thiesi.chitchat.feature.rooms.RoomsViewModel
import io.github.thiesi.chitchat.feature.targets.MessageTargetViewModel
import io.github.thiesi.chitchat.testing.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Real root navigation and keyboard; original requests and typed fixtures, never live HTTP. */
class SearchResultJourneyTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val sessions = TestSessions()
    private val api = NativeChatFixtures(sessions)
    private val typing = NativeTypingHarness(sessions.state)
    private val searchApi = SearchOperations()
    private lateinit var search: MessageSearchViewModel
    private lateinit var targets: MessageTargetViewModel
    private lateinit var room: ConversationViewModel
    private lateinit var direct: DirectThreadViewModel
    private lateinit var inbox: DirectInboxViewModel
    @Volatile private var ime = 0
    private val settingsStore = object : SettingsStore {
        override val settings = MutableStateFlow(AppSettings(server =
            (ServerAddress.parse("chat.example.test") as AddressResult.Valid).address))
        override suspend fun saveServer(server: ServerAddress?) { settings.value = settings.value.copy(server = server) }
        override suspend fun saveTheme(theme: ThemePreference) { settings.value = settings.value.copy(theme = theme) }
    }
    private inline fun <reified T : ViewModel> keep(activity: ComponentActivity, crossinline create: () -> T): T =
        ViewModelProvider(activity, viewModelFactory { initializer { create() } })[T::class.java]
    private fun install() = compose.activityRule.scenario.onActivity { activity ->
        activity.enableEdgeToEdge()
        val auth = keep(activity) { AuthViewModel(sessions) }
        val settings = keep(activity) { SettingsViewModel(settingsStore) }
        val account = keep(activity) { AccountViewModel(TestAccounts(sessions.state)) }
        val directory = keep(activity) { RoomsViewModel(EmptyRoomOperations(sessions.state)) }
        room = keep(activity) { ConversationViewModel(api.rooms, typing = typing.controller) }
        direct = keep(activity) { DirectThreadViewModel(api.directs, typing = typing.controller) }
        inbox = keep(activity) { DirectInboxViewModel(api.directs) }
        search = keep(activity) { MessageSearchViewModel(searchApi) }
        targets = keep(activity) { MessageTargetViewModel(api.lookups) }
        activity.setContent {
            val bottom = WindowInsets.ime.getBottom(LocalDensity.current)
            SideEffect { ime = bottom }
            Box(Modifier.fillMaxSize()) { TestChitChatApp(settings, auth, account, directory, room,
                directInbox = inbox, directThread = direct, messageTargets = targets, search = search) }
        }
    }
    @Before fun configuration() {
        val args = InstrumentationRegistry.getArguments()
        val config = InstrumentationRegistry.getInstrumentation().targetContext.resources.configuration
        args.getString("expectedOrientation")?.let { assertEquals(it.toInt(), config.orientation) }
        args.getString("expectedFontScale")?.let { assertEquals(it.toFloat(), config.fontScale, 0.01f) }
    }
    private fun root() {
        install()
        compose.waitUntil(5_000) { sessions.state.value.phase == SessionPhase.ANONYMOUS }
        compose.runOnIdle { sessions.state.value = sessions.state.value.copy(phase = SessionPhase.AUTHENTICATED,
            user = SessionUser(7, "Viewer", listOf("member"), 1, false), chatPolicy = api.policy, privacy = api.privacy, shareTyping = true) }
        compose.onNodeWithTag("message_search_open").performScrollTo().performNativeTap()
        compose.onNodeWithTag("message_search_query").assertIsDisplayed()
    }
    private fun type(tag: String, text: String) {
        compose.tapNativeInput(tag, { ime }).performTextInput(text)
        assertEquals(text, compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
    }
    private fun query(text: String = "needle") {
        type("message_search_query", text)
        compose.onNodeWithTag("message_search_submit").performNativeTap()
        compose.waitUntil(5_000) { search.state.value.results != null && !search.state.value.busy }
        hideIme()
    }
    private fun hideIme() {
        compose.waitForIdle()
        val before = ime; val window = nativeInputWindow()
        if (before > 0 || window.imeVisible != false) try { Espresso.closeSoftKeyboard() }
        catch (failure: RuntimeException) {
            throw AssertionError("Search routing IME cleanup: before=$before, after=$ime, beforeWindow=$window, afterWindow=${nativeInputWindow()}", failure)
        }
        compose.waitUntil(5_000) { ime == 0 }
        assertEquals("Native IME hidden must be confirmed", false, nativeInputWindow().imeVisible)
    }
    @After fun close() {
        try { hideIme() } finally {
            compose.activityRule.scenario.onActivity {
                api.lookups.gates.values.forEach { it.complete(Unit) }
                api.rooms.sendGate?.complete(Unit); api.directs.sendGate?.complete(Unit)
                if (::search.isInitialized) search.state.value.entry?.let(search::background)
                if (::targets.isInitialized) targets.state.value.request?.let(targets::cancel)
                if (::room.isInitialized) room.cancel()
                if (::direct.isInitialized) direct.background()
                if (::inbox.isInitialized) inbox.background()
                typing.close()
            }
        }
    }
    private fun text(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)
    private fun dialog(id: Int) = compose.onNode(hasText(text(id)) and hasAnyAncestor(isDialog()))
    private fun open(kind: String) {
        val tag = "message_search_open:$kind:20"
        compose.onNodeWithTag("message_search_results").performScrollToNode(hasTestTag(tag))
        compose.onNodeWithTag(tag).assertIsDisplayed().performNativeTap()
    }
    private fun roomTarget() {
        compose.waitUntil(5_000) { targets.state.value.request == null && room.state.value.highlightedMessageId == 20L && !room.state.value.busy }
        compose.onNodeWithTag("requested_message:20").assertIsDisplayed()
        compose.onNodeWithTag("message:20").assertIsSelected()
        compose.runOnIdle { assertFalse(room.state.value.latestWindow); assertEquals(20L, room.state.value.fixedUpper) }
    }
    private fun directTarget() {
        compose.waitUntil(5_000) { targets.state.value.request == null && direct.state.value.highlightedMessageId == 20L && !direct.state.value.busy }
        compose.onNodeWithTag("requested_message:20").assertIsDisplayed()
        compose.onNodeWithTag("direct:20").assertIsSelected()
        compose.runOnIdle { assertFalse(direct.state.value.latestWindow); assertEquals(20L, direct.state.value.fixedUpper) }
    }
    private fun backToSearch() {
        hideIme(); compose.onNodeWithText(text(R.string.back)).performNativeTap()
        compose.onNodeWithTag("message_search_query").assertIsDisplayed()
        compose.runOnIdle { assertEquals("", search.state.value.query); assertNull(search.state.value.results) }
    }
    @Test fun exactRoomAndDirectOriginalsUseTheirNamespacesWithoutReadOrTypingConsent() {
        root(); query(); open("room"); roomTarget()
        compose.runOnIdle {
            assertEquals(MessageTarget.RoomMessage(43, 20), api.lookups.calls.single().target)
            assertTrue(api.rooms.reads.isEmpty()); assertEquals(0, api.rooms.sends); assertEquals(0, typing.requests.get())
        }
        backToSearch(); query("another explicit query"); open("direct"); directTarget()
        compose.runOnIdle {
            assertEquals(MessageTarget.DirectMessage(9, 20), api.lookups.calls.last().target)
            assertTrue(api.directs.reads.isEmpty()); assertEquals(0, api.directs.sends); assertEquals(0, typing.requests.get())
            assertEquals(2, searchApi.calls.size); assertNull(search.state.value.targetBinding)
        }
    }
    @Test fun unavailableAndDeniedOriginalsStayInSearchAndRetryRemainsAnExplicitGet() {
        root(); query(); api.lookups.found = false; open("room")
        dialog(R.string.message_target_unavailable).assertIsDisplayed()
        compose.runOnIdle { assertNull(room.state.value.roomId); api.lookups.found = true }
        dialog(R.string.retry).performNativeTap(); roomTarget(); backToSearch()
        query(); api.lookups.failure = ApiFailure(FailureKind.HTTP, 403, "forbidden"); open("direct")
        compose.waitUntil(5_000) { search.state.value.targetBinding == null && search.state.value.error?.status == 403 }
        compose.onNodeWithText(text(R.string.message_search_access_lost)).assertExists()
        compose.onNodeWithTag("message_search_open:direct:20").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals("needle", search.state.value.query); assertNotNull(search.state.value.results)
            assertNull(direct.state.value.peerId); assertEquals(3, api.lookups.calls.size)
            assertTrue(api.rooms.reads.isEmpty()); assertTrue(api.directs.reads.isEmpty())
        }
    }
    @Test fun roomUnknownDeliverySurvivesOpeningAnOriginalWithoutHistoryCheckConsent() {
        root(); query(); open("room"); roomTarget(); type("conversation_composer", "Unconfirmed room draft")
        compose.runOnIdle { api.rooms.sendFailure = ApiFailure(FailureKind.TIMEOUT) }
        compose.onNodeWithTag("conversation_send").assertIsEnabled().performNativeTap()
        compose.waitUntil(5_000) { room.state.value.uncertainSend && !room.state.value.busy }
        backToSearch(); query(); val reads = api.rooms.reads.size; val typingBefore = typing.requests.get()
        open("room"); roomTarget()
        compose.runOnIdle {
            assertEquals("Unconfirmed room draft", room.state.value.draft); assertTrue(room.state.value.uncertainSend)
            assertFalse(room.state.value.reconciledAfterUncertain); assertEquals(1, api.rooms.sends)
            assertEquals(reads, api.rooms.reads.size); assertEquals(typingBefore, typing.requests.get())
        }
    }
    @Test fun directUnknownDeliverySurvivesOpeningAnOriginalWithoutDuplicateRiskConsent() {
        root(); query(); open("direct"); directTarget(); type("dm_composer", "Unconfirmed direct draft")
        compose.runOnIdle { api.directs.sendFailure = ApiFailure(FailureKind.TIMEOUT) }
        compose.onNodeWithTag("dm_send").assertIsEnabled().performNativeTap()
        compose.waitUntil(5_000) { direct.state.value.uncertainSend && !direct.state.value.busy }
        backToSearch(); query(); val reads = api.directs.reads.size; val typingBefore = typing.requests.get()
        open("direct"); directTarget()
        compose.runOnIdle {
            assertEquals("Unconfirmed direct draft", direct.state.value.draft); assertTrue(direct.state.value.uncertainSend)
            assertFalse(direct.state.value.checkedUnknown); assertEquals(1, api.directs.sends)
            assertEquals(reads, api.directs.reads.size); assertEquals(typingBefore, typing.requests.get())
        }
    }
    @Test fun aBusyRoomWriteKeepsTheSelectionAndIsNeitherCancelledNorReplayedForNavigation() {
        root(); query(); open("room"); roomTarget(); type("conversation_composer", "Pending room send")
        compose.runOnIdle { api.rooms.sendGate = CompletableDeferred() }
        compose.onNodeWithTag("conversation_send").assertIsEnabled().performNativeTap()
        compose.waitUntil(5_000) { api.rooms.sends == 1 }
        backToSearch(); query("open while write is pending"); open("room")
        compose.waitUntil(5_000) { targets.state.value.result?.available == true }
        compose.onNodeWithTag("message_target_dialog").assertIsDisplayed()
        val binding = checkNotNull(search.state.value.targetBinding)
        compose.runOnIdle {
            assertSame(binding.request, targets.state.value.request)
            assertFalse(api.rooms.sendGate!!.isCompleted); assertEquals(1, api.rooms.sends)
            api.rooms.sendGate!!.complete(Unit)
        }
        roomTarget()
        compose.runOnIdle { assertEquals(1, api.rooms.sends); assertNull(search.state.value.targetBinding) }
    }
    @Test fun actualRecreationRetainsTheExactSelectionAndPendingLookupWithoutReposting() {
        val target = MessageTarget.RoomMessage(43, 20); api.lookups.gates[target] = CompletableDeferred()
        root(); query("private recreation query"); open("room")
        compose.waitUntil(5_000) { api.lookups.calls.size == 1 && search.state.value.targetBinding != null }
        val oldSearch = search; val oldTargets = targets; val binding = checkNotNull(search.state.value.targetBinding)
        compose.activityRule.scenario.recreate(); install()
        compose.onNodeWithTag("message_target_dialog").assertIsDisplayed()
        compose.runOnIdle {
            assertSame(oldSearch, search); assertSame(oldTargets, targets); assertSame(binding, search.state.value.targetBinding)
            assertSame(binding.request, targets.state.value.request); assertEquals(1, searchApi.calls.size)
            assertEquals(1, api.lookups.calls.size); api.lookups.gates.getValue(target).complete(Unit)
        }
        roomTarget(); compose.runOnIdle { assertTrue(api.rooms.reads.isEmpty()) }
    }
    @Test fun oldCancelAndNonCooperativeLateRoomLookupCannotStealADirectSuccessor() {
        val a = MessageTarget.RoomMessage(43, 20); val b = MessageTarget.DirectMessage(9, 20)
        api.lookups.ignoreCancellation = true
        api.lookups.gates[a] = CompletableDeferred(); api.lookups.gates[b] = CompletableDeferred()
        root(); query(); open("room"); compose.onNodeWithTag("message_target_dialog").assertIsDisplayed()
        val old = checkNotNull(search.state.value.targetBinding)
        // Capture a component callback explicitly; ordinary navigation taps remain OS-routed.
        val oldCancel = dialog(R.string.cancel).fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        dialog(R.string.cancel).performNativeTap()
        compose.waitUntil(5_000) { search.state.value.targetBinding == null }; open("direct")
        compose.waitUntil(5_000) { api.lookups.calls.size == 2 && search.state.value.targetBinding != null }
        val next = checkNotNull(search.state.value.targetBinding)
        compose.runOnIdle { oldCancel(); search.cancelTarget(old); api.lookups.gates.getValue(a).complete(Unit) }
        compose.runOnIdle {
            assertSame(next, search.state.value.targetBinding); assertSame(next.request, targets.state.value.request)
            assertNull(room.state.value.roomId); api.lookups.gates.getValue(b).complete(Unit)
        }
        directTarget(); compose.runOnIdle { assertEquals(1, searchApi.calls.size); assertTrue(api.directs.reads.isEmpty()) }
    }
    @Test fun backgroundAndOwnerReplacementRetirePrivateSelectionsAndOldEntryCallbacks() {
        val target = MessageTarget.RoomMessage(43, 20); api.lookups.ignoreCancellation = true
        api.lookups.gates[target] = CompletableDeferred(); root(); query(); open("room")
        compose.waitUntil(5_000) { api.lookups.calls.size == 1 }
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.onActivity { api.lookups.gates.getValue(target).complete(Unit) }
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.onNodeWithTag("message_search_query").assertIsDisplayed()
        compose.runOnIdle { assertEquals("", search.state.value.query); assertNull(targets.state.value.request); assertNull(room.state.value.roomId) }
        query("new owner-sensitive query")
        val oldOpen = compose.onNodeWithTag("message_search_open:room:20").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnIdle { sessions.state.value = sessions.state.value.copy(generation = sessions.state.value.generation + 1,
            user = checkNotNull(sessions.state.value.user).copy(id = 10)) }
        compose.onNodeWithTag("message_search_open").performScrollTo().assertIsDisplayed()
        compose.runOnIdle {
            oldOpen(); assertEquals(1, api.lookups.calls.size); assertEquals(2, searchApi.calls.size)
            assertEquals("", search.state.value.query); assertNull(search.state.value.results); assertNull(targets.state.value.request)
        }
    }
    private inner class SearchOperations : MessageSearchOperations {
        override val session = sessions.state
        val calls = java.util.concurrent.CopyOnWriteArrayList<MessageSearchRequest>()
        override suspend fun search(expected: SessionState, request: MessageSearchRequest): MessageSearchPage {
            calls += request
            val room = MessageSearchHit.Room(20, "Room excerpt", SearchParticipant(8, "Peer"),
                SearchRoom(43, "room43", "Room 43"), "2026-11-06T12:00:00Z", null)
            val direct = MessageSearchHit.Direct(20, "Direct excerpt", SearchParticipant(9, "Other"),
                SearchParticipant(9, "Other"), false, "2026-11-06T12:00:00Z", null)
            return MessageSearchPage(when (request.scope) {
                MessageSearchScope.ROOMS -> listOf(room)
                MessageSearchScope.DIRECT -> listOf(direct)
                MessageSearchScope.ALL -> listOf(room, direct)
            }, false, null)
        }
    }
}

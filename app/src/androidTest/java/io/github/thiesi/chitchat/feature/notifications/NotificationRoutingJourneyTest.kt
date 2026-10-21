package io.github.thiesi.chitchat.feature.notifications

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import io.github.thiesi.chitchat.R
import io.github.thiesi.chitchat.core.conversation.ChatPolicy
import io.github.thiesi.chitchat.app.SettingsViewModel
import io.github.thiesi.chitchat.core.network.*
import io.github.thiesi.chitchat.core.server.*
import io.github.thiesi.chitchat.core.session.SessionPhase
import io.github.thiesi.chitchat.core.settings.*
import io.github.thiesi.chitchat.core.targets.MessageTarget
import io.github.thiesi.chitchat.core.typing.TypingTarget
import io.github.thiesi.chitchat.feature.account.TestChitChatApp
import io.github.thiesi.chitchat.feature.auth.AuthViewModel
import io.github.thiesi.chitchat.feature.auth.TestSessions
import io.github.thiesi.chitchat.feature.conversation.ConversationViewModel
import io.github.thiesi.chitchat.feature.direct.DirectInboxViewModel
import io.github.thiesi.chitchat.feature.direct.DirectThreadViewModel
import io.github.thiesi.chitchat.feature.realtime.NativeTypingHarness
import io.github.thiesi.chitchat.feature.targets.MessageTargetViewModel
import io.github.thiesi.chitchat.testing.tapNativeInput
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Native navigation/real IME/retained entry journeys; typed fixtures, not live-server acceptance. */
class NotificationRoutingJourneyTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val sessions = TestSessions()
    private val api = NotificationRoutingFixtures(sessions)
    private val store = ViewModelStore()
    private val typing = NativeTypingHarness(sessions.state)
    private val auth = AuthViewModel(sessions).also { store.put("auth", it) }
    private val room = ConversationViewModel(api.rooms, typing = typing.controller).also { store.put("room", it) }
    private val direct = DirectThreadViewModel(api.directs, typing = typing.controller).also { store.put("direct", it) }
    private val inbox = DirectInboxViewModel(api.directs).also { store.put("inbox", it) }
    private val notices = NotificationCenterViewModel(api.notices).also { store.put("notices", it) }
    private lateinit var targets: MessageTargetViewModel
    private val settings = SettingsViewModel(object : SettingsStore {
        override val settings = MutableStateFlow(AppSettings(server =
            (ServerAddress.parse("chat.example.test") as AddressResult.Valid).address))
        override suspend fun saveServer(server: ServerAddress?) { settings.value = settings.value.copy(server = server) }
        override suspend fun saveTheme(theme: ThemePreference) { settings.value = settings.value.copy(theme = theme) }
    }).also { store.put("settings", it) }
    @Volatile private var ime = 0
    @Before fun bind() {
        compose.activityRule.scenario.onActivity { activity ->
            activity.enableEdgeToEdge()
            targets = ViewModelProvider(activity, MessageTargetViewModel.factory(api.lookups))[MessageTargetViewModel::class.java]
        }
        val args = InstrumentationRegistry.getArguments()
        val config = InstrumentationRegistry.getInstrumentation().targetContext.resources.configuration
        args.getString("expectedOrientation")?.let { assertEquals(it.toInt(), config.orientation) }
        args.getString("expectedFontScale")?.let { assertEquals(it.toFloat(), config.fontScale, 0.01f) }
    }
    @After fun close() {
        Espresso.closeSoftKeyboard()
        compose.runOnIdle {
            api.lookups.gates.values.forEach { it.complete(Unit) }; api.notices.postGate?.complete(Unit)
            targets.background(); room.cancel(); direct.background(); inbox.background(); notices.background()
            store.clear(); typing.close()
        }
    }
    private fun install(activity: ComponentActivity) { activity.setContent {
        val bottom = WindowInsets.ime.getBottom(LocalDensity.current)
        SideEffect { ime = bottom }
        Box(Modifier.fillMaxSize()) { TestChitChatApp(settings, auth, conversationModel = room,
            directInbox = inbox, directThread = direct, messageTargets = targets, notifications = notices) }
    } }
    private fun root() {
        compose.runOnUiThread { install(compose.activity) }
        compose.waitUntil(5_000) { sessions.state.value.phase == SessionPhase.ANONYMOUS }
        compose.runOnIdle {
            runBlocking { sessions.signIn("Viewer", "fixture-password") }
            sessions.state.value = sessions.state.value.copy(chatPolicy = api.policy, privacy = api.privacy, shareTyping = true)
        }
        compose.onNodeWithTag("notifications_open").performScrollTo().assertIsDisplayed().performClick(); fresh()
    }
    private fun text(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)
    private fun fresh() { compose.waitUntil(5_000) { notices.state.value.loaded && !notices.state.value.busy && !notices.state.value.stale } }
    private fun at(tag: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("notification_list").performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
    }
    private fun view(id: Long) { at("notification_view:$id").assertIsEnabled().performClick() }
    private fun back(toCenter: Boolean = true) {
        compose.onNodeWithText(text(R.string.back)).assertIsDisplayed().performClick()
        if (toCenter) fresh() else compose.onNodeWithTag("notifications_open").performScrollTo().assertIsDisplayed()
    }
    private fun roomTarget(id: Long) {
        compose.waitUntil(5_000) { room.state.value.highlightedMessageId == id && !room.state.value.busy && targets.state.value.request == null }
        compose.onNodeWithTag("requested_message:$id").assertIsDisplayed(); compose.onNodeWithTag("message:$id").assertIsSelected()
    }
    private fun directTarget(id: Long) {
        compose.waitUntil(5_000) { direct.state.value.highlightedMessageId == id && !direct.state.value.busy && targets.state.value.request == null }
        compose.onNodeWithTag("requested_message:$id").assertIsDisplayed(); compose.onNodeWithTag("direct:$id").assertIsSelected()
    }
    private fun dialog(id: Int) = compose.onNode(hasText(text(id)) and hasAnyAncestor(isDialog()))
    private fun type(tag: String, value: String) { compose.tapNativeInput(tag, { ime }).performTextInput(value) }
    private fun dismissIme() { Espresso.closeSoftKeyboard(); compose.waitUntil(5_000) { ime == 0 } }
    private fun capture(name: String) {
        if (InstrumentationRegistry.getArguments().getString("captureNotificationTargets") != "true") return
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try { java.io.File(instrumentation.targetContext.getExternalFilesDir("screenshots"), name).outputStream().use {
            check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
        } } finally { bitmap.recycle() }
    }

    @Test fun roomLinksKeepBothDraftsAndUnknownDeliveryAndNeverReadTheHistoricalDestination() {
        root(); view(58); compose.waitUntil(5_000) { room.state.value.loaded && !room.state.value.busy }
        type("conversation_composer", "Unconfirmed room draft")
        compose.runOnIdle { api.rooms.sendFailure = ApiFailure(FailureKind.TIMEOUT) }
        compose.onNodeWithTag("conversation_send").assertIsDisplayed().assertIsEnabled().performClick()
        compose.waitUntil(5_000) { room.state.value.uncertainSend && !room.state.value.busy }; dismissIme(); back()
        view(60); roomTarget(20)
        compose.runOnIdle { assertFalse(room.state.value.latestWindow); assertEquals(20L, room.state.value.fixedUpper)
            assertTrue(api.rooms.histories.none { it.first == 43L && it.second == null && it.third > 1 }); assertFalse(43L in api.rooms.reads)
            assertEquals("Unconfirmed room draft", room.drafts.value[42]?.body); assertEquals(0, api.notices.posts) }
        capture("notice-room-target.png"); back(); view(59); roomTarget(2)
        compose.onNodeWithTag("conversation_composer").assert(SemanticsMatcher.expectValue(
            SemanticsProperties.EditableText, AnnotatedString("Unconfirmed room draft")))
        compose.runOnIdle { assertTrue(room.state.value.uncertainSend); assertFalse(room.state.value.reconciledAfterUncertain)
            assertEquals(1, api.rooms.sends); assertEquals(0, api.notices.posts) }
    }
    @Test fun directLinksKeepTheOriginalPeersUnknownDraftAndDoNotAcknowledgeNewerMessages() {
        root(); view(55); compose.waitUntil(5_000) { direct.state.value.loaded && !direct.state.value.busy }
        type("dm_composer", "Unconfirmed direct draft")
        compose.runOnIdle { api.directs.sendFailure = ApiFailure(FailureKind.TIMEOUT) }
        compose.onNodeWithTag("dm_send").assertIsDisplayed().assertIsEnabled().performClick()
        compose.waitUntil(5_000) { direct.state.value.uncertainSend && !direct.state.value.busy }; dismissIme(); back()
        view(57); directTarget(20); capture("notice-direct-target.png")
        compose.runOnIdle { assertEquals(9L, direct.state.value.peerId); assertFalse(9L in api.directs.reads)
            assertFalse(direct.state.value.latestWindow); assertEquals("Unconfirmed direct draft", direct.drafts.value[8]?.body) }
        back(); view(56); directTarget(18)
        compose.onNodeWithTag("dm_composer").assert(SemanticsMatcher.expectValue(
            SemanticsProperties.EditableText, AnnotatedString("Unconfirmed direct draft")))
        compose.runOnIdle { assertTrue(direct.state.value.uncertainSend); assertFalse(direct.state.value.checkedUnknown)
            assertEquals(1, api.directs.sends); assertEquals(0, api.notices.posts) }
    }
    @Test fun missingAndDeniedTargetsStayInTheCenterAndRetryIsAnExplicitFreshLookup() {
        root(); compose.runOnIdle { api.lookups.found = false }; view(60)
        dialog(R.string.message_target_unavailable).assertIsDisplayed()
        compose.runOnIdle { assertNull(room.state.value.roomId); assertEquals(0, api.notices.posts); api.lookups.found = true }
        dialog(R.string.retry).assertIsDisplayed().performClick(); roomTarget(20); back()
        compose.runOnIdle { api.lookups.failure = ApiFailure(FailureKind.HTTP, 403, "forbidden") }; view(57)
        dialog(R.string.message_target_unavailable).assertIsDisplayed(); dialog(R.string.cancel).performClick(); fresh()
        compose.runOnIdle { assertNull(direct.state.value.peerId); assertEquals(0, api.notices.posts) }
    }
    @Test fun oldCancelAndLateLookupCannotStealTheSuccessorAndOldEntryViewCannotReopenIt() {
        root(); val a = MessageTarget.RoomMessage(43, 20); val b = MessageTarget.DirectMessage(9, 20)
        compose.runOnIdle { api.lookups.ignoreCancellation = true; api.lookups.gates[a] = CompletableDeferred(); api.lookups.gates[b] = CompletableDeferred() }
        val oldView = at("notification_view:60").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        view(60); val oldCancel = dialog(R.string.cancel).fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        dialog(R.string.cancel).performClick(); fresh(); view(57)
        compose.runOnIdle { oldCancel(); api.lookups.gates.getValue(a).complete(Unit) }
        compose.waitUntil(5_000) { targets.state.value.request?.target == b }
        compose.runOnIdle { api.lookups.gates.getValue(b).complete(Unit) }; directTarget(20); back()
        // Return Home and create a DIFFERENT center entry with the same account and same row instances.
        back(toCenter = false); compose.onNodeWithTag("notifications_open").performScrollTo().performClick(); fresh()
        val count = api.lookups.calls.size
        compose.runOnIdle { oldView() }; compose.waitForIdle()
        compose.runOnIdle { assertNull(targets.state.value.request); assertEquals(count, api.lookups.calls.size) }
        compose.onNodeWithTag("notification_list").assertExists()
    }
    @Test fun realActivityRecreationRestoresTheSourceEntryAndRetainsExactlyOnePendingLookup() {
        root(); val target = MessageTarget.RoomMessage(43, 20)
        val oldView = at("notification_view:60").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnIdle { api.lookups.gates[target] = CompletableDeferred() }; view(60)
        compose.waitUntil(5_000) { api.lookups.calls.size == 1 }
        val request = targets.state.value.request; val source = targets.state.value.sourceEntryId
        assertNotNull(source); val previous = compose.activity
        compose.activityRule.scenario.recreate()
        compose.activityRule.scenario.onActivity { activity ->
            assertNotSame(previous, activity)
            assertSame(targets, ViewModelProvider(activity, MessageTargetViewModel.factory(api.lookups))[MessageTargetViewModel::class.java])
            install(activity)
        }
        try { compose.waitUntil(5_000) { runCatching { dialog(R.string.cancel).assertIsDisplayed() }.isSuccess } }
        catch (failure: ComposeTimeoutException) {
            val center = compose.onAllNodesWithTag("notification_list").fetchSemanticsNodes(false).isNotEmpty()
            val metadata = compose.runOnIdle { "same=${targets.state.value.request === request}, source=${targets.state.value.sourceEntryId == source}, owner=${request?.matches(sessions.state.value)}, calls=${api.lookups.calls.size}, center=$center, lifecycle=${compose.activity.lifecycle.currentState}" }
            capture("notice-restore-failure.png"); throw AssertionError("Restore: $metadata", failure)
        }
        compose.runOnIdle { oldView() } // The destroyed controller's equal source ID is NOT a live UI entry.
        compose.runOnIdle { assertSame(request, targets.state.value.request); assertEquals(source, targets.state.value.sourceEntryId)
            assertEquals(1, api.lookups.calls.size); api.lookups.gates.getValue(target).complete(Unit) }
        roomTarget(20); back(); compose.onNodeWithTag("notification_list").assertExists()
    }
    @Test fun ownerChangeWhileLookupIsPendingRetiresTheRouteAndCannotExposeItsLateBody() {
        root(); val target = MessageTarget.RoomMessage(43, 20)
        compose.runOnIdle { api.lookups.ignoreCancellation = true; api.lookups.gates[target] = CompletableDeferred() }; view(60)
        compose.waitUntil(5_000) { api.lookups.calls.size == 1 }
        compose.runOnIdle { sessions.state.value = sessions.state.value.copy(generation = sessions.state.value.generation + 1,
            user = SessionUser(9, "Replacement", listOf("member"), 1, false)); api.lookups.gates.getValue(target).complete(Unit) }
        compose.waitUntil(5_000) { targets.state.value.request == null && notices.state.value.unreadCount == 0 }
        compose.onNodeWithTag("notification_list").assertDoesNotExist(); compose.onNodeWithText("Room 43 message 20").assertDoesNotExist()
        compose.runOnIdle { assertTrue(room.state.value.messages.isEmpty()); assertEquals(0, api.notices.posts) }
    }
    @Test fun privatePingIsSelectedInItsOwnScrollableModalAndSuppressesRoomReadAndTyping() {
        root(); view(54)
        compose.waitUntil(5_000) { room.state.value.loaded && !room.state.value.busy && targets.state.value.request == null }
        compose.onNodeWithTag("private_ping:80").assertIsDisplayed().assertIsSelected()
        compose.onNodeWithTag("requested_message:80").assertDoesNotExist(); capture("notice-private-ping.png")
        compose.runOnIdle { assertEquals(80L, room.state.value.pingSelection?.id); assertFalse(43L in api.rooms.reads)
            typing.controller.input(sessions.state.value, TypingTarget.Room(43), true) }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(0, typing.requests.get()); assertEquals(0, api.notices.posts) }
        compose.runOnIdle { sessions.state.value = sessions.state.value.copy(chatPolicy = ChatPolicy(setOf(8), null, api.policy.display)) }
        compose.onNodeWithText(text(R.string.ping_target_missing_title)).assertIsDisplayed()
        compose.onNodeWithTag("private_ping:80").assertDoesNotExist()
        dialog(R.string.rooms_close).assertIsDisplayed().performClick()
        compose.waitUntil(5_000) { room.state.value.pingSelection == null }; back()
    }
    @Test fun expiredPingAndChangedIgnorePolicyRemainExplicitWithoutLatestRoomFallback() {
        root(); view(53); dialog(R.string.ping_target_unavailable).assertIsDisplayed(); dialog(R.string.cancel).performClick(); fresh()
        val target = MessageTarget.RoomPing(43, 80)
        compose.runOnIdle { api.lookups.gates[target] = CompletableDeferred() }; view(54)
        compose.waitUntil(5_000) { api.lookups.calls.size == 2 }
        compose.runOnIdle { sessions.state.value = sessions.state.value.copy(chatPolicy = ChatPolicy(setOf(8), null, api.policy.display))
            api.lookups.gates.getValue(target).complete(Unit) }
        dialog(R.string.ping_target_unavailable).assertIsDisplayed()
        compose.runOnIdle { assertNull(room.state.value.roomId); assertTrue(api.rooms.reads.isEmpty()); assertEquals(0, api.notices.posts) }
    }
    @Test fun unsupportedLinksAreInertAndAccountDestinationUsesTheExistingNativeScreen() {
        root()
        for (id in listOf(51, 50, 49, 48)) { at("notification:$id"); compose.onNodeWithTag("notification_view:$id").assertDoesNotExist() }
        view(52); compose.onNodeWithTag("notification_list").assertDoesNotExist(); back()
        compose.runOnIdle { assertTrue(api.lookups.calls.isEmpty()); assertEquals(0, api.notices.posts) }
    }
    @Test fun aQueuedViewDoesNotCancelOrReplayAnAlreadyPendingNotificationReadPost() {
        root(); val queuedView = at("notification_view:60").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnIdle { api.notices.postGate = CompletableDeferred() }
        at("notification_read:60").performClick(); compose.waitUntil(5_000) { notices.state.value.writing }
        compose.runOnIdle { queuedView() }; roomTarget(20)
        compose.runOnIdle { assertEquals(1, api.notices.posts); assertEquals(0, api.notices.cancelled)
            api.notices.postGate!!.complete(Unit) }
        compose.waitUntil(5_000) { !notices.state.value.writing }
        compose.runOnIdle { assertEquals(1, api.notices.posts); assertEquals(0, api.notices.cancelled) }
        back()
    }
}

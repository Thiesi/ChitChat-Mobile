package io.github.thiesi.chitchat.feature.targets

import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelStore
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import io.github.thiesi.chitchat.R
import io.github.thiesi.chitchat.app.SettingsViewModel
import io.github.thiesi.chitchat.core.conversation.*
import io.github.thiesi.chitchat.core.direct.*
import io.github.thiesi.chitchat.core.network.*
import io.github.thiesi.chitchat.core.rooms.*
import io.github.thiesi.chitchat.core.server.*
import io.github.thiesi.chitchat.core.session.*
import io.github.thiesi.chitchat.core.settings.*
import io.github.thiesi.chitchat.core.targets.*
import io.github.thiesi.chitchat.feature.account.TestChitChatApp
import io.github.thiesi.chitchat.feature.auth.AuthViewModel
import io.github.thiesi.chitchat.feature.auth.TestSessions
import io.github.thiesi.chitchat.feature.conversation.ConversationViewModel
import io.github.thiesi.chitchat.feature.direct.DirectInboxViewModel
import io.github.thiesi.chitchat.feature.direct.DirectThreadViewModel
import io.github.thiesi.chitchat.feature.rooms.RoomsViewModel
import io.github.thiesi.chitchat.testing.tapNativeInput
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Actual root navigation/IME/lifecycle with isolated typed fixtures; not live HTTP/auth acceptance. */
class ReplyTargetJourneyTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val sessions = TestSessions()
    private val roomApi = Rooms(sessions)
    private val directApi = Directs(sessions)
    private val lookup = Lookups(sessions, roomApi, directApi)
    private val store = ViewModelStore()
    private val auth = AuthViewModel(sessions).also { store.put("auth", it) }
    private val directory = RoomsViewModel(roomApi).also { store.put("rooms", it) }
    private val conversation = ConversationViewModel(roomApi).also { store.put("conversation", it) }
    private val inbox = DirectInboxViewModel(directApi).also { store.put("inbox", it) }
    private val thread = DirectThreadViewModel(directApi).also { store.put("thread", it) }
    private val targets = MessageTargetViewModel(lookup).also { store.put("targets", it) }
    @Volatile private var ime = 0

    @Before fun configuration() {
        compose.activityRule.scenario.onActivity { it.enableEdgeToEdge() }
        val args = InstrumentationRegistry.getArguments()
        val config = InstrumentationRegistry.getInstrumentation().targetContext.resources.configuration
        args.getString("expectedOrientation")?.let { assertEquals(it.toInt(), config.orientation) }
        args.getString("expectedFontScale")?.let { assertEquals(it.toFloat(), config.fontScale, 0.01f) }
    }
    @After fun stop() {
        Espresso.closeSoftKeyboard()
        compose.runOnIdle {
            lookup.gate?.complete(Unit); targets.background(); conversation.cancel(); thread.background(); inbox.background()
            directory.cancel(); store.clear()
        }
    }
    private fun text(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)
    private fun root(direct: Boolean = false) {
        val settings = SettingsViewModel(object : SettingsStore {
            override val settings = MutableStateFlow(AppSettings(server =
                (ServerAddress.parse("chat.example.test") as AddressResult.Valid).address))
            override suspend fun saveServer(server: ServerAddress?) { settings.value = settings.value.copy(server = server) }
            override suspend fun saveTheme(theme: ThemePreference) { settings.value = settings.value.copy(theme = theme) }
        }).also { store.put("settings", it) }
        compose.setContent {
            val bottom = WindowInsets.ime.getBottom(LocalDensity.current)
            SideEffect { ime = bottom }
            Box(Modifier.fillMaxSize()) { TestChitChatApp(settings, auth, roomModel = directory,
                conversationModel = conversation, directInbox = inbox, directThread = thread, messageTargets = targets) }
        }
        compose.waitUntil(5_000) { sessions.state.value.phase == SessionPhase.ANONYMOUS }
        // This journey starts a synthetic account, not a claimed password-form/server integration.
        compose.runOnIdle { runBlocking { sessions.signIn("Viewer", "fixture-password") }
            sessions.state.value = sessions.state.value.copy(chatPolicy = policy, privacy = privacy) }
        if (direct) {
            compose.onNodeWithText(text(R.string.dm_title)).performScrollTo().assertIsDisplayed().performClick()
            compose.waitUntil(5_000) { inbox.state.value.inbox != null && !inbox.state.value.busy }
            compose.onNodeWithTag("dm_inbox").performScrollToNode(hasTestTag("dm_conversation:8"))
            compose.onNodeWithTag("dm_conversation:8").assertIsDisplayed().performClick()
            compose.waitUntil(5_000) { thread.state.value.loaded && !thread.state.value.busy }
        } else {
            compose.onNodeWithText(text(R.string.rooms_title)).performScrollTo().assertIsDisplayed().performClick()
            compose.onNodeWithTag("rooms_directory").performScrollToNode(hasText("Fixture room"))
            compose.onNodeWithText("Fixture room").assertIsDisplayed().performClick()
            compose.onNode(hasText(text(R.string.rooms_open_conversation)) and hasAnyAncestor(isDialog()))
                .performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()
            compose.waitUntil(5_000) { conversation.state.value.loaded && !conversation.state.value.busy }
        }
    }
    private fun draft(tag: String, value: String) = compose.onNodeWithTag(tag).assert(
        SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(value)))
    private fun type(tag: String, value: String) = compose.tapNativeInput(tag, { ime }).performTextInput(value)
    private fun dismissIme() { Espresso.closeSoftKeyboard(); compose.waitUntil(5_000) { ime == 0 } }
    private fun quote(tag: String) = compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed().performClick()
    private fun dialogButton(id: Int) = compose.onNode(hasText(text(id)) and hasAnyAncestor(isDialog()))
        .assertIsDisplayed().assertIsEnabled()
    private fun capture(name: String) {
        if (InstrumentationRegistry.getArguments().getString("captureReplyFixture") != "true") return
        val bitmap = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = java.io.File(context.getExternalFilesDir("screenshots") ?: context.cacheDir, name)
        file.outputStream().use { assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }

    @Test fun roomReplyOpensExactOldMessageKeepsDraftAndRealKeyboardSend() {
        root(); type("conversation_composer", "Kept room draft"); dismissIme(); quote("room_reply:100")
        compose.waitUntil(5_000) { conversation.state.value.highlightedMessageId == 2L && targets.state.value.request == null }
        compose.onNodeWithTag("requested_message:2").assertIsDisplayed()
        compose.onNodeWithTag("message:2").assertIsSelected()
        draft("conversation_composer", "Kept room draft")
        compose.runOnIdle { assertEquals(2L, conversation.state.value.fixedUpper); assertFalse(conversation.state.value.latestWindow)
            assertEquals(0, roomApi.reads.get()); assertEquals(1, lookup.calls.get()) }
        capture("reply-room-target.png")
        type("conversation_composer", " still here")
        compose.onNodeWithTag("conversation_send").assertIsDisplayed().assertIsEnabled()
        capture("reply-room-keyboard.png")
        compose.onNodeWithTag("conversation_send").performClick()
        compose.waitUntil(5_000) { roomApi.sends.get() == 1 && !conversation.state.value.busy && conversation.state.value.draft.isEmpty() }
        compose.runOnIdle { assertTrue(conversation.state.value.latestWindow); assertNull(conversation.state.value.highlightedMessageId) }
    }
    @Test fun directReplyOpensExactOldMessageKeepsDraftAndRealKeyboardSend() {
        root(direct = true); type("dm_composer", "Kept direct draft"); dismissIme(); quote("direct_reply:100")
        compose.waitUntil(5_000) { thread.state.value.highlightedMessageId == 2L && targets.state.value.request == null }
        compose.onNodeWithTag("requested_message:2").assertIsDisplayed(); compose.onNodeWithTag("direct:2").assertIsSelected()
        draft("dm_composer", "Kept direct draft")
        compose.runOnIdle { assertEquals(2L, thread.state.value.fixedUpper); assertFalse(thread.state.value.latestWindow)
            assertEquals(0, directApi.reads.get()); assertEquals(1, lookup.calls.get()) }
        capture("reply-direct-target.png")
        type("dm_composer", " still here"); compose.onNodeWithTag("dm_send").assertIsDisplayed().assertIsEnabled()
        capture("reply-direct-keyboard.png"); compose.onNodeWithTag("dm_send").performClick()
        compose.waitUntil(5_000) { directApi.sends.get() == 1 && !thread.state.value.busy && thread.state.value.draft.isEmpty() }
        compose.runOnIdle { assertTrue(thread.state.value.latestWindow); assertNull(thread.state.value.highlightedMessageId) }
    }
    @Test fun missingRoomTargetExplainsFailureAndExplicitRetryFindsItWithoutFalseLatestAdoption() {
        root(); type("conversation_composer", "Kept missing-target draft"); dismissIme()
        lookup.found = false; quote("room_reply:100")
        compose.onNodeWithTag("message_target_dialog").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.message_target_unavailable)).performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertTrue(conversation.state.value.latestWindow); assertNull(conversation.state.value.highlightedMessageId)
            assertEquals("Kept missing-target draft", conversation.state.value.draft) }
        lookup.found = true; dialogButton(R.string.retry).performClick()
        compose.waitUntil(5_000) { conversation.state.value.highlightedMessageId == 2L && targets.state.value.request == null }
        compose.onNodeWithTag("requested_message:2").assertIsDisplayed()
        compose.runOnIdle { assertEquals(2, lookup.calls.get()); assertEquals(0, roomApi.reads.get()); assertEquals(0, roomApi.sends.get()) }
    }
    @Test fun deniedDirectTargetRetiresInaccessibleContentButCancelKeepsItsDraft() {
        root(direct = true); type("dm_composer", "Kept denied-target draft"); dismissIme()
        lookup.failure = ApiFailure(FailureKind.HTTP, 403, "forbidden"); quote("direct_reply:100")
        compose.waitUntil(5_000) { targets.state.value.error != null && !thread.state.value.loaded }
        compose.onNodeWithText(text(R.string.message_target_unavailable)).performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertTrue(thread.state.value.messages.isEmpty()); assertNull(thread.state.value.context)
            assertEquals("Kept denied-target draft", thread.state.value.draft); assertEquals(0, directApi.reads.get()) }
        dialogButton(R.string.cancel).performClick(); compose.onNodeWithTag("message_target_dialog").assertDoesNotExist()
        draft("dm_composer", "Kept denied-target draft"); compose.onNodeWithTag("dm_send").assertIsNotEnabled()
    }
    @Test fun retainedDeletedOriginalRemainsVisitableWithoutShowingTheOldBody() {
        roomApi.originalDeleted = true; root()
        compose.onNodeWithTag("room_reply:100").performScrollTo().assertTextContains(text(R.string.conversation_deleted))
        quote("room_reply:100")
        compose.waitUntil(5_000) { conversation.state.value.highlightedMessageId == 2L && targets.state.value.request == null }
        compose.onNodeWithTag("requested_message:2").assertIsDisplayed()
        compose.onNodeWithTag("message:2").assertIsSelected()
        compose.onNodeWithText(text(R.string.conversation_deleted)).assertIsDisplayed()
        compose.onNodeWithText("Room fixture 2").assertDoesNotExist()
        compose.runOnIdle { assertTrue(conversation.state.value.messages.single { it.id == 2L }.deleted)
            assertEquals(0, roomApi.reads.get()); assertEquals(0, roomApi.sends.get()) }
    }
    @Test fun expiredTargetRangeExplainsEmptyHistoryWithoutClaimingTheRoomIsEmpty() {
        root(); type("conversation_composer", "Kept expired-target draft"); dismissIme(); quote("room_reply:100")
        compose.waitUntil(5_000) { conversation.state.value.highlightedMessageId == 2L && targets.state.value.request == null }
        compose.onNodeWithTag("requested_message:2").assertIsDisplayed()
        compose.runOnIdle { roomApi.expireOriginalRange(); conversation.refresh() }
        compose.waitUntil(5_000) { !conversation.state.value.busy && conversation.state.value.messages.isEmpty() }
        compose.onNodeWithText(text(R.string.conversation_empty_range)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.conversation_empty)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.conversation_anchor_unavailable)).assertDoesNotExist()
        compose.onNodeWithTag("requested_message:2").assertDoesNotExist()
        draft("conversation_composer", "Kept expired-target draft")
        compose.runOnIdle { assertEquals(2L, conversation.state.value.fixedUpper); assertFalse(conversation.state.value.latestWindow)
            assertEquals(0, roomApi.reads.get()); assertEquals(0, roomApi.sends.get()) }
        capture("reply-room-empty-range.png")
        compose.onNodeWithContentDescription(text(R.string.conversation_details)).assertIsDisplayed().performClick()
        compose.onNode(hasText(text(R.string.conversation_latest)) and hasAnyAncestor(isDialog()))
            .performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()
        compose.waitUntil(5_000) { !conversation.state.value.busy && conversation.state.value.latestWindow }
        compose.runOnIdle { assertEquals(100L, conversation.state.value.messages.last().id) }
        compose.onNodeWithText(text(R.string.conversation_empty_range)).assertDoesNotExist()
        draft("conversation_composer", "Kept expired-target draft")
    }
    @Test fun realBackgroundRetiresLookupAndResumeReconcilesTheRetainedSelection() {
        root(); lookup.gate = CompletableDeferred(); quote("room_reply:100")
        compose.waitUntil(5_000) { lookup.calls.get() == 1 && targets.state.value.loading }
        try {
            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            assertNull(targets.state.value.result); assertFalse(targets.state.value.loading)
            lookup.gate!!.complete(Unit); lookup.gate = null
        } finally { compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED) }
        compose.waitUntil(5_000) { conversation.state.value.highlightedMessageId == 2L && targets.state.value.request == null }
        compose.onNodeWithTag("requested_message:2").assertIsDisplayed()
        compose.runOnIdle { assertEquals(2, lookup.calls.get()); assertEquals(0, roomApi.sends.get()); assertEquals(0, roomApi.reads.get()) }
    }

    private class Rooms(sessions: TestSessions) : RoomOperations, ConversationOperations {
        override val session = sessions.state
        val sends = AtomicInteger(); val reads = AtomicInteger()
        @Volatile var originalDeleted = false
        private var rows = (1L..100L).map { roomRow(it) }
        fun expireOriginalRange() { rows = rows.filter { it.id >= 80L } }
        private val info = RoomInfo(42, "fixture-room", "Fixture room", "", "private", 0, 0, 9, "member", false, "none", 0, 200)
        override suspend fun list() = listOf(info)
        override suspend fun detail(roomId: Long) = info.also { check(roomId == 42L) }
        override suspend fun context(roomId: Long) = ConversationContext(detail(roomId), checkNotNull(session.value.chatPolicy))
        override suspend fun history(roomId: Long, beforeId: Long?, limit: Int): MessagePage {
            check(roomId == 42L); val page = rows.filter { beforeId == null || it.id < beforeId }.takeLast(limit)
            val enriched = page.map { row -> when {
                originalDeleted && row.id == 2L -> roomRow(2, deleted = true)
                originalDeleted && row.id == 100L -> roomRow(100, quoteDeleted = true)
                else -> row
            } }
            return MessagePage(enriched, page.firstOrNull()?.id, page.size == limit)
        }
        override suspend fun pings(roomId: Long) = emptyList<RoomPing>()
        override suspend fun send(roomId: Long, body: String, replyId: Long?): SendResult {
            check(roomId == 42L); sends.incrementAndGet(); val row = roomRow(101, body, own = true); rows = rows + row
            return SendResult.Message(row)
        }
        override suspend fun read(roomId: Long, messageId: Long): Long { reads.incrementAndGet(); return messageId }
        override suspend fun join(roomId: Long): RoomInfo = error("Unexpected fixture membership mutation")
        override suspend fun leave(roomId: Long): Unit = error("Unexpected fixture membership mutation")
        override suspend fun create(input: CreateRoom): RoomInfo = error("Unexpected fixture creation")
    }
    private class Directs(sessions: TestSessions) : DirectOperations {
        override val session = sessions.state
        val sends = AtomicInteger(); val reads = AtomicInteger()
        private var rows = (1L..100L).map { directRow(it) }
        override suspend fun inbox() = DirectInbox(listOf(DirectConversation(MessagePerson(8, "Peer"),
            DirectPreview(100, "Latest fixture", stamp, false, false, null, null), 0)), privacy, DisplayPreferences())
        override suspend fun context(peerId: Long) = DirectContext(MessagePerson(peerId, "Peer"), DirectRelationship(false, true),
            privacy, DisplayPreferences(), session.value.chatPolicy?.mutedEverywhere)
        override suspend fun search(prefix: String, limit: Int) = listOf(MessagePerson(8, "Peer"))
        override suspend fun history(peerId: Long, beforeId: Long?, limit: Int): DirectPage {
            check(peerId == 8L); val page = rows.filter { beforeId == null || it.id < beforeId }.takeLast(limit)
            return DirectPage(page, page.firstOrNull()?.id, page.size == limit)
        }
        override suspend fun send(peerId: Long, body: String, replyId: Long?): Long {
            check(peerId == 8L); sends.incrementAndGet(); rows = rows + directRow(101, body, own = true); return 101
        }
        override suspend fun read(peerId: Long): Int { reads.incrementAndGet(); return 0 }
        override suspend fun setBlocked(peerId: Long, blocked: Boolean): DirectRelationship = error("Unexpected fixture block mutation")
    }
    private class Lookups(sessions: TestSessions, private val rooms: Rooms, private val directs: Directs) : MessageTargetOperations {
        override val session = sessions.state
        val calls = AtomicInteger()
        @Volatile var found = true
        @Volatile var failure: ApiFailure? = null
        @Volatile var gate: CompletableDeferred<Unit>? = null
        override suspend fun lookup(request: TargetRequest): TargetSnapshot {
            calls.incrementAndGet(); gate?.await(); failure?.let { throw it }
            return when (val target = request.target) {
                is MessageTarget.RoomMessage -> {
                    val page = rooms.history(target.roomId, beforeTarget(target.id))
                    TargetSnapshot.RoomMessage(request, rooms.context(target.roomId), MessagePage(
                        page.messages.filter { found || it.id != target.id }, page.olderCursor, page.mayHaveOlder), emptyList())
                }
                is MessageTarget.DirectMessage -> TargetSnapshot.DirectMessage(request, directs.context(target.peerId),
                    directs.history(target.peerId, beforeTarget(target.id)))
                else -> error("Not a reply fixture")
            }
        }
    }
    companion object {
        private const val stamp = "2026-10-20T12:00:00Z"
        private val policy = ChatPolicy(emptySet(), null, DisplayPreferences())
        private val privacy = MessagePrivacy(DirectPrivacy(false, false, "admin", "permanently", 0),
            RevisionPrivacy(false, "super_admin", true, true, false))
        private fun roomRow(id: Long, body: String = "Room fixture $id", own: Boolean = false,
            deleted: Boolean = false, quoteDeleted: Boolean = false) = RoomMessage(id, 42,
            if (own) 7 else 8, if (own) "Viewer" else "Peer", "text", body.takeUnless { deleted }, deleted, stamp,
            replyTo = if (id == 100L) MessageReply("room", 2, true, ReplyMessage(2, MessagePerson(8, "Peer"), "text",
                "Room fixture 2".takeUnless { quoteDeleted }, quoteDeleted)) else null)
        private fun directRow(id: Long, body: String = "Direct fixture $id", own: Boolean = false) = DirectMessage(id,
            MessagePerson(if (own) 7 else 8, "Sender"), MessagePerson(if (own) 8 else 7, "Recipient"), body, stamp, stamp, own,
            false, null, own, own, if (id == 100L) DirectReply("direct", 2, true, DirectReplyTarget(2, MessagePerson(8, "Peer"), "Direct fixture 2", false)) else null,
            emptyList(), emptyList(), null)
    }
}

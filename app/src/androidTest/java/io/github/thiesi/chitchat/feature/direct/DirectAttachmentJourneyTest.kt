package io.github.thiesi.chitchat.feature.direct

import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import io.github.thiesi.chitchat.R
import io.github.thiesi.chitchat.app.SettingsViewModel
import io.github.thiesi.chitchat.core.conversation.*
import io.github.thiesi.chitchat.core.direct.*
import io.github.thiesi.chitchat.core.media.*
import io.github.thiesi.chitchat.core.network.SessionUser
import io.github.thiesi.chitchat.core.server.*
import io.github.thiesi.chitchat.core.session.*
import io.github.thiesi.chitchat.core.settings.*
import io.github.thiesi.chitchat.core.ui.AttachmentPickerOverride
import io.github.thiesi.chitchat.core.ui.LocalAttachmentPickerOverride
import io.github.thiesi.chitchat.feature.account.TestChitChatApp
import io.github.thiesi.chitchat.feature.auth.AuthViewModel
import io.github.thiesi.chitchat.feature.auth.TestSessions
import io.github.thiesi.chitchat.testing.performNativeTap
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Real root and DM thread with a synthetic one-shot picker; no system picker, upload or live claim. */
class DirectAttachmentJourneyTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val sessions = TestSessions()
    private val api = Directs(sessions.state)
    private val wire = Uploads()
    private val budget = AttachmentMemoryBudget(12L * 1024 * 1024)
    private val thread = DirectThreadViewModel(api, attachmentBudget = budget, uploads = wire)
    private val inbox = DirectInboxViewModel(api)
    private val auth = AuthViewModel(sessions)
    private val store = ViewModelStore().also { it.put("thread", thread); it.put("inbox", inbox); it.put("auth", auth) }
    private val picks = AtomicInteger()

    @Before fun configuration() {
        compose.activityRule.scenario.onActivity { it.enableEdgeToEdge() }
        val args = InstrumentationRegistry.getArguments()
        val config = InstrumentationRegistry.getInstrumentation().targetContext.resources.configuration
        args.getString("expectedOrientation")?.let { assertEquals(it.toInt(), config.orientation) }
        args.getString("expectedFontScale")?.let { assertEquals(it.toFloat(), config.fontScale, 0.01f) }
    }
    @After fun stop() {
        wire.release()
        compose.runOnIdle { thread.background(); store.clear() }
        compose.waitUntil(5_000) { budget.reservedBytes == 0L }
    }
    private fun text(id: Int, vararg args: Any) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id, *args)

    private fun openThread() {
        val server = (ServerAddress.parse("https://fixture.example/") as AddressResult.Valid).address
        val settings = SettingsViewModel(object : SettingsStore {
            override val settings = MutableStateFlow(AppSettings(server = server))
            override suspend fun saveServer(server: ServerAddress?) { settings.value = settings.value.copy(server = server) }
            override suspend fun saveTheme(theme: ThemePreference) { settings.value = settings.value.copy(theme = theme) }
            override suspend fun savePureBlack(enabled: Boolean) = Unit
        }).also { store.put("settings", it) }
        val picker = AttachmentPickerOverride { _, _, deliver ->
            picks.incrementAndGet(); deliver(PickedAttachmentRead("notes.txt", "text/plain") { ByteArrayInputStream("dm fixture body".toByteArray()) })
        }
        compose.setContent { CompositionLocalProvider(LocalAttachmentPickerOverride provides picker) {
            Box(Modifier.size(320.dp, 640.dp)) { TestChitChatApp(settings, auth, directInbox = inbox, directThread = thread) }
        } }
        compose.waitUntil(5_000) { sessions.state.value.phase == SessionPhase.ANONYMOUS }
        compose.runOnIdle { sessions.state.value = sessions.state.value.copy(phase = SessionPhase.AUTHENTICATED,
            user = SessionUser(7, "Viewer", listOf("member"), 1, false), privacy = api.privacy) }
        compose.onNodeWithText("Direct messages").performScrollTo().assertIsEnabled().performNativeTap()
        compose.waitUntil(5_000) { inbox.state.value.inbox != null && !inbox.state.value.busy }
        compose.onNodeWithTag("dm_inbox").performScrollToNode(hasTestTag("dm_conversation:8"))
        compose.onNodeWithTag("dm_conversation:8").performNativeTap()
        compose.waitUntil(5_000) { thread.state.value.peerId == 8L && thread.state.value.loaded && !thread.state.value.busy }
    }

    @Test fun pickedFileSendsThroughTheDmActorAndClearsOnConfirmation() {
        openThread()
        compose.onNodeWithTag("dm_attach").assertIsDisplayed().assertIsEnabled().performNativeTap()
        compose.onNodeWithTag("attach_file").assertIsDisplayed().performNativeTap()
        compose.waitUntil(5_000) { thread.drafts.value[8]?.attachment?.phase == DirectAttachmentPhase.READY }
        compose.onNodeWithTag("attachment_card_name", useUnmergedTree = true).assertTextEquals("notes.txt")
        compose.onNodeWithText(text(R.string.attach_caption)).assertExists()
        compose.onNodeWithTag("dm_send").assertIsEnabled().performNativeTap()
        compose.waitUntil(5_000) { wire.calls == 1 }
        compose.onNodeWithTag("attachment_card_cancel").assertIsDisplayed().assertIsEnabled()
        compose.runOnIdle { wire.ack() }
        compose.waitUntil(5_000) { thread.drafts.value[8] == null && !thread.state.value.busy }
        compose.onNodeWithTag("attachment_card").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, picks.get()); assertEquals(1, wire.calls); assertEquals(0, api.sends.get()) }
    }

    @Test fun unavailableMessagingHidesThePaperclip() {
        api.messaging = false
        openThread()
        compose.onNodeWithTag("dm_attach").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, picks.get()) }
    }

    private class Uploads : AttachmentUploadOperations {
        @Volatile var calls = 0
        private val stop = CompletableDeferred<Unit>()
        private val result = CompletableDeferred<DirectUploadReceipt>()
        private val facts = MutableStateFlow(UploadTransferFacts(15, fileBytesBuffered = 4, multipartSizeBytes = 100,
            multipartAttemptedUpperBound = 10, writerStarted = true, writerRunning = true))
        fun release() { stop.complete(Unit) }
        fun ack() { facts.value = facts.value.copy(fileBytesBuffered = 15, multipartAttemptedUpperBound = 100,
            requestBodyEndObserved = true, requestBodyEndBytes = 100); release(); result.complete(DirectUploadReceipt(8, 19)) }
        override suspend fun direct(expected: SessionState, peerId: Long, retained: RetainedAttachmentPreparation,
            ready: AttachmentPreparation.Ready, caption: String, replyId: Long?,
            onTransfer: (StateFlow<UploadTransferFacts>) -> Unit): DirectUploadReceipt {
            val hold = checkNotNull(retained.holdUpload(ready)); calls++
            try { onTransfer(facts); return result.await() }
            finally {
                withContext(NonCancellable) { stop.await() }
                facts.value = facts.value.copy(writerRunning = false, callbackFinished = true, stopped = true)
                hold.close()
            }
        }
        override suspend fun room(expected: SessionState, roomId: Long, retained: RetainedAttachmentPreparation,
            ready: AttachmentPreparation.Ready, caption: String, replyId: Long?,
            onTransfer: (StateFlow<UploadTransferFacts>) -> Unit): RoomUploadReceipt = error("DM only")
    }

    private class Directs(override val session: StateFlow<SessionState>) : DirectOperations {
        val sends = AtomicInteger()
        @Volatile var messaging = true
        val privacy = MessagePrivacy(DirectPrivacy(false, false, "admin", "permanently", 0),
            RevisionPrivacy(false, "admin", true, true, true))
        private fun row() = DirectMessage(2, MessagePerson(8, "Peer"), MessagePerson(7, "Viewer"), "fixture",
            "2026-11-09T12:01:00Z", "2026-11-09T12:00:00Z", false, false, null, false, false,
            null, emptyList(), emptyList(), null)
        override suspend fun inbox() = DirectInbox(listOf(DirectConversation(MessagePerson(8, "Peer"),
            DirectPreview(2, "fixture", "2026-11-09T12:00:00Z", false, false, null, null), 0)), privacy, DisplayPreferences())
        override suspend fun context(peerId: Long) = DirectContext(MessagePerson(peerId, "Peer"),
            DirectRelationship(false, messaging), privacy, DisplayPreferences(), null)
        override suspend fun search(prefix: String, limit: Int) = emptyList<MessagePerson>()
        override suspend fun history(peerId: Long, beforeId: Long?, limit: Int) = DirectPage(listOf(row()), 2, false)
        override suspend fun send(peerId: Long, body: String, replyId: Long?): Long { sends.incrementAndGet(); error("No text POST") }
        override suspend fun read(peerId: Long): Int = 0
        override suspend fun setBlocked(peerId: Long, blocked: Boolean): DirectRelationship = error("No block POST")
    }
}

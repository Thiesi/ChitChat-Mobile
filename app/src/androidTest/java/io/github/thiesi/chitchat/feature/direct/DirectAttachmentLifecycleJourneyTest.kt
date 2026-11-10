package io.github.thiesi.chitchat.feature.direct

import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import io.github.thiesi.chitchat.app.SettingsViewModel
import io.github.thiesi.chitchat.core.conversation.*
import io.github.thiesi.chitchat.core.direct.*
import io.github.thiesi.chitchat.core.media.*
import io.github.thiesi.chitchat.core.network.SessionUser
import io.github.thiesi.chitchat.core.server.*
import io.github.thiesi.chitchat.core.session.*
import io.github.thiesi.chitchat.core.settings.*
import io.github.thiesi.chitchat.feature.account.TestChitChatApp
import io.github.thiesi.chitchat.feature.auth.AuthViewModel
import io.github.thiesi.chitchat.feature.auth.TestSessions
import io.github.thiesi.chitchat.testing.performNativeTap
import java.io.ByteArrayInputStream
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Synthetic upload admission, real app-root navigation/lifecycle; no native picker/upload/live claim. */
class DirectAttachmentLifecycleJourneyTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val sessions = TestSessions()
    private val api = Directs(sessions.state)
    private val wire = Uploads()
    private val budget = AttachmentMemoryBudget(12L * 1024 * 1024)
    private val thread = DirectThreadViewModel(api, attachmentBudget = budget, uploads = wire)
    private val inbox = DirectInboxViewModel(api)
    private val auth = AuthViewModel(sessions)
    private val store = ViewModelStore().also { it.put("thread", thread); it.put("inbox", inbox); it.put("auth", auth) }
    @Volatile private var atOriginalThread = true

    @Before fun configuration() {
        compose.activityRule.scenario.onActivity { it.enableEdgeToEdge() }
        val args = InstrumentationRegistry.getArguments()
        val config = InstrumentationRegistry.getInstrumentation().targetContext.resources.configuration
        args.getString("expectedOrientation")?.let { assertEquals(it.toInt(), config.orientation) }
        args.getString("expectedFontScale")?.let { assertEquals(it.toFloat(), config.fontScale, 0.01f) }
    }
    @After fun stop() {
        wire.stop.complete(Unit)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.runOnIdle { atOriginalThread = false; thread.background(); store.clear() }
        compose.waitUntil(5_000) { budget.reservedBytes == 0L }
    }
    private fun contentAndStartUpload() {
        val server = (ServerAddress.parse("https://fixture.example/") as AddressResult.Valid).address
        val settings = SettingsViewModel(object : SettingsStore {
            override val settings = MutableStateFlow(AppSettings(server = server))
            override suspend fun saveServer(server: ServerAddress?) { settings.value = settings.value.copy(server = server) }
            override suspend fun saveTheme(theme: ThemePreference) { settings.value = settings.value.copy(theme = theme) }
            override suspend fun savePureBlack(enabled: Boolean) = Unit
        }).also { store.put("settings", it) }
        compose.setContent { Box(Modifier.size(320.dp, 640.dp)) {
            TestChitChatApp(settings, auth, directInbox = inbox, directThread = thread)
        } }
        compose.waitUntil(5_000) { sessions.state.value.phase == SessionPhase.ANONYMOUS }
        compose.runOnIdle { sessions.state.value = sessions.state.value.copy(phase = SessionPhase.AUTHENTICATED,
            user = SessionUser(7, "Viewer", listOf("member"), 1, false), privacy = api.privacy) }
        compose.onNodeWithText("Direct messages").performScrollTo().assertIsEnabled().performNativeTap()
        compose.waitUntil(5_000) { inbox.state.value.inbox != null && !inbox.state.value.busy }
        compose.onNodeWithTag("dm_inbox").performScrollToNode(hasTestTag("dm_conversation:8"))
        compose.onNodeWithTag("dm_conversation:8").performNativeTap()
        compose.waitUntil(5_000) { thread.state.value.peerId == 8L && thread.state.value.loaded && !thread.state.value.busy }
        // Until native attachment controls land, inject only admission into the original shown pane.
        // This tests the production root's actual leave/background callbacks, not picker consent.
        compose.runOnIdle {
            thread.updateDraft("native caption fixture")
            assertTrue(thread.selectAttachment(sessions.state.value, 8, "synthetic-original-entry",
                PickedAttachmentRead("private-file", null) { ByteArrayInputStream("prepared".toByteArray()) },
                AttachmentPickSource.FILE) { atOriginalThread })
        }
        compose.waitUntil(5_000) { thread.drafts.value[8]?.attachment?.phase == DirectAttachmentPhase.READY }
        compose.waitForIdle() // Complete the producer's original synchronous handoff.
        compose.runOnIdle {
            assertTrue(thread.sendAttachment(sessions.state.value, 8, "synthetic-original-entry",
                thread.drafts.value.getValue(8).attachment!!) { atOriginalThread })
        }
        compose.waitUntil(5_000) { wire.calls == 1 }
    }
    private fun backToInbox() {
        compose.runOnIdle { atOriginalThread = false }
        compose.onNodeWithText("Back").performNativeTap()
        compose.onNodeWithTag("dm_inbox").assertIsDisplayed()
        compose.waitForIdle()
        compose.runOnIdle { assertFalse("Navigation must not cancel the authorized upload", wire.cancelled) }
    }

    @Test fun inboxNavigationRetainsUploadButAppStopCancelsUntilPhysicalStop() {
        contentAndStartUpload(); backToInbox()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.waitUntil(5_000) { wire.cancelled }
        assertEquals(DirectAttachmentPhase.CANCELLING, thread.drafts.value.getValue(8).attachment?.phase)
        assertTrue(thread.state.value.busy); assertTrue(budget.reservedBytes > 0)
        wire.stop.complete(Unit)
        compose.waitUntil(5_000) { thread.drafts.value[8]?.attachment?.phase == DirectAttachmentPhase.READY && !thread.state.value.busy }
        assertEquals(1, wire.calls); assertTrue(budget.reservedBytes > 0)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
    }

    @Test fun homeNavigationRetainsUploadAndKeepsItsAcknowledgmentPrivate() {
        contentAndStartUpload(); backToInbox()
        compose.onNodeWithText("Back").performNativeTap()
        compose.onNodeWithText("Direct messages").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertFalse(wire.cancelled); wire.ack() }
        compose.waitUntil(5_000) { thread.drafts.value[8]?.attachment?.phase == DirectAttachmentPhase.CONFIRMED }
        compose.runOnIdle {
            assertEquals("native caption fixture", thread.drafts.value.getValue(8).body)
            assertNull(thread.state.value.confirmedSendId)
            assertEquals(0L, budget.reservedBytes); assertEquals(1, wire.calls); assertFalse(wire.cancelled)
        }
    }

    private class Uploads : AttachmentUploadOperations {
        @Volatile var calls = 0
        @Volatile var cancelled = false
        val stop = CompletableDeferred<Unit>()
        private val result = CompletableDeferred<DirectUploadReceipt>()
        private val facts = MutableStateFlow(UploadTransferFacts(8, multipartSizeBytes = 100,
            multipartAttemptedUpperBound = 10, writerStarted = true, writerRunning = true))
        fun ack() { facts.value = facts.value.copy(multipartAttemptedUpperBound = 100,
            requestBodyEndObserved = true, requestBodyEndBytes = 100); stop.complete(Unit); result.complete(DirectUploadReceipt(8, 19)) }
        override suspend fun direct(expected: SessionState, peerId: Long, retained: RetainedAttachmentPreparation,
            ready: AttachmentPreparation.Ready, caption: String, replyId: Long?,
            onTransfer: (StateFlow<UploadTransferFacts>) -> Unit): DirectUploadReceipt {
            val hold = checkNotNull(retained.holdUpload(ready)); calls++
            try { onTransfer(facts); return result.await() }
            catch (cancelled: CancellationException) { this.cancelled = true; throw cancelled }
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
        val privacy = MessagePrivacy(DirectPrivacy(false, false, "admin", "permanently", 0),
            RevisionPrivacy(false, "admin", true, true, true))
        private fun row() = DirectMessage(2, MessagePerson(8, "Peer"), MessagePerson(7, "Viewer"), "fixture",
            "2026-11-09T12:01:00Z", "2026-11-09T12:00:00Z", false, false, null, false, false,
            null, emptyList(), emptyList(), null)
        override suspend fun inbox() = DirectInbox(listOf(DirectConversation(MessagePerson(8, "Peer"),
            DirectPreview(2, "fixture", "2026-11-09T12:00:00Z", false, false, null, null), 0)), privacy, DisplayPreferences())
        override suspend fun context(peerId: Long) = DirectContext(MessagePerson(peerId, "Peer"),
            DirectRelationship(false, true), privacy, DisplayPreferences(), null)
        override suspend fun search(prefix: String, limit: Int) = emptyList<MessagePerson>()
        override suspend fun history(peerId: Long, beforeId: Long?, limit: Int) = DirectPage(listOf(row()), 2, false)
        override suspend fun send(peerId: Long, body: String, replyId: Long?): Long = error("No text POST")
        override suspend fun read(peerId: Long): Int = error("No read consent")
        override suspend fun setBlocked(peerId: Long, blocked: Boolean): DirectRelationship = error("No block POST")
    }
}

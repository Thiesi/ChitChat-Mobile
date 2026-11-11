package io.github.thiesi.chitchat.feature.conversation

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
import io.github.thiesi.chitchat.core.media.*
import io.github.thiesi.chitchat.core.rooms.*
import io.github.thiesi.chitchat.core.server.*
import io.github.thiesi.chitchat.core.session.*
import io.github.thiesi.chitchat.core.settings.*
import io.github.thiesi.chitchat.core.ui.AttachmentPickerOverride
import io.github.thiesi.chitchat.core.ui.LocalAttachmentPickerOverride
import io.github.thiesi.chitchat.feature.account.TestChitChatApp
import io.github.thiesi.chitchat.feature.auth.AuthViewModel
import io.github.thiesi.chitchat.feature.auth.TestSessions
import io.github.thiesi.chitchat.feature.rooms.RoomsViewModel
import io.github.thiesi.chitchat.testing.performNativeTap
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Real root and room pane with a synthetic one-shot picker; no system picker, upload or live claim. */
class RoomAttachmentJourneyTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val sessions = TestSessions()
    private val auth = AuthViewModel(sessions)
    private val api = Rooms(sessions)
    private val directory = RoomsViewModel(api)
    private val budget = AttachmentMemoryBudget(12L * 1024 * 1024)
    private val wire = Uploads()
    private val model = ConversationViewModel(api, attachmentBudget = budget, uploads = wire)
    private val store = ViewModelStore().also { it.put("conversation", model) }
    private val picks = AtomicInteger()
    private var nextPick: () -> PickedAttachmentRead? = { file("report.pdf", "%PDF-1.4 fixture") }

    @Before fun configuration() {
        compose.activityRule.scenario.onActivity { it.enableEdgeToEdge() }
        val args = InstrumentationRegistry.getArguments()
        val config = InstrumentationRegistry.getInstrumentation().targetContext.resources.configuration
        args.getString("expectedOrientation")?.let { assertEquals(it.toInt(), config.orientation) }
        args.getString("expectedFontScale")?.let { assertEquals(it.toFloat(), config.fontScale, 0.01f) }
    }
    @After fun stop() {
        wire.release()
        compose.runOnIdle { model.cancel(); directory.cancel(); store.clear() }
        compose.waitUntil(5_000) { budget.reservedBytes == 0L }
    }

    private fun text(id: Int, vararg args: Any) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id, *args)
    private fun file(name: String, body: String, mime: String? = null) =
        PickedAttachmentRead(name, mime) { ByteArrayInputStream(body.toByteArray()) }

    private fun root(policy: AttachmentPolicy = AttachmentPolicy.Unknown) {
        val settings = SettingsViewModel(object : SettingsStore {
            override val settings = MutableStateFlow(AppSettings(server =
                (ServerAddress.parse("chat.example.test") as AddressResult.Valid).address))
            override suspend fun saveServer(server: ServerAddress?) { settings.value = settings.value.copy(server = server) }
            override suspend fun savePureBlack(enabled: Boolean) = Unit
            override suspend fun saveTheme(theme: ThemePreference) { settings.value = settings.value.copy(theme = theme) }
        })
        val picker = AttachmentPickerOverride { _, _, deliver -> picks.incrementAndGet(); deliver(nextPick()) }
        compose.setContent {
            CompositionLocalProvider(LocalAttachmentPickerOverride provides picker) {
                Box(Modifier.size(320.dp, 640.dp)) { TestChitChatApp(settings, auth, roomModel = directory, conversationModel = model) }
            }
        }
        compose.onNodeWithText("Username").performScrollTo().performTextInput("Viewer")
        compose.onNodeWithText("Password").performScrollTo().performTextInput("fixture-password")
        compose.onNodeWithText("Sign in").performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()
        compose.waitUntil(5_000) { sessions.state.value.phase == SessionPhase.AUTHENTICATED }
        compose.runOnIdle { sessions.state.value = sessions.state.value.copy(attachmentPolicy = policy,
            chatPolicy = ChatPolicy(emptySet(), null, DisplayPreferences())) }
        compose.onNodeWithText("Rooms").performScrollTo().assertIsDisplayed().performClick()
        compose.waitUntil(5_000) { directory.state.value.loaded && !directory.state.value.busy }
        compose.onNodeWithTag("rooms_directory").performScrollToNode(hasText("Room 42"))
        compose.onNodeWithText("Room 42").assertIsDisplayed().performClick()
        compose.onNode(hasText(text(R.string.rooms_open_conversation)) and hasAnyAncestor(isDialog()))
            .performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()
        compose.waitUntil(5_000) { model.state.value.roomId == 42L && model.state.value.loaded && !model.state.value.busy }
    }
    private fun attach() = compose.onNodeWithTag("conversation_attach")
    private fun card() = compose.onNodeWithTag("attachment_card")
    private fun ready() = compose.waitUntil(5_000) {
        model.drafts.value[42]?.attachment?.phase == RoomAttachmentPhase.READY
    }
    private fun pickFile() {
        attach().assertIsDisplayed().assertIsEnabled().performNativeTap()
        compose.onNodeWithTag("attach_file").assertIsDisplayed().performNativeTap()
    }

    @Test fun pickedFileShowsCardKeepsTextSendOffAndRemoveReleasesIt() {
        root(); pickFile(); ready()
        compose.onNodeWithTag("attach_sheet").assertDoesNotExist()
        card().assertIsDisplayed()
        compose.onNodeWithTag("attachment_card_name", useUnmergedTree = true).assertTextEquals("report.pdf")
        compose.runOnIdle { assertTrue(model.updateDraft(sessions.state.value, 42, "Caption fixture")) }
        compose.onNodeWithText(text(R.string.attach_caption)).assertExists()
        compose.runOnIdle { assertTrue(budget.reservedBytes > 0); assertEquals(1, picks.get()) }
        compose.onNodeWithTag("attachment_card_remove").assertIsEnabled().performNativeTap()
        card().assertDoesNotExist()
        compose.waitUntil(5_000) { budget.reservedBytes == 0L }
        compose.runOnIdle {
            assertEquals("Caption fixture", model.state.value.draft)
            assertNull(model.drafts.value[42]?.attachment); assertEquals(0, api.sends.get())
        }
        compose.onNodeWithTag("conversation_send").assertIsEnabled()
    }

    @Test fun secondPickNeedsReplaceConfirmationAndCancelKeepsTheFirst() {
        root(); pickFile(); ready()
        nextPick = { file("second.txt", "second fixture") }
        attach().performNativeTap()
        compose.onNodeWithTag("attach_replace_dialog").assertIsDisplayed()
        compose.onNode(hasText(text(R.string.cancel)) and hasAnyAncestor(isDialog())).performNativeTap()
        compose.onNodeWithTag("attachment_card_name", useUnmergedTree = true).assertTextEquals("report.pdf")
        compose.runOnIdle { assertEquals(1, picks.get()) }
        attach().performNativeTap()
        compose.onNodeWithTag("attach_replace_confirm").performNativeTap()
        compose.onNodeWithTag("attach_file").assertIsDisplayed().performNativeTap()
        compose.waitUntil(5_000) { model.drafts.value[42]?.attachment?.filename == "second.txt" &&
            model.drafts.value[42]?.attachment?.phase == RoomAttachmentPhase.READY }
        compose.onNodeWithTag("attachment_card_name", useUnmergedTree = true).assertTextEquals("second.txt")
        compose.runOnIdle { assertEquals(2, picks.get()); assertEquals(0, api.sends.get()) }
    }

    @Test fun serverWithoutUploadsExplainsInsteadOfOpeningAPicker() {
        root(AttachmentPolicy.Known(0, emptySet(), emptySet(), null))
        attach().assertContentDescriptionEquals(text(R.string.attach_label_server_off)).performNativeTap()
        compose.onNodeWithText(text(R.string.attach_server_off)).assertIsDisplayed()
        compose.onNodeWithTag("attach_sheet").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, picks.get()) }
    }

    @Test fun unreadableSettingsOfferCheckAgainWithoutClaimingARefusal() {
        root(AttachmentPolicy.Invalid)
        attach().assertContentDescriptionEquals(text(R.string.attach_label_unreadable)).performNativeTap()
        compose.onNodeWithText(text(R.string.attach_unreadable_title, "chat.example.test")).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.attach_server_off)).assertDoesNotExist()
        compose.onNodeWithTag("attach_check_again").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("attach_sheet").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, picks.get()) }
    }

    @Test fun oversizedFinalBytesShowTheServerFiguresAndNoPost() {
        root(AttachmentPolicy.Known(5, setOf("application/pdf", "text/plain"), emptySet(), UploadRateLimits(
            UploadRateLimit(10, 3_600), UploadRateLimit(10, 3_600))))
        attach().performNativeTap()
        compose.onNodeWithTag("attach_rate").assertIsDisplayed()
        compose.onNodeWithTag("attach_photo").assertDoesNotExist()
        nextPick = { file("big.txt", "0123456789", "text/plain") }
        compose.onNodeWithTag("attach_file").performNativeTap(); ready()
        compose.onNodeWithText(text(R.string.attach_too_large, text(R.string.attach_size_bytes, "10"),
            text(R.string.attach_size_bytes, "5"))).assertIsDisplayed()
        compose.onNodeWithTag("conversation_send").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, api.sends.get()); assertEquals(0, wire.calls) }
    }

    private fun sendPicked() {
        pickFile(); ready()
        compose.runOnIdle { assertTrue(model.updateDraft(sessions.state.value, 42, "Caption fixture")) }
        compose.onNodeWithTag("conversation_send").assertIsEnabled().performNativeTap()
        compose.waitUntil(5_000) { wire.calls == 1 }
    }

    @Test fun sendShowsProgressAndConfirmedUploadClearsTheDraftWithoutATextPost() {
        root(); sendPicked()
        compose.onNodeWithTag("attachment_card_cancel").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText(text(R.string.attach_sending_progress, text(R.string.attach_size_bytes, "8"),
            text(R.string.attach_size_bytes, "16"))).assertIsDisplayed()
        compose.onNodeWithTag("conversation_composer").assertIsNotEnabled()
        compose.runOnIdle { assertEquals("Caption fixture", wire.caption); wire.fullBody() }
        compose.onNodeWithText(text(R.string.attach_finishing)).assertIsDisplayed()
        compose.onNodeWithTag("attachment_card_cancel").assertDoesNotExist()
        compose.runOnIdle { wire.ack() }
        compose.waitUntil(5_000) { model.drafts.value[42] == null && !model.state.value.busy }
        card().assertDoesNotExist()
        compose.runOnIdle { assertEquals("", model.state.value.draft); assertEquals(0, api.sends.get()); assertEquals(1, wire.calls) }
    }

    @Test fun cancelWhileTheBodyIsStillSendingKeepsTheFileForALaterSend() {
        root(); sendPicked()
        compose.onNodeWithTag("attachment_card_cancel").performNativeTap()
        compose.waitUntil(5_000) { wire.cancelled }
        compose.runOnIdle { wire.release() }
        compose.waitUntil(5_000) { model.drafts.value[42]?.attachment?.phase == RoomAttachmentPhase.READY && !model.state.value.busy }
        compose.onNodeWithText(text(R.string.attach_unknown)).assertDoesNotExist()
        compose.onNodeWithTag("conversation_send").assertIsEnabled()
        compose.runOnIdle { assertEquals("Caption fixture", model.state.value.draft); assertEquals(1, wire.calls) }
    }

    @Test fun aServerSizeRefusalShowsItsLimitAndKeepsTheFile() {
        root(); sendPicked()
        compose.runOnIdle { wire.fail(io.github.thiesi.chitchat.core.network.ApiFailure(
            io.github.thiesi.chitchat.core.network.FailureKind.HTTP, 413, "attachment_too_large", maxBytes = 5)) }
        compose.waitUntil(5_000) { model.drafts.value[42]?.attachment?.phase == RoomAttachmentPhase.READY && !model.state.value.busy }
        compose.onNodeWithText(text(R.string.attach_refused_too_large_limit, text(R.string.attach_size_bytes, "5"))).assertIsDisplayed()
        compose.onNodeWithTag("conversation_send").assertIsEnabled()
        compose.runOnIdle { assertEquals(1, wire.calls); assertEquals(0, api.sends.get()) }
    }

    /** Transport fake: holds the READY allocation like the real one and reports body facts. */
    private class Uploads : AttachmentUploadOperations {
        @Volatile var calls = 0
        @Volatile var cancelled = false
        @Volatile var caption: String? = null
        private val stop = kotlinx.coroutines.CompletableDeferred<Unit>()
        private val result = kotlinx.coroutines.CompletableDeferred<RoomUploadReceipt>()
        private val facts = MutableStateFlow(UploadTransferFacts(16, fileBytesBuffered = 8, multipartSizeBytes = 100,
            multipartAttemptedUpperBound = 40, writerStarted = true, writerRunning = true))
        fun release() { stop.complete(Unit) }
        fun fullBody() { facts.value = facts.value.copy(fileBytesBuffered = 16, multipartAttemptedUpperBound = 100,
            requestBodyEndObserved = true, requestBodyEndBytes = 100) }
        fun ack() { fullBody(); release(); result.complete(RoomUploadReceipt(42, 19)) }
        fun fail(failure: Throwable) { release(); result.completeExceptionally(failure) }
        override suspend fun room(expected: SessionState, roomId: Long, retained: RetainedAttachmentPreparation,
            ready: AttachmentPreparation.Ready, caption: String, replyId: Long?,
            onTransfer: (kotlinx.coroutines.flow.StateFlow<UploadTransferFacts>) -> Unit): RoomUploadReceipt {
            val hold = checkNotNull(retained.holdUpload(ready)); calls++; this.caption = caption
            try { onTransfer(facts); return result.await() }
            catch (stopped: kotlinx.coroutines.CancellationException) { cancelled = true; throw stopped }
            finally {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { stop.await() }
                facts.value = facts.value.copy(writerRunning = false, callbackFinished = true, stopped = true)
                hold.close()
            }
        }
        override suspend fun direct(expected: SessionState, peerId: Long, retained: RetainedAttachmentPreparation,
            ready: AttachmentPreparation.Ready, caption: String, replyId: Long?,
            onTransfer: (kotlinx.coroutines.flow.StateFlow<UploadTransferFacts>) -> Unit): DirectUploadReceipt = error("Room only")
    }

    private class Rooms(sessions: TestSessions) : RoomOperations, ConversationOperations {
        override val session = sessions.state
        val sends = AtomicInteger()
        private fun info(id: Long) = RoomInfo(id, "room$id", "Room $id", "", "public", 0, 0, 9, "member", false, "none")
        override suspend fun list() = listOf(info(42))
        override suspend fun detail(roomId: Long) = info(roomId)
        override suspend fun join(roomId: Long): RoomInfo = error("No fixture membership write expected")
        override suspend fun leave(roomId: Long) = error("No fixture membership write expected")
        override suspend fun create(input: CreateRoom): RoomInfo = error("No fixture room creation expected")
        override suspend fun context(roomId: Long) = ConversationContext(info(roomId),
            session.value.chatPolicy ?: ChatPolicy(emptySet(), null, DisplayPreferences()))
        override suspend fun history(roomId: Long, beforeId: Long?, limit: Int) = MessagePage(
            listOf(RoomMessage(2, roomId, 8, "Peer", "text", "Fixture", false, "2026-10-20T12:00:00Z")), 2, false)
        override suspend fun pings(roomId: Long) = emptyList<RoomPing>()
        override suspend fun send(roomId: Long, body: String, replyId: Long?): SendResult { sends.incrementAndGet(); error("No text POST") }
        override suspend fun read(roomId: Long, messageId: Long): Long = messageId
    }
}

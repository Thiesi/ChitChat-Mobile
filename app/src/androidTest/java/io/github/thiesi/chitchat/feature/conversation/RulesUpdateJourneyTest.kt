package io.github.thiesi.chitchat.feature.conversation

import androidx.activity.ComponentActivity
import io.github.thiesi.chitchat.testing.openTab
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.thiesi.chitchat.R
import io.github.thiesi.chitchat.app.SettingsViewModel
import io.github.thiesi.chitchat.core.conversation.*
import io.github.thiesi.chitchat.core.media.*
import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.network.FailureKind
import io.github.thiesi.chitchat.core.policies.*
import io.github.thiesi.chitchat.core.rooms.*
import io.github.thiesi.chitchat.core.server.*
import io.github.thiesi.chitchat.core.session.*
import io.github.thiesi.chitchat.core.settings.*
import io.github.thiesi.chitchat.core.ui.AttachmentPickerOverride
import io.github.thiesi.chitchat.core.ui.LocalAttachmentPickerOverride
import io.github.thiesi.chitchat.feature.account.TestChitChatApp
import io.github.thiesi.chitchat.feature.auth.AuthViewModel
import io.github.thiesi.chitchat.feature.auth.TestSessions
import io.github.thiesi.chitchat.feature.policies.RulesUpdateViewModel
import io.github.thiesi.chitchat.feature.rooms.RoomsViewModel
import io.github.thiesi.chitchat.testing.performNativeTap
import java.io.ByteArrayInputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Rules re-acceptance in a real room (O3b3a; play-readiness.md §1). A send refused with `403 rules_acceptance_required`
 * keeps the draft, sets no unknown state and opens the dialog; Not now leaves the conversation banner; Review reopens;
 * I agree posts once and never resends. The dialog sits over the composed shell: an in-flight upload survives it, and no
 * read acknowledgment is sent while it's up. No network: the session fixture mirrors the transport's refusal signal
 * (JVM-tested in SessionRepositoryTest).
 */
class RulesUpdateJourneyTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val sessions = TestSessions()
    private val auth = AuthViewModel(sessions)
    private val api = RulesRooms(sessions)
    private val directory = RoomsViewModel(api)
    private val budget = AttachmentMemoryBudget(12L * 1024 * 1024)
    private val wire = RulesUploads()
    private val model = ConversationViewModel(api, attachmentBudget = budget, uploads = wire)
    // Owned like the app owns it, so teardown clears it (as RoomAttachmentJourneyTest does) before checking the budget.
    private val store = androidx.lifecycle.ViewModelStore().also { it.put("conversation", model) }
    private val accepts = CopyOnWriteArrayList<Int>()
    private val rulesOps = object : RulesOperations {
        override val state get() = sessions.state
        override suspend fun acceptRules(version: Int, expected: SessionState): RulesAcceptResult {
            accepts += version
            val status = RulesStatus(version, null, false)
            sessions.state.value = sessions.state.value.copy(rules = status, rulesRefused = false)
            return RulesAcceptResult.Accepted(status)
        }
        override suspend fun refresh() = sessions.refresh()
    }
    private val reads = object : PolicyReads {
        override suspend fun policyDocument(name: PolicyDocumentName, expected: SessionState) =
            PolicyDocument.Hosted("# Rules", 4, null, "Be kinder in public rooms.")
    }
    private val rulesUpdate = RulesUpdateViewModel(rulesOps, reads)
    private val rulesLink = PolicyLink("https://chat.example.test/rules", 4, "2026-11-16T10:00:00+00:00")

    @Before fun configuration() { compose.activityRule.scenario.onActivity { it.enableEdgeToEdge() } }
    @After fun stop() {
        wire.release()
        compose.runOnIdle { model.cancel(); directory.cancel(); store.clear() }
        compose.waitUntil(5_000) { budget.reservedBytes == 0L }
    }

    private fun text(id: Int, vararg args: Any) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id, *args)

    private fun root() {
        val settings = SettingsViewModel(object : SettingsStore {
            override val settings = MutableStateFlow(AppSettings(server =
                (ServerAddress.parse("chat.example.test") as AddressResult.Valid).address))
            override suspend fun saveServer(server: ServerAddress?) { settings.value = settings.value.copy(server = server) }
            override suspend fun savePureBlack(enabled: Boolean) = Unit
            override suspend fun saveTheme(theme: ThemePreference) = Unit
        })
        val picker = AttachmentPickerOverride { _, _, deliver ->
            deliver(PickedAttachmentRead("report.pdf", null) { ByteArrayInputStream("%PDF-1.4 fixture".toByteArray()) })
        }
        compose.setContent {
            CompositionLocalProvider(LocalAttachmentPickerOverride provides picker) {
                Box(Modifier.size(320.dp, 640.dp)) {
                    TestChitChatApp(settings, auth, roomModel = directory, conversationModel = model, rulesUpdate = rulesUpdate)
                }
            }
        }
        compose.waitUntil(5_000) { sessions.state.value.phase == SessionPhase.ANONYMOUS }
        compose.runOnIdle { kotlinx.coroutines.runBlocking { sessions.signIn("Viewer", "fixture-password") } }
        compose.waitUntil(5_000) { sessions.state.value.phase == SessionPhase.AUTHENTICATED }
        compose.runOnIdle { sessions.state.value = sessions.state.value.copy(attachmentPolicy = AttachmentPolicy.Unknown,
            chatPolicy = ChatPolicy(emptySet(), null, DisplayPreferences()), installationName = "The Common Room",
            public = PublicSession(false, emptyList(), io.github.thiesi.chitchat.core.network.Lockdown(false),
                policies = PolicyDiscovery(rulesLink, null, null)), rules = RulesStatus(4, null, false)) }
        compose.openTab("rooms").assertIsDisplayed().performClick()
        compose.waitUntil(5_000) { directory.state.value.loaded && !directory.state.value.busy }
        compose.onNodeWithTag("rooms_directory").performScrollToNode(hasText("Room 42"))
        compose.onNodeWithText("Room 42").assertIsDisplayed().performClick()
        compose.onNode(hasText(text(R.string.rooms_open_conversation)) and hasAnyAncestor(isDialog()))
            .performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()
        compose.waitUntil(5_000) { model.state.value.roomId == 42L && model.state.value.loaded && !model.state.value.busy }
    }

    private fun dialogShown() = compose.onAllNodesWithTag("rules_updated").fetchSemanticsNodes().isNotEmpty()

    @Test fun aRefusedSendKeepsTheDraftOpensTheDialogAndNothingResendsAfterIAgree() {
        root()
        compose.runOnIdle { api.refuse = true; assertTrue(model.updateDraft(sessions.state.value, 42, "Hello rules")) }
        compose.onNodeWithTag("conversation_send").assertIsEnabled().performNativeTap()
        compose.waitUntil(5_000) { dialogShown() }
        compose.runOnIdle {
            assertEquals(1, api.sends.get())
            assertEquals("The draft stays", "Hello rules", model.state.value.draft)
            assertFalse("A definite refusal: no unknown state", model.state.value.uncertainSend)
        }
        compose.onNodeWithTag("rules_updated_summary").assertExists()
        // Not now: back to the room, with the banner; the composer stays enabled.
        compose.onNodeWithTag("rules_updated_not_now").performScrollTo().performClick()
        compose.waitUntil(5_000) { !dialogShown() }
        compose.onNodeWithTag("rules_banner").assertIsDisplayed()
        compose.onNodeWithTag("conversation_composer").assertIsEnabled()
        // Review reopens it; I agree posts once.
        compose.onNodeWithTag("rules_banner_review").performClick()
        compose.waitUntil(5_000) { dialogShown() }
        compose.runOnIdle { api.refuse = false }
        compose.onNodeWithTag("rules_updated_agree").performScrollTo().performClick()
        compose.waitUntil(5_000) { !dialogShown() }
        compose.onNodeWithTag("rules_banner").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(listOf(4), accepts)
            assertEquals("Nothing resends after I agree", 1, api.sends.get())
            assertEquals("Hello rules", model.state.value.draft)
        }
    }

    @Test fun anInFlightUploadSurvivesTheDialogAndCompletesUnderneathIt() {
        root()
        compose.onNodeWithTag("conversation_attach").assertIsDisplayed().assertIsEnabled().performNativeTap()
        compose.onNodeWithTag("attach_file").assertIsDisplayed().performNativeTap()
        compose.waitUntil(5_000) { model.drafts.value[42]?.attachment?.phase == RoomAttachmentPhase.READY }
        compose.onNodeWithTag("conversation_send").assertIsEnabled().performNativeTap()
        compose.waitUntil(5_000) { wire.calls == 1 }
        // A refusal elsewhere opens the dialog while the upload is still in flight.
        compose.runOnIdle { sessions.state.value = sessions.state.value.copy(rulesRefused = true,
            rulesRefusal = sessions.state.value.rulesRefusal + 1) }
        compose.waitUntil(5_000) { dialogShown() }
        compose.waitForIdle()
        compose.runOnIdle { assertFalse("The upload isn't cancelled by the dialog", wire.cancelled) }
        compose.runOnIdle { wire.ack() } // The upload completes underneath the dialog.
        compose.waitUntil(5_000) { model.drafts.value[42] == null }
        compose.runOnIdle { assertFalse(wire.cancelled); assertEquals(1, wire.calls) }
        assertTrue(dialogShown())
    }

    /**
     * A newer message arriving under the dialog isn't acknowledged while the dialog is up (its window holds focus); once
     * the dialog closes, the same message is acknowledged, so the suppression isn't vacuous.
     */
    @Test fun noReadIsSentWhileTheDialogIsUpAndTheReadFollowsWhenItCloses() {
        root()
        compose.runOnIdle { sessions.state.value = sessions.state.value.copy(rulesRefused = true,
            rulesRefusal = sessions.state.value.rulesRefusal + 1) }
        compose.waitUntil(5_000) { dialogShown() }
        compose.runOnIdle { api.newer = true; model.refresh() }
        compose.waitUntil(5_000) { model.state.value.messages.any { it.id == 3L } && !model.state.value.busy }
        compose.waitForIdle()
        compose.runOnIdle { assertFalse("No read acknowledgment of the newer message while the dialog is up", 3L in api.reads) }
        compose.onNodeWithTag("rules_updated_not_now").performScrollTo().performClick()
        compose.waitUntil(5_000) { !dialogShown() }
        compose.waitUntil(5_000) { 3L in api.reads }
    }
}

/** Transport fake: holds the READY allocation like the real one and reports body facts. */
internal class RulesUploads : AttachmentUploadOperations {
    @Volatile var calls = 0
    @Volatile var cancelled = false
    private val stop = kotlinx.coroutines.CompletableDeferred<Unit>()
    private val result = kotlinx.coroutines.CompletableDeferred<RoomUploadReceipt>()
    private val facts = MutableStateFlow(UploadTransferFacts(16, fileBytesBuffered = 8, multipartSizeBytes = 100,
        multipartAttemptedUpperBound = 40, writerStarted = true, writerRunning = true))
    fun release() { stop.complete(Unit) }
    fun ack() {
        facts.value = facts.value.copy(fileBytesBuffered = 16, multipartAttemptedUpperBound = 100,
            requestBodyEndObserved = true, requestBodyEndBytes = 100)
        release(); result.complete(RoomUploadReceipt(42, 19))
    }
    override suspend fun room(expected: SessionState, roomId: Long, retained: RetainedAttachmentPreparation,
        ready: AttachmentPreparation.Ready, caption: String, replyId: Long?,
        onTransfer: (kotlinx.coroutines.flow.StateFlow<UploadTransferFacts>) -> Unit): RoomUploadReceipt {
        val hold = checkNotNull(retained.holdUpload(ready)); calls++
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

internal class RulesRooms(private val sessions: TestSessions) : RoomOperations, ConversationOperations {
    override val session = sessions.state
    val sends = AtomicInteger()
    val reads = CopyOnWriteArrayList<Long>()
    @Volatile var refuse = false
    @Volatile var newer = false
    private fun info(id: Long) = RoomInfo(id, "room$id", "Room $id", "", "public", 0, 0, 9, "member", false, "none")
    override suspend fun list() = listOf(info(42))
    override suspend fun detail(roomId: Long) = info(roomId)
    override suspend fun join(roomId: Long): RoomInfo = error("No fixture membership write expected")
    override suspend fun leave(roomId: Long) = error("No fixture membership write expected")
    override suspend fun create(input: CreateRoom): RoomInfo = error("No fixture room creation expected")
    override suspend fun context(roomId: Long) = ConversationContext(info(roomId),
        session.value.chatPolicy ?: ChatPolicy(emptySet(), null, DisplayPreferences()))
    override suspend fun history(roomId: Long, beforeId: Long?, limit: Int): MessagePage {
        val messages = listOf(RoomMessage(2, roomId, 8, "Peer", "text", "Fixture", false, "2026-10-20T12:00:00Z")) +
            if (newer) listOf(RoomMessage(3, roomId, 8, "Peer", "text", "Newer fixture", false, "2026-10-20T12:01:00Z")) else emptyList()
        return MessagePage(messages, messages.first().id, false)
    }
    override suspend fun pings(roomId: Long) = emptyList<RoomPing>()
    override suspend fun send(roomId: Long, body: String, replyId: Long?): SendResult {
        sends.incrementAndGet()
        if (refuse) {
            // Mirrors SessionRepository's fenced transport signal for this exact refusal.
            sessions.state.value = sessions.state.value.copy(rulesRefused = true, rulesRefusal = sessions.state.value.rulesRefusal + 1)
            throw ApiFailure(FailureKind.HTTP, 403, "rules_acceptance_required")
        }
        return SendResult.Message(RoomMessage(9, roomId, 7, "Viewer", "text", body, false, "2026-10-20T12:02:00Z"))
    }
    override suspend fun read(roomId: Long, messageId: Long): Long { reads += messageId; return messageId }
}

package io.github.thiesi.chitchat.feature.conversation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import io.github.thiesi.chitchat.core.conversation.ChatPolicy
import io.github.thiesi.chitchat.core.conversation.ConversationContext
import io.github.thiesi.chitchat.core.conversation.ConversationOperations
import io.github.thiesi.chitchat.core.conversation.DisplayPreferences
import io.github.thiesi.chitchat.core.conversation.MessageAttachment
import io.github.thiesi.chitchat.core.conversation.MessagePage
import io.github.thiesi.chitchat.core.conversation.MessagePerson
import io.github.thiesi.chitchat.core.conversation.MessageReaction
import io.github.thiesi.chitchat.core.conversation.MessageReply
import io.github.thiesi.chitchat.core.conversation.RoomMessage
import io.github.thiesi.chitchat.core.conversation.RoomMute
import io.github.thiesi.chitchat.core.conversation.RoomPing
import io.github.thiesi.chitchat.core.conversation.SendResult
import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.network.FailureKind
import io.github.thiesi.chitchat.core.network.SessionUser
import io.github.thiesi.chitchat.core.rooms.RoomInfo
import io.github.thiesi.chitchat.core.session.SessionPhase
import io.github.thiesi.chitchat.core.session.SessionState
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Before
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ConversationJourneyTest {
    @get:Rule val compose = createComposeRule()
    private val fake = TestConversations()
    private val model = ConversationViewModel(fake)
    @Volatile private var imeBottom = 0
    @Volatile private var focused = false
    @Volatile private var lifecycleState = ""
    @Before fun verifyRequestedConfiguration() {
        val args = InstrumentationRegistry.getArguments()
        val config = InstrumentationRegistry.getInstrumentation().targetContext.resources.configuration
        args.getString("expectedOrientation")?.let { assertEquals(it.toInt(), config.orientation) }
        args.getString("expectedFontScale")?.let { assertEquals(it.toFloat(), config.fontScale, 0.01f) }
    }
    @After fun tearDown() { model.cancel() }
    private fun content(height: Int = 640) {
        compose.setContent {
            val bottom = WindowInsets.ime.getBottom(LocalDensity.current)
            val focus = LocalWindowInfo.current.isWindowFocused
            val life = LocalLifecycleOwner.current.lifecycle.currentStateAsState().value
            SideEffect { imeBottom = bottom; focused = focus; lifecycleState = life.name }
            MaterialTheme { Box(Modifier.size(320.dp, height.dp)) { ConversationScreen(model, 42, {}) } }
        }
        loaded()
    }
    private fun loaded() { compose.waitUntil(5_000) { model.state.value.loaded && !model.state.value.busy } }
    private fun send(body: String) {
        compose.onNodeWithTag("conversation_composer").assertIsDisplayed().performTextInput(body)
        compose.runOnIdle { assertTrue("Fixture input reached its VM", body == model.state.value.draft) }
        compose.waitUntil(5_000) { imeBottom > 0 }
        // Send is pinned beside the field: no composer scroll that can race cursor bring-into-view.
        compose.onNodeWithTag("conversation_composer").assertIsDisplayed()
        compose.onNodeWithTag("conversation_send").assertIsDisplayed().assertIsEnabled().performClick()
    }
    private fun timelineText(text: String) {
        compose.onNodeWithTag("conversation_timeline").performScrollToNode(hasText(text))
        compose.onNodeWithText(text).assertIsDisplayed()
    }
    /** Failure-only fixture diagnostics: counts/state/bounds, never content or credentials. */
    private fun sentOutcome(readExpected: Boolean = false) {
        try {
            compose.waitUntil(5_000) { fake.sends == 1 && !model.state.value.busy && (!readExpected || fake.lastRead == 4L) }
        } catch (failure: androidx.compose.ui.test.ComposeTimeoutException) {
            var safe = "state unavailable"
            runCatching { compose.runOnIdle {
                val state = model.state.value
                safe = "sends=${fake.sends}, busy=${state.busy}, sending=${state.sending}, loaded=${state.loaded}, " +
                    "stale=${state.stale}, latestWindow=${state.latestWindow}, atLatest=${state.atLatest}, " +
                    "lastRead=${fake.lastRead}, readCursor=${state.readCursor}, readError=${state.readError?.kind}, " +
                    "sendError=${state.error?.kind}, uncertain=${state.uncertainSend}, scroll=${state.scrollRequest}"
            } }
            val newest = runCatching { compose.onNodeWithTag("message:4").fetchSemanticsNode().boundsInRoot.toString() }.getOrDefault("absent")
            val timeline = runCatching { compose.onNodeWithTag("conversation_timeline").fetchSemanticsNode().boundsInRoot.toString() }.getOrDefault("absent")
            throw AssertionError("Fixture send outcome: $safe; newestBounds=$newest; timelineBounds=$timeline", failure)
        }
    }

    @Test fun multilineSendUsesExplicitActionAndAcknowledgedMessageClearsDraft() {
        content()
        if (!compose.onNodeWithText("Manual refresh; realtime", substring = true).isDisplayed()) {
            compose.onNodeWithContentDescription("Room details").performClick()
            compose.onNodeWithText("Manual refresh; realtime", substring = true).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Close").performClick()
        }
        else compose.onNodeWithText("Manual refresh; realtime", substring = true).assertIsDisplayed()
        val body = (1..30).joinToString("\n") { "Tall fixture line $it" }
        var readAtLiveEdge = false
        fake.onRead = { id -> if (id == 4L) readAtLiveEdge = model.state.value.atLatest }
        send(body)
        sentOutcome(readExpected = true)
        compose.runOnIdle {
            assertEquals("", model.state.value.draft); assertEquals(1, fake.sends)
            assertEquals(4L, fake.lastRead); assertTrue(readAtLiveEdge)
        }
    }

    @Test fun tallNewestHistoryAcknowledgesOnlyAtVisibleLiveEdgeWithoutSending() {
        val body = (1..30).joinToString("\n") { "Tall history fixture line $it" }
        fake.messages = listOf(message(2, "Earlier fixture"), message(3, body))
        var readAtLiveEdge = false
        fake.onRead = { id -> if (id == 3L) readAtLiveEdge = model.state.value.atLatest }
        content()
        compose.waitUntil(5_000) { fake.lastRead == 3L }
        compose.runOnIdle {
            assertEquals(3L, fake.lastRead); assertTrue(readAtLiveEdge)
            assertEquals(0, fake.sends)
        }
    }

    @Test fun compactDetailsPreserveControlsWithoutReadingAnArrivalBehindTheDialog() {
        content(height = 220)
        compose.waitUntil(5_000) { fake.lastRead == 3L }
        compose.onNodeWithContentDescription("Room details").assertIsDisplayed().performClick()
        compose.onNodeWithText("Manual refresh; realtime", substring = true).performScrollTo().assertIsDisplayed()
        compose.runOnIdle { fake.messages += message(4, "Arrival fixture"); model.refresh() }
        compose.waitUntil(5_000) { model.state.value.messages.lastOrNull()?.id == 4L && !model.state.value.busy }
        compose.runOnIdle { assertEquals(3L, fake.lastRead); assertEquals(0, fake.sends) }
        // Row visibility alone did not reach the end sentinel. Exercise the real latest action;
        // it dismisses details and the viewport must independently prove the live edge.
        val beforeLatest = model.state.value.scrollRequest
        val latestLabel = InstrumentationRegistry.getInstrumentation().targetContext.getString(io.github.thiesi.chitchat.R.string.conversation_latest)
        val latestAction = compose.onNodeWithText(latestLabel).performScrollTo().assertIsDisplayed().assertIsEnabled()
        if (InstrumentationRegistry.getArguments().getString("captureRoomFixture") == "true") {
            val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            val target = InstrumentationRegistry.getInstrumentation().targetContext
            val file = java.io.File(target.getExternalFilesDir("screenshots") ?: target.cacheDir, "room-latest-before.png")
            file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        latestAction.performClick()
        try { compose.waitUntil(5_000) { model.state.value.scrollRequest > beforeLatest && !model.state.value.busy } }
        catch (failure: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("Latest action fixture: before=$beforeLatest, after=${model.state.value.scrollRequest}, busy=${model.state.value.busy}, error=${model.state.value.error?.kind}", failure)
        }
        try { compose.waitUntil(5_000) { fake.lastRead == 4L } }
        catch (failure: androidx.compose.ui.test.ComposeTimeoutException) {
            var safe = "unavailable"
            compose.runOnIdle { val state = model.state.value
                safe = "focused=$focused, lifecycle=$lifecycleState, ime=$imeBottom, edge=${state.atLatest}, latest=${state.latestWindow}, busy=${state.busy}, stale=${state.stale}, cursor=${state.readCursor}, attemptedCalls=${fake.readCalls}, scrollRequest=${state.scrollRequest}, error=${state.readError?.kind}"
            }
            val node = compose.onNodeWithTag("conversation_timeline").fetchSemanticsNode()
            val range = node.config[SemanticsProperties.VerticalScrollAxisRange]
            safe += "; range=${range.value()}/${range.maxValue()}, bounds=${node.boundsInRoot}"
            throw AssertionError("Modal fixture read proof: $safe", failure)
        }
        compose.onNodeWithText("Arrival fixture").assertIsDisplayed()
    }

    @Test fun compactReadRetryRemainsActionableWhileTheDialogSuppressesReadProof() {
        fake.readFailure = ApiFailure(FailureKind.NETWORK); content(height = 220)
        compose.waitUntil(5_000) { model.state.value.readError != null }
        compose.onNodeWithContentDescription("Room details").performClick()
        val label = InstrumentationRegistry.getInstrumentation().targetContext.getString(io.github.thiesi.chitchat.R.string.conversation_retry_read)
        compose.onNodeWithText(label).performScrollTo().assertIsEnabled()
        compose.runOnIdle { assertEquals(1, fake.readCalls); fake.readFailure = null }
        compose.onNodeWithText(label).performClick()
        compose.waitUntil(5_000) { fake.lastRead == 3L && model.state.value.readError == null }
        compose.runOnIdle { assertEquals(2, fake.readCalls) }
    }

    @Test fun uncertainSendRequiresGetReconciliationAndExplicitDuplicateRiskChoice() {
        fake.sendFailure = ApiFailure(FailureKind.TIMEOUT)
        content(); send("Possibly sent fixture")
        compose.waitUntil(5_000) { model.state.value.uncertainSend }
        compose.onNodeWithText("Send").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText("Allow another send despite duplicate risk").assertDoesNotExist()
        compose.runOnIdle { fake.sendFailure = null }
        compose.onNodeWithText("Refresh latest").performScrollTo().performClick(); loaded()
        compose.onNodeWithText("Send").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText("Allow another send despite duplicate risk").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, fake.sends); assertEquals("Possibly sent fixture", model.state.value.draft) }
        compose.onNodeWithText("Send").assertIsDisplayed().assertIsEnabled().performClick()
        compose.waitUntil(5_000) { fake.sends == 2 && !model.state.value.busy }
    }

    @Test fun ignoredMessageRequiresTemporaryRevealAndIgnoredPingsStayHidden() {
        fake.ignored = setOf(8); fake.pingList = listOf(ping())
        fake.messages = listOf(message(3, "Ignored fixture"))
        content()
        compose.onNodeWithText("Ignored fixture").assertDoesNotExist()
        timelineText("Ignored message · Reveal temporarily")
        compose.onNodeWithText("Ignored message · Reveal temporarily").performClick()
        timelineText("Ignored fixture")
        compose.onNodeWithText("Private pings").performScrollTo().performClick()
        compose.onNodeWithText("No private pings in this room.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Private ping fixture").assertDoesNotExist()
    }

    @Test fun messageMetadataDisplaysWithoutFakeInteractionControls() {
        fake.messages = listOf(
            RoomMessage(2, 42, 8, "Bob", "text", null, true, "2026-10-20 12:00:00+00",
                reactions = listOf(MessageReaction("👍", listOf(MessagePerson(7, "Alice")), true))),
            RoomMessage(3, 42, 8, "Bob", "emote", "waves", false, "2026-10-20T12:00:00+00:00",
                MessageAttachment(7, "fixture.txt", "text/plain", 10, "0".repeat(64), false),
                MessageReply("room", 1, false, null),
                editedAt = "2026-10-20T12:01:00+00:00"))
        content()
        timelineText("Message deleted")
        timelineText("👍 1")
        timelineText("* Bob waves")
        timelineText("Reply to an unavailable message")
        timelineText("Edited · previous versions may be retained by the server")
        compose.onNodeWithText("Edit").assertDoesNotExist(); compose.onNodeWithText("Download").assertDoesNotExist()
    }

    @Test fun mutedRoomShowsReasonAndCannotPost() {
        fake.mute = RoomMute(1, "room", 42, null, "Fixture moderation reason")
        content()
        compose.onNodeWithText("Posting muted: Fixture moderation reason", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("conversation_composer").assertIsDisplayed().performTextInput("Blocked fixture")
        compose.onNodeWithText("Send").assertIsDisplayed().assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, fake.sends) }
    }

    @Test fun olderPageRestoresVisibleMessageAndPixelAnchor() {
        fake.messages = (80L..100).map { message(it, "Fixture $it") }
        fake.olderMessages = (60L..79).map { message(it, "Fixture $it") }
        content()
        compose.onNodeWithTag("conversation_timeline").performScrollToNode(hasText("Load older messages"))
        val before = compose.onNodeWithTag("message:80").fetchSemanticsNode().boundsInRoot.top
        compose.onNodeWithText("Load older messages").performClick()
        compose.waitUntil(5_000) { model.state.value.messages.firstOrNull()?.id == 60L && !model.state.value.busy }
        compose.waitForIdle()
        val after = compose.onNodeWithTag("message:80").fetchSemanticsNode().boundsInRoot.top
        assertEquals(before, after, 2f)
    }

    @Test fun privatePingNeverAppearsAsRoomMessage() {
        fake.pingResult = true
        content(); send("/ping Bob Private ping fixture")
        sentOutcome()
        compose.onNodeWithText("Private ping fixture").assertDoesNotExist()
        compose.onNodeWithText("Private pings").performScrollTo().performClick()
        compose.onNodeWithText("Private ping fixture").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals(listOf(2L, 3L), model.state.value.messages.map { it.id }) }
    }

    @Test fun compositionRestorationKeepsOnlyVmDraftAndLogoutScrubsContent() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { MaterialTheme { Box(Modifier.size(320.dp, 640.dp)) { ConversationScreen(model, 42, {}) } } }
        loaded()
        compose.onNodeWithTag("conversation_composer").assertIsDisplayed().performTextInput("VM-only fixture draft")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("conversation_composer").assertIsDisplayed()
        compose.onNodeWithText("VM-only fixture draft").assertIsDisplayed()
        compose.runOnIdle { fake.session.value = SessionState(generation = 2, phase = SessionPhase.SIGNED_OUT) }
        compose.onNodeWithText("VM-only fixture draft").assertDoesNotExist()
        compose.runOnIdle { assertTrue(model.state.value.messages.isEmpty()); assertEquals(0, fake.sends) }
    }

    private class TestConversations : ConversationOperations {
        override val session = MutableStateFlow(SessionState(generation = 1, phase = SessionPhase.AUTHENTICATED,
            user = SessionUser(7, "Alice", listOf("member"), 1, false)))
        var messages = listOf(message(2, "Earlier fixture"), message(3, "Latest fixture"))
        var olderMessages = emptyList<RoomMessage>()
        var ignored = emptySet<Long>(); var mute: RoomMute? = null; var pingList = emptyList<RoomPing>()
        @Volatile var sends = 0
        var sendFailure: ApiFailure? = null; var pingResult = false
        @Volatile var lastRead = 0L
        var onRead: ((Long) -> Unit)? = null
        @Volatile var readCalls = 0
        var readFailure: ApiFailure? = null
        override suspend fun context(roomId: Long) = ConversationContext(RoomInfo(42, "lounge", "Lounge", "", "public", 0, 0, 7,
            "member", false, "none", 1, 2, mute), ChatPolicy(ignored, null, DisplayPreferences()))
        override suspend fun history(roomId: Long, beforeId: Long?, limit: Int): MessagePage {
            val page = if (beforeId == null) messages else olderMessages
            return MessagePage(page, page.firstOrNull()?.id, beforeId == null && olderMessages.isNotEmpty())
        }
        override suspend fun pings(roomId: Long) = pingList
        override suspend fun send(roomId: Long, body: String): SendResult {
            sends++; sendFailure?.let { throw it }
            if (pingResult) return SendResult.Ping(ping())
            val result = message(4, body); messages = messages + result; return SendResult.Message(result)
        }
        override suspend fun read(roomId: Long, messageId: Long): Long {
            readCalls++; readFailure?.let { throw it }
            lastRead = messageId; onRead?.invoke(messageId); return messageId
        }
    }
    companion object {
        private fun message(id: Long, body: String) = RoomMessage(id, 42, 8, "Bob", "text", body, false, "2026-10-20 12:00:00+00")
        private fun ping() = RoomPing(500, 42, MessagePerson(8, "Bob"), MessagePerson(7, "Alice"), "Private ping fixture", "2026-10-20T12:00:00+00:00")
    }
}

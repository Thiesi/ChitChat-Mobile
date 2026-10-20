package io.github.thiesi.chitchat.feature.conversation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
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
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ConversationJourneyTest {
    @get:Rule val compose = createComposeRule()
    private val fake = TestConversations()
    private val model = ConversationViewModel(fake)
    @After fun tearDown() { model.cancel() }
    private fun content() {
        compose.setContent { MaterialTheme { Box(Modifier.size(320.dp, 640.dp)) { ConversationScreen(model, 42, {}) } } }
        loaded()
    }
    private fun loaded() { compose.waitUntil(5_000) { model.state.value.loaded && !model.state.value.busy } }
    private fun send(body: String) {
        compose.onNodeWithTag("conversation_composer").performScrollTo().performTextInput(body)
        compose.onNodeWithText("Send").performScrollTo().performClick()
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
        compose.onNodeWithText("Manual refresh; realtime", substring = true).assertIsDisplayed()
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

    @Test fun uncertainSendRequiresGetReconciliationAndExplicitDuplicateRiskChoice() {
        fake.sendFailure = ApiFailure(FailureKind.TIMEOUT)
        content(); send("Possibly sent fixture")
        compose.waitUntil(5_000) { model.state.value.uncertainSend }
        compose.onNodeWithText("Send").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Allow another send despite duplicate risk").assertDoesNotExist()
        compose.runOnIdle { fake.sendFailure = null }
        compose.onNodeWithText("Refresh latest").performScrollTo().performClick(); loaded()
        compose.onNodeWithText("Send").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Allow another send despite duplicate risk").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, fake.sends); assertEquals("Possibly sent fixture", model.state.value.draft) }
        compose.onNodeWithText("Send").performScrollTo().performClick()
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
        compose.onNodeWithTag("conversation_composer").performScrollTo().performTextInput("Blocked fixture")
        compose.onNodeWithText("Send").performScrollTo().assertIsNotEnabled()
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
        compose.onNodeWithTag("conversation_composer").performScrollTo().performTextInput("VM-only fixture draft")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("conversation_composer").performScrollTo()
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
        var sends = 0; var sendFailure: ApiFailure? = null; var pingResult = false
        var lastRead = 0L; var onRead: ((Long) -> Unit)? = null
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
            lastRead = messageId; onRead?.invoke(messageId); return messageId
        }
    }
    companion object {
        private fun message(id: Long, body: String) = RoomMessage(id, 42, 8, "Bob", "text", body, false, "2026-10-20 12:00:00+00")
        private fun ping() = RoomPing(500, 42, MessagePerson(8, "Bob"), MessagePerson(7, "Alice"), "Private ping fixture", "2026-10-20T12:00:00+00:00")
    }
}

package io.github.thiesi.chitchat.feature.conversation

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import io.github.thiesi.chitchat.R
import io.github.thiesi.chitchat.core.account.IgnoreOperations
import io.github.thiesi.chitchat.core.conversation.*
import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.network.FailureKind
import io.github.thiesi.chitchat.core.network.SessionUser
import io.github.thiesi.chitchat.core.realtime.*
import io.github.thiesi.chitchat.core.rooms.RoomInfo
import io.github.thiesi.chitchat.core.session.SessionPhase
import io.github.thiesi.chitchat.core.session.SessionState
import io.github.thiesi.chitchat.feature.account.IgnorePersonViewModel
import io.github.thiesi.chitchat.feature.realtime.NativeTypingHarness
import io.github.thiesi.chitchat.testing.tapNativeInput
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** UI fixtures only: real presentation/reconciliation/coordinator, no live transport. */
class RoomIgnoreJourneyTest {
    @get:Rule val compose = createComposeRule()
    private val api = Conversations()
    private val changes = Ignores(api)
    private val ignores = IgnorePersonViewModel(changes)
    private val typing = NativeTypingHarness(api.session)
    private val model = ConversationViewModel(api, Events(), typing.controller)
    @Volatile private var ime = 0
    @Before fun requestedConfiguration() {
        val args = InstrumentationRegistry.getArguments()
        val config = InstrumentationRegistry.getInstrumentation().targetContext.resources.configuration
        args.getString("expectedOrientation")?.let { assertEquals(it.toInt(), config.orientation) }
        args.getString("expectedFontScale")?.let { assertEquals(it.toFloat(), config.fontScale, 0.01f) }
    }
    @After fun stop() { Espresso.closeSoftKeyboard(); ignores.cancel(); model.cancel(); typing.close() }
    private fun text(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)
    private fun content() {
        compose.setContent {
            val bottom = WindowInsets.ime.getBottom(LocalDensity.current)
            SideEffect { ime = bottom }
            MaterialTheme { Box(Modifier.size(320.dp, 640.dp)) { ConversationScreen(model, 42, changes::refresh, ignores) } }
        }
        compose.waitUntil(5_000) { model.state.value.loaded && !model.state.value.busy }
    }
    private fun author() {
        compose.onNodeWithTag("conversation_timeline").performScrollToNode(hasTestTag("room_author:3"))
        compose.onNodeWithTag("room_author:3").assertIsDisplayed().performClick()
        compose.onNodeWithTag("room_person_dialog").assertIsDisplayed()
    }
    private fun change() = compose.onNodeWithTag("room_ignore_change").performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()
    private fun settled(count: Int) { compose.waitUntil(5_000) { changes.calls.get() == count && !ignores.state.value.busy && !model.state.value.syncing } }
    private fun close() = compose.onNodeWithText(text(R.string.rooms_close)).assertIsDisplayed().performClick()

    @Test fun ignoreAndStopIgnoringRemainAvailableFromCollapsedAuthorWithoutSending() {
        content(); author()
        compose.onNodeWithText(text(R.string.ignore_scope)).performScrollTo().assertIsDisplayed()
        change(); settled(1); close()
        compose.onNodeWithText("Latest fixture").assertDoesNotExist()
        author(); compose.onNodeWithText(text(R.string.ignore_confirmed)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("room_ignore_change").assertTextEquals(text(R.string.ignore_stop))
        change(); settled(2); close()
        compose.onNodeWithTag("conversation_timeline").performScrollToNode(hasText("Latest fixture"))
        compose.onNodeWithText("Latest fixture").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, api.sends); assertTrue(api.session.value.chatPolicy!!.ignoredUserIds.isEmpty()) }
    }
    @Test fun unknownListIsNotAnEmptyListAndRefreshDoesNotPost() {
        content(); compose.runOnIdle { api.session.value = api.session.value.copy(chatPolicy = null) }
        author(); compose.onNodeWithText(text(R.string.ignore_unknown)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("room_ignore_change").assertDoesNotExist()
        compose.onNode(hasText(text(R.string.refresh_session)) and hasAnyAncestor(isDialog())).performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(5_000) { api.session.value.chatPolicy != null }
        compose.onNodeWithTag("room_ignore_change").performScrollTo().assertIsEnabled()
        assertEquals(0, changes.calls.get()); assertEquals(1, changes.gets.get())
    }
    @Test fun timeoutKeepsLastConfirmedStateUntilExplicitGetWithoutPostReplay() {
        changes.failure = ApiFailure(FailureKind.TIMEOUT); changes.applyBeforeFailure = true
        content(); author(); change()
        compose.waitUntil(5_000) { ignores.state.value.unconfirmed }
        compose.onNodeWithText(text(R.string.ignore_unconfirmed)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("room_ignore_change").performScrollTo().assertIsNotEnabled()
        compose.onNode(hasText(text(R.string.refresh_session)) and hasAnyAncestor(isDialog())).performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(5_000) { !ignores.state.value.unconfirmed }
        compose.onNodeWithTag("room_ignore_change").performScrollTo().assertTextEquals(text(R.string.ignore_stop)).assertIsEnabled()
        assertEquals(1, changes.calls.get()); assertEquals(1, changes.gets.get())
    }
    @Test fun staleAccountDialogAndCallbacksCannotActForReplacementAccount() {
        content(); author()
        val stale = checkNotNull(ignores.state.value.target)
        compose.runOnIdle { api.session.value = api.session.value.copy(generation = 2,
            user = SessionUser(9, "Second", listOf("member"), 1, false)) }
        compose.waitUntil(5_000) { ignores.state.value.target == null }
        compose.onNodeWithTag("room_person_dialog").assertDoesNotExist()
        compose.runOnIdle { ignores.change(stale, true); assertEquals(0, changes.calls.get()) }
    }
    @Test fun dialogSuppressesReadAndTypingWhileDraftSurvivesNativeKeyboardRoundTrip() {
        content(); compose.waitUntil(5_000) { api.read.get() == 3L }
        compose.tapNativeInput("conversation_composer", { ime }).performTextInput("Draft fixture")
        compose.waitUntil(5_000) { typing.requests.get() == 1 }
        compose.runOnIdle { typing.hint(42) }
        compose.waitUntil(5_000) { typing.controller.state.value.people.isNotEmpty() }
        compose.onNodeWithTag("typing_status").assertIsDisplayed()
        typing.capture("ignore-composer-keyboard.png")
        // The native Back action dismisses the IME before selecting a timeline author.
        Espresso.pressBack(); compose.waitUntil(5_000) { ime == 0 }
        author(); compose.waitUntil(5_000) { typing.controller.state.value.people.isEmpty() }
        compose.runOnIdle { api.rows = api.rows + message(4, "Arrival fixture"); model.invalidate() }
        compose.waitUntil(5_000) { model.state.value.messages.lastOrNull()?.id == 4L && !model.state.value.syncing }
        compose.runOnIdle { assertEquals(3L, api.read.get()); assertEquals(1, typing.requests.get()); assertEquals(0, api.sends) }
        typing.capture("ignore-person-dialog.png")
        close()
        compose.tapNativeInput("conversation_composer", { ime }).assert(SemanticsMatcher.expectValue(
            SemanticsProperties.EditableText, AnnotatedString("Draft fixture")))
        compose.onNodeWithTag("conversation_send").assertIsDisplayed().assertIsEnabled()
        assertEquals(1, typing.requests.get()) // Focus/restored draft is not input or a new signal.
    }
    @Test fun explicitCancelLeavesUnconfirmedPreferenceForGetRatherThanRetrying() {
        changes.gate = CompletableDeferred()
        content(); author(); change()
        compose.waitUntil(5_000) { ignores.state.value.busy && changes.calls.get() == 1 }
        compose.onNodeWithText(text(R.string.cancel)).assertIsDisplayed().performClick()
        compose.waitUntil(5_000) { api.session.value.refreshRequired && !api.session.value.busy }
        author(); compose.onNodeWithTag("room_ignore_change").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.ignore_unconfirmed)).performScrollTo().assertIsDisplayed()
        assertEquals(1, changes.calls.get()); assertEquals(0, changes.gets.get())
    }

    private class Ignores(private val api: Conversations) : IgnoreOperations {
        override val session = api.session
        val calls = AtomicInteger(); val gets = AtomicInteger()
        var serverIds = emptySet<Long>()
        var failure: ApiFailure? = null; var applyBeforeFailure = false
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun change(expected: SessionState, userId: Long, ignored: Boolean): Set<Long> {
            check(expected.generation == session.value.generation)
            calls.incrementAndGet(); session.value = session.value.copy(busy = true)
            var confirmed = false
            try {
                gate?.await()
                if (failure == null || applyBeforeFailure) serverIds = if (ignored) serverIds + userId else serverIds - userId
                failure?.let { throw it }
                confirmed = true; return serverIds
            } finally {
                session.value = session.value.copy(busy = false, refreshRequired = !confirmed,
                    chatPolicy = if (confirmed) ChatPolicy(serverIds, null, DisplayPreferences()) else session.value.chatPolicy)
            }
        }
        fun refresh() {
            gets.incrementAndGet()
            session.value = session.value.copy(refreshRequired = false, chatPolicy = ChatPolicy(serverIds, null, DisplayPreferences()))
        }
    }
    private class Conversations : ConversationOperations {
        override val session = MutableStateFlow(SessionState(generation = 1, phase = SessionPhase.AUTHENTICATED,
            user = SessionUser(7, "Viewer", listOf("member"), 1, false), shareTyping = true,
            chatPolicy = ChatPolicy(emptySet(), null, DisplayPreferences())))
        var rows = listOf(message(2, "Earlier fixture"), message(3, "Latest fixture"))
        var sends = 0; val read = AtomicLong()
        override suspend fun context(roomId: Long) = ConversationContext(RoomInfo(42, "lounge", "Lounge", "", "public", 0, 0,
            7, "member", false, "none", 0, 0, null), session.value.chatPolicy ?: throw ApiFailure(FailureKind.INCOMPATIBLE))
        override suspend fun history(roomId: Long, beforeId: Long?, limit: Int): MessagePage {
            val page = rows.filter { beforeId == null || it.id < beforeId }.takeLast(limit)
            return MessagePage(page, page.firstOrNull()?.id, false)
        }
        override suspend fun pings(roomId: Long) = emptyList<RoomPing>()
        override suspend fun send(roomId: Long, body: String, replyId: Long?): SendResult { sends++; error("No send expected") }
        override suspend fun read(roomId: Long, messageId: Long): Long { read.set(messageId); return messageId }
    }
    private class Events : RealtimeController {
        override val state = MutableStateFlow(RealtimeState(generation = 1, phase = RealtimePhase.LIVE,
            selectedRoomId = 42, presencePhase = PresencePhase.ACTIVE))
        override val events = MutableSharedFlow<RealtimeEvent>()
        override fun selectRoom(roomId: Long?, eligible: Boolean, interacted: Boolean) = Unit
        override fun activity(roomId: Long) = Unit
        override fun reenter(roomId: Long) = Unit
        override fun retry() = Unit
        override fun dismissBroadcast(id: Long) = Unit
    }
    companion object {
        private fun message(id: Long, body: String) = RoomMessage(id, 42, 8, "Peer", "text", body, false, "2026-10-20T12:00:00Z")
    }
}

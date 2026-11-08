package io.github.thiesi.chitchat.feature.targets

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.lifecycle.ViewModelProvider
import io.github.thiesi.chitchat.core.network.SessionUser
import io.github.thiesi.chitchat.core.server.AddressResult
import io.github.thiesi.chitchat.core.server.ServerAddress
import io.github.thiesi.chitchat.core.session.SessionPhase
import io.github.thiesi.chitchat.core.session.SessionState
import io.github.thiesi.chitchat.core.targets.MessageTarget
import io.github.thiesi.chitchat.core.targets.TargetSnapshot
import io.github.thiesi.chitchat.feature.auth.TestSessions
import io.github.thiesi.chitchat.feature.conversation.ConversationViewModel
import io.github.thiesi.chitchat.testing.NativeChatFixtures
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Stable parent, no navigation/input/IME: room-state publication alone must wake the host. */
class ReplyTargetSubscriptionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val sessions = TestSessions()
    private val api = NativeChatFixtures(sessions)
    private val sendGate = CompletableDeferred<Unit>()
    private lateinit var room: ConversationViewModel
    private lateinit var targets: MessageTargetViewModel

    @After fun retireFixtures() {
        compose.activityRule.scenario.onActivity {
            sendGate.complete(Unit)
            if (::targets.isInitialized) targets.state.value.request?.let(targets::cancel)
            if (::room.isInitialized) room.background()
        }
    }

    @Test fun aPendingRoomWriteBecomingIdleAdoptsWithoutAParentRecomposition() {
        val server = (ServerAddress.parse("chat.example.test") as AddressResult.Valid).address
        val owner = SessionState(server, 1, SessionPhase.AUTHENTICATED,
            user = SessionUser(7, "Viewer", listOf("member"), 1, false), chatPolicy = api.policy, privacy = api.privacy)
        compose.activityRule.scenario.onActivity { activity ->
            sessions.state.value = owner
            room = ViewModelProvider(activity, ConversationViewModel.factory(api.rooms))[ConversationViewModel::class.java]
            targets = ViewModelProvider(activity, MessageTargetViewModel.factory(api.lookups))[MessageTargetViewModel::class.java]
            room.foreground(); room.open(43, interacted = true, expected = owner)
        }
        compose.waitUntil(5_000) { room.state.value.loaded && !room.state.value.busy }
        compose.runOnIdle {
            assertTrue(room.updateDraft(owner, 43, "Synthetic pending send"))
            api.rooms.sendGate = sendGate
            room.send(owner, 43)
        }
        compose.waitUntil(5_000) { api.rooms.sends == 1 && room.state.value.sending }
        compose.runOnIdle {
            room.leavePane() // Search pauses the pane; it must not cancel its existing POST.
            targets.foreground()
            assertNotNull(targets.load(owner, MessageTarget.RoomMessage(43, 20), "search-entry"))
        }
        compose.waitUntil(5_000) { targets.state.value.let { !it.loading && it.result?.available == true } }
        val fixedValue = targets.state.value
        val snapshot = checkNotNull(fixedValue.result as? TargetSnapshot.RoomMessage)
        val accepted = AtomicInteger()
        val scheme = lightColorScheme()
        val accept: (TargetSnapshot) -> Boolean = { value ->
            value is TargetSnapshot.RoomMessage && room.prepareTarget(value).also { if (it) accepted.incrementAndGet() }
        }
        compose.activityRule.scenario.onActivity { activity ->
            activity.setContent {
                // No parent flow collection, changing callbacks, theme or target-state input.
                // In particular, do not accidentally subscribe this parent to room.state.
                MaterialTheme(colorScheme = scheme) {
                    ReplyTargetHost(targets, fixedValue, owner, room, null, true, null, null,
                        onAccept = accept, onDenied = { _, _ -> error("Unexpected fixture denial") },
                        refreshSession = {}, entryId = "search-entry", actualEntryId = { "search-entry" })
                }
            }
        }
        compose.waitForIdle() // Drain initial lifecycle/clock effects while the POST is still held.
        compose.onNodeWithTag("message_target_dialog").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(0, accepted.get()); assertFalse(sendGate.isCompleted)
            assertFalse(room.canPrepareTarget(snapshot)); assertSame(fixedValue.request, targets.state.value.request)
            sendGate.complete(Unit)
        }
        compose.waitUntil(5_000) { !room.state.value.busy && !room.state.value.sending }
        compose.runOnIdle { assertTrue(room.canPrepareTarget(snapshot)); assertEquals(1, api.rooms.sends) }
        compose.waitUntil(5_000) { accepted.get() == 1 }
        compose.runOnIdle {
            assertNull(targets.state.value.request); assertEquals(20L, room.state.value.highlightedMessageId)
            assertEquals(20L, room.state.value.fixedUpper); assertFalse(room.state.value.latestWindow)
            assertFalse(room.state.value.uncertainSend); assertFalse(sendGate.isCancelled)
            assertEquals(1, api.rooms.sends); assertTrue(api.rooms.reads.isEmpty())
        }
    }
}

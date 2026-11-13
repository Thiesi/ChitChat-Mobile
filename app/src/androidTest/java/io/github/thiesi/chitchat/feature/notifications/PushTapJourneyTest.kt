package io.github.thiesi.chitchat.feature.notifications

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import io.github.thiesi.chitchat.app.SettingsViewModel
import io.github.thiesi.chitchat.core.push.PendingPushTap
import io.github.thiesi.chitchat.core.push.PushTap
import io.github.thiesi.chitchat.core.server.*
import io.github.thiesi.chitchat.core.session.SessionPhase
import io.github.thiesi.chitchat.core.settings.*
import io.github.thiesi.chitchat.feature.account.TestChitChatApp
import io.github.thiesi.chitchat.feature.auth.AuthViewModel
import io.github.thiesi.chitchat.feature.auth.TestSessions
import io.github.thiesi.chitchat.feature.conversation.ConversationViewModel
import io.github.thiesi.chitchat.feature.direct.DirectInboxViewModel
import io.github.thiesi.chitchat.feature.direct.DirectThreadViewModel
import io.github.thiesi.chitchat.feature.targets.MessageTargetViewModel
import io.github.thiesi.chitchat.testing.NativeChatFixtures
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * A tapped push through the real app shell (push P3b): Activity is the landing page, the link goes
 * through the guarded destination flow, and a tap that needs sign-in or another account is dropped.
 * Typed fixtures, no keyboard input.
 */
class PushTapJourneyTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val sessions = TestSessions()
    private val api = NativeChatFixtures(sessions)
    private val store = ViewModelStore()
    private val auth = AuthViewModel(sessions).also { store.put("auth", it) }
    private val room = ConversationViewModel(api.rooms).also { store.put("room", it) }
    private val direct = DirectThreadViewModel(api.directs).also { store.put("direct", it) }
    private val inbox = DirectInboxViewModel(api.directs).also { store.put("inbox", it) }
    private val notices = NotificationCenterViewModel(api.notices).also { store.put("notices", it) }
    private val taps = PendingPushTap()
    private lateinit var targets: MessageTargetViewModel
    private val settings = SettingsViewModel(object : SettingsStore {
        override val settings = MutableStateFlow(AppSettings(server =
            (ServerAddress.parse("chat.example.test") as AddressResult.Valid).address))
        override suspend fun saveServer(server: ServerAddress?) { settings.value = settings.value.copy(server = server) }
        override suspend fun savePureBlack(enabled: Boolean) = Unit
        override suspend fun saveTheme(theme: ThemePreference) { settings.value = settings.value.copy(theme = theme) }
    }).also { store.put("settings", it) }

    @Before fun bind() {
        compose.activityRule.scenario.onActivity { activity ->
            targets = ViewModelProvider(activity, MessageTargetViewModel.factory(api.lookups))[MessageTargetViewModel::class.java]
        }
    }

    @After fun close() {
        compose.runOnIdle {
            api.lookups.gates.values.forEach { it.complete(Unit) }
            targets.background(); room.cancel(); direct.background(); inbox.background(); notices.background(); store.clear()
        }
    }

    private fun install() = compose.runOnUiThread { compose.activity.setContent {
        TestChitChatApp(settings, auth, conversationModel = room, directInbox = inbox, directThread = direct,
            messageTargets = targets, notifications = notices, pushTaps = taps)
    } }

    private fun signedIn() {
        install()
        compose.waitUntil(5_000) { sessions.state.value.phase == SessionPhase.ANONYMOUS }
        compose.runOnIdle {
            runBlocking { sessions.signIn("Viewer", "fixture-password") }
            sessions.state.value = sessions.state.value.copy(chatPolicy = api.policy, privacy = api.privacy, shareTyping = true)
        }
        compose.onNodeWithTag("notifications_open").performScrollTo().assertIsDisplayed()
    }

    private fun tap(link: String?, user: Long = sessions.state.value.user!!.id): PushTap {
        val tap = PushTap(sessions.state.value.server!!.value, user, link)
        compose.runOnIdle { taps.offer(tap) }
        return tap
    }

    private fun consumed() = compose.waitUntil(5_000) { taps.state.value == null }

    @Test fun aTapOpensItsDestinationThroughTheGuardedFlowWithoutMarkingAnythingRead() {
        signedIn()
        tap("/messages.php?user_id=9&peer_name=Peer&message_id=20&with=9")
        compose.waitUntil(5_000) { direct.state.value.highlightedMessageId == 20L && !direct.state.value.busy && targets.state.value.request == null }
        compose.onNodeWithTag("requested_message:20").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(9L, direct.state.value.peerId)
            assertNull("Consumed once", taps.state.value)
            assertEquals("Opening is not marking read", 0, api.notices.posts)
        }
    }

    @Test fun aTapWithoutAUsableLinkLandsOnActivityAndNeverLeavesTheApp() {
        signedIn()
        tap(null); consumed()
        compose.onNodeWithTag("notification_list").assertIsDisplayed()
        tap("https://elsewhere.example/messages.php?with=9"); consumed()
        compose.onNodeWithTag("notification_list").assertIsDisplayed()
        compose.runOnIdle { assertNull(direct.state.value.peerId); assertEquals(0, api.notices.posts) }
    }

    @Test fun aTapForAnotherAccountOrBeforeSignInIsDroppedAndNeverReplayed() {
        install()
        compose.waitUntil(5_000) { sessions.state.value.phase == SessionPhase.ANONYMOUS }
        // Before sign-in: dropped, and signing in afterwards doesn't route it.
        val early = PushTap(sessions.state.value.server!!.value, 1, "/messages.php?with=9")
        compose.runOnIdle { taps.offer(early) }; consumed()
        compose.runOnIdle {
            runBlocking { sessions.signIn("Viewer", "fixture-password") }
            sessions.state.value = sessions.state.value.copy(chatPolicy = api.policy, privacy = api.privacy, shareTyping = true)
        }
        compose.onNodeWithTag("notifications_open").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("notification_list").assertDoesNotExist()
        // Another account on this server, then another server: both dropped where they stand.
        tap("/messages.php?with=9", user = sessions.state.value.user!!.id + 1); consumed()
        compose.runOnIdle { taps.offer(PushTap("https://other.example.test/", sessions.state.value.user!!.id, "/messages.php?with=9")) }
        consumed()
        compose.onNodeWithTag("notifications_open").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("notification_list").assertDoesNotExist()
        compose.runOnIdle { assertNull(direct.state.value.peerId); assertEquals(0, api.notices.posts) }
    }
}

package io.github.thiesi.chitchat.feature.conversation

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import io.github.thiesi.chitchat.R
import io.github.thiesi.chitchat.app.SettingsViewModel
import io.github.thiesi.chitchat.core.conversation.*
import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.network.FailureKind
import io.github.thiesi.chitchat.core.reactions.ReactionEmoji
import io.github.thiesi.chitchat.core.reactions.selectedBy
import io.github.thiesi.chitchat.core.server.*
import io.github.thiesi.chitchat.core.session.SessionPhase
import io.github.thiesi.chitchat.core.settings.*
import io.github.thiesi.chitchat.feature.account.TestChitChatApp
import io.github.thiesi.chitchat.feature.auth.*
import io.github.thiesi.chitchat.feature.notifications.NotificationCenterViewModel
import io.github.thiesi.chitchat.feature.targets.MessageTargetViewModel
import io.github.thiesi.chitchat.testing.NativeChatFixtures
import io.github.thiesi.chitchat.testing.nativeInputWindow
import io.github.thiesi.chitchat.testing.performNativeTap
import io.github.thiesi.chitchat.testing.tapNativeInput
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*

/** Full app navigation/real dialog and OS taps; synthetic server, not live acceptance. */
class RoomReactionJourneyTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val sessions = TestSessions()
    private val api = NativeChatFixtures(sessions)
    private lateinit var room: ConversationViewModel
    private lateinit var notices: NotificationCenterViewModel
    private lateinit var targets: MessageTargetViewModel
    @Volatile private var ime = 0
    private val settingsStore = object : SettingsStore {
        override val settings = MutableStateFlow(AppSettings(server =
            (ServerAddress.parse("chat.example.test") as AddressResult.Valid).address))
        override suspend fun saveServer(server: ServerAddress?) { settings.value = settings.value.copy(server = server) }
        override suspend fun savePureBlack(enabled: Boolean) = Unit
        override suspend fun saveTheme(theme: ThemePreference) { settings.value = settings.value.copy(theme = theme) }
    }
    @Before fun bind() { compose.activityRule.scenario.onActivity(::install) }
    private fun install(activity: ComponentActivity) {
        val args = InstrumentationRegistry.getArguments(); val config = activity.resources.configuration
        args.getString("expectedOrientation")?.let { assertEquals(it.toInt(), config.orientation) }
        args.getString("expectedFontScale")?.let { assertEquals(it.toFloat(), config.fontScale, 0.01f) }
        activity.enableEdgeToEdge()
        room = ViewModelProvider(activity, ConversationViewModel.factory(api.rooms, mutations = api.authorChanges,
            reactions = api.reactions))[ConversationViewModel::class.java]
        notices = ViewModelProvider(activity, NotificationCenterViewModel.factory(api.notices))[NotificationCenterViewModel::class.java]
        targets = ViewModelProvider(activity, MessageTargetViewModel.factory(api.lookups))[MessageTargetViewModel::class.java]
        val settings = ViewModelProvider(activity, SettingsViewModel.factory(settingsStore))[SettingsViewModel::class.java]
        val auth = ViewModelProvider(activity, AuthViewModel.factory(sessions))[AuthViewModel::class.java]
        activity.setContent {
            val bottom = WindowInsets.ime.getBottom(LocalDensity.current)
            SideEffect { ime = bottom }
            Box(Modifier.fillMaxSize()) { TestChitChatApp(settings, auth, conversationModel = room,
                notifications = notices, messageTargets = targets) }
        }
    }
    private fun text(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)
    private fun settle() { compose.waitUntil(5_000) { room.state.value.let { it.loaded && !it.busy && !it.stale && !it.reading } } }
    private fun enterRoom(deleted: Boolean = false) {
        compose.waitUntil(5_000) { sessions.state.value.phase == SessionPhase.ANONYMOUS }
        compose.runOnIdle {
            runBlocking { sessions.signIn("Viewer", "fixture-password") }
            val policy = if (deleted) ChatPolicy(emptySet(), RoomMute(1, "global", null, null, "Fixture"), DisplayPreferences()) else api.policy
            sessions.state.value = sessions.state.value.copy(chatPolicy = policy, privacy = api.privacy)
            runBlocking { api.rooms.send(43, "Reaction fixture", null) } // Seed, not the reaction operation under test.
            api.rooms.reactionSummaries[43L to 101L] = listOf(MessageReaction(ReactionEmoji.THUMBS_UP.value,
                listOf(MessagePerson(if (deleted) 7 else 8, if (deleted) "Viewer" else "Peer")), !deleted))
            if (deleted) api.rooms.deleted += 43L to 101L
        }
        compose.onNodeWithText(text(R.string.app_name)).assertIsDisplayed().performNativeTap()
        compose.onNodeWithTag("notifications_open").performScrollTo().performNativeTap()
        compose.waitUntil(5_000) { notices.state.value.loaded && !notices.state.value.busy }
        compose.onNodeWithTag("notification_list").performScrollToNode(hasTestTag("notification_view:60"))
        compose.onNodeWithTag("notification_view:60").performNativeTap()
        compose.waitUntil(5_000) { room.state.value.roomId == 43L && room.state.value.loaded && !room.state.value.busy && targets.state.value.request == null }
        compose.onNodeWithContentDescription(text(R.string.conversation_details)).assertIsDisplayed().performNativeTap()
        compose.onNode(hasText(text(R.string.conversation_latest)) and hasAnyAncestor(isDialog()))
            .performScrollTo().assertIsEnabled().performNativeTap()
        settle(); compose.runOnIdle { room.updateDraft("Kept unsent draft") }
    }
    private fun choose() {
        compose.onNodeWithTag("conversation_timeline").performScrollToNode(hasTestTag("message:101"))
        compose.onNodeWithText("Reaction fixture").assertIsDisplayed().performNativeTap()
        compose.onNodeWithTag("message_reactions_action").performScrollTo().assertIsEnabled().performNativeTap()
        compose.onNodeWithTag("room_reactions_dialog").assertIsDisplayed()
    }
    private fun tap(tag: String) {
        compose.onNodeWithTag("room_reaction_choices").performScrollToNode(hasTestTag(tag))
        compose.onNodeWithTag(tag).assertIsDisplayed().assertIsEnabled().assertHeightIsAtLeast(48.dp)
            .performNativeTap()
    }
    private fun selected(emoji: ReactionEmoji) = room.state.value.messages.first { it.id == 101L }
        .reactions.firstOrNull { it.emoji == emoji.value }?.selectedBy(7) == true
    private fun change(emoji: ReactionEmoji, desired: Boolean) {
        assertNotEquals(desired, selected(emoji)); val before = api.reactions.writes.size
        tap("reaction_choice:${emoji.name}")
        compose.waitUntil(5_000) { api.reactions.writes.size == before + 1 && room.state.value.reactionDialog == null && selected(emoji) == desired }
        settle(); assertEquals(before + 1, api.reactions.writes.size)
    }
    @After fun cleanup() {
        api.reactions.gate?.complete(Unit)
        compose.waitForIdle()
        if (ime > 0 || nativeInputWindow().imeVisible != false) Espresso.closeSoftKeyboard()
        compose.waitUntil(5_000) { ime == 0 }
        assertEquals(false, nativeInputWindow().imeVisible)
    }

    @Test fun viewerSelectionCompleteCountsPeopleAndExplicitAddRemoveUseNewCanonicalResults() {
        enterRoom()
        compose.onNodeWithTag("conversation_timeline").performScrollToNode(hasTestTag("room_reaction:101:0"))
        compose.onNodeWithTag("room_reaction:101:0").assertIsNotSelected() // Other actor's flag is true.
        choose(); change(ReactionEmoji.THUMBS_UP, true)
        compose.onNodeWithTag("conversation_timeline").performScrollToNode(hasTestTag("room_reaction:101:0"))
        compose.onNodeWithTag("room_reaction:101:0").assertIsSelected().assertIsEnabled().performNativeTap()
        tap("reaction_people")
        compose.onNodeWithTag("room_reaction_choices").performScrollToNode(hasText("Viewer"))
        compose.onNode(hasText("Viewer") and hasAnyAncestor(isDialog())).assertIsDisplayed()
        compose.runOnIdle { assertEquals(listOf(8L, 7L), room.state.value.messages.first { it.id == 101L }.reactions.single().users.map { it.id }) }
        compose.onNodeWithTag("reaction_close").performNativeTap() // Back to choices.
        change(ReactionEmoji.THUMBS_UP, false)
        compose.runOnIdle {
            assertEquals(listOf(Triple(101L, ReactionEmoji.THUMBS_UP.value, true), Triple(101L, ReactionEmoji.THUMBS_UP.value, false)), api.reactions.writes.toList())
            assertEquals("Kept unsent draft", room.state.value.draft); assertEquals(1, api.rooms.sends)
        }
    }

    @Test fun committedUnknownChoiceNeedsExplicitHistoryReviewAndFreshDifferentChoice() {
        enterRoom(); compose.runOnIdle { api.reactions.failure = ApiFailure(FailureKind.HTTP, 500, "publication_failed") }
        choose(); tap("reaction_choice:HEART")
        compose.waitUntil(5_000) { room.state.value.reactionUnknown != null && !room.state.value.busy }
        val original = checkNotNull(room.state.value.reactionDialog).request
        compose.runOnIdle { assertFalse(room.state.value.uncertainSend); api.reactions.failure = null }
        tap("reaction_check_history"); settle()
        compose.runOnIdle { assertNull(room.state.value.reactionUnknown); assertTrue(room.state.value.reactionDialog?.checked == true); assertEquals(1, api.reactions.writes.size) }
        compose.onNodeWithTag("room_reaction_choices").performScrollToNode(hasTestTag("reaction_choice:HEART"))
        compose.onNodeWithTag("reaction_choice:HEART").assertIsNotEnabled().assertIsSelected()
        tap("reaction_review")
        compose.runOnIdle { assertNotSame(original, room.state.value.reactionDialog?.request); assertEquals(1, api.reactions.writes.size) }
        change(ReactionEmoji.SAD, true)
        compose.runOnIdle { assertEquals("Kept unsent draft", room.state.value.draft); assertEquals(1, api.rooms.sends) }
    }

    @Test fun closingUnknownPickerKeepsRecoveryReachableThroughDetailsWithoutReplay() {
        enterRoom(); compose.runOnIdle { api.reactions.failure = ApiFailure(FailureKind.NETWORK) }
        choose(); tap("reaction_choice:HEART")
        compose.waitUntil(5_000) { room.state.value.reactionUnknown != null && !room.state.value.busy }
        compose.onNodeWithTag("reaction_close").assertIsEnabled().performNativeTap()
        compose.runOnIdle { api.reactions.failure = null; assertNotNull(room.state.value.reactionUnknown) }
        compose.onNodeWithContentDescription(text(R.string.conversation_details)).performNativeTap()
        compose.onNode(hasTestTag("reaction_check_history") and hasAnyAncestor(isDialog()))
            .performScrollTo().assertIsDisplayed().assertIsEnabled().performNativeTap()
        settle(); compose.runOnIdle { assertNull(room.state.value.reactionUnknown); assertNull(room.state.value.reactionDialog); assertEquals(1, api.reactions.writes.size) }
        choose(); change(ReactionEmoji.CELEBRATE, true)
    }

    @Test fun aMutedViewerCanRemoveOwnRetainedReactionFromADeletedRowButCannotAdd() {
        enterRoom(deleted = true)
        compose.onNodeWithTag("conversation_timeline").performScrollToNode(hasTestTag("room_reaction:101:0"))
        compose.onNodeWithTag("room_reaction:101:0").assertIsSelected().assertIsEnabled().performNativeTap()
        compose.onNodeWithTag("room_reaction_choices").performScrollToNode(hasTestTag("reaction_choice:HEART"))
        compose.onNodeWithTag("reaction_choice:HEART").assertIsNotEnabled()
        change(ReactionEmoji.THUMBS_UP, false)
        compose.runOnIdle { assertTrue(room.state.value.messages.first { it.id == 101L }.deleted); assertEquals(1, api.reactions.writes.size) }
    }

    @Test fun closePickerThenOnePhysicalComposerTapProvesImeAndCompleteMultilineSend() {
        enterRoom()
        compose.tapNativeInput("conversation_composer", { ime }).performTextReplacement("Initial draft")
        choose(); compose.onNodeWithTag("reaction_close").performNativeTap()
        compose.runOnIdle { assertEquals("Initial draft", room.state.value.draft) }
        val body = "Reaction draft\nsecond line"
        compose.tapNativeInput("conversation_composer", { ime }).performTextReplacement(body)
        assertEquals(body, compose.onNodeWithTag("conversation_composer").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        compose.onNodeWithTag("conversation_send").assertIsDisplayed().assertIsEnabled().performNativeTap()
        compose.waitUntil(5_000) { room.state.value.messages.any { it.id == 102L && it.body == body } && room.state.value.draft.isEmpty() }
        compose.runOnIdle { assertEquals(2, api.rooms.sends); assertTrue(api.reactions.writes.isEmpty()); assertFalse(room.state.value.uncertainSend) }
    }

    @Test fun configurationRecreationRetainsOnePendingChoiceWithoutCancellingOrReplayingPost() {
        enterRoom(); compose.runOnIdle { api.reactions.gate = CompletableDeferred() }
        choose(); tap("reaction_choice:HEART")
        compose.waitUntil(5_000) { room.state.value.reactionWriting && api.reactions.writes.size == 1 }
        val request = checkNotNull(room.state.value.reactionDialog).request
        compose.activityRule.scenario.recreate(); compose.activityRule.scenario.onActivity(::install)
        compose.waitUntil(5_000) { room.state.value.reactionDialog?.request === request && room.state.value.reactionWriting }
        compose.runOnIdle { assertEquals(1, api.reactions.writes.size); api.reactions.gate?.complete(Unit) }
        compose.waitUntil(5_000) { room.state.value.reactionDialog == null && selected(ReactionEmoji.HEART) }
        settle(); compose.runOnIdle { assertEquals(1, api.reactions.writes.size); assertNull(room.state.value.reactionUnknown) }
    }
}

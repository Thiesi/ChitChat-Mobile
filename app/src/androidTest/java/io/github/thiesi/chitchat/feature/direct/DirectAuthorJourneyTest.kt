package io.github.thiesi.chitchat.feature.direct

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
import io.github.thiesi.chitchat.core.mutations.AuthorChangeKind
import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.network.FailureKind
import io.github.thiesi.chitchat.core.conversation.phpTrim
import io.github.thiesi.chitchat.core.server.*
import io.github.thiesi.chitchat.core.session.SessionPhase
import io.github.thiesi.chitchat.core.settings.*
import io.github.thiesi.chitchat.feature.account.TestChitChatApp
import io.github.thiesi.chitchat.feature.auth.*
import io.github.thiesi.chitchat.feature.notifications.NotificationCenterViewModel
import io.github.thiesi.chitchat.feature.targets.MessageTargetViewModel
import io.github.thiesi.chitchat.feature.realtime.NativeTypingHarness
import io.github.thiesi.chitchat.testing.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*

/** Real app navigation/independent native dialog; fake transport only, not live DM acceptance. */
class DirectAuthorJourneyTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val sessions = TestSessions()
    private val api = NativeChatFixtures(sessions)
    private val typing = NativeTypingHarness(sessions.state)
    private lateinit var thread: DirectThreadViewModel
    private lateinit var notices: NotificationCenterViewModel
    private lateinit var targets: MessageTargetViewModel
    @Volatile private var ime = 0
    private val settingsStore = object : SettingsStore {
        override val settings = MutableStateFlow(AppSettings(server =
            (ServerAddress.parse("chat.example.test") as AddressResult.Valid).address))
        override suspend fun saveServer(server: ServerAddress?) { settings.value = settings.value.copy(server = server) }
        override suspend fun saveTheme(theme: ThemePreference) { settings.value = settings.value.copy(theme = theme) }
    }
    @Before fun bind() { compose.activityRule.scenario.onActivity(::install) }
    private fun install(activity: ComponentActivity) {
        val args = InstrumentationRegistry.getArguments(); val config = activity.resources.configuration
        args.getString("expectedOrientation")?.let { assertEquals(it.toInt(), config.orientation) }
        args.getString("expectedFontScale")?.let { assertEquals(it.toFloat(), config.fontScale, 0.01f) }
        activity.enableEdgeToEdge()
        thread = ViewModelProvider(activity, DirectThreadViewModel.factory(api.directs, typing = typing.controller,
            mutations = api.authorChanges))[DirectThreadViewModel::class.java]
        val inbox = ViewModelProvider(activity, DirectInboxViewModel.factory(api.directs))[DirectInboxViewModel::class.java]
        notices = ViewModelProvider(activity, NotificationCenterViewModel.factory(api.notices))[NotificationCenterViewModel::class.java]
        targets = ViewModelProvider(activity, MessageTargetViewModel.factory(api.lookups))[MessageTargetViewModel::class.java]
        val settings = ViewModelProvider(activity, SettingsViewModel.factory(settingsStore))[SettingsViewModel::class.java]
        val auth = ViewModelProvider(activity, AuthViewModel.factory(sessions))[AuthViewModel::class.java]
        activity.setContent {
            val bottom = WindowInsets.ime.getBottom(LocalDensity.current)
            SideEffect { ime = bottom }
            Box(Modifier.fillMaxSize()) { TestChitChatApp(settings, auth, directInbox = inbox, directThread = thread,
                notifications = notices, messageTargets = targets) }
        }
    }
    private fun text(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)
    private fun dialogText(id: Int) = compose.onNode(hasText(text(id)) and hasAnyAncestor(isDialog()))
    private fun settle() { compose.waitUntil(5_000) { thread.state.value.loaded && !thread.state.value.busy && !thread.state.value.stale } }
    private fun enterThread() {
        compose.waitUntil(5_000) { sessions.state.value.phase == SessionPhase.ANONYMOUS }
        compose.runOnIdle {
            runBlocking { sessions.signIn("Viewer", "fixture-password") }
            sessions.state.value = sessions.state.value.copy(chatPolicy = api.policy, privacy = api.privacy, shareTyping = true)
            runBlocking { api.directs.send(8, "Authored direct fixture", null) } // Seed, not another send journey.
        }
        compose.onNodeWithText(text(R.string.app_name)).assertIsDisplayed().performNativeTap()
        compose.onNodeWithTag("notifications_open").performScrollTo().performNativeTap()
        compose.waitUntil(5_000) { notices.state.value.loaded && !notices.state.value.busy }
        // Fixture55 is the plain peer8 conversation;57 is an exact-message link for peer9.
        compose.onNodeWithTag("notification_list").performScrollToNode(hasTestTag("notification_view:55"))
        compose.onNodeWithTag("notification_view:55").performNativeTap()
        compose.waitUntil(5_000) { thread.state.value.peerId == 8L && thread.state.value.loaded && !thread.state.value.busy && targets.state.value.request == null }
        compose.runOnIdle { assertTrue(api.lookups.calls.isEmpty()) }
        compose.onNodeWithContentDescription(text(R.string.dm_details)).assertIsDisplayed().performNativeTap()
        dialogText(R.string.dm_latest).performScrollTo().assertIsEnabled().performNativeTap(); settle()
        compose.runOnIdle { thread.updateDraft("Kept unsent direct draft") }
    }
    private fun choose(edit: Boolean = true) {
        compose.onNodeWithTag("dm_timeline").performScrollToNode(hasTestTag("direct:101"))
        compose.onNodeWithText("Authored direct fixture").assertIsDisplayed().performNativeTap()
        compose.onNodeWithTag(if (edit) "message_edit_action" else "message_delete_action")
            .performScrollTo().assertIsEnabled().performNativeTap()
        compose.onNodeWithTag("author_change_dialog").assertIsDisplayed()
    }
    private fun edit(body: String) {
        compose.onNodeWithTag("author_edit_body").performScrollTo()
        compose.tapNativeInput("author_edit_body", { compose.nativeAuthorImeBottom() }).performTextReplacement(body)
        compose.onNodeWithTag("author_edit_body").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        assertEquals(body, compose.onNodeWithTag("author_edit_body").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
    }
    private fun hideIme() {
        compose.waitForIdle(); Espresso.closeSoftKeyboard()
        compose.waitUntil(5_000) { ime == 0 && (compose.onAllNodesWithTag("author_edit_body").fetchSemanticsNodes().isEmpty() || compose.nativeAuthorImeBottom() == 0) }
        assertEquals(false, nativeInputWindow().imeVisible)
    }
    private fun confirm(kind: AuthorChangeKind = AuthorChangeKind.EDIT) {
        val choice = checkNotNull(thread.state.value.authorDialog)
        val body = choice.body.phpTrim(); val id = choice.request.original.id
        compose.onNodeWithTag("author_change_confirm").assertIsDisplayed().assertIsEnabled().performNativeTap()
        compose.waitUntil(5_000) { thread.state.value.let { state -> state.authorDialog == null &&
            state.messages.firstOrNull { it.id == id }?.let {
                if (kind == AuthorChangeKind.DELETE) it.deleted else !it.deleted && it.body == body
            } == true } }
        settle()
    }
    @After fun cleanup() { api.authorChanges.gate?.complete(Unit); hideIme(); typing.close() }

    @Test fun literalMultilineEditPreservesTheNormalDraftWithoutSendReadOrTypingFromTheModal() {
        enterThread(); choose(); dialogText(R.string.author_retention).assertExists()
        val reads = compose.runOnIdle { api.directs.reads.size }
        edit("/me literal\nsecond direct line")
        compose.runOnIdle { assertEquals(reads, api.directs.reads.size); assertEquals(0, typing.requests.get()) }
        confirm()
        compose.runOnIdle {
            assertEquals("/me literal\nsecond direct line", thread.state.value.messages.first { it.id == 101L }.body)
            assertEquals("Kept unsent direct draft", thread.state.value.draft)
            assertEquals(listOf(101L to false), api.authorChanges.directWrites.toList())
            assertEquals(1, api.directs.sends); assertNull(thread.state.value.authorUnknown); assertEquals(0, typing.requests.get())
        }
    }
    @Test fun blockedParticipantStillHasExplicitAuthorDeletionWithoutUnblockingOrSending() {
        enterThread(); compose.runOnIdle { thread.setBlocked(sessions.state.value, 8, true) }; settle()
        choose(edit = false); dialogText(R.string.author_retention).assertExists()
        compose.runOnIdle { assertTrue(api.authorChanges.directWrites.isEmpty()) }; confirm(AuthorChangeKind.DELETE)
        compose.runOnIdle {
            assertTrue(thread.state.value.messages.first { it.id == 101L }.deleted)
            assertNull(thread.state.value.messages.first { it.id == 101L }.body)
            assertTrue(checkNotNull(thread.state.value.context).relationship.blockedByMe)
            assertEquals("Kept unsent direct draft", thread.state.value.draft); assertEquals(1, api.directs.sends)
            assertEquals(listOf(101L to true), api.authorChanges.directWrites.toList())
        }
        compose.onNodeWithText("Authored direct fixture").assertDoesNotExist()
    }
    @Test fun recreationRetainsPrivateEditInTheViewModelWithoutPosting() {
        enterThread(); choose(); edit("Retained direct edit\nsecond line"); hideIme()
        compose.activityRule.scenario.recreate(); compose.activityRule.scenario.onActivity(::install)
        compose.onNodeWithTag("author_change_dialog").assertIsDisplayed()
        assertEquals("Retained direct edit\nsecond line", compose.onNodeWithTag("author_edit_body").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        compose.runOnIdle { assertTrue(api.authorChanges.directWrites.isEmpty()); assertEquals("Kept unsent direct draft", thread.state.value.draft) }
        edit("Retained direct edit\nfinal line"); confirm()
        compose.runOnIdle { assertEquals(1, api.authorChanges.directWrites.size) }
    }
    @Test fun remoteCanonicalChangeHidesOldEditorAndRejectsItsSave() {
        enterThread(); choose(); edit("Private in-progress proposal")
        compose.runOnIdle {
            val original = thread.state.value.messages.first { it.id == 101L }
            api.directs.changeOwn(original, "Remote canonical direct edit", false); thread.invalidate()
        }
        compose.waitUntil(5_000) { thread.state.value.authorDialog?.let { !thread.authorTargetCurrent(it.request) } == true && !thread.state.value.busy }
        compose.onNodeWithTag("author_edit_body").assertDoesNotExist()
        compose.onNodeWithTag("author_change_confirm").assertIsNotEnabled()
        dialogText(R.string.message_actions_unavailable).assertExists()
        compose.runOnIdle { assertTrue(api.authorChanges.directWrites.isEmpty()); assertEquals("Kept unsent direct draft", thread.state.value.draft) }
        dialogText(R.string.cancel).performNativeTap()
    }
    @Test fun committedButUnconfirmedDirectEditRequiresGetReviewAndSeparateSaveWithoutReplay() {
        enterThread(); compose.runOnIdle { api.authorChanges.failure = ApiFailure(FailureKind.HTTP, 500, "metadata_failed") }
        choose(); edit("Already committed direct fixture")
        compose.onNodeWithTag("author_change_confirm").assertIsDisplayed().performNativeTap()
        compose.waitUntil(5_000) { thread.state.value.authorUnknown != null && !thread.state.value.busy }
        compose.runOnIdle { assertFalse(thread.state.value.uncertainSend); api.authorChanges.failure = null }
        hideIme(); compose.onNodeWithTag("author_check_history").assertIsDisplayed().assertIsEnabled().performNativeTap(); settle()
        compose.runOnIdle {
            assertNull(thread.state.value.authorUnknown); assertTrue(checkNotNull(thread.state.value.authorDialog).checked)
            assertEquals(1, api.authorChanges.directWrites.size)
            assertEquals("Already committed direct fixture", thread.state.value.messages.first { it.id == 101L }.body)
            assertEquals("Kept unsent direct draft", thread.state.value.draft); assertEquals(1, api.directs.sends)
        }
        compose.onNodeWithTag("author_change_confirm").assertIsDisplayed().performNativeTap() // Review is not POST.
        compose.runOnIdle { assertEquals(1, api.authorChanges.directWrites.size) }
        compose.onNodeWithTag("author_change_confirm").assertIsDisplayed().performNativeTap() // Unchanged validation, not replay.
        dialogText(R.string.author_unchanged).assertExists()
        compose.runOnIdle { assertEquals(1, api.authorChanges.directWrites.size) }
        dialogText(R.string.cancel).performNativeTap()
    }
    @Test fun singleNativeSaveProvesOwnWindowKeyboardGeometryAndANewCanonicalOutcome() {
        enterThread(); choose(); edit("Direct geometry fixture\nsecond line")
        val before = compose.nativeAuthorSaveGeometry(compose.activity, ime)
        assertNotNull("Native keyboard boundary unavailable: ${before.details}", before.keyboardTop)
        assertTrue("Save covered by keyboard: ${before.details}", before.pointY < checkNotNull(before.keyboardTop))
        compose.onNodeWithTag("author_change_confirm").assertIsDisplayed().assertIsEnabled().performNativeTap()
        try { compose.waitUntil(5_000) { thread.state.value.let { state -> state.authorDialog == null &&
            state.messages.firstOrNull { it.id == 101L }?.body == "Direct geometry fixture\nsecond line" } } }
        catch (failure: ComposeTimeoutException) {
            val after = runCatching { compose.nativeAuthorSaveGeometry(compose.activity, ime).details }.getOrDefault("dialog absent")
            throw AssertionError("Single direct Save unconfirmed: before=${before.details}; after=$after; busy=${thread.state.value.busy}, " +
                "stale=${thread.state.value.stale}, modal=${thread.state.value.authorDialog != null}, writes=${api.authorChanges.directWrites.size}", failure)
        }
        settle(); compose.runOnIdle {
            assertEquals(1, api.authorChanges.directWrites.size); assertEquals("Kept unsent direct draft", thread.state.value.draft)
        }
    }
}

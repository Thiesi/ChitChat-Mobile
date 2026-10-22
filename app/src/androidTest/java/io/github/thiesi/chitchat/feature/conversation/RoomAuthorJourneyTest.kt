package io.github.thiesi.chitchat.feature.conversation

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import io.github.thiesi.chitchat.R
import io.github.thiesi.chitchat.app.SettingsViewModel
import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.conversation.phpTrim
import io.github.thiesi.chitchat.core.network.FailureKind
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
import io.github.thiesi.chitchat.testing.nativeTapPoint
import io.github.thiesi.chitchat.testing.tapNativeInput
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*

/** Actual app root/navigation/dialog windows; fake server writes, not live mutation acceptance. */
class RoomAuthorJourneyTest {
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
        override suspend fun saveTheme(theme: ThemePreference) { settings.value = settings.value.copy(theme = theme) }
    }
    @Before fun bind() { compose.activityRule.scenario.onActivity(::install) }
    private fun install(activity: ComponentActivity) {
        val args = InstrumentationRegistry.getArguments(); val config = activity.resources.configuration
        args.getString("expectedOrientation")?.let { assertEquals(it.toInt(), config.orientation) }
        args.getString("expectedFontScale")?.let { assertEquals(it.toFloat(), config.fontScale, 0.01f) }
        activity.enableEdgeToEdge()
        room = ViewModelProvider(activity, ConversationViewModel.factory(api.rooms, mutations = api.authorChanges))[ConversationViewModel::class.java]
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
    private fun dialogText(id: Int) = compose.onNode(hasText(text(id)) and hasAnyAncestor(isDialog()))
    private fun settle() { compose.waitUntil(5_000) { room.state.value.loaded && !room.state.value.busy && !room.state.value.stale && !room.state.value.reading } }
    private fun enterRoom() {
        compose.waitUntil(5_000) { sessions.state.value.phase == SessionPhase.ANONYMOUS }
        compose.runOnIdle {
            runBlocking { sessions.signIn("Viewer", "fixture-password") }
            sessions.state.value = sessions.state.value.copy(chatPolicy = api.policy, privacy = api.privacy)
            // Seed one authored server row. Tests below exercise author controls, not another send fixture.
            runBlocking { api.rooms.send(43, "Authored fixture", null) }
        }
        compose.onNodeWithText(text(R.string.app_name)).assertIsDisplayed().performNativeTap()
        compose.onNodeWithTag("notifications_open").performScrollTo().performNativeTap()
        compose.waitUntil(5_000) { notices.state.value.loaded && !notices.state.value.busy }
        compose.onNodeWithTag("notification_list").performScrollToNode(hasTestTag("notification_view:60"))
        compose.onNodeWithTag("notification_view:60").performNativeTap()
        compose.waitUntil(5_000) { room.state.value.roomId == 43L && room.state.value.loaded && !room.state.value.busy && targets.state.value.request == null }
        // Use the same accessible Details route in both layouts; compact panes omit the inline header.
        compose.onNodeWithContentDescription(text(R.string.conversation_details)).assertIsDisplayed().performNativeTap()
        dialogText(R.string.conversation_latest).performScrollTo().assertIsEnabled().performNativeTap(); settle()
        compose.runOnIdle { room.updateDraft("Kept unsent draft") }
    }
    private fun choose(edit: Boolean = true) {
        compose.onNodeWithTag("conversation_timeline").performScrollToNode(hasTestTag("message:101"))
        compose.onNodeWithText("Authored fixture").assertIsDisplayed().performNativeTap()
        compose.onNodeWithTag(if (edit) "message_edit_action" else "message_delete_action")
            .performScrollTo().assertIsEnabled().performNativeTap()
        compose.onNodeWithTag("author_change_dialog").assertIsDisplayed()
    }
    private fun edit(body: String) {
        compose.onNodeWithTag("author_edit_body").performScrollTo()
        compose.tapNativeInput("author_edit_body", ::editorImeBottom).performTextReplacement(body)
        compose.onNodeWithTag("author_edit_body").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        assertEquals(body, compose.onNodeWithTag("author_edit_body").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
    }
    // The Dialog owns the editor's insets; the unfocused Activity is a different window.
    private fun editorImeBottom(): Int {
        val node = compose.onNodeWithTag("author_edit_body").fetchSemanticsNode()
        var bottom = 0
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val view = (node.root as? ViewRootForTest)?.view
            val insets = view?.let(ViewCompat::getRootWindowInsets)
            if (insets?.isVisible(WindowInsetsCompat.Type.ime()) == true)
                bottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
        }
        return bottom // Missing target-window metadata fails closed, not "keyboard ready".
    }
    private fun hideIme() {
        compose.waitForIdle()
        if (ime > 0 || nativeInputWindow().imeVisible != false) Espresso.closeSoftKeyboard()
        compose.waitUntil(5_000) { ime == 0 }
        assertEquals(false, nativeInputWindow().imeVisible)
    }
    /** Own test windows and scalars only: no dumps, titles, input text, focus/IME requests or readiness waits. */
    private class SaveGeometry(val pointY: Float, val keyboardTop: Int?, val details: String)
    private fun saveGeometry(): SaveGeometry {
        val node = compose.onNodeWithTag("author_change_confirm").fetchSemanticsNode()
        var result: SaveGeometry? = null
        compose.runOnIdle {
            val view = (node.root as? ViewRootForTest)?.view
            val dialogFrame = android.graphics.Rect(); view?.getWindowVisibleDisplayFrame(dialogFrame)
            val activity = compose.activity.window.decorView
            val activityFrame = android.graphics.Rect(); activity.getWindowVisibleDisplayFrame(activityFrame)
            val activityLocation = IntArray(2); activity.getLocationOnScreen(activityLocation)
            val dialogLocation = IntArray(2); view?.getLocationOnScreen(dialogLocation)
            val activityInsets = ViewCompat.getRootWindowInsets(activity)
            val dialogInsets = view?.let(ViewCompat::getRootWindowInsets)
            val point = nativeTapPoint(node.boundsInWindow, node.positionInWindow, node.positionOnScreen, view?.display?.displayId)
            val keyboardTop = dialogInsets?.takeIf { it.isVisible(WindowInsetsCompat.Type.ime()) }
                ?.getInsets(WindowInsetsCompat.Type.ime())?.bottom?.let { dialogLocation[1] + checkNotNull(view).height - it }
            val details = "bounds=${node.boundsInWindow}, point=$point, dialogView=(${dialogLocation[0]},${dialogLocation[1]},${view?.width},${view?.height}), " +
                "dialogFrame=$dialogFrame, dialogIme=${dialogInsets?.isVisible(WindowInsetsCompat.Type.ime())}/${dialogInsets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom}, " +
                "activityView=(${activityLocation[0]},${activityLocation[1]},${activity.width},${activity.height}), activityFrame=$activityFrame, " +
                "activityIme=${activityInsets?.isVisible(WindowInsetsCompat.Type.ime())}/${activityInsets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom}, composeIme=$ime"
            result = SaveGeometry(point.y, keyboardTop, details)
        }
        return checkNotNull(result)
    }
    private fun confirmMutation(kind: AuthorChangeKind = AuthorChangeKind.EDIT) {
        val choice = checkNotNull(room.state.value.authorDialog)
        val id = choice.request.original.id
        val body = choice.body.phpTrim()
        compose.onNodeWithTag("author_change_confirm").assertIsDisplayed().assertIsEnabled().performNativeTap()
        // Prove a new outcome, not pre-existing loaded/not-busy state. Never tap again on timeout.
        compose.waitUntil(5_000) { room.state.value.let { state -> state.authorDialog == null &&
            state.messages.firstOrNull { it.id == id }?.let {
                if (kind == AuthorChangeKind.DELETE) it.deleted else !it.deleted && it.body == body
            } == true } }
        settle()
    }
    @After fun cleanup() { api.authorChanges.gate?.complete(Unit); hideIme() }

    @Test fun multilineEditKeepsNativeSaveVisibleAndDoesNotSendOrReplaceTheComposerDraft() {
        enterRoom(); choose(); dialogText(R.string.author_retention).assertExists()
        edit("/ping Peer\nliteral edited text")
        confirmMutation()
        compose.runOnIdle {
            assertEquals("/ping Peer\nliteral edited text", room.state.value.messages.first { it.id == 101L }.body)
            assertEquals("Kept unsent draft", room.state.value.draft)
            assertEquals(listOf(101L to false), api.authorChanges.writes.toList())
            assertEquals(1, api.rooms.sends); assertNull(room.state.value.authorUnknown)
        }
        compose.onNodeWithTag("author_change_dialog").assertDoesNotExist()
    }

    @Test fun deletionRequiresExplicitConfirmationAndRetiresTheVisibleBody() {
        enterRoom(); choose(edit = false)
        dialogText(R.string.author_retention).assertExists()
        compose.runOnIdle { assertTrue(api.authorChanges.writes.isEmpty()) }
        confirmMutation(AuthorChangeKind.DELETE)
        compose.runOnIdle {
            val changed = room.state.value.messages.first { it.id == 101L }
            assertTrue(changed.deleted); assertNull(changed.body); assertNull(changed.replyTo); assertNull(changed.attachment)
            assertEquals("Kept unsent draft", room.state.value.draft)
            assertEquals(listOf(101L to true), api.authorChanges.writes.toList()); assertEquals(1, api.rooms.sends)
        }
        compose.onNodeWithText("Authored fixture").assertDoesNotExist()
    }

    @Test fun recreationRetainsPrivateEditorTextWithoutPostingOrUsingSavedInstanceText() {
        enterRoom(); choose(); edit("Retained edit\nsecond line"); hideIme()
        compose.activityRule.scenario.recreate(); compose.activityRule.scenario.onActivity(::install)
        compose.onNodeWithTag("author_change_dialog").assertIsDisplayed()
        assertEquals("Retained edit\nsecond line", compose.onNodeWithTag("author_edit_body").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        compose.runOnIdle { assertTrue(api.authorChanges.writes.isEmpty()); assertEquals("Kept unsent draft", room.state.value.draft) }
        edit("Retained edit\nfinal line")
        confirmMutation()
        compose.runOnIdle { assertEquals(1, api.authorChanges.writes.size) }
    }

    @Test fun remoteCanonicalChangeHidesOldEditorTextAndRejectsTheOldSave() {
        enterRoom(); choose(); edit("Private in-progress edit")
        compose.runOnIdle {
            val original = room.state.value.messages.first { it.id == 101L }
            api.rooms.changeOwn(original, "Remote canonical edit", false); room.refresh()
        }
        compose.waitUntil(5_000) { room.state.value.authorDialog?.let { !room.authorTargetCurrent(it.request) } == true && !room.state.value.busy }
        compose.onNodeWithTag("author_edit_body").assertDoesNotExist()
        compose.onNodeWithTag("author_change_confirm").assertIsNotEnabled()
        dialogText(R.string.message_actions_unavailable).assertExists()
        compose.runOnIdle { assertTrue(api.authorChanges.writes.isEmpty()); assertEquals("Kept unsent draft", room.state.value.draft) }
        dialogText(R.string.cancel).assertIsDisplayed().performNativeTap()
    }

    @Test fun committedButUnconfirmedEditNeedsAnExplicitGetAndANewChoiceWithoutReplay() {
        enterRoom(); compose.runOnIdle { api.authorChanges.failure = ApiFailure(FailureKind.HTTP, 500, "metadata_failed") }
        choose(); edit("Already committed fixture")
        compose.onNodeWithTag("author_change_confirm").assertIsDisplayed().performNativeTap()
        compose.waitUntil(5_000) { room.state.value.authorUnknown != null && !room.state.value.busy }
        compose.runOnIdle { assertFalse(room.state.value.uncertainSend); api.authorChanges.failure = null }
        assertEquals("Already committed fixture", compose.onNodeWithTag("author_edit_body").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        hideIme()
        compose.onNodeWithTag("author_check_history").assertIsDisplayed().assertIsEnabled().performNativeTap(); settle()
        compose.runOnIdle {
            assertNull(room.state.value.authorUnknown); assertEquals(1, api.authorChanges.writes.size)
            assertEquals("Already committed fixture", room.state.value.messages.first { it.id == 101L }.body)
            assertEquals("Kept unsent draft", room.state.value.draft); assertEquals(1, api.rooms.sends)
        }
        compose.runOnIdle { assertTrue(room.state.value.authorDialog?.checked == true) }
        compose.onNodeWithTag("author_change_confirm").assertIsDisplayed().performNativeTap() // Fresh review, no POST.
        compose.runOnIdle { assertEquals(1, api.authorChanges.writes.size) }
        compose.onNodeWithTag("author_change_confirm").assertIsDisplayed().performNativeTap() // Already reflected: local unchanged validation.
        dialogText(R.string.author_unchanged).assertExists()
        compose.runOnIdle { assertEquals(1, api.authorChanges.writes.size) }
        dialogText(R.string.cancel).assertIsDisplayed().performNativeTap()
    }

    /** Large-font/IME hit-region regression, in addition to complete character and real write assertions. */
    @Test fun singleNativeSaveActivationRecordsOwnWindowGeometryWithoutRetry() {
        enterRoom(); choose(); edit("Geometry fixture\nsecond line")
        val before = saveGeometry()
        assertNotNull("Native keyboard boundary unavailable: ${before.details}", before.keyboardTop)
        assertTrue("Save is covered by the keyboard: ${before.details}", before.pointY < checkNotNull(before.keyboardTop))
        compose.onNodeWithTag("author_change_confirm").assertIsDisplayed().assertIsEnabled().performNativeTap()
        try {
            compose.waitUntil(5_000) { room.state.value.let { state -> state.authorDialog == null &&
                state.messages.firstOrNull { it.id == 101L }?.body == "Geometry fixture\nsecond line" } }
        } catch (failure: ComposeTimeoutException) {
            throw AssertionError("Single Save unconfirmed: before=${before.details}; after=${saveGeometry().details}; " +
                "busy=${room.state.value.busy}, stale=${room.state.value.stale}, modal=${room.state.value.authorDialog != null}, " +
                "writes=${api.authorChanges.writes.size}", failure)
        }
        settle()
        compose.runOnIdle {
            assertEquals(1, api.authorChanges.writes.size)
            assertEquals("Geometry fixture\nsecond line", room.state.value.messages.first { it.id == 101L }.body)
            assertEquals("Kept unsent draft", room.state.value.draft)
        }
    }
}

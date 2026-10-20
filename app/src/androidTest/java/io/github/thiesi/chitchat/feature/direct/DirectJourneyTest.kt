package io.github.thiesi.chitchat.feature.direct

import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.platform.app.InstrumentationRegistry
import io.github.thiesi.chitchat.app.SettingsViewModel
import io.github.thiesi.chitchat.core.conversation.*
import io.github.thiesi.chitchat.core.direct.*
import io.github.thiesi.chitchat.core.network.*
import io.github.thiesi.chitchat.core.server.*
import io.github.thiesi.chitchat.core.session.*
import io.github.thiesi.chitchat.core.settings.*
import io.github.thiesi.chitchat.core.realtime.*
import io.github.thiesi.chitchat.feature.account.TestChitChatApp
import io.github.thiesi.chitchat.feature.auth.AuthViewModel
import io.github.thiesi.chitchat.feature.auth.TestSessions
import io.github.thiesi.chitchat.feature.realtime.NativeTypingHarness
import io.github.thiesi.chitchat.testing.tapNativeInput
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Native UI with canonical fixture operations; HTTP/TLS is tested separately, no live DM claim. */
class DirectJourneyTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val api = NativeDirects()
    private val typing = NativeTypingHarness(api.session)
    private val model = DirectThreadViewModel(api, typing = typing.controller)
    @Volatile private var imeBottom = 0
    private val keyboardHides = java.util.concurrent.atomic.AtomicInteger()
    private val keyboardShows = java.util.concurrent.atomic.AtomicInteger()
    @Before fun verifyRequestedConfiguration() {
        // Match MainActivity's edge-to-edge/resize contract, not the default test host's pan.
        compose.activityRule.scenario.onActivity {
            it.enableEdgeToEdge()
        }
        val args = InstrumentationRegistry.getArguments()
        val config = InstrumentationRegistry.getInstrumentation().targetContext.resources.configuration
        args.getString("expectedOrientation")?.let { assertEquals(it.toInt(), config.orientation) }
        args.getString("expectedFontScale")?.let { assertEquals(it.toFloat(), config.fontScale, 0.01f) }
    }
    @After fun stop() { closeSoftKeyboard(); compose.runOnIdle { model.background(); api.readGate?.complete(Unit); typing.close() } }
    private fun content(ready: Boolean = true, loadFailure: Boolean = false, height: Int = 640) {
        compose.setContent {
            val bottom = WindowInsets.ime.getBottom(LocalDensity.current)
            SideEffect { imeBottom = bottom }
            MaterialTheme { Box(Modifier.size(320.dp, height.dp).imePadding()) { DirectThreadScreen(model, 8, {}) } }
        }
        compose.waitUntil(5_000) { if (loadFailure) model.state.value.error != null && !model.state.value.busy
            else model.state.value.loaded && (!ready || !model.state.value.busy) }
    }
    @Test fun nativeTypingKeepsKeyboardSendVisibleAndOptOutClearsImmediately() {
        val sessions = api.session as MutableStateFlow<SessionState>
        // Room ignore is not a DM block: this unblocked peer's direct hint must still display.
        sessions.value = sessions.value.copy(shareTyping = true, chatPolicy = ChatPolicy(setOf(8), null, DisplayPreferences()))
        content()
        tapInput("dm_composer").performTextInput("Typing fixture")
        compose.waitUntil(5_000) { typing.requests.get() == 1 }
        compose.runOnIdle { typing.hint(null) }
        displayed("typing_status").assertTextEquals("Peer is typing…")
        keyboardSend()
        typing.capture("dm-typing-keyboard.png")
        compose.runOnIdle { sessions.value = sessions.value.copy(shareTyping = false) }
        compose.waitUntil(5_000) { typing.controller.state.value.people.isEmpty() }
        compose.onNodeWithTag("typing_status").assertDoesNotExist()
        tapInput("dm_composer").performTextInput(" more")
        compose.runOnIdle { assertEquals(1, typing.requests.get()) }
    }

    private fun displayed(tag: String): SemanticsNodeInteraction {
        val node = compose.onNodeWithTag(tag)
        compose.waitUntil(5_000) { runCatching { node.assertIsDisplayed() }.isSuccess }
        return node.assertIsDisplayed()
    }
    private fun tapInput(tag: String): SemanticsNodeInteraction {
        return compose.tapNativeInput(tag, { imeBottom }) {
            val platform = compose.runOnIdle {
                val activity = compose.activity
                val decor = activity.window.decorView
                val focused = decor.findFocus()
                val imm = activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                val visible = if (android.os.Build.VERSION.SDK_INT >= 30)
                    decor.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime()) else null
                "windowFocus=${decor.hasWindowFocus()}, lifecycle=${activity.lifecycle.currentState}, platformIme=$visible, active=${imm.isActive}, accepting=${imm.isAcceptingText}, editorView=${focused?.javaClass?.simpleName}, softMode=${activity.window.attributes.softInputMode}"
            }
            runCatching { captureFixture("dm-tap-failure.png") }
            "shows=${keyboardShows.get()}, hides=${keyboardHides.get()}, $platform"
        }
    }
    private fun keyboardSend(): SemanticsNodeInteraction {
        val node = compose.onNodeWithTag("dm_send")
        val requireIme = InstrumentationRegistry.getArguments().getString("expectSoftKeyboard") == "true"
        // Android IME/window measure and draw run outside Compose's virtual-clock idling.
        try { compose.waitUntil(5_000) { (!requireIme || imeBottom > 0) &&
            runCatching { node.assertIsDisplayed().assertIsEnabled() }.isSuccess } }
        catch (failure: androidx.compose.ui.test.ComposeTimeoutException) {
            val input = compose.onNodeWithTag("dm_composer").fetchSemanticsNode()
            val send = node.fetchSemanticsNode()
            throw AssertionError("Composer fixture: IME=$imeBottom, focused=${input.config[SemanticsProperties.Focused]}, inputWindow=${input.boundsInWindow}, sendWindow=${send.boundsInWindow}, disabled=${send.config.contains(SemanticsProperties.Disabled)}, shows=${keyboardShows.get()}, hides=${keyboardHides.get()}", failure)
        }
        return node.assertIsDisplayed().assertIsEnabled()
    }
    private fun send(body: String) {
        tapInput("dm_composer").performTextInput(body)
        val semantics = compose.onNodeWithTag("dm_composer").fetchSemanticsNode().config
        assertEquals(body, semantics[SemanticsProperties.EditableText].text)
        assertFalse(semantics.contains(SemanticsProperties.ContentDescription))
        val label = InstrumentationRegistry.getInstrumentation().targetContext.getString(io.github.thiesi.chitchat.R.string.dm_message)
        assertTrue(semantics[SemanticsProperties.Text].any { it.text == label })
        val action = keyboardSend()
        captureFixture("dm-composer-before-send.png")
        action.performClick()
    }
    private fun captureFixture(name: String) {
        if (InstrumentationRegistry.getArguments().getString("captureDirectFixture") != "true") return
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val output = java.io.File(target.getExternalFilesDir("screenshots") ?: target.cacheDir, name)
        output.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    private fun details() {
        val action = compose.onNodeWithContentDescription("Conversation details")
        action.assertIsDisplayed().performClick()
    }
    private fun dialogNode(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        val node = compose.onNode(matcher and hasAnyAncestor(isDialog()))
        if (runCatching { node.assertExists() }.isFailure) details()
        return node.performScrollTo().assertIsDisplayed()
    }
    private fun notice(text: String) = dialogNode(hasText(text))

    @Test fun privacyIsNativeAndMultilineSendUsesThePinnedAction() {
        content()
        details()
        compose.onNodeWithText("Editing or deleting", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Canonical DM inspection is disabled", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Close").performClick()
        send("Native multiline fixture\nSecond line")
        compose.waitUntil(5_000) { api.sends == 1 && !model.state.value.busy }
        compose.runOnIdle { assertEquals("", model.state.value.draft); assertEquals(99L, model.state.value.confirmedSendId) }
        compose.runOnIdle { assertEquals(1, model.state.value.messages.count { it.id == 99L }) }
        // Credential-free fixture image for actual visual inspection, not live-server acceptance.
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val output = java.io.File(target.getExternalFilesDir("screenshots") ?: target.cacheDir, "dm-fixture.png")
        output.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        compose.onNodeWithTag("direct:99").assertIsDisplayed()
    }

    @Test fun unknownSendNeedsExplicitHistoryCheckAndDuplicateRiskConsent() {
        api.sendFailure = ApiFailure(FailureKind.TIMEOUT); content(); send("Unknown native fixture")
        compose.waitUntil(5_000) { model.state.value.uncertainSend }
        compose.onNodeWithTag("dm_send").assertIsNotEnabled()
        compose.runOnIdle { model.invalidate() }
        compose.waitUntil(5_000) { !model.state.value.busy && !model.state.value.stale }
        compose.onNodeWithText("Allow another send despite duplicate risk").assertDoesNotExist()
        notice("Check latest history").performClick()
        compose.waitUntil(5_000) { model.state.value.checkedUnknown }
        notice("Allow another send despite duplicate risk").performClick()
        compose.runOnIdle { assertEquals(1, api.sends); api.sendFailure = null }
        compose.onNodeWithTag("dm_send").assertIsEnabled().performClick()
        compose.waitUntil(5_000) { api.sends == 2 && !model.state.value.busy }
    }

    @Test fun blockingStaysGenericAndCanonicalDeletionIsNotGuessedFromText() {
        api.available = false
        api.rows = listOf(row(18, "Message deleted.", attachment = MessageAttachment(3, "note.txt", "text/plain", 3, "f".repeat(64), false)),
            row(19, deleted = true))
        content()
        compose.onNodeWithTag("dm_timeline").performScrollToNode(hasText("Message deleted."))
        compose.onNodeWithText("Message deleted.").assertIsDisplayed()
        compose.onNodeWithText("note.txt", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("dm_timeline").performScrollToNode(hasText("Message deleted"))
        compose.onNodeWithText("Message deleted").assertIsDisplayed()
        compose.onNodeWithTag("dm_send").assertIsNotEnabled()
        details()
        compose.onNodeWithText("Block").performScrollTo().performClick()
        compose.onNodeWithTag("dm_confirm_block").performClick()
        compose.waitUntil(5_000) { model.state.value.context?.relationship?.blockedByMe == true && !model.state.value.busy }
        details()
        compose.onNodeWithText("Unblock").performScrollTo().performClick()
        compose.onNodeWithTag("dm_confirm_block").performClick()
        compose.waitUntil(5_000) { api.blocks == 2 && !model.state.value.busy }
        compose.runOnIdle { assertFalse(model.state.value.context!!.relationship.messagingAvailable) }
        dialogNode(hasText("Messaging is unavailable", substring = true))
    }

    @Test fun wholePeerReadRaceKeepsLocalAttentionWhenTheUserScrollsAway() {
        api.rows = (1L..80L).map { row(it, read = null) }; api.readGate = CompletableDeferred()
        content(ready = false); compose.waitUntil(5_000) { api.reads == 1 }
        compose.onNodeWithTag("dm_timeline").performTouchInput { swipeDown() }
        compose.waitUntil(5_000) { !model.state.value.atLatest }
        compose.runOnIdle { api.rows += row(81, read = null); api.readGate!!.complete(Unit) }
        compose.waitUntil(5_000) { !model.state.value.busy && model.state.value.messages.lastOrNull()?.id == 81L }
        compose.runOnIdle {
            assertEquals(1, api.reads); assertTrue(model.state.value.messages.all { it.readAt != null })
            assertTrue(model.attention.value[8]?.ids?.contains(81) == true)
        }
        dialogNode(hasTestTag("dm_local_attention"))
    }

    @Test fun compositionRestorationUsesOnlyVmDraftAndLogoutClearsPrivateState() {
        val restore = StateRestorationTester(compose)
        restore.setContent { MaterialTheme { Box(Modifier.size(320.dp, 640.dp).imePadding()) { DirectThreadScreen(model, 8, {}) } } }
        compose.waitUntil(5_000) { model.state.value.loaded && !model.state.value.busy }
        compose.onNodeWithTag("dm_composer").performTextInput("Private VM draft fixture")
        restore.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Private VM draft fixture").assertIsDisplayed()
        compose.runOnIdle { (api.session as MutableStateFlow<SessionState>).value = SessionState(generation = 2, phase = SessionPhase.SIGNED_OUT) }
        compose.onNodeWithText("Private VM draft fixture").assertDoesNotExist()
        compose.runOnIdle { assertEquals("", model.state.value.draft); assertTrue(model.drafts.value.isEmpty()); assertTrue(model.state.value.messages.isEmpty()) }
    }

    @Test fun rateLimitExpiryReenablesTheExplicitActionWithoutEditingOrAutomaticPosting() {
        content()
        compose.runOnIdle { model.updateDraft("Rate wait fixture")
            val state = api.session as MutableStateFlow<SessionState>
            state.value = state.value.copy(retryAtMillis = System.currentTimeMillis() + 2000)
        }
        compose.onNodeWithTag("dm_send").assertIsNotEnabled()
        compose.waitUntil(5_000) { runCatching { compose.onNodeWithTag("dm_send").assertIsEnabled() }.isSuccess }
        compose.runOnIdle { assertEquals(0, api.sends); assertEquals("Rate wait fixture", model.state.value.draft) }
    }

    @Test fun modalConversationDetailsCannotAcknowledgeAnArrivalBehindTheDialog() {
        content()
        details()
        compose.onNodeWithText("Canonical DM inspection is disabled", substring = true).performScrollTo().assertIsDisplayed()
        compose.runOnIdle { api.rows += row(19, read = null); model.invalidate() }
        compose.waitUntil(5_000) { model.state.value.messages.lastOrNull()?.id == 19L && !model.state.value.busy }
        compose.runOnIdle { assertEquals(0, api.reads); assertTrue(model.attention.value[8]?.ids?.contains(19) == true) }
        compose.onNodeWithText("Close").performClick()
        // At large fonts the arriving row can be below the preserved old anchor. Reveal it;
        // dismissing a modal is not permission to jump or acknowledge off-screen content.
        compose.onNodeWithTag("dm_timeline").performTouchInput { swipeUp() }
        compose.waitUntil(5_000) { api.reads == 1 }
    }

    @Test fun compactInitialFailureStillExposesSessionSafeExplicitRecovery() {
        api.contextFailure = ApiFailure(FailureKind.NETWORK)
        content(loadFailure = true, height = 180)
        compose.onNodeWithTag("dm_send").assertIsNotEnabled()
        details()
        compose.onNodeWithText("The network request failed.", substring = true).performScrollTo().assertIsDisplayed()
        compose.runOnIdle { api.contextFailure = null; assertEquals(0, api.sends); assertEquals(0, api.reads) }
        compose.onNodeWithText("Refresh").performScrollTo().performClick()
        compose.waitUntil(5_000) { model.state.value.loaded && !model.state.value.busy }
        compose.onNodeWithTag("dm_send").assertIsEnabled()
    }

    @Test fun compactReadRetryReconcilesAndWaitsForUnobscuredReadProof() {
        api.rows = listOf(row(18, read = null)); api.readFailure = ApiFailure(FailureKind.NETWORK)
        content(ready = false, height = 220)
        compose.waitUntil(5_000) { model.state.value.readError != null }
        details()
        compose.onNodeWithText("Refresh and retry read confirmation").performScrollTo().assertIsEnabled()
        compose.runOnIdle { assertEquals(1, api.reads); api.readFailure = null }
        compose.onNodeWithText("Refresh and retry read confirmation").performClick()
        compose.waitUntil(5_000) { api.reads == 2 && !model.state.value.busy && model.state.value.readError == null }
        compose.runOnIdle { assertTrue(model.state.value.messages.all { it.readAt != null }) }
    }

    @Test fun nativeInboxSearchBackNavigationAndAccountSwitchKeepTheirOriginalScope() {
        val sessions = TestSessions(); val auth = AuthViewModel(sessions)
        val direct = NativeDirects(sessions.state); val connection = Connection()
        val inbox = DirectInboxViewModel(direct, connection); val thread = DirectThreadViewModel(direct, connection)
        direct.rows = (16L..18L).map { row(it, read = null) }
        val settings = SettingsViewModel(object : SettingsStore {
            override val settings = MutableStateFlow(AppSettings(server = (ServerAddress.parse("chat.example.test") as AddressResult.Valid).address))
            override suspend fun saveServer(server: ServerAddress?) { settings.value = settings.value.copy(server = server) }
            override suspend fun saveTheme(theme: ThemePreference) { settings.value = settings.value.copy(theme = theme) }
        })
        compose.setContent {
            val bottom = WindowInsets.ime.getBottom(LocalDensity.current)
            SideEffect { imeBottom = bottom }
            val keyboard = LocalSoftwareKeyboardController.current
            val observed = remember(keyboard) { object : SoftwareKeyboardController {
                override fun show() { keyboardShows.incrementAndGet(); keyboard?.show() }
                override fun hide() { keyboardHides.incrementAndGet(); keyboard?.hide() }
            } }
            CompositionLocalProvider(LocalSoftwareKeyboardController provides observed) {
                Box(Modifier.size(320.dp, 640.dp)) { TestChitChatApp(settings, auth, directInbox = inbox, directThread = thread, realtime = connection) }
            }
        }
        compose.onNodeWithText("Username").performScrollTo().performTextInput("Viewer")
        compose.onNodeWithText("Password").performScrollTo().performTextInput("fixture-password")
        closeSoftKeyboard()
        if (InstrumentationRegistry.getArguments().getString("expectSoftKeyboard") == "true")
            compose.waitUntil(5_000) { imeBottom == 0 }
        compose.onNodeWithText("Sign in").performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()
        compose.waitUntil(5_000) { sessions.state.value.phase == SessionPhase.AUTHENTICATED }
        compose.runOnIdle { connection.state.value = RealtimeState(phase = RealtimePhase.LIVE, generation = sessions.state.value.generation) }
        if (InstrumentationRegistry.getArguments().getString("expectSoftKeyboard") == "true")
            compose.waitUntil(5_000) { imeBottom == 0 }
        val beforeInbox = compose.runOnIdle { keyboardHides.get() }
        compose.onNodeWithText("Direct messages").performScrollTo().performClick()
        compose.waitUntil(5_000) { inbox.state.value.inbox != null && !inbox.state.value.busy }
        compose.runOnIdle { assertEquals("Unfocused inbox navigation must not enqueue a keyboard hide", beforeInbox, keyboardHides.get()) }
        compose.onNodeWithTag("dm_inbox").performScrollToNode(hasText("3 unread"))
        compose.onNodeWithText("3 unread").assertIsDisplayed()
        compose.onNodeWithTag("dm_inbox").performScrollToNode(hasTestTag("dm_search_input"))
        compose.onNodeWithTag("dm_search_input").performScrollTo()
        tapInput("dm_search_input").performTextInput("Pe")
        if (InstrumentationRegistry.getArguments().getString("expectSoftKeyboard") == "true") {
            try { compose.waitUntil(5_000) { imeBottom > 0 && runCatching { compose.onNodeWithTag("dm_search_input").assertIsDisplayed() }.isSuccess } }
            catch (failure: androidx.compose.ui.test.ComposeTimeoutException) {
                val input = compose.onNodeWithTag("dm_search_input").fetchSemanticsNode()
                throw AssertionError("Search fixture: IME=$imeBottom, focused=${input.config[SemanticsProperties.Focused]}, window=${input.boundsInWindow}, hides=${keyboardHides.get()}, busy=${inbox.state.value.busy}, queryReceived=${inbox.state.value.query == "Pe"}", failure)
            }
        }
        compose.onNodeWithTag("dm_search_input").performImeAction()
        compose.waitUntil(5_000) { inbox.state.value.searched }
        compose.onNodeWithTag("dm_inbox").performScrollToNode(hasTestTag("dm_person:8"))
        compose.onNodeWithTag("dm_person:8").performClick()
        compose.waitUntil(5_000) { thread.state.value.loaded && !thread.state.value.busy }
        compose.runOnIdle { connection.state.value = connection.state.value.copy(broadcasts = listOf(BroadcastNotice(9, null,
                "Native announcement fixture", java.time.Instant.parse("2026-10-20T12:00:00Z")))) }
        // Recipient navigation deliberately retires search focus/IME before editing a new scope.
        if (InstrumentationRegistry.getArguments().getString("expectSoftKeyboard") == "true")
            compose.waitUntil(5_000) { imeBottom == 0 }
        tapInput("dm_composer").performTextInput("Scoped draft fixture")
        keyboardSend()
        captureFixture("dm-app-draft-keyboard.png")
        compose.runOnIdle { connection.state.value = connection.state.value.copy(phase = RealtimePhase.ERROR, error = ApiFailure(FailureKind.NETWORK)) }
        details()
        notice("Native announcement fixture")
        notice(InstrumentationRegistry.getInstrumentation().targetContext.getString(io.github.thiesi.chitchat.R.string.retry)).performClick()
        compose.runOnIdle { assertEquals(1, connection.retries)
            connection.state.value = connection.state.value.copy(phase = RealtimePhase.LIVE, error = null, reconcileVersion = 1) }
        compose.waitUntil(5_000) { !thread.state.value.busy && !thread.state.value.stale }
        notice(InstrumentationRegistry.getInstrumentation().targetContext.getString(io.github.thiesi.chitchat.R.string.realtime_dismiss_notice)).performClick()
        compose.runOnIdle { assertTrue(connection.state.value.broadcasts.isEmpty()) }
        compose.onNodeWithText("Close").performClick()
        closeSoftKeyboard()
        compose.onNodeWithText("Back").assertIsDisplayed().performClick()
        compose.onNodeWithTag("dm_inbox").assertIsDisplayed()
        compose.waitUntil(5_000) { !inbox.state.value.busy && inbox.state.value.inbox != null }
        compose.onNodeWithText("3 unread").assertDoesNotExist()
        compose.onNodeWithTag("dm_inbox").performScrollToNode(hasTestTag("dm_conversation:8"))
        compose.onNodeWithTag("dm_conversation:8").performClick()
        compose.waitUntil(5_000) { thread.state.value.loaded && !thread.state.value.busy }
        compose.onNodeWithText("Scoped draft fixture").assertIsDisplayed()
        val readsBeforeSwitch = direct.histories
        compose.runOnIdle { sessions.state.value = sessions.state.value.copy(generation = sessions.state.value.generation + 1,
            user = SessionUser(9, "Replacement", listOf("member"), 1, false)) }
        compose.waitUntil(5_000) { thread.state.value.peerId == null }
        compose.onNodeWithText("Scoped draft fixture").assertDoesNotExist(); compose.onNodeWithText("Back").assertDoesNotExist()
        compose.runOnIdle { assertEquals(readsBeforeSwitch, direct.histories); assertTrue(thread.drafts.value.isEmpty()); thread.background(); inbox.background() }
    }

    private class Connection : RealtimeController {
        override val state = MutableStateFlow(RealtimeState())
        override val events = MutableSharedFlow<RealtimeEvent>()
        var retries = 0
        override fun selectRoom(roomId: Long?, eligible: Boolean, interacted: Boolean) = Unit
        override fun activity(roomId: Long) = Unit
        override fun reenter(roomId: Long) = Unit
        override fun retry() { retries++ }
        override fun dismissBroadcast(id: Long) { state.value = state.value.copy(broadcasts = state.value.broadcasts.filterNot { it.id == id }) }
    }

    private class NativeDirects(override val session: StateFlow<SessionState> = MutableStateFlow(SessionState(generation = 1,
        phase = SessionPhase.AUTHENTICATED, user = SessionUser(7, "Viewer", listOf("member"), 1, false)))) : DirectOperations {
        val privacy = MessagePrivacy(DirectPrivacy(false, false, "admin", "for up to 30 days", 30),
            RevisionPrivacy(false, "super_admin", true, true, false))
        @Volatile var rows = listOf(row(18))
        @Volatile var sends = 0; @Volatile var reads = 0; @Volatile var blocks = 0; @Volatile var histories = 0
        var sendFailure: ApiFailure? = null; var available = true; var blocked = false; var readGate: CompletableDeferred<Unit>? = null
        var contextFailure: ApiFailure? = null
        var readFailure: ApiFailure? = null
        override suspend fun context(peerId: Long): DirectContext {
            contextFailure?.let { throw it }
            return DirectContext(MessagePerson(peerId, "Peer"), DirectRelationship(blocked, !blocked && available), privacy, DisplayPreferences(), null)
        }
        override suspend fun inbox(): DirectInbox {
            val last = rows.lastOrNull() ?: return DirectInbox(emptyList(), privacy, DisplayPreferences())
            return DirectInbox(listOf(DirectConversation(MessagePerson(8, "Peer"),
                DirectPreview(last.id, last.body, last.createdAt, last.outgoing, last.deleted, last.editedAt, last.attachment),
                rows.count { !it.outgoing && it.readAt == null })), privacy, DisplayPreferences())
        }
        override suspend fun search(prefix: String, limit: Int) = listOf(MessagePerson(8, "Peer"))
        override suspend fun history(peerId: Long, beforeId: Long?, limit: Int): DirectPage {
            histories++
            val page = rows.filter { peerId in setOf(it.sender.id, it.recipient.id) && (beforeId == null || it.id < beforeId) }.takeLast(limit)
            return DirectPage(page, page.firstOrNull()?.id, page.size == limit)
        }
        override suspend fun send(peerId: Long, body: String, replyId: Long?): Long {
            sends++; sendFailure?.let { throw it }; rows += row(99, body, read = null, outgoing = true); return 99
        }
        override suspend fun read(peerId: Long): Int {
            reads++; readFailure?.let { throw it }; readGate?.await()
            val count = rows.count { !it.outgoing && it.readAt == null }
            rows = rows.map { row(it.id, it.body.orEmpty(), read = if (it.outgoing) it.readAt else "2026-10-20T10:01:00Z",
                outgoing = it.outgoing, deleted = it.deleted, attachment = it.attachment) }
            return count
        }
        override suspend fun setBlocked(peerId: Long, blocked: Boolean): DirectRelationship {
            blocks++; this.blocked = blocked; return DirectRelationship(blocked, !blocked && available)
        }
    }
    companion object {
        private fun row(id: Long, body: String = "Native history fixture", read: String? = "2026-10-20T10:01:00Z",
            outgoing: Boolean = false, deleted: Boolean = false, attachment: MessageAttachment? = null) = DirectMessage(id,
            MessagePerson(if (outgoing) 7 else 8, "Sender"), MessagePerson(if (outgoing) 8 else 7, "Recipient"),
            if (deleted) null else body, read, "2026-10-20T10:00:00Z", outgoing, deleted, null, outgoing, outgoing,
            null, emptyList(), emptyList(), attachment)
    }
}

package io.github.thiesi.chitchat.feature.conversation

import android.content.ClipboardManager
import android.content.Intent
import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import io.github.thiesi.chitchat.core.ui.configureChatWindow
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import io.github.thiesi.chitchat.R
import io.github.thiesi.chitchat.app.SettingsViewModel
import io.github.thiesi.chitchat.core.conversation.*
import io.github.thiesi.chitchat.core.network.*
import io.github.thiesi.chitchat.core.server.*
import io.github.thiesi.chitchat.core.session.SessionPhase
import io.github.thiesi.chitchat.core.settings.*
import io.github.thiesi.chitchat.core.typing.TypingTarget
import io.github.thiesi.chitchat.core.ui.MESSAGE_MENTION_ANNOTATION
import io.github.thiesi.chitchat.core.direct.DirectMention
import io.github.thiesi.chitchat.feature.account.TestChitChatApp
import io.github.thiesi.chitchat.feature.auth.*
import io.github.thiesi.chitchat.feature.direct.*
import io.github.thiesi.chitchat.feature.notifications.*
import io.github.thiesi.chitchat.feature.realtime.NativeTypingHarness
import io.github.thiesi.chitchat.feature.targets.MessageTargetViewModel
import io.github.thiesi.chitchat.testing.tapNativeInput
import io.github.thiesi.chitchat.testing.performNativeTap
import io.github.thiesi.chitchat.testing.NativeChatFixtures
import io.github.thiesi.chitchat.testing.nativeInputWindow
import io.github.thiesi.chitchat.testing.nativeImeServerAfterFailure
import io.github.thiesi.chitchat.testing.NativeTextInputTrace
import io.github.thiesi.chitchat.testing.NativeWindowTestActivity
import io.github.thiesi.chitchat.testing.recreatedWindowAfterFailure
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicLong
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runners.model.Statement

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
private annotation class ContentDuringOnCreate

/** Real navigation/windows/IME/provider retention; deterministic fixtures, not live-server acceptance. */
class ReplyComposerJourneyTest {
    private val compose = createAndroidComposeRule<NativeWindowTestActivity>()
    private var onCreateHost = false
    private val bootstrap = TestRule { base, description -> object : Statement() {
        override fun evaluate() {
            // Keep the original late-install path as the unchanged baseline control. The new
            // comparison differs only in content installation during onCreate, like MainActivity.
            onCreateHost = description.getAnnotation(ContentDuringOnCreate::class.java) != null
            val application = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
            val callback = object : Application.ActivityLifecycleCallbacks {
                override fun onActivityCreated(activity: Activity, state: Bundle?) {
                    if (activity is NativeWindowTestActivity) activity.createContent = { install(activity) }
                }
                override fun onActivityStarted(activity: Activity) = Unit
                override fun onActivityResumed(activity: Activity) = Unit
                override fun onActivityPaused(activity: Activity) = Unit
                override fun onActivityStopped(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
                override fun onActivityDestroyed(activity: Activity) = Unit
            }
            if (onCreateHost) application.registerActivityLifecycleCallbacks(callback)
            try { base.evaluate() } finally {
                if (onCreateHost) application.unregisterActivityLifecycleCallbacks(callback)
            }
        }
    } }
    @get:Rule val activityAndCompose: RuleChain = RuleChain.outerRule(bootstrap).around(compose)
    private val sessions = TestSessions()
    private val api = NativeChatFixtures(sessions)
    private val typingClock = AtomicLong(10_000L)
    private val typing = NativeTypingHarness(sessions.state, elapsed = typingClock::get)
    private lateinit var room: ConversationViewModel
    private lateinit var direct: DirectThreadViewModel
    private lateinit var targets: MessageTargetViewModel
    private lateinit var notices: NotificationCenterViewModel
    private val settingsStore = object : SettingsStore {
        override val settings = MutableStateFlow(AppSettings(server =
            (ServerAddress.parse("chat.example.test") as AddressResult.Valid).address))
        override suspend fun saveServer(server: ServerAddress?) { settings.value = settings.value.copy(server = server) }
        override suspend fun savePureBlack(enabled: Boolean) = Unit
        override suspend fun saveTheme(theme: ThemePreference) { settings.value = settings.value.copy(theme = theme) }
    }
    @Volatile private var ime = 0
    @Before fun bind() {
        if (!onCreateHost) compose.activityRule.scenario.onActivity { install(it) }
    }
    private fun install(activity: ComponentActivity) {
        val args = InstrumentationRegistry.getArguments()
        val config = activity.resources.configuration
        args.getString("expectedOrientation")?.let { assertEquals(it.toInt(), config.orientation) }
        args.getString("expectedFontScale")?.let { assertEquals(it.toFloat(), config.fontScale, 0.01f) }
        room = ViewModelProvider(activity, ConversationViewModel.factory(api.rooms, typing = typing.controller))[ConversationViewModel::class.java]
        direct = ViewModelProvider(activity, DirectThreadViewModel.factory(api.directs, typing = typing.controller))[DirectThreadViewModel::class.java]
        targets = ViewModelProvider(activity, MessageTargetViewModel.factory(api.lookups))[MessageTargetViewModel::class.java]
        notices = ViewModelProvider(activity, NotificationCenterViewModel.factory(api.notices))[NotificationCenterViewModel::class.java]
        val inbox = ViewModelProvider(activity, DirectInboxViewModel.factory(api.directs))[DirectInboxViewModel::class.java]
        val settings = ViewModelProvider(activity, SettingsViewModel.factory(settingsStore))[SettingsViewModel::class.java]
        val auth = ViewModelProvider(activity, AuthViewModel.factory(sessions))[AuthViewModel::class.java]
        activity.setContent {
            val bottom = WindowInsets.ime.getBottom(LocalDensity.current)
            SideEffect { ime = bottom }
            Box(Modifier.fillMaxSize()) { TestChitChatApp(settings, auth, conversationModel = room,
                directInbox = inbox, directThread = direct, messageTargets = targets, notifications = notices,
                onThemeChanged = { activity.configureChatWindow(it) }) }
        }
    }
    private fun root() {
        compose.waitUntil(5_000) { sessions.state.value.phase == SessionPhase.ANONYMOUS }
        compose.runOnIdle {
            runBlocking { sessions.signIn("Viewer", "fixture-password") }
            sessions.state.value = sessions.state.value.copy(chatPolicy = api.policy, privacy = api.privacy, shareTyping = true)
        }
        // Retire a previous fixture's system clipboard preview with an outside, inert app tap.
        // Do not clear exported text or let the preview intercept the notification-entry tap.
        compose.onNodeWithText(text(R.string.app_name)).assertIsDisplayed().performNativeTap()
        compose.onNodeWithTag("notifications_open").performScrollTo().assertIsDisplayed().performNativeTap(); fresh()
    }
    private fun text(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)
    private fun fresh() { compose.waitUntil(5_000) { notices.state.value.loaded && !notices.state.value.busy && !notices.state.value.stale } }
    private fun view(id: Long) {
        compose.onNodeWithTag("notification_list").performScrollToNode(hasTestTag("notification_view:$id"))
        compose.onNodeWithTag("notification_view:$id").assertIsDisplayed().performNativeTap()
    }
    private fun pane(isRoom: Boolean) {
        val lookupsBefore = api.lookups.calls.size
        view(if (isRoom) 60 else 57)
        // Notification View already supplies consent; its guarded handoff adopts without a second dialog.
        compose.waitUntil(5_000) { api.lookups.calls.size == lookupsBefore + 1 && targets.state.value.request == null &&
            if (isRoom) room.state.value.roomId == 43L && room.state.value.loaded && !room.state.value.busy &&
                !room.state.value.stale && room.state.value.highlightedMessageId == 20L
            else direct.state.value.peerId == 9L && direct.state.value.loaded && !direct.state.value.busy &&
                !direct.state.value.stale && direct.state.value.highlightedMessageId == 20L }
        compose.onNodeWithTag("requested_message:20").assertIsDisplayed()
        compose.onNodeWithTag(if (isRoom) "message:20" else "direct:20").assertIsSelected()
    }
    private fun hideIme() {
        compose.waitForIdle()
        val before = ime
        val window = nativeInputWindow()
        if (before > 0 || window.imeVisible != false) try { Espresso.closeSoftKeyboard() }
        catch (failure: RuntimeException) {
            runCatching { capture("reply-ime-hide-failure-${android.os.SystemClock.uptimeMillis()}.png") }
            throw AssertionError("IME cleanup: before=$before, after=$ime, beforeWindow=$window, afterWindow=${nativeInputWindow()}", failure)
        }
        compose.waitUntil(5_000) { ime == 0 }
        assertEquals("IME visibility must be confirmed, not inferred from a missing window", false, nativeInputWindow().imeVisible)
    }
    private fun back() { hideIme(); compose.onNodeWithText(text(R.string.back)).assertIsDisplayed().performNativeTap(); fresh() }
    private fun dialogTag(tag: String) = compose.onNode(hasTestTag(tag) and hasAnyAncestor(isDialog()))
    private fun dialog(id: Int) = compose.onNode(hasText(text(id)) and hasAnyAncestor(isDialog()))
    private fun menu(isRoom: Boolean, resource: Long = if (isRoom) 43 else 9, id: Long = 20) {
        hideIme()
        val timeline = if (isRoom) "conversation_timeline" else "dm_timeline"
        val body = if (isRoom) "Room $resource message $id" else "Direct $resource message $id"
        compose.onNodeWithTag(timeline).performScrollToNode(hasText(body))
        compose.onNode(hasText(body) and hasAnyAncestor(hasTestTag(timeline))).assertIsDisplayed().performTouchInput { longClick() }
        compose.onNodeWithTag("message_actions").assertIsDisplayed()
    }
    /** API26+ interception blocks fixture URLs before any other app receives them. */
    private fun observeLinkExports(block: (java.util.concurrent.CopyOnWriteArrayList<Intent>) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val exported = java.util.concurrent.CopyOnWriteArrayList<Intent>()
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action != Intent.ACTION_VIEW) return null
                exported += Intent(intent)
                return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
            }
        }
        instrumentation.addMonitor(monitor)
        try { block(exported) } finally { instrumentation.removeMonitor(monitor) }
    }
    private fun formattedMenu(isRoom: Boolean, line: String) {
        val timeline = if (isRoom) "conversation_timeline" else "dm_timeline"
        compose.onNodeWithTag(timeline).performScrollToNode(hasText(line))
        compose.onNode(hasText(line) and hasAnyAncestor(hasTestTag(timeline))).assertIsDisplayed().performNativeTap()
        compose.onNodeWithTag("message_actions").assertIsDisplayed()
    }
    private fun formattedLinksAndCopy(isRoom: Boolean) = observeLinkExports { exported ->
        val scope = if (isRoom) "room" else "direct"
        val url = "https://example.test/$scope"
        val line = "Formatted bold italic literal_* $url"
        val source = "Formatted *bold* _italic_ `literal_*` $url\n> quoted _passage_\n```\nhttps://example.test/code-only\n```"
        if (isRoom) api.rooms.bodyOverrides[43L to 20L] = source else api.directs.bodyOverrides[9L to 20L] = source
        root(); pane(isRoom)
        compose.onNodeWithText("quoted passage", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("https://example.test/code-only", useUnmergedTree = true).assertIsDisplayed()
        assertTrue(exported.isEmpty())
        formattedMenu(isRoom, line)
        dialogTag("message_link:0").performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("message_link:1").assertDoesNotExist()
        assertTrue(exported.isEmpty())
        dialogTag("message_link:0").performNativeTap()
        compose.runOnIdle {
            val intent = exported.single()
            assertEquals(url, intent.dataString)
            assertEquals(Intent.ACTION_VIEW, intent.action)
            assertTrue(intent.hasCategory(Intent.CATEGORY_BROWSABLE))
            assertNull(intent.extras); assertNull(intent.clipData)
            assertTrue(api.rooms.reads.isEmpty()); assertTrue(api.directs.reads.isEmpty())
            assertEquals(0, api.rooms.sends + api.directs.sends); assertEquals(0, api.notices.posts)
        }
        // A blocked test launch leaves the existing modal suppressing reads and typing.
        compose.onNodeWithTag("message_actions").assertIsDisplayed()
        dialogTag("message_copy_action").performScrollTo().performNativeTap()
        val clipboard = InstrumentationRegistry.getInstrumentation().targetContext.getSystemService(ClipboardManager::class.java)
        assertEquals(source, clipboard.primaryClip!!.getItemAt(0).text.toString())
        assertEquals(1, exported.size)
    }
    @Test fun formattedRoomLinksRequireAChoiceAndCopyKeepsOriginalMarkup() = formattedLinksAndCopy(true)
    @Test fun formattedDirectLinksRequireAChoiceAndCopyKeepsOriginalMarkup() = formattedLinksAndCopy(false)
    private fun queuedLinkIsFenced(isRoom: Boolean) = observeLinkExports { exported ->
        val overrides = if (isRoom) api.rooms.bodyOverrides else api.directs.bodyOverrides
        val resource = if (isRoom) 43L else 9L
        val source = "https://example.test/original"
        overrides[resource to 20L] = source
        root(); pane(isRoom); formattedMenu(isRoom, source)
        dialogTag("message_link:0").performScrollTo()
        val old = dialogTag("message_link:0").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnIdle {
            overrides[resource to 20L] = "https://example.test/replaced"
            if (isRoom) room.refresh() else direct.refresh()
        }
        compose.waitUntil(5_000) { if (isRoom) !room.state.value.busy && room.state.value.messages.any { it.id == 20L && it.body == "https://example.test/replaced" }
            else !direct.state.value.busy && direct.state.value.messages.any { it.id == 20L && it.body == "https://example.test/replaced" } }
        compose.runOnIdle { old() }
        assertTrue(exported.isEmpty())
        val current = dialogTag("message_link:0").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnIdle {
            sessions.state.value = sessions.state.value.copy(generation = sessions.state.value.generation + 1,
                user = SessionUser(8, "Other", listOf("member"), 1, false))
        }
        compose.waitUntil(5_000) { if (isRoom) room.state.value.messages.isEmpty() else direct.state.value.messages.isEmpty() }
        compose.runOnIdle { current() }
        assertTrue(exported.isEmpty()); assertEquals(0, api.rooms.sends + api.directs.sends)
        compose.onNodeWithTag("message_link:0").assertDoesNotExist()
    }
    @Test fun queuedRoomLinkRejectsCanonicalReplacementAndOwnerChange() = queuedLinkIsFenced(true)
    @Test fun queuedDirectLinkRejectsCanonicalReplacementAndOwnerChange() = queuedLinkIsFenced(false)
    @Test fun roomEmotePrefixStaysLiteralAndItsBodyUsesInlineGrammar() = observeLinkExports { exported ->
        api.rooms.typeOverrides[43L to 20L] = "emote"
        api.rooms.bodyOverrides[43L to 20L] = "> _waves_\n```\nhttps://example.test/emote\n```"
        root(); pane(true)
        formattedMenu(true, "* Peer > waves\n```\nhttps://example.test/emote\n```")
        dialogTag("message_link:0").performScrollTo().assertIsDisplayed()
        assertTrue(exported.isEmpty())
        dialog(R.string.cancel).performNativeTap()
    }
    private fun visibleBody(value: String): AnnotatedString = compose.onNodeWithText(value, useUnmergedTree = true)
        .assertIsDisplayed().fetchSemanticsNode().config[SemanticsProperties.Text].single()
    private fun mentionMarks(value: AnnotatedString) = value.getStringAnnotations(MESSAGE_MENTION_ANNOTATION, 0, value.length)
    @Test fun roomMentionsUseServerSnapshotsAndIgnoredBodiesRemainHidden() {
        val source = "_Hi @Viewer_ @Other @room @here\n> quote @Viewer\n`code @Viewer` https://example.test/@Viewer\n```\nfenced @Viewer\n```"
        val line = "Hi @Viewer @Other @room @here"
        api.rooms.bodyOverrides[43L to 20L] = source
        api.rooms.mentionOverrides[43L to 20L] = listOf(MessageMention(7, "Viewer", false), MessageMention(99, "OtherMember", true))
        root(); pane(true)
        val body = visibleBody(line)
        assertEquals(listOf("personal", "broadcast", "broadcast"), mentionMarks(body).map { it.item })
        assertEquals(listOf("@Viewer", "@room", "@here"), mentionMarks(body).map { body.text.substring(it.start, it.end) })
        assertEquals(1, mentionMarks(visibleBody("quote @Viewer")).size)
        assertTrue(mentionMarks(visibleBody("code @Viewer https://example.test/@Viewer")).isEmpty())
        assertTrue(mentionMarks(visibleBody("fenced @Viewer")).isEmpty())
        formattedMenu(true, line); dialogTag("message_copy_action").performNativeTap()
        val clipboard = InstrumentationRegistry.getInstrumentation().targetContext.getSystemService(ClipboardManager::class.java)
        assertEquals(source, clipboard.primaryClip!!.getItemAt(0).text.toString())
        compose.runOnIdle { sessions.state.value = sessions.state.value.copy(chatPolicy = ChatPolicy(setOf(8), null, api.policy.display)) }
        compose.onNodeWithText(line, useUnmergedTree = true).assertDoesNotExist()
        assertEquals(0, api.rooms.sends); assertEquals(0, api.notices.posts); assertEquals(0, typing.requests.get())
    }
    @Test fun directMentionsNeverInferRoomBroadcastsOrDecorateCodeAndLinks() {
        val source = "Direct @Viewer @room @here @Other `code @Viewer` https://example.test/@Viewer"
        val line = "Direct @Viewer @room @here @Other code @Viewer https://example.test/@Viewer"
        api.directs.bodyOverrides[9L to 20L] = source
        api.directs.mentionOverrides[9L to 20L] = listOf(DirectMention(7, "Viewer"))
        root(); pane(false)
        val body = visibleBody(line)
        val mark = mentionMarks(body).single()
        assertEquals("personal", mark.item); assertEquals(7, mark.start); assertEquals(14, mark.end)
        formattedMenu(false, line); dialogTag("message_copy_action").performNativeTap()
        val clipboard = InstrumentationRegistry.getInstrumentation().targetContext.getSystemService(ClipboardManager::class.java)
        assertEquals(source, clipboard.primaryClip!!.getItemAt(0).text.toString())
        assertEquals(0, api.directs.sends); assertEquals(0, api.notices.posts); assertEquals(0, typing.requests.get())
    }
    @Test fun complexEmoteFallbackIsUndecoratedAndOwnerRetirementClearsTheBody() {
        val source = "@Viewer " + "*a ".repeat(1_000)
        api.rooms.bodyOverrides[43L to 20L] = source
        api.rooms.typeOverrides[43L to 20L] = "emote"
        api.rooms.mentionOverrides[43L to 20L] = listOf(MessageMention(7, "Viewer", true))
        root(); pane(true)
        val body = visibleBody("* Peer $source")
        assertTrue(mentionMarks(body).isEmpty())
        assertEquals("* Peer $source", body.text)
        compose.runOnIdle { sessions.state.value = sessions.state.value.copy(generation = sessions.state.value.generation + 1,
            user = SessionUser(8, "Other", listOf("member"), 1, false)) }
        compose.waitUntil(5_000) { room.state.value.messages.isEmpty() }
        compose.onNodeWithText("* Peer $source", useUnmergedTree = true).assertDoesNotExist()
        assertEquals(0, api.rooms.sends); assertEquals(0, api.notices.posts)
    }
    private fun reply(isRoom: Boolean) {
        menu(isRoom); chooseReply(isRoom)
        compose.waitUntil(5_000) { (if (isRoom) room.state.value.composerReply else direct.state.value.composerReply)?.messageId == 20L }
        compose.onNodeWithTag("composer_reply").assertIsDisplayed()
    }
    private fun chooseReply(isRoom: Boolean) {
        dialogTag("message_reply_action").assertIsEnabled().performNativeTap()
        val input = compose.onNodeWithTag(if (isRoom) "conversation_composer" else "dm_composer")
        val requireIme = InstrumentationRegistry.getArguments().getString("expectSoftKeyboard") == "true"
        // Reply deliberately transfers focus after its dialog releases the window. Observe that
        // natural transition before hide/navigation; never request focus, force IME or retry a tap.
        try {
            compose.waitUntil(5_000) { (!requireIme || ime > 0) &&
                runCatching { input.assertIsFocused().assertIsDisplayed() }.isSuccess }
        } catch (failure: ComposeTimeoutException) {
            // Failure-only scalars: no message contents, raw trees, extra taps or focus changes.
            val observedAt = android.os.SystemClock.uptimeMillis()
            val deadlineIme = ime
            val selected = if (isRoom) room.state.value.composerReply else direct.state.value.composerReply
            val focused = runCatching { input.assertIsFocused() }.isSuccess
            val displayed = runCatching { input.assertIsDisplayed() }.isSuccess
            val bounds = runCatching { input.fetchSemanticsNode().boundsInWindow }.getOrNull()
            val options = runCatching { compose.onAllNodesWithTag("message_actions").fetchSemanticsNodes().size }.getOrNull()
            val canSend = compose.runOnIdle { if (isRoom) room.canSend() else direct.canSend() }
            val layout = recreatedWindowAfterFailure()
            val window = nativeInputWindow()
            val inputSessions = NativeTextInputTrace.describe()
            val server = nativeImeServerAfterFailure()
            throw AssertionError("Reply handoff: uptime=$observedAt, room=$isRoom, selected=${selected != null}, " +
                "focused=$focused, displayed=$displayed, bounds=$bounds, options=$options, canSend=$canSend, " +
                "deadlineIME=$deadlineIme, laterIME=$ime, window=$window, layout=$layout, inputSessions=$inputSessions, server=$server", failure)
        }
    }
    private fun options() {
        hideIme(); compose.onNodeWithTag("composer_reply_options").assertIsDisplayed().performNativeTap()
        compose.onNodeWithTag("composer_reply_dialog").assertIsDisplayed()
    }
    private fun type(isRoom: Boolean, value: String) {
        compose.tapNativeInput(if (isRoom) "conversation_composer" else "dm_composer", { ime }).performTextInput(value)
    }
    private fun capture(name: String) {
        if (InstrumentationRegistry.getArguments().getString("captureReplyComposer") != "true") return
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try { java.io.File(instrumentation.targetContext.getExternalFilesDir("screenshots"), name).outputStream().use {
            check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
        } } finally { bitmap.recycle() }
    }
    @Test fun roomLongPressCopyAndMultilineReplyUseCurrentContentAndAnExplicitSend() {
        root(); pane(true); menu(true)
        dialogTag("message_copy_action").performNativeTap()
        val clipboard = InstrumentationRegistry.getInstrumentation().targetContext.getSystemService(ClipboardManager::class.java)
        assertEquals("Room 43 message 20", clipboard.primaryClip!!.getItemAt(0).text.toString())
        assertTrue(clipboard.primaryClipDescription!!.extras!!.getBoolean("android.content.extra.IS_SENSITIVE"))
        reply(true); type(true, "Room answer\nSecond line"); capture("reply-room.png")
        compose.onNodeWithTag("conversation_send").assertContentDescriptionEquals(text(R.string.conversation_send))
            .assertIsDisplayed().assertIsEnabled().performNativeTap()
        compose.waitUntil(5_000) { !room.state.value.busy && room.state.value.messages.any { it.id == 101L } }
        assertEquals(Triple(43L, "Room answer\nSecond line", 20L), api.rooms.sent.single())
        assertNull(room.state.value.composerReply); assertEquals("", room.state.value.draft)
        hideIme(); compose.onNodeWithTag("room_reply:101").performScrollTo().assertIsDisplayed()
        assertEquals(20L, room.state.value.messages.first { it.id == 101L }.replyTo?.messageId)
        assertEquals(0, api.notices.posts)
    }
    @Test fun directReplyKeepsSlashTextLiteralAndShowsItsCanonicalOutgoingQuote() {
        root(); pane(false); reply(false); type(false, "/ping Peer literal\nSecond line")
        compose.onNodeWithText(text(R.string.composer_reply_ping_title)).assertDoesNotExist(); capture("reply-direct.png")
        compose.onNodeWithTag("dm_send").assertContentDescriptionEquals(text(R.string.dm_send))
            .assertIsDisplayed().assertIsEnabled().performNativeTap()
        compose.waitUntil(5_000) { !direct.state.value.busy && direct.state.value.confirmedSendId == 101L }
        assertEquals(Triple(9L, "/ping Peer literal\nSecond line", 20L), api.directs.sent.single())
        assertNull(direct.state.value.composerReply); assertEquals("", direct.state.value.draft)
        hideIme(); compose.onNodeWithTag("direct_reply:101").performScrollTo().assertIsDisplayed()
        assertEquals(20L, direct.state.value.messages.first { it.id == 101L }.replyTo?.messageId)
        assertEquals(0, api.notices.posts)
    }
    private fun rejectedOriginal(isRoom: Boolean) {
        root(); pane(isRoom); reply(isRoom); type(isRoom, "Keep rejected answer")
        compose.runOnIdle {
            val error = ApiFailure(FailureKind.HTTP, 400, "invalid_reply_target")
            if (isRoom) api.rooms.sendFailure = error else api.directs.sendFailure = error
        }
        compose.onNodeWithTag(if (isRoom) "conversation_send" else "dm_send").performNativeTap()
        compose.waitUntil(5_000) { if (isRoom) !room.state.value.busy && room.state.value.error?.code == "invalid_reply_target"
            else !direct.state.value.busy && direct.state.value.error?.code == "invalid_reply_target" }
        hideIme()
        compose.onNodeWithContentDescription(text(if (isRoom) R.string.conversation_details else R.string.dm_details)).performNativeTap()
        dialog(R.string.composer_reply_invalid).performScrollTo().assertIsDisplayed()
        dialog(if (isRoom) R.string.rooms_close else R.string.dm_close).performNativeTap()
        assertEquals(1, if (isRoom) api.rooms.sends else api.directs.sends)
        assertEquals("Keep rejected answer", if (isRoom) room.state.value.draft else direct.state.value.draft)
        options(); dialogTag("composer_reply_remove").performScrollTo().performNativeTap()
        compose.onNodeWithTag("composer_reply").assertDoesNotExist()
        assertNull(if (isRoom) room.state.value.error else direct.state.value.error)
        compose.onNodeWithTag(if (isRoom) "conversation_send" else "dm_send").assertIsNotEnabled()
        compose.onNodeWithContentDescription(text(if (isRoom) R.string.conversation_details else R.string.dm_details)).performNativeTap()
        dialog(if (isRoom) R.string.conversation_refresh else R.string.dm_refresh).performScrollTo().performNativeTap()
        compose.waitUntil(5_000) { if (isRoom) !room.state.value.busy && !room.state.value.stale
            else !direct.state.value.busy && !direct.state.value.stale }
        compose.runOnIdle { if (isRoom) api.rooms.sendFailure = null else api.directs.sendFailure = null }
        compose.onNodeWithTag(if (isRoom) "conversation_send" else "dm_send").assertIsEnabled().performNativeTap()
        compose.waitUntil(5_000) { if (isRoom) !room.state.value.busy && api.rooms.sends == 2 else !direct.state.value.busy && api.directs.sends == 2 }
        assertNull((if (isRoom) api.rooms.sent else api.directs.sent).last().third)
    }
    @Test fun expiredRoomOriginalRequiresExplicitRemovalBeforeAnOrdinarySend() = rejectedOriginal(true)
    @Test fun expiredDirectOriginalRequiresExplicitRemovalBeforeAnOrdinarySend() = rejectedOriginal(false)
    @Test fun replyOnlyDraftsRestoreTheirOwnRoomAndPeerThroughRealNavigation() {
        root(); pane(true); reply(true); val roomChoice = room.state.value.composerReply!!
        back(); view(58); compose.waitUntil(5_000) { room.state.value.roomId == 42L && room.state.value.loaded && !room.state.value.busy }
        menu(true, 42, 100); chooseReply(true)
        back(); pane(true); assertSame(roomChoice, room.state.value.composerReply)
        assertEquals(100L, room.drafts.value.getValue(42).composerReply?.messageId)
        assertEquals("", room.state.value.draft); back(); pane(false); reply(false)
        val peerChoice = direct.state.value.composerReply!!
        back(); view(55); compose.waitUntil(5_000) { direct.state.value.peerId == 8L && direct.state.value.loaded && !direct.state.value.busy }
        menu(false, 8, 100); chooseReply(false)
        back(); pane(false); assertSame(peerChoice, direct.state.value.composerReply)
        assertEquals(100L, direct.drafts.value.getValue(8).composerReply?.messageId)
        assertEquals("", direct.state.value.draft); assertEquals(0, api.rooms.sends + api.directs.sends)
    }
    @Test fun realActivityProvidersRetainBothComposersWhileDestroyedEntryCallbacksStayInert() {
        providerRecreation(checkGeometry = false)
    }
    @Test @ContentDuringOnCreate fun onCreateInstallationKeepsRecreatedReplyComposersAboveTheNativeKeyboard() {
        providerRecreation(checkGeometry = true)
    }
    private fun providerRecreation(checkGeometry: Boolean) {
        root()
        for (isRoom in listOf(true, false)) {
            pane(isRoom); reply(isRoom)
            if (checkGeometry) {
                val bounds = compose.onNodeWithTag(if (isRoom) "conversation_composer" else "dm_composer").fetchSemanticsNode().boundsInWindow
                var windowHeight = 0
                compose.activityRule.scenario.onActivity { windowHeight = it.window.decorView.height }
                val native = nativeInputWindow()
                assertEquals(1, native.resumedActivities)
                assertEquals(true, native.imeVisible)
                val bottom = checkNotNull(native.imeBottom)
                assertTrue("Pinned composer must remain above its native keyboard", bounds.bottom <= windowHeight - bottom)
            }
            type(isRoom, "Rotation draft"); hideIme()
            val oldRoom = room; val oldDirect = direct
            val original = if (isRoom) room.state.value.composerReply else direct.state.value.composerReply
            val oldOptions = compose.onNodeWithTag("composer_reply_options").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
            compose.activityRule.scenario.recreate()
            if (!onCreateHost) compose.activityRule.scenario.onActivity { install(it) }
            compose.waitUntil(5_000) { if (isRoom) room.state.value.loaded && !room.state.value.busy && !room.state.value.stale
                else direct.state.value.loaded && !direct.state.value.busy && !direct.state.value.stale }
            assertSame(oldRoom, room); assertSame(oldDirect, direct)
            assertSame(original, if (isRoom) room.state.value.composerReply else direct.state.value.composerReply)
            compose.onNodeWithTag(if (isRoom) "conversation_composer" else "dm_composer").assertTextContains("Rotation draft")
            compose.runOnIdle { oldOptions() }
            compose.onNodeWithTag("composer_reply_dialog").assertDoesNotExist()
            back()
        }
        assertEquals(2, api.lookups.calls.size); assertEquals(0, api.rooms.sends + api.directs.sends)
    }
    @Test fun messageModalRejectsQueuedInputAndSendAndSuppressesAnArmedTypingCoordinator() {
        root(); pane(true); type(true, "Keep hidden draft")
        compose.waitUntil(5_000) { typing.requests.get() > 0 }
        val oldInput = compose.onNodeWithTag("conversation_composer").fetchSemanticsNode().config[SemanticsActions.SetText].action!!
        val oldSend = compose.onNodeWithTag("conversation_send").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        menu(true); val before = typing.requests.get()
        compose.runOnIdle {
            typingClock.addAndGet(5_000L) // Expire throttling so it cannot mask a missing modal guard.
            oldInput(AnnotatedString("Hidden input")); oldSend()
            typing.controller.input(sessions.state.value, TypingTarget.Room(43), true)
        }
        compose.waitForIdle()
        assertEquals("Keep hidden draft", room.state.value.draft); assertEquals(before, typing.requests.get())
        assertTrue(api.rooms.reads.isEmpty()); assertEquals(0, api.rooms.sends); assertEquals(0, api.notices.posts)
        dialog(R.string.cancel).performNativeTap()
        type(true, " Visible again")
        compose.waitUntil(5_000) { typing.requests.get() == before + 1 } // Same elapsed clock, actual visible input.
    }
    @Test fun uncertainDirectReplyRemainsGuardedAfterLeavingAndReturningThroughATarget() {
        root(); pane(false); reply(false); val original = direct.state.value.composerReply!!
        type(false, "Unknown reply"); compose.runOnIdle { api.directs.sendFailure = ApiFailure(FailureKind.TIMEOUT) }
        compose.onNodeWithTag("dm_send").performNativeTap(); compose.waitUntil(5_000) { direct.state.value.uncertainSend && !direct.state.value.busy }
        back(); pane(false); assertSame(original, direct.state.value.composerReply)
        assertEquals("Unknown reply", direct.state.value.draft); assertFalse(direct.state.value.checkedUnknown)
        options(); dialogTag("composer_reply_remove").performScrollTo().performNativeTap()
        assertTrue(direct.state.value.uncertainSend); compose.onNodeWithTag("dm_send").assertIsNotEnabled()
        assertEquals(1, api.directs.sends); assertEquals(20L, api.directs.sent.single().third)
    }
    @Test fun retiredAccountCannotReceiveALateSendOrExportThroughItsOldCopyAction() {
        root(); pane(true); reply(true); type(true, "Old account reply")
        compose.runOnIdle { api.rooms.sendGate = CompletableDeferred(); api.rooms.ignoreSendCancellation = true }
        compose.onNodeWithTag("conversation_send").performNativeTap(); compose.waitUntil(5_000) { api.rooms.sends == 1 }
        menu(true)
        val oldCopy = dialogTag("message_copy_action").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        val clipboard = InstrumentationRegistry.getInstrumentation().targetContext.getSystemService(ClipboardManager::class.java)
        compose.runOnIdle {
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("fixture", "Owner sentinel"))
            sessions.state.value = sessions.state.value.copy(generation = sessions.state.value.generation + 1,
                user = SessionUser(8, "Other", listOf("member"), 1, false))
        }
        compose.waitUntil(5_000) { room.state.value.messages.isEmpty() && room.state.value.composerReply == null }
        compose.runOnIdle { oldCopy(); api.rooms.sendGate!!.complete(Unit) }; compose.waitForIdle()
        assertEquals("Owner sentinel", clipboard.primaryClip!!.getItemAt(0).text.toString())
        assertTrue(room.state.value.messages.isEmpty()); assertTrue(room.drafts.value.isEmpty())
        assertNull(room.state.value.composerReply); assertEquals(1, api.rooms.sends)
    }
    @Test fun deletedOriginalIsRedactedAndPrivatePingExplicitlyIgnoresTheRetainedReference() {
        root(); pane(true); reply(true)
        compose.runOnIdle { api.rooms.deleted += 43L to 20L }
        hideIme()
        compose.onNodeWithContentDescription(text(R.string.conversation_details)).performNativeTap()
        dialog(R.string.conversation_refresh).performScrollTo().performNativeTap()
        compose.waitUntil(5_000) { !room.state.value.busy && !room.state.value.syncing && room.state.value.messages.any { it.id == 20L && it.deleted } }
        options(); dialog(R.string.conversation_deleted).assertIsDisplayed()
        compose.onNodeWithText("Room 43 message 20").assertDoesNotExist(); dialog(R.string.rooms_close).performNativeTap()
        type(true, "/ping Peer nudge"); options()
        dialog(R.string.composer_reply_ping_title).assertIsDisplayed()
        dialog(R.string.composer_reply_ping_ignored).performScrollTo().assertIsDisplayed(); capture("reply-ping-warning.png")
        dialog(R.string.rooms_close).performNativeTap()
        compose.onNodeWithTag("conversation_send").assertIsDisplayed().assertIsEnabled().performNativeTap()
        compose.waitUntil(5_000) { !room.state.value.busy && room.state.value.notice == "ping_sent" }
        assertNull(room.state.value.composerReply); assertEquals("", room.state.value.draft)
        assertEquals(20L, api.rooms.sent.single().third); assertTrue(room.state.value.messages.none { it.id == 101L })
    }
    @Test fun oldOptionsCancelCannotClearASameIdSuccessorAndViewOriginalKeepsTheDraft() {
        root(); pane(true); reply(true); val first = room.state.value.composerReply!!
        options(); val oldRemove = dialogTag("composer_reply_remove").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        dialog(R.string.rooms_close).performNativeTap(); reply(true); val second = room.state.value.composerReply!!
        assertNotSame(first, second); options(); compose.runOnIdle { oldRemove() }
        compose.onNodeWithTag("composer_reply_dialog").assertIsDisplayed()
        assertSame(second, room.state.value.composerReply); assertEquals(1, api.lookups.calls.size)
        dialog(R.string.message_target_open_quote).performScrollTo().performNativeTap()
        compose.waitUntil(5_000) { api.lookups.calls.size == 2 && targets.state.value.request == null &&
            !room.state.value.busy && !room.state.value.stale && room.state.value.highlightedMessageId == 20L }
        compose.onNodeWithTag("requested_message:20").assertIsDisplayed()
        compose.onNodeWithTag("message:20").assertIsSelected()
        assertSame(second, room.state.value.composerReply); assertEquals(2, api.lookups.calls.size)
        assertEquals(20L, room.state.value.fixedUpper); assertEquals(0, api.rooms.sends); assertEquals(0, api.notices.posts)
    }
    @Test fun ignoredReplyPreviewStaysHiddenAndBlockedDirectHistoryStillAllowsExplicitCopy() {
        root(); pane(true); reply(true)
        compose.runOnIdle { sessions.state.value = sessions.state.value.copy(chatPolicy = ChatPolicy(setOf(8), null, api.policy.display)) }
        options(); dialog(R.string.composer_reply_ignored).assertIsDisplayed()
        compose.onNodeWithText("Room 43 message 20").assertDoesNotExist(); dialog(R.string.rooms_close).performNativeTap()
        back(); compose.runOnIdle { sessions.state.value = sessions.state.value.copy(chatPolicy = api.policy) }; pane(false)
        compose.onNodeWithContentDescription(text(R.string.dm_details)).performNativeTap()
        dialog(R.string.dm_block).performScrollTo().performNativeTap(); dialogTag("dm_confirm_block").performNativeTap()
        compose.waitUntil(5_000) { !direct.state.value.busy && direct.state.value.context?.relationship?.blockedByMe == true }
        menu(false); dialogTag("message_reply_action").assertIsNotEnabled()
        dialogTag("message_copy_action").assertIsEnabled().performNativeTap()
        val clipboard = InstrumentationRegistry.getInstrumentation().targetContext.getSystemService(ClipboardManager::class.java)
        assertEquals("Direct 9 message 20", clipboard.primaryClip!!.getItemAt(0).text.toString())
        assertEquals(0, api.directs.sends)
    }
    @After fun close() {
        try { hideIme() } finally { compose.runOnIdle {
            api.rooms.sendGate?.complete(Unit); api.directs.sendGate?.complete(Unit)
            api.lookups.gates.values.forEach { it.complete(Unit) }
            if (::room.isInitialized) room.background()
            if (::direct.isInitialized) direct.background()
            if (::targets.isInitialized) targets.background()
            if (::notices.isInitialized) notices.background()
            typing.close()
        } }
    }
}

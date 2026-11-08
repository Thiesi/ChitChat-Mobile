package io.github.thiesi.chitchat.feature.conversation

import android.os.Build
import android.view.View
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import io.github.thiesi.chitchat.R
import io.github.thiesi.chitchat.app.SettingsViewModel
import io.github.thiesi.chitchat.core.server.*
import io.github.thiesi.chitchat.core.session.SessionPhase
import io.github.thiesi.chitchat.core.settings.*
import io.github.thiesi.chitchat.feature.account.TestChitChatApp
import io.github.thiesi.chitchat.feature.auth.*
import io.github.thiesi.chitchat.feature.direct.*
import io.github.thiesi.chitchat.feature.notifications.NotificationCenterViewModel
import io.github.thiesi.chitchat.feature.realtime.NativeTypingHarness
import io.github.thiesi.chitchat.feature.targets.MessageTargetViewModel
import io.github.thiesi.chitchat.testing.NativeChatFixtures
import io.github.thiesi.chitchat.testing.RecreatedInsetsProbe
import io.github.thiesi.chitchat.testing.nativeInputWindow
import io.github.thiesi.chitchat.testing.performNativeTap
import io.github.thiesi.chitchat.testing.tapNativeInput
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Opt-in measurement69/6726488531. Not part of the app acceptance suite. */
@RunWith(Parameterized::class)
class RecreatedComposerInsetsDiagnostic(private val cycle: Int) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "cycle{0}") fun cycles() = (1..20).map { arrayOf(it) }
    }
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val sessions = TestSessions()
    private val api = NativeChatFixtures(sessions)
    private val typingClock = AtomicLong(10_000L)
    private val typing = NativeTypingHarness(sessions.state, elapsed = typingClock::get)
    private val probe = RecreatedInsetsProbe(cycle)
    private lateinit var room: ConversationViewModel
    private lateinit var direct: DirectThreadViewModel
    private lateinit var targets: MessageTargetViewModel
    private lateinit var notices: NotificationCenterViewModel
    private var sampleCurrent: ((String) -> Unit)? = null
    private val store = object : SettingsStore {
        override val settings = MutableStateFlow(AppSettings(server =
            (ServerAddress.parse("chat.example.test") as AddressResult.Valid).address))
        override suspend fun saveServer(server: ServerAddress?) { settings.value = settings.value.copy(server = server) }
        override suspend fun savePureBlack(enabled: Boolean) = Unit
        override suspend fun saveTheme(theme: ThemePreference) { settings.value = settings.value.copy(theme = theme) }
    }

    private fun install(activity: ComponentActivity) {
        activity.enableEdgeToEdge()
        room = ViewModelProvider(activity, ConversationViewModel.factory(api.rooms, typing = typing.controller))[ConversationViewModel::class.java]
        direct = ViewModelProvider(activity, DirectThreadViewModel.factory(api.directs, typing = typing.controller))[DirectThreadViewModel::class.java]
        targets = ViewModelProvider(activity, MessageTargetViewModel.factory(api.lookups))[MessageTargetViewModel::class.java]
        notices = ViewModelProvider(activity, NotificationCenterViewModel.factory(api.notices))[NotificationCenterViewModel::class.java]
        val inbox = ViewModelProvider(activity, DirectInboxViewModel.factory(api.directs))[DirectInboxViewModel::class.java]
        val settings = ViewModelProvider(activity, SettingsViewModel.factory(store))[SettingsViewModel::class.java]
        val auth = ViewModelProvider(activity, AuthViewModel.factory(sessions))[AuthViewModel::class.java]
        activity.setContent {
            val view = LocalView.current
            val density = LocalDensity.current
            val insets = WindowInsets.ime
            val bottom = insets.getBottom(density)
            // Keep the original single mirrored producer, including any stale writes. Observation
            // identity must not repair the probe or grant the unchanged predicate a different input.
            SideEffect {
                probe.mirroredBottom = bottom
                probe.mirroredProducer = System.identityHashCode(view)
                probe.sample("compose", activity, view, bottom)
            }
            DisposableEffect(view) {
                val sample: (String) -> Unit = { probe.sample(it, activity, view, insets.getBottom(density)) }
                sampleCurrent = sample
                val tree = view.viewTreeObserver
                val draw = ViewTreeObserver.OnPreDrawListener { sample("draw"); true }
                val attach = object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(v: View) { sample("attach") }
                    override fun onViewDetachedFromWindow(v: View) { sample("detach") }
                }
                tree.addOnPreDrawListener(draw)
                view.addOnAttachStateChangeListener(attach)
                sample("install")
                onDispose {
                    sample("dispose")
                    if (tree.isAlive) tree.removeOnPreDrawListener(draw)
                    view.removeOnAttachStateChangeListener(attach)
                    if (sampleCurrent === sample) sampleCurrent = null
                }
            }
            Box(Modifier.fillMaxSize()) { TestChitChatApp(settings, auth, conversationModel = room,
                directInbox = inbox, directThread = direct, messageTargets = targets, notifications = notices) }
        }
    }

    private fun mark(stage: String, event: String) {
        compose.activityRule.scenario.onActivity { probe.stage = stage; sampleCurrent?.invoke(event) }
    }
    private fun text(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)
    private fun fresh() { compose.waitUntil(5_000) {
        notices.state.value.loaded && !notices.state.value.busy && !notices.state.value.stale } }
    private fun root() {
        compose.waitUntil(5_000) { sessions.state.value.phase == SessionPhase.ANONYMOUS }
        compose.runOnIdle {
            runBlocking { sessions.signIn("Viewer", "fixture-password") }
            sessions.state.value = sessions.state.value.copy(chatPolicy = api.policy, privacy = api.privacy, shareTyping = true)
        }
        compose.onNodeWithText(text(R.string.app_name)).assertIsDisplayed().performNativeTap()
        compose.onNodeWithTag("notifications_open").performScrollTo().assertIsDisplayed().performNativeTap(); fresh()
    }
    private fun pane(isRoom: Boolean) {
        val before = api.lookups.calls.size
        val id = if (isRoom) 60 else 57
        compose.onNodeWithTag("notification_list").performScrollToNode(hasTestTag("notification_view:$id"))
        compose.onNodeWithTag("notification_view:$id").assertIsDisplayed().performNativeTap()
        compose.waitUntil(5_000) { api.lookups.calls.size == before + 1 && targets.state.value.request == null &&
            if (isRoom) room.state.value.roomId == 43L && room.state.value.loaded && !room.state.value.busy &&
                !room.state.value.stale && room.state.value.highlightedMessageId == 20L
            else direct.state.value.peerId == 9L && direct.state.value.loaded && !direct.state.value.busy &&
                !direct.state.value.stale && direct.state.value.highlightedMessageId == 20L }
        compose.onNodeWithTag("requested_message:20").assertIsDisplayed()
        compose.onNodeWithTag(if (isRoom) "message:20" else "direct:20").assertIsSelected()
    }
    private fun hideIme() {
        compose.waitForIdle()
        if (probe.mirroredBottom > 0 || nativeInputWindow().imeVisible != false) Espresso.closeSoftKeyboard()
        compose.waitUntil(5_000) { probe.mirroredBottom == 0 }
        assertEquals(false, nativeInputWindow().imeVisible)
    }
    private fun reply(isRoom: Boolean) {
        hideIme()
        val timeline = if (isRoom) "conversation_timeline" else "dm_timeline"
        val body = if (isRoom) "Room 43 message 20" else "Direct 9 message 20"
        compose.onNodeWithTag(timeline).performScrollToNode(hasText(body))
        compose.onNode(hasText(body) and hasAnyAncestor(hasTestTag(timeline))).assertIsDisplayed().performTouchInput { longClick() }
        compose.onNodeWithTag("message_actions").assertIsDisplayed()
        mark(if (isRoom) "ROOM_REPLY" else "DM_REPLY", "wait_start")
        compose.onNode(hasTestTag("message_reply_action") and hasAnyAncestor(isDialog())).assertIsEnabled().performNativeTap()
        val input = compose.onNodeWithTag(if (isRoom) "conversation_composer" else "dm_composer")
        compose.waitUntil(5_000) { probe.mirroredBottom > 0 &&
            runCatching { input.assertIsFocused().assertIsDisplayed() }.isSuccess }
        compose.waitUntil(5_000) { (if (isRoom) room.state.value.composerReply else direct.state.value.composerReply)?.messageId == 20L }
        compose.onNodeWithTag("composer_reply").assertIsDisplayed()
        mark(if (isRoom) "ROOM_REPLY" else "DM_REPLY", "proved")
    }

    @Test fun roomRecreationThenDirectReply() {
        var failed = false
        var original: Throwable? = null
        try {
            assertEquals(35, Build.VERSION.SDK_INT)
            assertEquals("true", InstrumentationRegistry.getArguments().getString("expectSoftKeyboard"))
            compose.activityRule.scenario.onActivity { install(it) }
            root(); pane(true); reply(true)
            compose.tapNativeInput("conversation_composer", { probe.mirroredBottom }).performTextInput("Rotation draft")
            hideIme()
            val oldRoom = room; val oldDirect = direct; val choice = room.state.value.composerReply
            val oldOptions = compose.onNodeWithTag("composer_reply_options").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
            mark("RECREATE", "before_recreate")
            compose.activityRule.scenario.recreate()
            compose.activityRule.scenario.onActivity { install(it) }
            compose.waitUntil(5_000) { room.state.value.loaded && !room.state.value.busy && !room.state.value.stale }
            assertSame(oldRoom, room); assertSame(oldDirect, direct); assertSame(choice, room.state.value.composerReply)
            compose.onNodeWithTag("conversation_composer").assertTextContains("Rotation draft")
            compose.runOnIdle { oldOptions() }
            compose.onNodeWithTag("composer_reply_dialog").assertDoesNotExist()
            mark("RECREATE", "after_recreate")
            hideIme(); compose.onNodeWithText(text(R.string.back)).assertIsDisplayed().performNativeTap(); fresh()
            pane(false); reply(false)
            assertEquals(2, api.lookups.calls.size); assertEquals(0, api.rooms.sends + api.directs.sends)
        } catch (failure: Throwable) {
            failed = true; original = failure
            runCatching { mark(probe.stage, "failure") }
            throw failure
        } finally {
            var cleanupProblem: Throwable? = null
            fun clean(action: () -> Unit) {
                try { action() } catch (failure: Throwable) {
                    val previous = cleanupProblem
                    if (previous == null) cleanupProblem = failure else previous.addSuppressed(failure)
                }
            }
            clean { hideIme() }
            clean { mark("CLEANUP", "cleanup_end") }
            clean { compose.runOnIdle { if (::room.isInitialized) room.background() } }
            clean { compose.runOnIdle { if (::direct.isInitialized) direct.background() } }
            clean { compose.runOnIdle { if (::targets.isInitialized) targets.background() } }
            clean { compose.runOnIdle { if (::notices.isInitialized) notices.background() } }
            clean { compose.runOnIdle { typing.close() } }
            clean { compose.activityRule.scenario.onActivity { probe.emit(failed, cleanupProblem != null) } }
            cleanupProblem?.let { problem ->
                if (original != null) original.addSuppressed(problem) else throw problem
            }
        }
    }
}

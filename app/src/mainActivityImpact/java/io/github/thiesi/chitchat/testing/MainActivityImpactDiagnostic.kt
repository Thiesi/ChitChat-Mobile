package io.github.thiesi.chitchat.testing

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.WindowInsets
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import io.github.thiesi.chitchat.MainActivity
import io.github.thiesi.chitchat.app.EmptyRoomOperations
import io.github.thiesi.chitchat.app.SettingsViewModel
import io.github.thiesi.chitchat.core.server.AddressResult
import io.github.thiesi.chitchat.core.server.ServerAddress
import io.github.thiesi.chitchat.core.session.SessionPhase
import io.github.thiesi.chitchat.core.settings.*
import io.github.thiesi.chitchat.feature.account.AccountViewModel
import io.github.thiesi.chitchat.feature.account.TestAccounts
import io.github.thiesi.chitchat.feature.auth.AuthViewModel
import io.github.thiesi.chitchat.feature.auth.TestSessions
import io.github.thiesi.chitchat.feature.conversation.ConversationViewModel
import io.github.thiesi.chitchat.feature.direct.DirectInboxViewModel
import io.github.thiesi.chitchat.feature.direct.DirectThreadViewModel
import io.github.thiesi.chitchat.feature.notifications.NotificationCenterViewModel
import io.github.thiesi.chitchat.feature.rooms.RoomsViewModel
import io.github.thiesi.chitchat.feature.targets.MessageTargetViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runners.model.Statement

/** One authorized API35 measurement. Real MainActivity owns all window/content setup. */
class MainActivityImpactDiagnostic {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val sessions = TestSessions()
    private val api = NativeChatFixtures(sessions)
    private val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var direct: DirectThreadViewModel
    private lateinit var notices: NotificationCenterViewModel
    private lateinit var targets: MessageTargetViewModel
    private var creations = 0
    private var tapAttempted = false
    private var geometryProved = false
    private val settingsStore = object : SettingsStore {
        override val settings = MutableStateFlow(AppSettings(server =
            (ServerAddress.parse("chat.example.test") as AddressResult.Valid).address))
        override suspend fun saveServer(server: ServerAddress?) { settings.value = settings.value.copy(server = server) }
        override suspend fun saveTheme(theme: ThemePreference) { settings.value = settings.value.copy(theme = theme) }
        override suspend fun savePureBlack(enabled: Boolean) { settings.value = settings.value.copy(pureBlack = enabled) }
    }

    // API35's pre-created callback runs after Activity attachment, before MainActivity.onCreate.
    // Seed only synthetic operations in the REAL Activity's normal ViewModelStore. Never install
    // content, replace the Activity, change its window/theme/insets, or touch focus/IME here.
    private val seeds = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityPreCreated(activity: Activity, savedInstanceState: Bundle?) {
            if (activity.javaClass != MainActivity::class.java) return
            activity as MainActivity
            creations++
            ViewModelProvider(activity, SettingsViewModel.factory(settingsStore))[SettingsViewModel::class.java]
            ViewModelProvider(activity, AuthViewModel.factory(sessions))[AuthViewModel::class.java]
            ViewModelProvider(activity, AccountViewModel.factory(TestAccounts(sessions.state)))[AccountViewModel::class.java]
            ViewModelProvider(activity, RoomsViewModel.factory(EmptyRoomOperations(sessions.state)))[RoomsViewModel::class.java]
            ViewModelProvider(activity, ConversationViewModel.factory(api.rooms))[ConversationViewModel::class.java]
            ViewModelProvider(activity, DirectInboxViewModel.factory(api.directs))[DirectInboxViewModel::class.java]
            direct = ViewModelProvider(activity, DirectThreadViewModel.factory(api.directs))[DirectThreadViewModel::class.java]
            notices = ViewModelProvider(activity, NotificationCenterViewModel.factory(api.notices))[NotificationCenterViewModel::class.java]
            targets = ViewModelProvider(activity, MessageTargetViewModel.factory(api.lookups))[MessageTargetViewModel::class.java]
        }
        override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }
    private val seedRule = TestRule { base, _ -> object : Statement() {
        override fun evaluate() {
            check(android.os.Build.VERSION.SDK_INT == 35) { "Impact measurement requires pinned API35" }
            val application = instrumentation.targetContext.applicationContext as Application
            application.registerActivityLifecycleCallbacks(seeds)
            try { base.evaluate() } finally { application.unregisterActivityLifecycleCallbacks(seeds) }
        }
    } }
    @get:Rule val rules: RuleChain = RuleChain.outerRule(seedRule).around(compose)

    private data class WindowSample(val uptime: Long, val activity: Int, val window: Int,
        val height: Int, val decorHeight: Int, val originX: Int, val originY: Int,
        val focused: Boolean, val attached: Boolean, val imeVisible: Boolean?, val imeBottom: Int?)

    private fun window(): WindowSample {
        var result: WindowSample? = null
        instrumentation.runOnMainSync {
            val activity = compose.activity
            val decor = activity.window.decorView
            val insets = decor.rootWindowInsets
            val bounds = activity.windowManager.currentWindowMetrics.bounds
            result = WindowSample(SystemClock.uptimeMillis(), System.identityHashCode(activity),
                System.identityHashCode(activity.window), bounds.height(), decor.height, bounds.left, bounds.top, decor.hasWindowFocus(),
                decor.isAttachedToWindow, insets?.isVisible(WindowInsets.Type.ime()),
                insets?.getInsets(WindowInsets.Type.ime())?.bottom)
        }
        return checkNotNull(result)
    }
    private fun report(phase: String, input: Rect? = null, send: Rect? = null, cleanup: Boolean? = null) {
        val w = window()
        val data = JSONObject().put("phase", phase).put("uptimeMillis", w.uptime)
            .put("activity", w.activity).put("window", w.window).put("creations", creations)
            .put("windowHeight", w.height).put("windowFocused", w.focused).put("attached", w.attached)
            .put("decorHeight", w.decorHeight).put("originX", w.originX).put("originY", w.originY)
            .put("imeVisible", w.imeVisible ?: JSONObject.NULL).put("imeBottom", w.imeBottom ?: JSONObject.NULL)
            .put("tapAttempted", tapAttempted).put("geometryProved", geometryProved)
            .put("inputTop", input?.top ?: JSONObject.NULL).put("inputBottom", input?.bottom ?: JSONObject.NULL)
            .put("sendTop", send?.top ?: JSONObject.NULL).put("sendBottom", send?.bottom ?: JSONObject.NULL)
            .put("cleanupComplete", cleanup ?: JSONObject.NULL)
        Log.i("MainActivityImpact", data.toString())
    }

    @Test fun recreatedRealMainActivityKeepsDirectComposerAboveNativeIme() {
        var primary: Throwable? = null
        try {
            compose.waitUntil(5_000) { sessions.state.value.phase == SessionPhase.ANONYMOUS }
            compose.runOnIdle {
                runBlocking { sessions.signIn("Viewer", "fixture-password") }
                sessions.state.value = sessions.state.value.copy(chatPolicy = api.policy, privacy = api.privacy, shareTyping = false)
            }
            compose.onNodeWithTag("notifications_open").performScrollTo().assertIsDisplayed().performNativeTap()
            compose.waitUntil(5_000) { notices.state.value.loaded && !notices.state.value.busy && !notices.state.value.stale }
            compose.onNodeWithTag("notification_list").performScrollToNode(hasTestTag("notification_view:57"))
            compose.onNodeWithTag("notification_view:57").assertIsDisplayed().performNativeTap()
            compose.waitUntil(5_000) { direct.state.value.peerId == 9L && direct.state.value.loaded &&
                !direct.state.value.busy && !direct.state.value.stale && targets.state.value.request == null }
            compose.onNodeWithTag("dm_composer").assertIsDisplayed()
            val oldDirect = direct
            val before = window()
            report("before_recreation")
            compose.activityRule.scenario.recreate()
            compose.waitUntil(5_000) { creations == 2 && direct.state.value.peerId == 9L &&
                direct.state.value.loaded && !direct.state.value.busy && !direct.state.value.stale }
            assertSame(oldDirect, direct)
            val after = window()
            assertNotEquals(before.activity, after.activity)
            assertNotEquals(before.window, after.window)
            assertEquals(SessionPhase.AUTHENTICATED, sessions.state.value.phase)
            val input = compose.onNodeWithTag("dm_composer").assertIsDisplayed()
            report("before_tap")
            tapAttempted = true
            input.performNativeTap() // Exactly one composer tap; no semantic text injection or focus request.
            // This new impact measurement proves native IME + actual geometry. It does not
            // replace or relax any existing positive-Compose-inset acceptance predicate.
            compose.waitUntil(5_000) { window().let { it.focused && it.attached && it.imeVisible == true && (it.imeBottom ?: 0) > 0 } &&
                runCatching { input.assertIsFocused().assertIsDisplayed() }.isSuccess }
            val native = window()
            assertEquals("Default-display fullscreen window required", 0, native.originX)
            assertEquals("Default-display fullscreen window required", 0, native.originY)
            val field = input.fetchSemanticsNode().boundsInWindow
            val send = compose.onNodeWithTag("dm_send").assertIsDisplayed().fetchSemanticsNode().boundsInWindow
            // Use full WindowMetrics bounds, not a possibly adjustResize-shrunken decor.
            val keyboardTop = native.height - checkNotNull(native.imeBottom)
            assertTrue(native.height > 0 && keyboardTop > 0)
            report("geometry", field, send)
            assertTrue("Input must remain wholly above the visible native IME", field.top >= 0 && field.bottom <= keyboardTop)
            assertTrue("Send must remain wholly above the visible native IME", send.top >= 0 && send.bottom <= keyboardTop)
            geometryProved = true
            assertEquals(0, api.directs.sends)
        } catch (failure: Throwable) {
            primary = failure
            runCatching { report("failure", runCatching { compose.onNodeWithTag("dm_composer").fetchSemanticsNode().boundsInWindow }.getOrNull(),
                runCatching { compose.onNodeWithTag("dm_send").fetchSemanticsNode().boundsInWindow }.getOrNull()) }
            throw failure
        } finally {
            val cleanup = runCatching {
                Espresso.closeSoftKeyboard()
                compose.waitUntil(5_000) { window().imeVisible == false }
            }
            runCatching { report("cleanup", cleanup = cleanup.isSuccess) }
            cleanup.exceptionOrNull()?.let { if (primary != null) primary.addSuppressed(it) else throw it }
        }
    }
}

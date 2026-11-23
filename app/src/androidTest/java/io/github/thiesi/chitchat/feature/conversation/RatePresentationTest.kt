package io.github.thiesi.chitchat.feature.conversation

import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.thiesi.chitchat.R
import io.github.thiesi.chitchat.app.SettingsViewModel
import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.network.FailureKind
import io.github.thiesi.chitchat.core.server.AddressResult
import io.github.thiesi.chitchat.core.server.ServerAddress
import io.github.thiesi.chitchat.core.session.RateBudget
import io.github.thiesi.chitchat.core.session.RateWait
import io.github.thiesi.chitchat.core.session.SessionPhase
import io.github.thiesi.chitchat.core.settings.AppSettings
import io.github.thiesi.chitchat.core.settings.SettingsStore
import io.github.thiesi.chitchat.core.settings.ThemePreference
import io.github.thiesi.chitchat.feature.account.TestChitChatApp
import io.github.thiesi.chitchat.feature.auth.AuthViewModel
import io.github.thiesi.chitchat.feature.auth.TestSessions
import io.github.thiesi.chitchat.feature.direct.DirectInboxViewModel
import io.github.thiesi.chitchat.feature.direct.DirectThreadViewModel
import io.github.thiesi.chitchat.feature.notifications.NotificationCenterViewModel
import io.github.thiesi.chitchat.feature.targets.MessageTargetViewModel
import io.github.thiesi.chitchat.testing.FontScaleTestActivity
import io.github.thiesi.chitchat.testing.NativeChatFixtures
import io.github.thiesi.chitchat.testing.openTab
import io.github.thiesi.chitchat.testing.performNativeTap
import io.github.thiesi.chitchat.testing.tapNativeInput
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test

/**
 * #323b1 captures for the PL's J (her list, items 1–4, 11 and 12): the composer's own rate-wait line in the real app root
 * with the native keyboard, at a real Configuration font scale. The fake server refuses the send with `429 rate_limited`;
 * a known wait also has its deadline in the session (as the repository records it). `rate-{room|dm}-{send|ping}-…`.
 * Fake server writes, no live mutation.
 */
class RatePresentationTest {
    @get:Rule val compose = createEmptyComposeRule()
    @After fun reset() { FontScaleTestActivity.requestedFontScale = 1f }
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun text(id: Int) = context.getString(id)
    /** The activity window's IME inset, read in composition (as the conversation journeys do) for the one native tap. */
    @Volatile private var imeBottom = 0

    // 1. Room composer with the send line, a draft and a reply chip: light and dark × default and 200%.
    @Test fun roomSendLight() = scales { name, factor -> flow("rate-room-send-light-$name", dm = false, dark = false, factor, reply = true) }
    @Test fun roomSendDark() = scales { name, factor -> flow("rate-room-send-dark-$name", dm = false, dark = true, factor, reply = true) }
    // 2. DM composer with the send line: light and dark × default and 200%.
    @Test fun dmSendLight() = scales { name, factor -> flow("rate-dm-send-light-$name", dm = true, dark = false, factor) }
    @Test fun dmSendDark() = scales { name, factor -> flow("rate-dm-send-dark-$name", dm = true, dark = true, factor) }
    // 3. Room composer at 200% landscape with the keyboard up.
    @Test fun roomSendLandscapeKeyboard() =
        flow("rate-room-send-light-font200-landscape-ime", dm = false, dark = false, 2f, landscape = true, keyboard = true)
    // 4. The ping line (default light).
    @Test fun roomPing() = flow("rate-room-ping-light-default", dm = false, dark = false, 1f, ping = true)
    // 11. The unknown-wait send line (light default).
    @Test fun roomSendUnknownWait() = flow("rate-room-send-unknown-light-default", dm = false, dark = false, 1f, known = false)
    // 12. A conversation with a composer wait and no status row (asserted).
    @Test fun roomConversationNoStatusRow() =
        flow("rate-room-conversation-light-default", dm = false, dark = false, 1f, noStatusRow = true)

    private fun scales(run: (String, Float) -> Unit) { run("default", 1f); run("font200", 2f) }

    private fun flow(name: String, dm: Boolean, dark: Boolean, fontScale: Float, reply: Boolean = false, ping: Boolean = false,
        known: Boolean = true, landscape: Boolean = false, keyboard: Boolean = false, noStatusRow: Boolean = false) {
        FontScaleTestActivity.requestedFontScale = fontScale
        val sessions = TestSessions()
        val api = NativeChatFixtures(sessions)
        val settingsStore = object : SettingsStore {
            override val settings = MutableStateFlow(AppSettings(server =
                (ServerAddress.parse("chat.example.test") as AddressResult.Valid).address,
                theme = if (dark) ThemePreference.DARK else ThemePreference.LIGHT))
            override suspend fun saveServer(server: ServerAddress?) { settings.value = settings.value.copy(server = server) }
            override suspend fun savePureBlack(enabled: Boolean) = Unit
            override suspend fun saveTheme(theme: ThemePreference) { settings.value = settings.value.copy(theme = theme) }
        }
        ActivityScenario.launch(FontScaleTestActivity::class.java).use { scenario ->
            if (landscape) {
                scenario.onActivity { it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
                compose.waitUntil(5_000) {
                    var orientation = 0
                    scenario.onActivity { orientation = it.resources.configuration.orientation }
                    orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
                }
            }
            lateinit var room: ConversationViewModel
            lateinit var thread: DirectThreadViewModel
            lateinit var notices: NotificationCenterViewModel
            scenario.onActivity { activity ->
                activity.enableEdgeToEdge()
                room = ViewModelProvider(activity, ConversationViewModel.factory(api.rooms, mutations = api.authorChanges))[ConversationViewModel::class.java]
                thread = ViewModelProvider(activity, DirectThreadViewModel.factory(api.directs, mutations = api.authorChanges))[DirectThreadViewModel::class.java]
                val inbox = ViewModelProvider(activity, DirectInboxViewModel.factory(api.directs))[DirectInboxViewModel::class.java]
                notices = ViewModelProvider(activity, NotificationCenterViewModel.factory(api.notices))[NotificationCenterViewModel::class.java]
                val targets = ViewModelProvider(activity, MessageTargetViewModel.factory(api.lookups))[MessageTargetViewModel::class.java]
                val settings = ViewModelProvider(activity, SettingsViewModel.factory(settingsStore))[SettingsViewModel::class.java]
                val auth = ViewModelProvider(activity, AuthViewModel.factory(sessions))[AuthViewModel::class.java]
                activity.setContent {
                    val bottom = androidx.compose.foundation.layout.WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current)
                    androidx.compose.runtime.SideEffect { imeBottom = bottom }
                    Box(Modifier.fillMaxSize()) { TestChitChatApp(settings, auth, conversationModel = room, directInbox = inbox,
                        directThread = thread, notifications = notices, messageTargets = targets) }
                }
            }
            // Into the conversation, as the journeys do: sign in, Activity, the notification for the room or the DM.
            compose.waitUntil(5_000) { sessions.state.value.phase == SessionPhase.ANONYMOUS }
            compose.runOnIdle {
                runBlocking { sessions.signIn("Viewer", "fixture-password") }
                sessions.state.value = sessions.state.value.copy(chatPolicy = api.policy, privacy = api.privacy)
                runBlocking { if (dm) api.directs.send(8, "Authored direct fixture", null) else api.rooms.send(43, "Authored fixture", null) }
            }
            compose.onNodeWithTag("bar_title").performNativeTap()
            compose.openTab("activity").performNativeTap()
            compose.waitUntil(5_000) { notices.state.value.loaded && !notices.state.value.busy }
            val notice = if (dm) "notification_view:55" else "notification_view:60"
            compose.onNodeWithTag("notification_list").performScrollToNode(hasTestTag(notice))
            compose.onNodeWithTag(notice).performNativeTap()
            if (dm) {
                compose.waitUntil(5_000) { thread.state.value.peerId == 8L && thread.state.value.loaded && !thread.state.value.busy }
                compose.onNodeWithContentDescription(text(R.string.dm_details)).performNativeTap()
                compose.onNode(hasText(text(R.string.dm_latest)) and hasAnyAncestor(isDialog())).performScrollTo().performNativeTap()
                compose.waitUntil(5_000) { thread.state.value.let { it.loaded && !it.busy && !it.stale } }
            } else {
                compose.waitUntil(5_000) { room.state.value.roomId == 43L && room.state.value.loaded && !room.state.value.busy }
                compose.onNodeWithContentDescription(text(R.string.conversation_details)).performNativeTap()
                compose.onNode(hasText(text(R.string.conversation_latest)) and hasAnyAncestor(isDialog())).performScrollTo().performNativeTap()
                compose.waitUntil(5_000) { room.state.value.let { it.loaded && !it.busy && !it.stale && !it.reading } }
                if (reply) {
                    compose.onNodeWithTag("conversation_timeline").performScrollToNode(hasTestTag("message:101"))
                    compose.onNodeWithText("Authored fixture").performNativeTap()
                    compose.onNodeWithTag("message_reply_action").performScrollTo().performNativeTap()
                    compose.waitUntil(5_000) { room.state.value.composerReply != null }
                }
            }
            // The draft, then the server's own refusal; a known wait's deadline as the repository records it.
            val input = if (dm) "dm_composer" else "conversation_composer"
            compose.tapNativeInput(input, { imeBottom }).performTextReplacement(
                if (ping) "/ping Peer fixture" else if (dm) "Unsent direct fixture" else "Unsent fixture")
            val budget = if (dm) RateBudget.DIRECT_MESSAGE_SEND else if (ping) RateBudget.ROOM_PING else RateBudget.ROOM_SEND
            compose.runOnIdle {
                val refusal = ApiFailure(FailureKind.HTTP, 429, "rate_limited", if (known) 300 else null)
                if (dm) api.directs.sendFailure = refusal else api.rooms.sendFailure = refusal
                if (known) sessions.state.value = sessions.state.value.copy(rateWaits = mapOf(budget to RateWait(
                    android.os.SystemClock.elapsedRealtime() + 300_000, sessions.state.value.generation, checkNotNull(sessions.state.value.user).id)))
            }
            compose.onNodeWithTag(if (dm) "dm_send" else "conversation_send").performNativeTap()
            compose.waitUntil(5_000) { (if (dm) thread.state.value.sendLimit else room.state.value.sendLimit) != null }
            if (!keyboard) {
                androidx.test.espresso.Espresso.closeSoftKeyboard()
                compose.waitUntil(5_000) { imeBottom == 0 }
            }
            compose.waitForIdle()
            // PL (J on af1481d): never assertIsDisplayed alone, which passes on a sliver. The line, the composer and Send are
            // each unclipped (clipped size equals full size) and inside the window, and above the keyboard top when it's up.
            unclipped(name, "rate_wait_line")
            unclipped(name, input)
            unclipped(name, if (dm) "dm_send" else "conversation_send")
            if (noStatusRow) check(compose.onAllNodesWithTag("status_slot").fetchSemanticsNodes().isEmpty()) {
                "$name: a composer wait shows no status row" }
            capture(name)
            if (keyboard) { androidx.test.espresso.Espresso.closeSoftKeyboard(); compose.waitUntil(5_000) { imeBottom == 0 } }
        }
    }

    private fun unclipped(name: String, tag: String) {
        val node = compose.onNodeWithTag(tag).fetchSemanticsNode()
        val visible = node.boundsInWindow
        val window = compose.onRoot().fetchSemanticsNode().size
        val detail = "$name: $tag visible=$visible size=${node.size} window=$window ime=$imeBottom"
        check(kotlin.math.abs(visible.width - node.size.width) <= 1f && kotlin.math.abs(visible.height - node.size.height) <= 1f) {
            "Clipped ($detail)" }
        check(visible.left >= 0f && visible.top >= 0f && visible.right <= window.width + 0.5f && visible.bottom <= window.height + 0.5f) {
            "Outside the window ($detail)" }
        if (imeBottom > 0) check(visible.bottom <= window.height - imeBottom + 0.5f) { "Under the keyboard ($detail)" }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let { java.io.File(it) }
            ?: checkNotNull(context.getExternalFilesDir(null))
        val folder = java.io.File(output, "ccm-rate-presentation")
        check(folder.isDirectory || folder.mkdirs())
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        try {
            java.io.File(folder, "$name.png").outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }
}

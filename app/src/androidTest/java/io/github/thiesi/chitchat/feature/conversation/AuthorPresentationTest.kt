package io.github.thiesi.chitchat.feature.conversation

import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
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
import io.github.thiesi.chitchat.core.session.SessionPhase
import io.github.thiesi.chitchat.core.settings.AppSettings
import io.github.thiesi.chitchat.core.settings.SettingsStore
import io.github.thiesi.chitchat.core.settings.ThemePreference
import io.github.thiesi.chitchat.feature.account.TestChitChatApp
import io.github.thiesi.chitchat.feature.auth.AuthViewModel
import io.github.thiesi.chitchat.feature.direct.DirectInboxViewModel
import io.github.thiesi.chitchat.feature.direct.DirectThreadViewModel
import io.github.thiesi.chitchat.feature.notifications.NotificationCenterViewModel
import io.github.thiesi.chitchat.feature.targets.MessageTargetViewModel
import io.github.thiesi.chitchat.testing.FontScaleTestActivity
import io.github.thiesi.chitchat.testing.NativeChatFixtures
import io.github.thiesi.chitchat.feature.auth.TestSessions
import io.github.thiesi.chitchat.testing.nativeAuthorImeBottom
import io.github.thiesi.chitchat.testing.openTab
import io.github.thiesi.chitchat.testing.performNativeTap
import io.github.thiesi.chitchat.testing.tapNativeInput
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test

/**
 * #70/#284 captures for the PL's J (PL list of 2026-11-23): the author edit dialog's recovery states in the real app root
 * with the native keyboard, at a real Configuration font scale. unknown = checked, read-only body with Review, no keyboard;
 * reviewed = right after Review, editable, no keyboard, Save at rest; editing = after one native tap, keyboard up.
 * `author-edit-{room|dm}-{theme}-{scale}-{state}.png`. Fake server writes, no live mutation.
 */
class AuthorPresentationTest {
    @get:Rule val compose = createEmptyComposeRule()
    @After fun reset() { FontScaleTestActivity.requestedFontScale = 1f }
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun text(id: Int) = context.getString(id)

    @Test fun roomLight() = flow(dm = false, dark = false, scales = listOf("default" to 1f, "font200" to 2f))
    @Test fun roomDark() = flow(dm = false, dark = true, scales = listOf("default" to 1f, "font200" to 2f))
    @Test fun roomLandscapeEditing() = flow(dm = false, dark = false, scales = listOf("font200" to 2f), landscape = true,
        only = setOf("editing"))
    @Test fun dmReviewed() = flow(dm = true, dark = false, scales = listOf("default" to 1f), only = setOf("reviewed"))

    private fun flow(dm: Boolean, dark: Boolean, scales: List<Pair<String, Float>>, landscape: Boolean = false,
        only: Set<String>? = null) {
        for ((scaleName, fontScale) in scales) {
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
                lateinit var targets: MessageTargetViewModel
                scenario.onActivity { activity ->
                    activity.enableEdgeToEdge()
                    room = ViewModelProvider(activity, ConversationViewModel.factory(api.rooms, mutations = api.authorChanges))[ConversationViewModel::class.java]
                    thread = ViewModelProvider(activity, DirectThreadViewModel.factory(api.directs, mutations = api.authorChanges))[DirectThreadViewModel::class.java]
                    val inbox = ViewModelProvider(activity, DirectInboxViewModel.factory(api.directs))[DirectInboxViewModel::class.java]
                    notices = ViewModelProvider(activity, NotificationCenterViewModel.factory(api.notices))[NotificationCenterViewModel::class.java]
                    targets = ViewModelProvider(activity, MessageTargetViewModel.factory(api.lookups))[MessageTargetViewModel::class.java]
                    val settings = ViewModelProvider(activity, SettingsViewModel.factory(settingsStore))[SettingsViewModel::class.java]
                    val auth = ViewModelProvider(activity, AuthViewModel.factory(sessions))[AuthViewModel::class.java]
                    activity.setContent {
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
                    compose.onNodeWithTag("dm_timeline").performScrollToNode(hasTestTag("direct:101"))
                    compose.onNodeWithText("Authored direct fixture").performNativeTap()
                } else {
                    compose.waitUntil(5_000) { room.state.value.roomId == 43L && room.state.value.loaded && !room.state.value.busy }
                    compose.onNodeWithContentDescription(text(R.string.conversation_details)).performNativeTap()
                    compose.onNode(hasText(text(R.string.conversation_latest)) and hasAnyAncestor(isDialog())).performScrollTo().performNativeTap()
                    compose.waitUntil(5_000) { room.state.value.let { it.loaded && !it.busy && !it.stale && !it.reading } }
                    compose.onNodeWithTag("conversation_timeline").performScrollToNode(hasTestTag("message:101"))
                    compose.onNodeWithText("Authored fixture").performNativeTap()
                }
                compose.onNodeWithTag("message_edit_action").performScrollTo().performNativeTap()
                compose.waitUntil(5_000) { runCatching { compose.onNodeWithTag("author_change_dialog").fetchSemanticsNode() }.isSuccess }
                // A Save whose outcome is unknown, then Check (a GET): the read-only, checked body with Review.
                compose.runOnIdle { api.authorChanges.failure = ApiFailure(FailureKind.HTTP, 500, "metadata_failed") }
                compose.onNodeWithTag("author_edit_body").performScrollTo()
                compose.tapNativeInput("author_edit_body", { compose.nativeAuthorImeBottom() })
                    .performTextReplacement(if (dm) "Already committed direct fixture" else "Already committed fixture")
                compose.onNodeWithTag("author_change_confirm").performNativeTap()
                compose.waitUntil(5_000) {
                    if (dm) thread.state.value.let { it.authorUnknown != null && !it.busy } else room.state.value.let { it.authorUnknown != null && !it.busy }
                }
                compose.runOnIdle { api.authorChanges.failure = null }
                compose.waitUntil(5_000) { compose.nativeAuthorImeBottom() == 0 } // The read-only body's keyboard closes by itself.
                compose.onNodeWithTag("author_check_history").performNativeTap()
                compose.waitUntil(5_000) {
                    if (dm) thread.state.value.let { it.authorUnknown == null && !it.busy && !it.stale } else room.state.value.let { it.authorUnknown == null && !it.busy && !it.stale }
                }
                val theme = if (dark) "dark" else "light"
                val prefix = "author-edit-${if (dm) "dm" else "room"}-$theme-$scaleName"
                fun shot(state: String) { if (only == null || state in only) capture("$prefix-$state" + if (landscape) "-landscape" else "") }
                shot("unknown")
                compose.onNodeWithTag("author_change_confirm").performNativeTap() // Review, no POST.
                compose.waitForIdle()
                shot("reviewed")
                if (only == null || "editing" in only) {
                    compose.tapNativeInput("author_edit_body", { compose.nativeAuthorImeBottom() })
                    compose.waitForIdle()
                    shot("editing")
                    androidx.test.espresso.Espresso.closeSoftKeyboard()
                }
            }
        }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let { java.io.File(it) }
            ?: checkNotNull(context.getExternalFilesDir(null))
        val folder = java.io.File(output, "ccm-author-presentation")
        check(folder.isDirectory || folder.mkdirs())
        val bitmap = compose.onNodeWithTag("author_change_dialog").captureToImage().asAndroidBitmap()
        try {
            java.io.File(folder, "$name.png").outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }
}

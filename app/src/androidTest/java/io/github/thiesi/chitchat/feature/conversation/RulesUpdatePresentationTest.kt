package io.github.thiesi.chitchat.feature.conversation

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.thiesi.chitchat.R
import io.github.thiesi.chitchat.app.SettingsViewModel
import io.github.thiesi.chitchat.core.conversation.*
import io.github.thiesi.chitchat.core.media.*
import io.github.thiesi.chitchat.core.policies.*
import io.github.thiesi.chitchat.core.realtime.RealtimeController
import io.github.thiesi.chitchat.core.realtime.RealtimeEvent
import io.github.thiesi.chitchat.core.realtime.RealtimePhase
import io.github.thiesi.chitchat.core.realtime.RealtimeState
import io.github.thiesi.chitchat.core.server.*
import io.github.thiesi.chitchat.core.session.*
import io.github.thiesi.chitchat.core.settings.*
import io.github.thiesi.chitchat.core.ui.AttachmentPickerOverride
import io.github.thiesi.chitchat.core.ui.LocalAttachmentPickerOverride
import io.github.thiesi.chitchat.feature.account.TestChitChatApp
import io.github.thiesi.chitchat.feature.auth.AuthViewModel
import io.github.thiesi.chitchat.feature.auth.TestSessions
import io.github.thiesi.chitchat.feature.policies.RulesUpdateViewModel
import io.github.thiesi.chitchat.feature.rooms.RoomsViewModel
import io.github.thiesi.chitchat.testing.FontScaleTestActivity
import io.github.thiesi.chitchat.testing.openTab
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Rules re-acceptance (O3b3a) for J, at a real Configuration font scale, light and dark: the dialog over a room with a
 * long "What changed" collapsed and expanded (default and 200%), and the conversation banner at 200%, alone and stacked
 * under the offline slot notice, with the composer still visible. No network.
 */
class RulesUpdatePresentationTest {
    @get:Rule val compose = createEmptyComposeRule()
    @After fun reset() { FontScaleTestActivity.requestedFontScale = 1f }

    private val summary = (1..12).joinToString("\n") { "Change $it: a clearer rule about how people treat each other in public rooms." }
    private val rulesLink = PolicyLink("https://chat.example.test/rules", 4, "2026-11-16T10:00:00+00:00")

    private class Realtime : RealtimeController {
        override val state = MutableStateFlow(RealtimeState(phase = RealtimePhase.LIVE))
        override val events = MutableSharedFlow<RealtimeEvent>()
        override fun selectRoom(roomId: Long?, eligible: Boolean, interacted: Boolean) = Unit
        override fun activity(roomId: Long) = Unit
        override fun reenter(roomId: Long) = Unit
        override fun retry() = Unit
        override fun dismissBroadcast(id: Long) = Unit
    }

    @Test fun presentationLight() = presentation(dark = false)
    @Test fun presentationDark() = presentation(dark = true)

    private fun presentation(dark: Boolean) {
        val label = if (dark) "dark" else "light"
        for ((name, fontScale) in listOf("default" to 1f, "font200" to 2f)) {
            FontScaleTestActivity.requestedFontScale = fontScale
            val sessions = TestSessions()
            val auth = AuthViewModel(sessions)
            val api = RulesRooms(sessions)
            val directory = RoomsViewModel(api)
            val budget = AttachmentMemoryBudget(12L * 1024 * 1024)
            val model = ConversationViewModel(api, attachmentBudget = budget, uploads = RulesUploads())
            val realtime = Realtime()
            val rulesOps = object : RulesOperations {
                override val state get() = sessions.state
                override suspend fun acceptRules(version: Int, expected: SessionState) = RulesAcceptResult.Outdated
                override suspend fun refresh() = Unit
            }
            val reads = object : PolicyReads {
                override suspend fun policyDocument(name: PolicyDocumentName, expected: SessionState) =
                    PolicyDocument.Hosted("# Rules", 4, null, summary)
            }
            val rulesUpdate = RulesUpdateViewModel(rulesOps, reads)
            val documents = io.github.thiesi.chitchat.feature.policies.PolicyDocumentViewModel(reads) // As production supplies it.
            val settings = SettingsViewModel(object : SettingsStore {
                override val settings = MutableStateFlow(AppSettings(server = (ServerAddress.parse("chat.example.test") as AddressResult.Valid).address,
                    theme = if (dark) ThemePreference.DARK else ThemePreference.LIGHT))
                override suspend fun saveServer(server: ServerAddress?) { settings.value = settings.value.copy(server = server) }
                override suspend fun savePureBlack(enabled: Boolean) = Unit
                override suspend fun saveTheme(theme: ThemePreference) = Unit
            })
            ActivityScenario.launch(FontScaleTestActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    assertEquals("A real Configuration font scale", fontScale, activity.resources.configuration.fontScale)
                    activity.setContent {
                        CompositionLocalProvider(LocalAttachmentPickerOverride provides AttachmentPickerOverride { _, _, _ -> }) {
                            Box(Modifier.fillMaxSize().testTag("rules_host")) {
                                TestChitChatApp(settings, auth, roomModel = directory, conversationModel = model,
                                    realtime = realtime, policies = documents, rulesUpdate = rulesUpdate)
                            }
                        }
                    }
                }
                compose.waitUntil(5_000) { !settings.state.value.loading }
                compose.waitUntil(5_000) { sessions.state.value.phase == SessionPhase.ANONYMOUS }
                compose.runOnIdle { kotlinx.coroutines.runBlocking { sessions.signIn("Viewer", "fixture-password") } }
                compose.waitUntil(5_000) { sessions.state.value.phase == SessionPhase.AUTHENTICATED }
                compose.runOnIdle {
                    sessions.state.value = sessions.state.value.copy(attachmentPolicy = AttachmentPolicy.Unknown,
                        chatPolicy = ChatPolicy(emptySet(), null, DisplayPreferences()), installationName = "The Common Room",
                        public = PublicSession(false, emptyList(), io.github.thiesi.chitchat.core.network.Lockdown(false),
                            policies = PolicyDiscovery(rulesLink, null, null)), rules = RulesStatus(4, null, false))
                    realtime.state.value = RealtimeState(phase = RealtimePhase.LIVE, generation = sessions.state.value.generation)
                }
                compose.openTab("rooms").performClick()
                compose.waitUntil(5_000) { directory.state.value.loaded && !directory.state.value.busy }
                compose.onNodeWithTag("rooms_directory").performScrollToNode(hasText("Room 42"))
                compose.onNodeWithText("Room 42").performClick()
                compose.onNode(hasText(text(R.string.rooms_open_conversation)) and hasAnyAncestor(isDialog())).performScrollTo().performClick()
                compose.waitUntil(5_000) { model.state.value.roomId == 42L && model.state.value.loaded && !model.state.value.busy }
                // The server now requires the updated rules: the dialog opens over the room.
                compose.runOnIdle { sessions.state.value = sessions.state.value.copy(rules = RulesStatus(1, null, true)) }
                compose.waitUntil(5_000) { compose.onAllNodesWithTag("rules_updated_summary").fetchSemanticsNodes().isNotEmpty() }
                capture("rules-$label-$name-dialog", "rules_updated")
                compose.onNodeWithTag("rules_updated_show_all").performScrollTo().performClick()
                compose.onNodeWithTag("rules_updated_summary").performScrollTo()
                capture("rules-$label-$name-dialog-expanded", "rules_updated")
                for (tag in listOf("rules_updated_read", "rules_updated_show_all", "rules_updated_agree", "rules_updated_not_now"))
                    compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
                compose.onNodeWithTag("rules_updated_not_now").performClick()
                compose.waitUntil(5_000) { compose.onAllNodesWithTag("rules_updated").fetchSemanticsNodes().isEmpty() }
                compose.onNodeWithTag("rules_banner").assertIsDisplayed()
                compose.onNodeWithTag("conversation_composer").assertIsDisplayed()
                if (fontScale == 2f) {
                    capture("rules-$label-$name-banner", "rules_host")
                    val review = compose.onNodeWithTag("rules_banner_review").fetchSemanticsNode()
                    val min = with(review.layoutInfo.density) { 48.dp.toPx() } - 0.5f
                    assertTrue("Review has a 48dp target", review.touchBoundsInRoot.height >= min && review.touchBoundsInRoot.width >= min)
                    // J1 (PR #276): there's no send-while-hidden step (TL). Under every connection-loss level-2 notice, Send
                    // is off or deferred by existing design: the room is stale or the session isn't ready, and Offline defers
                    // a text draft (S4a). Unreachable is the one theoretical exception, gated in the real app by room
                    // presence. The 403 -> dialog path is proven by RulesUpdateJourneyTest.
                    // While the slot shows a level-2 notice the strip yields, so the transcript and composer keep their room at
                    // 200%; it returns when the notice clears.
                    compose.runOnIdle { realtime.state.value = realtime.state.value.copy(phase = RealtimePhase.OFFLINE) }
                    compose.waitUntil(5_000) { compose.onAllNodesWithTag("status_slot").fetchSemanticsNodes().isNotEmpty() }
                    compose.onNodeWithTag("rules_banner").assertDoesNotExist()
                    compose.onNodeWithText("Fixture").assertIsDisplayed()
                    compose.onNodeWithTag("conversation_composer").assertIsDisplayed()
                    capture("rules-$label-$name-banner-offline", "rules_host")
                    compose.runOnIdle { realtime.state.value = realtime.state.value.copy(phase = RealtimePhase.LIVE) }
                    compose.waitUntil(5_000) { compose.onAllNodesWithTag("rules_banner").fetchSemanticsNodes().isNotEmpty() }
                    compose.onNodeWithTag("rules_banner").assertIsDisplayed()
                }
                compose.runOnIdle { model.cancel(); directory.cancel() }
            }
        }
    }

    private fun text(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    private fun capture(name: String, node: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let { java.io.File(it) }
            ?: checkNotNull(instrumentation.targetContext.getExternalFilesDir(null))
        val folder = java.io.File(output, "ccm-rules-presentation")
        check(folder.isDirectory || folder.mkdirs())
        val bitmap = compose.onNodeWithTag(node).captureToImage().asAndroidBitmap()
        try {
            java.io.File(folder, "$name.png").outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }
}

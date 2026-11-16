package io.github.thiesi.chitchat.app

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performFirstLinkClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import io.github.thiesi.chitchat.core.network.Lockdown
import io.github.thiesi.chitchat.core.network.SessionUser
import io.github.thiesi.chitchat.core.policies.PolicyDiscovery
import io.github.thiesi.chitchat.core.policies.PolicyLink
import io.github.thiesi.chitchat.core.policies.RulesAcceptResult
import io.github.thiesi.chitchat.core.policies.RulesOperations
import io.github.thiesi.chitchat.core.policies.RulesStatus
import io.github.thiesi.chitchat.core.server.AddressResult
import io.github.thiesi.chitchat.core.server.ServerAddress
import io.github.thiesi.chitchat.core.session.PublicSession
import io.github.thiesi.chitchat.core.session.SessionPhase
import io.github.thiesi.chitchat.core.session.SessionState
import io.github.thiesi.chitchat.core.settings.AppSettings
import io.github.thiesi.chitchat.core.settings.GuidelinesAcceptance
import io.github.thiesi.chitchat.core.settings.GuidelinesAcceptanceStore
import io.github.thiesi.chitchat.core.settings.SettingsStore
import io.github.thiesi.chitchat.core.settings.ThemePreference
import io.github.thiesi.chitchat.core.theme.ChitChatTheme
import io.github.thiesi.chitchat.feature.account.TestChitChatApp
import io.github.thiesi.chitchat.feature.auth.AuthViewModel
import io.github.thiesi.chitchat.feature.auth.TestSessions
import io.github.thiesi.chitchat.feature.policies.GuidelinesViewModel
import io.github.thiesi.chitchat.testing.FontScaleTestActivity
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * "Before you chat" (O3b2) at a real Configuration font scale, light and dark: without and with server rules (the
 * consent line while acceptance is required), the 409 line, and the Create form's consent line. Captures for J, every
 * action reachable by scrolling, 48dp document rows. No network.
 */
class GuidelinesPresentationTest {
    @get:Rule val compose = createEmptyComposeRule()
    @After fun reset() { FontScaleTestActivity.requestedFontScale = 1f }

    private val server = (ServerAddress.parse("chat.example.test") as AddressResult.Valid).address
    private val policies = PolicyDiscovery(PolicyLink("https://chat.example.test/rules", 4, "2026-11-08T12:00:00+00:00"),
        PolicyLink("https://chat.example.test/privacy", null, "2026-11-08T12:00:00+00:00"), null)
    private fun session(withRules: Boolean) = SessionState(server = server, generation = 1, phase = SessionPhase.AUTHENTICATED,
        user = SessionUser(7, "Viewer", listOf("member"), 1, false), installationName = "The Common Room",
        public = PublicSession(true, emptyList(), Lockdown(false), policies = if (withRules) policies else PolicyDiscovery(null, null, null)),
        rules = if (withRules) RulesStatus(1, null, true) else null)
    private val store = object : GuidelinesAcceptanceStore {
        override val accepted = MutableStateFlow<Set<GuidelinesAcceptance>>(emptySet())
        override suspend fun record(acceptance: GuidelinesAcceptance) = Unit
        override suspend fun forgetServer(origin: String) = Unit
    }
    private fun rules(state: SessionState) = object : RulesOperations {
        override val state = MutableStateFlow(state)
        override suspend fun acceptRules(version: Int, expected: SessionState) = RulesAcceptResult.Outdated
        override suspend fun refresh() = Unit
    }

    private val documents = io.github.thiesi.chitchat.feature.policies.PolicyDocumentViewModel(object : io.github.thiesi.chitchat.core.policies.PolicyReads {
        override suspend fun policyDocument(name: io.github.thiesi.chitchat.core.policies.PolicyDocumentName, expected: SessionState) =
            io.github.thiesi.chitchat.core.policies.PolicyDocument.NotPublished
    })

    @Test fun presentationLight() = presentation(dark = false)
    @Test fun presentationDark() = presentation(dark = true)

    private fun presentation(dark: Boolean) {
        val label = if (dark) "dark" else "light"
        for ((name, fontScale) in listOf("default" to 1f, "font200" to 2f)) {
            for (scene in listOf("plain", "rules", "outdated")) {
                FontScaleTestActivity.requestedFontScale = fontScale
                val current = session(withRules = scene != "plain")
                val model = GuidelinesViewModel(rules(current), store)
                ActivityScenario.launch(FontScaleTestActivity::class.java).use { scenario ->
                    scenario.onActivity { activity ->
                        assertEquals("A real Configuration font scale", fontScale, activity.resources.configuration.fontScale)
                        activity.setContent { ChitChatTheme(dark = dark) { Box(Modifier.fillMaxSize().testTag("guidelines_host")) {
                            GuidelinesScreen(current, server.value, model, documents = null, onSignOut = {})
                        } } }
                    }
                    if (scene == "outdated") {
                        compose.onNodeWithTag("guidelines_agree").performScrollTo().performClick()
                        compose.waitUntil(5_000) { model.state.value.outdated }
                    }
                    capture("guidelines-$label-$name-$scene")
                    val actions = listOf("guidelines_agree", "guidelines_sign_out") +
                        if (scene != "plain") listOf("guidelines_rules_row", "guidelines_privacy_row") else emptyList()
                    val small = actions.mapNotNull { tag -> compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed(); undersized(tag) }
                    assertTrue("A-floor: 48dp targets ($label, $name, $scene): $small", small.isEmpty())
                    if (scene != "plain") compose.onNodeWithTag("guidelines_consent").performScrollTo().assertIsDisplayed()
                    compose.onNodeWithTag("guidelines_sign_out").performScrollTo()
                    capture("guidelines-$label-$name-$scene-actions")
                }
            }
        }
    }

    /** The Create form's consent line (rules and privacy) above Create my account, through the real onboarding flow. */
    @Test fun createFormConsentLine() {
        for (dark in listOf(false, true)) for ((name, fontScale) in listOf("default" to 1f, "font200" to 2f)) {
            val label = if (dark) "dark" else "light"
            FontScaleTestActivity.requestedFontScale = fontScale
            val sessions = TestSessions().apply {
                registrationEnabled = true
                nextConnect = { it.copy(installationName = "The Common Room", public = it.public!!.copy(policies = policies)) }
            }
            val settings = SettingsViewModel(object : SettingsStore {
                override val settings = MutableStateFlow(AppSettings(theme = if (dark) ThemePreference.DARK else ThemePreference.LIGHT))
                override suspend fun saveServer(server: ServerAddress?) { settings.value = settings.value.copy(server = server) }
                override suspend fun savePureBlack(enabled: Boolean) = Unit
                override suspend fun saveTheme(theme: ThemePreference) = Unit
            })
            val auth = AuthViewModel(sessions)
            ActivityScenario.launch(FontScaleTestActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.setContent { Box(Modifier.fillMaxSize().testTag("guidelines_host")) {
                        TestChitChatApp(settings, auth, policies = documents)
                    } }
                }
                compose.waitUntil(5_000) { !settings.state.value.loading }
                compose.onNodeWithTag("onboarding_welcome_start").performScrollTo().performClick()
                compose.onNodeWithTag("onboarding_server_input").performTextReplacement("chat.example.test")
                Espresso.closeSoftKeyboard()
                compose.onNodeWithTag("onboarding_continue").performScrollTo().performClick()
                compose.onNodeWithTag("onboarding_create").performScrollTo().performClick()
                compose.onNodeWithTag("registration_rules_consent").performScrollTo().assertIsDisplayed()
                // Both linked phrases are real links (the production root always supplies the document screen).
                val text = compose.onNodeWithTag("registration_rules_consent").fetchSemanticsNode()
                    .config[androidx.compose.ui.semantics.SemanticsProperties.Text].single()
                assertEquals(listOf("registration_rules_link", "registration_privacy_link"),
                    text.getLinkAnnotations(0, text.length).map { (it.item as androidx.compose.ui.text.LinkAnnotation.Clickable).tag })
                capture("guidelines-$label-$name-create")
                if (!dark && fontScale == 1f) {
                    // Reading the rules from the consent line keeps what was typed: the document opens over the form.
                    compose.onNodeWithTag("registration_password").performScrollTo().performTextReplacement("fixture-password")
                    Espresso.closeSoftKeyboard()
                    compose.onNodeWithTag("registration_rules_consent").performScrollTo().performFirstLinkClick { range ->
                        (range.item as? androidx.compose.ui.text.LinkAnnotation.Clickable)?.tag == "registration_rules_link"
                    }
                    compose.onNodeWithTag("policy_document_dialog").assertIsDisplayed()
                    compose.waitUntil(5_000) {
                        compose.onAllNodes(androidx.compose.ui.test.hasTestTag("policy_not_published")).fetchSemanticsNodes().isNotEmpty()
                    }
                    compose.onNodeWithTag("policy_document_dialog_back").performClick()
                    compose.onNodeWithTag("policy_document_dialog").assertDoesNotExist()
                    val kept = compose.onNodeWithTag("registration_password").fetchSemanticsNode()
                        .config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text
                    assertEquals("The password is kept after reading the rules", "fixture-password", kept)
                }
            }
        }
    }

    private fun undersized(tag: String): String? {
        val node = compose.onNodeWithTag(tag).fetchSemanticsNode()
        val density = node.layoutInfo.density
        val min = with(density) { 48.dp.toPx() } - 0.5f
        val touch = node.touchBoundsInRoot.let { it.width to it.height }
        return if (touch.first >= min && touch.second >= min) null else "$tag touch=${touch.first}x${touch.second}px"
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let { java.io.File(it) }
            ?: checkNotNull(instrumentation.targetContext.getExternalFilesDir(null))
        val folder = java.io.File(output, "ccm-guidelines-presentation")
        check(folder.isDirectory || folder.mkdirs())
        val bitmap = compose.onNodeWithTag("guidelines_host").captureToImage().asAndroidBitmap()
        try {
            java.io.File(folder, "$name.png").outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }
}

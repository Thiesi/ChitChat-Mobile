package io.github.thiesi.chitchat.app

import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.thiesi.chitchat.R
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
import io.github.thiesi.chitchat.core.settings.GuidelinesAcceptance
import io.github.thiesi.chitchat.core.settings.GuidelinesAcceptanceStore
import io.github.thiesi.chitchat.core.theme.ChitChatTheme
import io.github.thiesi.chitchat.feature.auth.AuthViewModel
import io.github.thiesi.chitchat.feature.auth.TestSessions
import io.github.thiesi.chitchat.feature.policies.GuidelinesViewModel
import io.github.thiesi.chitchat.testing.FontScaleTestActivity
import io.github.thiesi.chitchat.testing.performNativeTap
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The app's own privacy policy link (P-174; TL, PL 2026-11-24): "The app's privacy policy" in About and privacy (after
 * the app's items, before the server's documents) and as a text link on "Before you chat" (after the operator line),
 * "Privacy policy" at Welcome's foot. Always shown, whatever the server publishes; a tap opens only the explicit choice
 * naming the fixed host. Captures `app-privacy-*` for the PL's J (taken before asserts). No network.
 */
class AppPrivacyPresentationTest {
    @get:Rule val compose = createEmptyComposeRule()
    @After fun reset() { FontScaleTestActivity.requestedFontScale = 1f }
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val server = (ServerAddress.parse("chat.example.test") as AddressResult.Valid).address
    private val documents = PolicyDiscovery(PolicyLink("https://chat.example.test/rules", 4, "2026-11-08T12:00:00+00:00"),
        PolicyLink("https://chat.example.test/privacy", null, "2026-11-08T12:00:00+00:00"), null)

    /** "ChitChat" on purpose: the app's label must still differ from "{installation}'s privacy policy" (PL). */
    private fun session(withDocuments: Boolean) = SessionState(server = server, generation = 1, phase = SessionPhase.AUTHENTICATED,
        user = SessionUser(7, "Viewer", listOf("member"), 1, false), installationName = "ChitChat",
        public = PublicSession(true, emptyList(), Lockdown(false),
            policies = if (withDocuments) documents else PolicyDiscovery(null, null, null)),
        rules = if (withDocuments) RulesStatus(1, null, true) else null)

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

    private fun launch(fontScale: Float, content: @androidx.compose.runtime.Composable () -> Unit): ActivityScenario<FontScaleTestActivity> {
        FontScaleTestActivity.requestedFontScale = fontScale
        val scenario = ActivityScenario.launch(FontScaleTestActivity::class.java)
        scenario.onActivity { activity ->
            assertEquals("A real Configuration font scale", fontScale, activity.resources.configuration.fontScale)
            activity.setContent { content() }
        }
        return scenario
    }

    private val scales = listOf("default" to 1f, "font200" to 2f)

    /** About and privacy with the server's privacy policy published: both rows, the app's first (PL order). */
    @Test fun aboutShowsTheAppsRowBeforeTheServersDocuments() {
        for (dark in listOf(false, true)) for ((name, fontScale) in scales) {
            val label = if (dark) "dark" else "light"
            val current = session(withDocuments = true)
            val auth = AuthViewModel(TestSessions()) // Outside composition (lint).
            launch(fontScale) {
                ChitChatTheme(dark = dark) { androidx.compose.material3.Surface(Modifier.fillMaxSize(),
                    color = androidx.compose.material3.MaterialTheme.colorScheme.background) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
                    YouScreen(current, auth, server.value, pushAvailable = false, onNotifications = {},
                        onAppearance = {}, onAccount = {}, onChangeServer = {}, onLicences = {}, onPolicy = {})
                } } }
            }.use {
                compose.onNodeWithTag("you_about").performScrollTo()
                capture("app-privacy-about-$label-$name", "you_about")
                val app = top("app_privacy_row")
                val server = top("you_privacy_policy")
                assertTrue("The app's row before the server's privacy policy ($app, $server)", app < server)
                assertTrue("After Open-source licences", top("licences_open") < app)
                assertHeight("app_privacy_row")
            }
        }
    }

    /** "Before you chat" with the server's rules and privacy policy: the app's text link after the operator line. */
    @Test fun guidelinesShowTheLinkAfterTheOperatorLine() {
        val failures = mutableListOf<Throwable>()
        for (dark in listOf(false, true)) for ((name, fontScale) in scales)
            runCatching { guidelines(dark, name, fontScale, withDocuments = true) }.exceptionOrNull()?.let(failures::add)
        runCatching { guidelines(dark = false, "default", 1f, withDocuments = false) }.exceptionOrNull()?.let(failures::add)
        failures.firstOrNull()?.let { throw AssertionError("${failures.size} case(s) failed; first: ${it.message}", it) }
    }

    private fun guidelines(dark: Boolean, name: String, fontScale: Float, withDocuments: Boolean) {
        val label = if (dark) "dark" else "light"
        val current = session(withDocuments)
        val model = GuidelinesViewModel(rules(current), store)
        launch(fontScale) {
            ChitChatTheme(dark = dark) { Box(Modifier.fillMaxSize().testTag("app_privacy_host")
                .background(androidx.compose.material3.MaterialTheme.colorScheme.background)) {
                GuidelinesScreen(current, server.value, model, documents = null, onSignOut = {})
            } }
        }.use {
            compose.onNodeWithTag("guidelines_app_privacy").performScrollTo()
            capture(if (withDocuments) "app-privacy-guidelines-$label-$name" else "app-privacy-guidelines-plain-$label-$name",
                "app_privacy_host")
            assertTrue("After the operator line", top("guidelines_operator") < top("guidelines_app_privacy"))
            assertTrue("Before Agree", top("guidelines_app_privacy") < top("guidelines_agree"))
            assertEquals(context.getString(R.string.app_privacy_policy), text("guidelines_app_privacy"))
            assertHeight("guidelines_app_privacy")
        }
    }

    /** Welcome (always dark) with "Privacy policy" at its foot; no accessibility-feedback footer there (TL). */
    @Test fun welcomeHasTheFooterLink() {
        for ((name, fontScale) in scales) {
            // The real app on its real surface (PL): Welcome as onboarding renders it, in the dark theme.
            val settings = SettingsViewModel(object : io.github.thiesi.chitchat.core.settings.SettingsStore {
                override val settings = MutableStateFlow(io.github.thiesi.chitchat.core.settings.AppSettings(
                    theme = io.github.thiesi.chitchat.core.settings.ThemePreference.DARK))
                override suspend fun saveServer(server: ServerAddress?) { settings.value = settings.value.copy(server = server) }
                override suspend fun savePureBlack(enabled: Boolean) = Unit
                override suspend fun saveTheme(theme: io.github.thiesi.chitchat.core.settings.ThemePreference) = Unit
            })
            val auth = AuthViewModel(TestSessions())
            launch(fontScale) {
                Box(Modifier.fillMaxSize().testTag("app_privacy_host")) {
                    io.github.thiesi.chitchat.feature.account.TestChitChatApp(settings, auth)
                }
            }.use {
                compose.waitUntil(5_000) { !settings.state.value.loading }
                compose.onNodeWithTag("welcome_app_privacy").performScrollTo()
                capture("app-privacy-welcome-dark-$name", "app_privacy_host")
                assertEquals(context.getString(R.string.app_privacy_policy_short), text("welcome_app_privacy"))
                assertTrue("Below Get started", top("onboarding_welcome_start") < top("welcome_app_privacy"))
                assertTrue("No accessibility-feedback footer on Welcome", compose.onAllNodesWithTag("a11y_feedback").fetchSemanticsNodes().isEmpty())
                assertHeight("welcome_app_privacy")
            }
        }
    }

    /** A tap opens only the choice, which names the fixed host; the URL is the constant, never a server value. */
    @Test fun aTapOpensOnlyTheChoiceNamingTheFixedHost() {
        assertEquals("https://ChitChat.NetWorkXXIII.de/app/privacy", APP_PRIVACY_URL)
        for ((name, fontScale) in scales) {
            val current = session(withDocuments = false)
            val auth = AuthViewModel(TestSessions()) // Outside composition (lint).
            launch(fontScale) {
                ChitChatTheme(dark = false) { Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
                    YouScreen(current, auth, server.value, pushAvailable = false, onNotifications = {},
                        onAppearance = {}, onAccount = {}, onChangeServer = {}, onLicences = {}, onPolicy = {})
                } }
            }.use { scenario ->
                var resumedBefore: Any? = null
                scenario.onActivity { resumedBefore = it }
                compose.onNodeWithTag("app_privacy_row").performScrollTo().performNativeTap()
                compose.waitUntil(5_000) { compose.onAllNodesWithTag("app_privacy_dialog").fetchSemanticsNodes().isNotEmpty() }
                captureDialog("app-privacy-choice-light-$name")
                val words = compose.onAllNodes(hasAnyAncestor(hasTestTag("app_privacy_body")), useUnmergedTree = true)
                    .fetchSemanticsNodes().flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.joinToString(" ") { it.text }
                assertTrue("The choice names the fixed host: $words", "chitchat.networkxxiii.de" in words.lowercase())
                var resumedAfter: Any? = null
                scenario.onActivity { resumedAfter = it }
                assertTrue("Nothing opened by itself: the same activity is still resumed", resumedBefore === resumedAfter)
                assertTrue("Shown without any server documents (this session publishes none)",
                    compose.onAllNodesWithTag("you_privacy_policy").fetchSemanticsNodes().isEmpty())
            }
        }
    }

    /** PL: the label differs from "{installation}'s privacy policy" even for an installation named ChitChat. */
    @Test fun theLabelNeverCollidesWithAServersPolicyTitle() {
        val app = context.getString(R.string.app_privacy_policy)
        assertNotEquals(context.getString(R.string.policy_privacy_title, "ChitChat"), app)
    }

    private fun node(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()
    /** The unclipped layout position (a node scrolled out of view has empty window bounds). */
    private fun top(tag: String) = node(tag).positionInRoot.y
    /** The link's merged text (the clickable box merges its child Text). */
    private fun text(tag: String) =
        compose.onNodeWithTag(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }
    private fun assertHeight(tag: String) {
        val density = context.resources.displayMetrics.density
        assertTrue("$tag is at least 48dp", node(tag).size.height >= 48 * density - 1)
    }

    private fun capture(name: String, tag: String) = save(name, compose.onNodeWithTag(tag))
    private fun captureDialog(name: String) = save(name, compose.onNode(isDialog()))

    private fun save(name: String, target: SemanticsNodeInteraction) {
        compose.waitForIdle()
        val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let { java.io.File(it) }
            ?: checkNotNull(context.getExternalFilesDir(null))
        val folder = java.io.File(output, "ccm-app-privacy-presentation")
        check(folder.isDirectory || folder.mkdirs())
        val bitmap = target.captureToImage().asAndroidBitmap()
        try { java.io.File(folder, "$name.png").outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
    }
}

package io.github.thiesi.chitchat.feature.auth

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.thiesi.chitchat.app.SettingsViewModel
import io.github.thiesi.chitchat.core.policies.PolicyDiscovery
import io.github.thiesi.chitchat.core.policies.PolicyLink
import io.github.thiesi.chitchat.core.server.AddressResult
import io.github.thiesi.chitchat.core.server.ServerAddress
import io.github.thiesi.chitchat.core.settings.AppSettings
import io.github.thiesi.chitchat.core.settings.SettingsStore
import io.github.thiesi.chitchat.core.settings.ThemePreference
import io.github.thiesi.chitchat.feature.account.TestChitChatApp
import io.github.thiesi.chitchat.testing.FontScaleTestActivity
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * #390 (owner's amended rule, 2026-11-28; PL placement; TL and PL review-key ruling): at 200% in a 320×260 dp window the
 * consent line (168 px) is taller than the viewport can show with Create (line + 12dp + 48dp > 148 px). The first tap on
 * Create brings the line's top to 8dp below the viewport's top, announces once and doesn't submit; a later tap submits.
 * The review mark resets when the agreement changes (the sentence, or the rules document's identity or version) or the
 * form leaves composition. #275's fits branch is covered, unchanged, by AuthPinnedSubmitJourneyTest. The line is measured
 * in screen_viewport's own layout coordinates, never window positions (#372).
 */
class ConsentLineReviewJourneyTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val density = instrumentation.targetContext.resources.displayMetrics.density
    private val rules = PolicyDiscovery(PolicyLink("https://chat.example.test/rules", 4, "2026-11-08T12:00:00+00:00"), null, null)
    private val sessions = TestSessions().apply { registrationEnabled = true }
    private val auth = AuthViewModel(sessions)
    private val settings = SettingsViewModel(object : SettingsStore {
        override val settings = MutableStateFlow(AppSettings(server = (ServerAddress.parse("chat.example.test") as AddressResult.Valid).address))
        override suspend fun saveServer(server: ServerAddress?) { settings.value = settings.value.copy(server = server) }
        override suspend fun savePureBlack(enabled: Boolean) = Unit
        override suspend fun saveTheme(theme: ThemePreference) = Unit
    })
    @After fun reset() { FontScaleTestActivity.requestedFontScale = 1f }

    private fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    /**
     * The line in the viewport from layout coordinates: (top, bottom, viewport height). screen_viewport's innermost
     * coordinates are inside its verticalScroll and the 24dp padding after it (ChitChatApp), so the scrolled content's
     * origin is 24dp above them and the content 48dp taller (#372 probe (d)).
     */
    private fun line(): Triple<Float, Float, Float> {
        compose.waitForIdle()
        val viewport = compose.onNodeWithTag("screen_viewport", useUnmergedTree = true).fetchSemanticsNode()
        val range = viewport.config[SemanticsProperties.VerticalScrollAxisRange]
        val content = viewport.layoutInfo.coordinates
        val pad = 24f * density
        val line = compose.onNodeWithTag("registration_rules_consent", useUnmergedTree = true).fetchSemanticsNode().layoutInfo.coordinates
        val top = pad + content.localPositionOf(line, Offset.Zero).y - range.value()
        return Triple(top, top + line.size.height, content.size.height + 2 * pad - range.maxValue())
    }

    /** Open Create, fill it and hide the keyboard (Done): the block is in flow on this short window. */
    private fun openFilledForm(scenario: ActivityScenario<FontScaleTestActivity>) {
        scenario.onActivity { it.setContent { Box(Modifier.size(320.dp, 260.dp).testTag("consent_host")) { TestChitChatApp(settings, auth) } } }
        compose.waitUntil(5_000) { !settings.state.value.loading }
        compose.onNodeWithTag("onboarding_create").performScrollTo().performClick()
        fill()
    }

    private fun fill() {
        compose.onNodeWithTag("registration_username").performScrollTo().performTextInput("Alice")
        compose.onNodeWithTag("registration_password").performScrollTo().performTextInput("fixture-password")
        compose.onNodeWithTag("registration_confirmation").performScrollTo().performTextInput("fixture-password")
        compose.onNodeWithTag("registration_confirmation").performImeAction()
        compose.waitUntil(5_000) { exists("registration_submit") }
        assertTrue("The block is in flow on this window", !exists("pinned_action"))
    }

    private fun tapCreate() {
        compose.onNodeWithTag("registration_submit").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
    }

    private fun announcements() = compose.onAllNodesWithTag("registration_review_announcement").fetchSemanticsNodes().size

    /** The first tap's result: no submit, one announcement, the line's top 8dp under the viewport's top (±1 px). */
    private fun assertBroughtTop(why: String) {
        val (top, bottom, viewport) = line()
        assertTrue("$why: can't fit (line ${bottom - top} + 60dp > viewport $viewport)", bottom - top + 60f * density > viewport)
        assertEquals("$why: the line's top 8dp under the viewport's top (line $top..$bottom, viewport $viewport)", 8f * density, top, 1f)
        assertEquals("$why: not submitted", 0, sessions.registrations)
        assertEquals("$why: announced once", 1, announcements())
    }

    private fun launch(body: (ActivityScenario<FontScaleTestActivity>) -> Unit) {
        sessions.nextConnect = { it.copy(installationName = "The Common Room", public = it.public!!.copy(policies = rules)) }
        FontScaleTestActivity.requestedFontScale = 2f
        ActivityScenario.launch(FontScaleTestActivity::class.java).use(body)
    }

    @Test fun theFirstTapBringsTheLineTopAndAnnouncesOnceThenALaterTapSubmits() = launch { scenario ->
        openFilledForm(scenario)
        capture("auth-light-font200-consent-before-first-tap")
        tapCreate()
        assertBroughtTop("First tap")
        capture("auth-light-font200-consent-after-first-tap")
        compose.onNodeWithTag("registration_submit").performScrollTo()
        capture("auth-light-font200-consent-scrolled-to-create")
        tapCreate()
        compose.waitUntil(5_000) { sessions.registrations == 1 }
        assertEquals("A later tap submits, once", 1, sessions.registrations)
        // What the person sees next in this fixture: a new account with no recovery key to show, so "Find your people"
        // (O2b). The guidelines gate isn't wired into TestChitChatApp (its guidelines model is null).
        compose.waitUntil(5_000) { exists("find_people") }
        capture("auth-light-font200-consent-submitted-find-your-people")
    }

    @Test fun aChangedInstallationNameResetsTheReview() = launch { scenario ->
        openFilledForm(scenario)
        tapCreate()
        assertBroughtTop("First tap")
        sessions.state.value = sessions.state.value.copy(installationName = "The Other Room") // A new sentence.
        compose.waitForIdle()
        tapCreate()
        assertBroughtTop("After the sentence changed, the next tap brings again")
    }

    @Test fun aNewRulesVersionWithTheSameSentenceResetsTheReview() = launch { scenario ->
        openFilledForm(scenario)
        tapCreate()
        assertBroughtTop("First tap")
        val current = sessions.state.value
        sessions.state.value = current.copy(public = current.public!!.copy(policies =
            rules.copy(rules = PolicyLink("https://chat.example.test/rules", 5, "2026-11-28T12:00:00+00:00"))))
        compose.waitForIdle()
        tapCreate()
        assertBroughtTop("After a new rules version, the next tap brings again")
    }

    @Test fun leavingTheFormResetsTheReview() = launch { scenario ->
        openFilledForm(scenario)
        tapCreate()
        assertBroughtTop("First tap")
        scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntil(5_000) { exists("onboarding_create") && !exists("registration_submit") }
        compose.onNodeWithTag("onboarding_create").performScrollTo().performClick()
        compose.waitUntil(5_000) { exists("registration_username") }
        if (!exists("registration_submit")) fill()
        tapCreate()
        assertBroughtTop("After leaving and returning, the first tap brings again")
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let { java.io.File(it) }
            ?: checkNotNull(instrumentation.targetContext.getExternalFilesDir(null))
        val folder = java.io.File(output, "ccm-auth-presentation")
        check(folder.isDirectory || folder.mkdirs())
        val bitmap = compose.onNodeWithTag("consent_host").captureToImage().asAndroidBitmap()
        try {
            java.io.File(folder, "$name.png").outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }
}

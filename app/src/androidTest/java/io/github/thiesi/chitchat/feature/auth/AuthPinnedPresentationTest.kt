package io.github.thiesi.chitchat.feature.auth

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
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
import io.github.thiesi.chitchat.testing.tapNativeInput
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * #275 for J, at a real Configuration font scale, light and dark: Sign in and Create with the keyboard up (default and
 * 200%), and Create with the keyboard down at 200%, showing the pinned consent block. No network.
 */
class AuthPinnedPresentationTest {
    @get:Rule val compose = createEmptyComposeRule()
    @After fun reset() { FontScaleTestActivity.requestedFontScale = 1f }
    @Volatile private var imeBottom = 0
    // #351: where the keyboard's inset animation is headed. A capture with the keyboard up waits until the inset has
    // arrived there (a bounded positive condition), not for a time: Compose is idle between the platform's inset frames,
    // so waitForIdle alone could capture the form one inset frame early (the 1px shift).
    @Volatile private var imeTarget = 0
    // B (#351, P-78): the label rule's margin and the floating label's line height, in px, from the composition.
    @Volatile private var marginPx = 0f
    @Volatile private var bandPx = 0f
    private val rules = PolicyDiscovery(PolicyLink("https://chat.example.test/rules", 4, "2026-11-08T12:00:00+00:00"), null, null)

    @Test fun presentationLight() = presentation(dark = false)
    @Test fun presentationDark() = presentation(dark = true)

    private fun presentation(dark: Boolean) {
        val label = if (dark) "dark" else "light"
        for ((name, fontScale) in listOf("default" to 1f, "font200" to 2f)) for (form in listOf("signin", "create")) {
            FontScaleTestActivity.requestedFontScale = fontScale
            val sessions = TestSessions().apply {
                registrationEnabled = true
                nextConnect = { it.copy(installationName = "The Common Room", public = it.public!!.copy(policies = rules)) }
            }
            val auth = AuthViewModel(sessions)
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
                        val bottom = WindowInsets.ime.getBottom(LocalDensity.current)
                        val target = imeTargetBottom()
                        val margin = with(LocalDensity.current) { io.github.thiesi.chitchat.core.ui.FormDialogLabelMargin.toPx() }
                        val band = with(LocalDensity.current) { androidx.compose.material3.MaterialTheme.typography.bodySmall.lineHeight.toPx() }
                        SideEffect { imeBottom = bottom; imeTarget = target; marginPx = margin; bandPx = band }
                        // #350/#351: the caret drawn steadily (no blink phase) in the keyboard-up captures, test-only.
                        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalCursorBlinkEnabled provides false) {
                            Box(Modifier.fillMaxSize().testTag("auth_host")) { TestChitChatApp(settings, auth) }
                        }
                    }
                }
                compose.waitUntil(5_000) { !settings.state.value.loading }
                if (form == "signin") {
                    compose.onNodeWithTag("onboarding_sign_in").performScrollTo().performClick()
                    compose.onNodeWithTag("sign_in_password").performScrollTo()
                    compose.tapNativeInput("sign_in_password", imeBottom = { imeBottom })
                    compose.onNodeWithTag("sign_in_submit").assertIsDisplayed()
                    capture("auth-$label-$name-signin-keyboard")
                    assertLabelRule("sign_in_password", "$label-$name signin keyboard")
                } else {
                    compose.onNodeWithTag("onboarding_create").performScrollTo().performClick()
                    if (fontScale == 2f) {
                        // Deterministic for this fixture (rules only, full-screen host): pinned even at 200% (TL: assert, don't branch).
                        assertEquals("Create block pinned", true, compose.onAllNodesWithTag("pinned_action").fetchSemanticsNodes().isNotEmpty())
                        compose.onNodeWithTag("registration_rules_consent").assertIsDisplayed()
                        capture("auth-$label-$name-create-block")
                    }
                    compose.onNodeWithTag("registration_username").performScrollTo()
                    compose.tapNativeInput("registration_username", imeBottom = { imeBottom })
                    capture("auth-$label-$name-create-keyboard")
                    assertLabelRule("registration_username", "$label-$name create keyboard")
                    // B, a focus move in a steady view (PL condition 5): Next from the password to the confirmation (the
                    // same keyboard, so the viewport keeps its height) keeps Compose's animation and lands by the same rule.
                    compose.onNodeWithTag("registration_username").performImeAction()
                    settledKeyboard()
                    val steady = viewportHeight()
                    compose.onNodeWithTag("registration_password").performImeAction()
                    settledKeyboard()
                    assertEquals("A steady viewport across the focus move", steady, viewportHeight())
                    assertLabelRule("registration_confirmation", "$label-$name create next")
                }
            }
        }
    }

    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
    @androidx.compose.runtime.Composable
    private fun imeTargetBottom(): Int = WindowInsets.imeAnimationTarget.getBottom(LocalDensity.current)

    private fun settledKeyboard() {
        compose.waitUntil(5_000) { imeBottom > 0 && imeBottom == imeTarget }
        compose.waitForIdle()
    }

    private fun viewportHeight(): Int =
        compose.onNodeWithTag("screen_viewport", useUnmergedTree = true).fetchSemanticsNode().size.height

    /**
     * B's end state (#351, P-78, PL's four tiers): the tier that applies to the measured field and viewport, and its
     * placement. 1: both margins (the label's top and the field's bottom each the margin or more inside); 2: the top
     * margin, the bottom inside; 3: the field whole; 4: the label entirely in or entirely out, the bottom inside. Measured
     * unclipped: the field's tagged node starts at the label's top (M3 pads the field by half the label above its border)
     * and ends at its supporting line, in the screen scroller's viewport.
     */
    private fun assertLabelRule(tag: String, step: String) {
        compose.waitForIdle()
        val viewport = compose.onNodeWithTag("screen_viewport", useUnmergedTree = true).fetchSemanticsNode()
        val field = compose.onNodeWithTag(tag).fetchSemanticsNode()
        val top = field.positionInRoot.y - viewport.positionInRoot.y
        val bottom = top + field.size.height
        val v = viewport.size.height.toFloat()
        val height = field.size.height
        val tier = when {
            height + 2 * marginPx <= v + 1f -> 1
            height + marginPx <= v + 1f -> 2
            height <= v + 1f -> 3
            else -> 4
        }
        val facts = "$step $tag: tier=$tier labelTop=$top fieldBottom=$bottom viewport=$v margin=$marginPx band=$bandPx"
        when (tier) {
            1 -> assertTrue("Tier 1: both margins ($facts)", top >= marginPx - 1f && bottom <= v - marginPx + 1f)
            2 -> assertTrue("Tier 2: the top margin, the bottom inside ($facts)", top >= marginPx - 1f && bottom <= v + 1f)
            3 -> assertTrue("Tier 3: the field whole ($facts)", top >= -1f && bottom <= v + 1f)
            else -> assertTrue("Tier 4: the label entirely in or out ($facts)", top >= -1f || top <= -bandPx + 1f)
        }
    }

    /** #351 DIAGNOSTIC (uncommitted): opt-in by `-e scrollSettleDiagnostic auth`; observation only. */
    @Test fun scrollSettleDiagnostic() {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("scrollSettleDiagnostic") == "auth")
        val tags = listOf("registration_username")
        val reps = org.json.JSONArray()
        for (rep in 0 until 40) {
            FontScaleTestActivity.requestedFontScale = 2f
            val sessions = TestSessions().apply {
                registrationEnabled = true
                nextConnect = { it.copy(installationName = "The Common Room", public = it.public!!.copy(policies = rules)) }
            }
            val auth = AuthViewModel(sessions)
            val settings = SettingsViewModel(object : SettingsStore {
                override val settings = MutableStateFlow(AppSettings(server = (ServerAddress.parse("chat.example.test") as AddressResult.Valid).address,
                    theme = ThemePreference.LIGHT))
                override suspend fun saveServer(server: ServerAddress?) { settings.value = settings.value.copy(server = server) }
                override suspend fun savePureBlack(enabled: Boolean) = Unit
                override suspend fun saveTheme(theme: ThemePreference) = Unit
            })
            ActivityScenario.launch(FontScaleTestActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.setContent {
                        val bottom = WindowInsets.ime.getBottom(LocalDensity.current)
                        val target = imeTargetBottom()
                        SideEffect { imeBottom = bottom; imeTarget = target }
                        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalCursorBlinkEnabled provides false) {
                            Box(Modifier.fillMaxSize().testTag("auth_host")) { TestChitChatApp(settings, auth) }
                        }
                    }
                }
                compose.waitUntil(5_000) { !settings.state.value.loading }
                compose.onNodeWithTag("onboarding_create").performScrollTo().performClick()
                assertEquals("Create block pinned", true, compose.onAllNodesWithTag("pinned_action").fetchSemanticsNodes().isNotEmpty())
                compose.onNodeWithTag("registration_rules_consent").assertIsDisplayed()
                capture("diag-auth-$rep-block")
                compose.onNodeWithTag("registration_username").performScrollTo()
                val roots = compose.onAllNodes(androidx.compose.ui.test.isRoot()).fetchSemanticsNodes()
            .mapNotNull { it.root as? androidx.compose.ui.platform.ViewRootForTest }
                val before = io.github.thiesi.chitchat.testing.ScrollSettleRecorder.snapshotNow(roots, tags)
                val recorder = io.github.thiesi.chitchat.testing.ScrollSettleRecorder(roots, tags).start()
                compose.tapNativeInput("registration_username", imeBottom = { imeBottom })
                val tapped = recorder.elapsed()
                compose.waitUntil(5_000) { imeBottom > 0 && imeBottom == imeTarget }
                compose.waitForIdle()
                val captureAt = recorder.elapsed()
                val atCapture = io.github.thiesi.chitchat.testing.ScrollSettleRecorder.snapshotNow(roots, tags)
                capture("diag-auth-$rep-keyboard")
                compose.waitUntil(5_000) { recorder.done }
                val after = io.github.thiesi.chitchat.testing.ScrollSettleRecorder.snapshotNow(roots, tags)
                reps.put(org.json.JSONObject().put("rep", rep).put("tappedMs", tapped).put("captureMs", captureAt)
                    .put("png", io.github.thiesi.chitchat.testing.ScrollSettleRecorder.pngHash("ccm-auth-presentation", "diag-auth-$rep-keyboard"))
                    .put("imeBottomAtCapture", imeBottom).put("imeTargetAtCapture", imeTarget)
                    .put("before", before).put("atCapture", atCapture).put("after", after).put("frames", recorder.samples))
            }
        }
        FontScaleTestActivity.requestedFontScale = 1f
        io.github.thiesi.chitchat.testing.ScrollSettleRecorder.write("scroll-settle-auth.json",
            org.json.JSONObject().put("api", android.os.Build.VERSION.SDK_INT).put("form", "auth-light-font200-create-keyboard").put("reps", reps))
    }

    private fun capture(name: String) {
        if (name.endsWith("-keyboard")) compose.waitUntil(5_000) { imeBottom > 0 && imeBottom == imeTarget }
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let { java.io.File(it) }
            ?: checkNotNull(instrumentation.targetContext.getExternalFilesDir(null))
        val folder = java.io.File(output, "ccm-auth-presentation")
        check(folder.isDirectory || folder.mkdirs())
        val bitmap = compose.onNodeWithTag("auth_host").captureToImage().asAndroidBitmap()
        try {
            java.io.File(folder, "$name.png").outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }
}

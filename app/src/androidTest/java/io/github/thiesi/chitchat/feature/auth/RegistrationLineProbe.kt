package io.github.thiesi.chitchat.feature.auth

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
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
import io.github.thiesi.chitchat.core.ui.KeepProbe
import io.github.thiesi.chitchat.feature.account.TestChitChatApp
import io.github.thiesi.chitchat.testing.FontScaleTestActivity
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/**
 * DIAGNOSTIC (never merged), #372 (d): the registration form's rules-consent check (RegistrationForm's line rect from
 * positionInWindow against slot.viewport from boundsInWindow) at four line positions, at 100% and 200% portrait (a short
 * window, so the Create block is in flow) and 200% landscape. Per position: the line's place from layout coordinates
 * (content position minus the scroll value, against the viewport height), the window-path inputs Create uses, and Create's
 * actual decision (submitted or announced). Logs only (CCMBounds); Assume-gated (a3Probe=run).
 */
class RegistrationLineProbe {
    @get:Rule val compose = createEmptyComposeRule()
    private val rules = PolicyDiscovery(PolicyLink("https://chat.example.test/rules", 4, "2026-11-08T12:00:00+00:00"), null, null)
    @After fun reset() { FontScaleTestActivity.requestedFontScale = 1f }

    private fun log(line: String) { android.util.Log.i("CCMBounds", line); KeepProbe.log("B372 $line") }
    private fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    /** The line in the viewport from layout coordinates: (top, bottom, viewport height). */
    private fun geometry(): Triple<Float, Float, Float>? = runCatching {
        val viewport = compose.onNodeWithTag("screen_viewport", useUnmergedTree = true).fetchSemanticsNode()
        val range = viewport.config[SemanticsProperties.VerticalScrollAxisRange]
        // The node's innermost coordinates are inside its verticalScroll and the 24dp padding after it (ChitChatApp's
        // screen_viewport chain): the scrolled content's origin is 24dp above them, and the content is 48dp taller.
        val content = viewport.layoutInfo.coordinates
        val pad = 24f * InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        val line = compose.onNodeWithTag("registration_rules_consent", useUnmergedTree = true).fetchSemanticsNode().layoutInfo.coordinates
        val top = pad + content.localPositionOf(line, Offset.Zero).y - range.value()
        Triple(top, top + line.size.height, content.size.height + 2 * pad - range.maxValue())
    }.getOrNull()

    /** The same inputs Create's check uses: the line's positionInWindow + size, and the viewport's boundsInWindow. */
    private fun windowPath(): String = runCatching {
        val viewport = compose.onNodeWithTag("screen_viewport", useUnmergedTree = true).fetchSemanticsNode()
        val line = compose.onNodeWithTag("registration_rules_consent", useUnmergedTree = true).fetchSemanticsNode().layoutInfo.coordinates
        val top = line.positionInWindow().y
        val frame = viewport.boundsInWindow
        "linePositionInWindow=$top..${top + line.size.height} viewportBoundsInWindow=${frame.top}..${frame.bottom} " +
            "windowPathVisible=${top >= frame.top && top + line.size.height <= frame.bottom}"
    }.getOrDefault("windowPath=unavailable")

    private fun case(name: String, scale: Float, landscape: Boolean, height: Int?, position: String) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("a3Probe") == "run")
        val sessions = TestSessions().apply { registrationEnabled = true }
        val auth = AuthViewModel(sessions)
        val settings = SettingsViewModel(object : SettingsStore {
            override val settings = MutableStateFlow(AppSettings(server = (ServerAddress.parse("chat.example.test") as AddressResult.Valid).address))
            override suspend fun saveServer(server: ServerAddress?) { settings.value = settings.value.copy(server = server) }
            override suspend fun savePureBlack(enabled: Boolean) = Unit
            override suspend fun saveTheme(theme: ThemePreference) = Unit
        })
        sessions.nextConnect = { it.copy(installationName = "The Common Room", public = it.public!!.copy(policies = rules)) }
        FontScaleTestActivity.requestedFontScale = scale
        ActivityScenario.launch(FontScaleTestActivity::class.java).use { scenario ->
            if (landscape) {
                scenario.onActivity { it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
                compose.waitUntil(10_000) {
                    var o = 0; scenario.onActivity { o = it.resources.configuration.orientation }
                    o == android.content.res.Configuration.ORIENTATION_LANDSCAPE
                }
            }
            scenario.onActivity { it.setContent {
                if (height != null) Box(Modifier.size(320.dp, height.dp)) { TestChitChatApp(settings, auth) }
                else Box(Modifier.fillMaxSize()) { TestChitChatApp(settings, auth) }
            } }
            compose.waitUntil(5_000) { !settings.state.value.loading }
            compose.onNodeWithTag("onboarding_create").performScrollTo().performClick()
            compose.onNodeWithTag("registration_username").performScrollTo().performTextInput("Alice")
            compose.onNodeWithTag("registration_password").performScrollTo().performTextInput("fixture-password")
            compose.onNodeWithTag("registration_confirmation").performScrollTo().performTextInput("fixture-password")
            compose.onNodeWithTag("registration_confirmation").performImeAction()
            compose.waitUntil(5_000) { exists("registration_submit") }
            val inFlow = !exists("pinned_action")
            val start = geometry()
            if (!inFlow || start == null) { log("REG $name $position inFlow=$inFlow geometry=$start: no in-flow line to place (skipped)"); return@use }
            val (top, bottom, viewportH) = start
            val h = bottom - top
            val target = when (position) {
                "visible" -> (viewportH - h) / 2f
                "partly-top" -> -h / 2f
                "partly-bottom" -> viewportH - h / 2f
                else -> viewportH + 20f // below the viewport
            }
            runCatching { compose.onNodeWithTag("screen_viewport", useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, top - target) } }
            compose.waitForIdle()
            val placed = geometry()
            val geometryVisible = placed != null && placed.first >= -0.5f && placed.second <= placed.third + 0.5f
            val path = windowPath()
            compose.onNodeWithTag("registration_submit").performSemanticsAction(SemanticsActions.OnClick)
            compose.waitForIdle()
            val announced = exists("registration_review_announcement")
            val submitted = sessions.registrations > 0
            log("REG $name $position inFlow=$inFlow target=$target placed=${placed?.first}..${placed?.second} viewportH=${placed?.third} " +
                "geometryVisible=$geometryVisible $path createSubmitted=$submitted createAnnounced=$announced " +
                "agree=${geometryVisible == submitted && submitted != announced}")
            if (landscape) scenario.onActivity { it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
        }
    }

    private val positions = listOf("visible", "partly-top", "partly-bottom", "offscreen")
    @Test fun diag372Registration100Short() { for (p in positions) case("reg100-short", 1f, false, 260, p) }
    @Test fun diag372Registration200Short() { for (p in positions) case("reg200-short", 2f, false, 260, p) }
    @Test fun diag372Registration200Portrait() { for (p in positions) case("reg200-portrait", 2f, false, null, p) }
    @Test fun diag372Registration200Landscape() { for (p in positions) case("reg200-landscape", 2f, true, null, p) }
}

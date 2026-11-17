package io.github.thiesi.chitchat.app

import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.thiesi.chitchat.core.account.AccountAccess
import io.github.thiesi.chitchat.core.account.AccountOperations
import io.github.thiesi.chitchat.core.account.RecoveryKeyStatus
import io.github.thiesi.chitchat.core.network.SessionUser
import io.github.thiesi.chitchat.core.session.SessionPhase
import io.github.thiesi.chitchat.core.session.SessionState
import io.github.thiesi.chitchat.core.theme.ChitChatTheme
import io.github.thiesi.chitchat.feature.account.RecoveryKeyNotice
import io.github.thiesi.chitchat.feature.account.RecoveryKeyScreen
import io.github.thiesi.chitchat.feature.account.RecoveryKeyViewModel
import io.github.thiesi.chitchat.testing.FontScaleTestActivity
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * O4a2b for J, at a real Configuration font scale, light and dark: the Recovery key screen with a key and not set up
 * (default and 200%), and the "didn't reach this phone" notice at 200%. Collected into `recovery-presentation`.
 */
class RecoveryKeySettingsPresentationTest {
    @get:Rule val compose = createEmptyComposeRule()
    @After fun reset() { FontScaleTestActivity.requestedFontScale = 1f }

    private fun session(status: RecoveryKeyStatus) = SessionState(generation = 1, phase = SessionPhase.AUTHENTICATED,
        user = SessionUser(7, "Viewer", listOf("member"), 1, false), recoveryKey = status, installationName = "The Common Room")

    private fun model(session: SessionState) = RecoveryKeyViewModel(object : AccountOperations {
        override val session = MutableStateFlow(session)
        override suspend fun open(): AccountAccess = error("Captures send nothing")
    })

    @Test fun presentationLight() = presentation(dark = false)
    @Test fun presentationDark() = presentation(dark = true)

    private fun presentation(dark: Boolean) {
        val label = if (dark) "dark" else "light"
        for ((name, fontScale) in listOf("default" to 1f, "font200" to 2f)) {
            for ((state, status) in listOf("exists" to RecoveryKeyStatus.Known(true, "2026-11-17T10:00:00+00:00"),
                "notset" to RecoveryKeyStatus.Known(false, null))) {
                val current = session(status)
                launch(fontScale, dark) { androidx.compose.material3.Surface(color = MaterialTheme.colorScheme.background) {
                    // The app's ScreenContent scrolls inside the Scaffold, which provides the content colour; so does
                    // this host, so the screen is captured as it lives there.
                    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
                        .verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(16.dp).testTag("recovery_host")) {
                        RecoveryKeyScreen(model(current), current, "The Common Room")
                    }
                } }
                compose.onNodeWithTag("recovery_key_screen").assertExists()
                capture("recovery-$label-$name-$state")
                if (state == "exists") {
                    // P-220: the Replace dialog's body scrolls, so both actions stay displayed at either scale.
                    compose.onNodeWithTag("recovery_key_replace").performScrollTo().performClick()
                    compose.onNodeWithTag("recovery_key_replace_confirm").assertIsDisplayed()
                    compose.onNodeWithTag("recovery_key_replace_cancel").assertIsDisplayed()
                }
            }
            if (fontScale == 2f) {
                launch(fontScale, dark) { androidx.compose.material3.Surface(color = MaterialTheme.colorScheme.background) {
                    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).testTag("recovery_host")) {
                        RecoveryKeyNotice(onGoThere = {}, onNotNow = {})
                    }
                } }
                compose.onNodeWithTag("recovery_key_notice").assertExists()
                compose.onNodeWithTag("recovery_key_notice_go_there").assertHeightIsAtLeast(48.dp)
                capture("recovery-$label-$name-notice")
            }
        }
    }

    private var scenario: ActivityScenario<FontScaleTestActivity>? = null
    private fun launch(fontScale: Float, dark: Boolean, content: @androidx.compose.runtime.Composable () -> Unit) {
        scenario?.close()
        FontScaleTestActivity.requestedFontScale = fontScale
        scenario = ActivityScenario.launch(FontScaleTestActivity::class.java).also {
            it.onActivity { activity ->
                assertEquals("A real Configuration font scale", fontScale, activity.resources.configuration.fontScale)
                activity.setContent { ChitChatTheme(dark = dark) { content() } }
            }
        }
    }

    @After fun closeScenario() { scenario?.close() }

    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let { java.io.File(it) }
            ?: checkNotNull(instrumentation.targetContext.getExternalFilesDir(null))
        val folder = java.io.File(output, "ccm-recovery-presentation")
        check(folder.isDirectory || folder.mkdirs())
        val bitmap = compose.onNodeWithTag("recovery_host").captureToImage().asAndroidBitmap()
        try {
            java.io.File(folder, "$name.png").outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }
}

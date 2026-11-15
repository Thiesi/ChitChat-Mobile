package io.github.thiesi.chitchat.app

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBar
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.thiesi.chitchat.R
import io.github.thiesi.chitchat.core.network.SessionUser
import io.github.thiesi.chitchat.core.server.AddressResult
import io.github.thiesi.chitchat.core.server.ServerAddress
import io.github.thiesi.chitchat.core.session.SessionPhase
import io.github.thiesi.chitchat.core.session.SessionState
import io.github.thiesi.chitchat.core.theme.ChitChatTheme
import io.github.thiesi.chitchat.feature.auth.AuthViewModel
import io.github.thiesi.chitchat.feature.auth.TestSessions
import io.github.thiesi.chitchat.testing.FontScaleTestActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * The You hub (presentation lead, PR251/PR256): the top bar on a tab root and a pushed screen (P-76), the profile
 * header, the session group with the outlined Sign out (P-75) and About and privacy (P-77), captured at a real
 * Configuration font scale (default and 2.0), light and dark. No network.
 */
@OptIn(ExperimentalMaterial3Api::class)
class YouPresentationTest {
    @get:Rule val compose = createEmptyComposeRule()
    @After fun reset() { FontScaleTestActivity.requestedFontScale = 1f }

    private val session = SessionState(server = (ServerAddress.parse("chat.example.test") as AddressResult.Valid).address,
        generation = 1, phase = SessionPhase.AUTHENTICATED, user = SessionUser(7, "Viewer", listOf("member", "admin"), 1, false))

    @Test fun presentationLight() = presentation(dark = false)
    @Test fun presentationDark() = presentation(dark = true)

    private fun presentation(dark: Boolean) {
        val label = if (dark) "dark" else "light"
        for ((name, fontScale) in listOf("default" to 1f, "font200" to 2f)) {
            FontScaleTestActivity.requestedFontScale = fontScale
            ActivityScenario.launch(FontScaleTestActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    assertEquals("A real Configuration font scale", fontScale, activity.resources.configuration.fontScale)
                    val auth = AuthViewModel(TestSessions())
                    activity.setContent { ChitChatTheme(dark = dark) { Column(Modifier.fillMaxSize()) {
                        // P-76 with the real pieces: a tab root (large title, no Back) and a pushed screen (icon Back).
                        TopAppBar(title = { BarTitle(R.string.nav_you, large = true) }, Modifier.testTag("bar_root"))
                        TopAppBar(title = { BarTitle(R.string.oss_licences_title) }, Modifier.testTag("bar_pushed"),
                            navigationIcon = { NavBackButton {} })
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(24.dp)) {
                            YouScreen(session, auth, session.server?.value, pushAvailable = false, onNotifications = {},
                                onAppearance = {}, onAccount = {}, onChangeServer = {}, onLicences = {})
                        }
                    } } }
                }
                capture("you-$label-$name-rootbar", "bar_root")
                capture("you-$label-$name-pushedbar", "bar_pushed")
                capture("you-$label-$name-header", "you_header") // The admin fixture: steward "Administrator" badge.
                val signOut = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.sign_out)
                compose.onNode(hasText(signOut) and hasAnyAncestor(hasTestTag("you_session"))).performScrollTo()
                capture("you-$label-$name-session", "you_session")
                compose.onNodeWithTag("you_about").performScrollTo()
                capture("you-$label-$name-about", "you_about")
            }
        }
    }

    private fun capture(name: String, tag: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let { java.io.File(it) }
            ?: checkNotNull(instrumentation.targetContext.getExternalFilesDir(null))
        val folder = java.io.File(output, "ccm-you-presentation")
        check(folder.isDirectory || folder.mkdirs())
        val bitmap = compose.onNodeWithTag(tag).captureToImage().asAndroidBitmap()
        try {
            java.io.File(folder, "$name.png").outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }
}

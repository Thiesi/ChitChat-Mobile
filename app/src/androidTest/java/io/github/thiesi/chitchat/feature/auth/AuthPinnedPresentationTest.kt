package io.github.thiesi.chitchat.feature.auth

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
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
                        SideEffect { imeBottom = bottom }
                        Box(Modifier.fillMaxSize().testTag("auth_host")) { TestChitChatApp(settings, auth) }
                    }
                }
                compose.waitUntil(5_000) { !settings.state.value.loading }
                if (form == "signin") {
                    compose.onNodeWithTag("onboarding_sign_in").performScrollTo().performClick()
                    compose.tapNativeInput("sign_in_password", imeBottom = { imeBottom })
                    compose.onNodeWithTag("sign_in_submit").assertIsDisplayed()
                    capture("auth-$label-$name-signin-keyboard")
                } else {
                    compose.onNodeWithTag("onboarding_create").performScrollTo().performClick()
                    if (fontScale == 2f) {
                        compose.onNodeWithTag("registration_rules_consent").assertIsDisplayed()
                        capture("auth-$label-$name-create-block")
                    }
                    compose.tapNativeInput("registration_username", imeBottom = { imeBottom })
                    capture("auth-$label-$name-create-keyboard")
                }
            }
        }
    }

    private fun capture(name: String) {
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

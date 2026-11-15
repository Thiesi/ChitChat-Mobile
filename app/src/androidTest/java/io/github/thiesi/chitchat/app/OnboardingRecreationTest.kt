package io.github.thiesi.chitchat.app

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import io.github.thiesi.chitchat.core.server.ServerAddress
import io.github.thiesi.chitchat.core.settings.*
import io.github.thiesi.chitchat.feature.account.TestChitChatApp
import io.github.thiesi.chitchat.feature.auth.AuthViewModel
import io.github.thiesi.chitchat.feature.auth.TestSessions
import io.github.thiesi.chitchat.testing.OnCreateContentTestActivity
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertNotSame
import org.junit.Rule
import org.junit.Test

/**
 * P-50 on the production recreation path: the host sets its content in onCreate like MainActivity, so the platform
 * restores the composition after recreate(). Onboarding must come back on the same step, never on Welcome.
 */
class OnboardingRecreationTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val sessions = TestSessions()
    private val auth = AuthViewModel(sessions)
    private val settings = SettingsViewModel(object : SettingsStore {
        override val settings = MutableStateFlow(AppSettings())
        override suspend fun saveServer(server: ServerAddress?) { settings.value = settings.value.copy(server = server) }
        override suspend fun savePureBlack(enabled: Boolean) = Unit
        override suspend fun saveTheme(theme: ThemePreference) = Unit
    })
    @After fun reset() { OnCreateContentTestActivity.content = null }

    private fun recreate(scenario: ActivityScenario<OnCreateContentTestActivity>) {
        var before: OnCreateContentTestActivity? = null
        scenario.onActivity { before = it }
        scenario.recreate()
        scenario.onActivity { assertNotSame("A new Activity instance", before, it) }
    }

    @Test fun stepTwoServerFoundAndTheChosenFormSurviveRecreation() {
        OnCreateContentTestActivity.content = { TestChitChatApp(settings, auth) }
        ActivityScenario.launch(OnCreateContentTestActivity::class.java).use { scenario ->
            compose.waitUntil(5_000) { !settings.state.value.loading }
            compose.onNodeWithTag("onboarding_welcome_start").performScrollTo().performClick()
            compose.onNodeWithTag("onboarding_server_input").assertExists()
            recreate(scenario)
            compose.onNodeWithTag("onboarding_server_input").assertExists() // Step 2, not Welcome.
            compose.onNodeWithTag("onboarding_welcome_start").assertDoesNotExist()
            compose.onNodeWithTag("onboarding_server_input").performTextReplacement("chat.example.test")
            Espresso.closeSoftKeyboard()
            compose.onNodeWithTag("onboarding_continue").performScrollTo().performClick()
            compose.onNodeWithTag("onboarding_sign_in").performScrollTo().performClick()
            recreate(scenario)
            compose.onNodeWithTag("onboarding_back").performScrollTo().assertExists() // Still on the chosen form.
            compose.onNodeWithTag("onboarding_sign_in").assertDoesNotExist()
        }
    }
}

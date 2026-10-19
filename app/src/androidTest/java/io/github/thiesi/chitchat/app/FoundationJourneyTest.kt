package io.github.thiesi.chitchat.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import io.github.thiesi.chitchat.core.server.ServerAddress
import io.github.thiesi.chitchat.core.settings.AppSettings
import io.github.thiesi.chitchat.core.settings.SettingsStore
import io.github.thiesi.chitchat.core.settings.ThemePreference
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test

class FoundationJourneyTest {
    @get:Rule val compose = createComposeRule()

    private fun model(): SettingsViewModel {
        val store = object : SettingsStore {
            override val settings = MutableStateFlow(AppSettings())
            override suspend fun saveServer(server: ServerAddress?) { settings.value = settings.value.copy(server = server) }
            override suspend fun saveTheme(theme: ThemePreference) { settings.value = settings.value.copy(theme = theme) }
        }
        return SettingsViewModel(store)
    }

    @Test fun rejectsCleartextAndAllowsCorrection() {
        val model = model()
        compose.setContent { Box(Modifier.requiredSize(320.dp, 480.dp)) { ChitChatApp(model) } }
        compose.onNodeWithText("Server address").performTextInput("http://chat.example.org")
        compose.onNodeWithText("Save server").performScrollTo().performClick()
        compose.onNodeWithText("Your server must use HTTPS.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Server address").performTextReplacement("https://chat.example.org")
        compose.onNodeWithText("Save server").performScrollTo().performClick()
        compose.onNodeWithText("https://chat.example.org/").assertIsDisplayed()
    }

    @Test fun savesAddressChangesThemeAndForgetsServer() {
        val model = model()
        compose.setContent { Box(Modifier.requiredSize(320.dp, 480.dp)) { ChitChatApp(model) } }
        compose.onNodeWithText("Server address").performTextInput("chat.example.org")
        compose.onNodeWithText("Save server").performScrollTo().performClick()
        compose.onNodeWithText("https://chat.example.org/").assertIsDisplayed()
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Dark").performClick()
        compose.onNodeWithText("Forget server").performScrollTo().performClick()
        compose.onNodeWithText("Forget this server?").assertIsDisplayed()
        compose.onAllNodesWithText("Forget server").onLast().performClick()
        compose.onNodeWithText("Back").performClick()
        compose.onNodeWithText("Save server").performScrollTo().assertIsDisplayed()
    }
}

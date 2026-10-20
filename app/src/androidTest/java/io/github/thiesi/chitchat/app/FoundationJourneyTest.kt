package io.github.thiesi.chitchat.app

import io.github.thiesi.chitchat.feature.account.TestChitChatApp as ChitChatApp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.espresso.Espresso.closeSoftKeyboard
import io.github.thiesi.chitchat.core.server.AddressError
import io.github.thiesi.chitchat.core.server.ServerAddress
import io.github.thiesi.chitchat.core.settings.AppSettings
import io.github.thiesi.chitchat.core.settings.SettingsStore
import io.github.thiesi.chitchat.core.settings.ThemePreference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.delay
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import androidx.compose.runtime.remember
import io.github.thiesi.chitchat.feature.auth.AuthViewModel
import io.github.thiesi.chitchat.feature.auth.TestSessions

class FoundationJourneyTest {
    @get:Rule val compose = createComposeRule()
    private fun saveServer() {
        closeSoftKeyboard()
        compose.onNodeWithText("Save server").performScrollTo().assertIsEnabled().assertIsDisplayed().performClick()
    }

    private fun model(failFirstWrite: Boolean = false, reloadDelayMillis: Long = 0, writes: AtomicInteger = AtomicInteger()): SettingsViewModel {
        val saved = MutableStateFlow(AppSettings())
        var failWrite = failFirstWrite
        val store = object : SettingsStore {
            override val settings = saved.onStart { delay(reloadDelayMillis) }
            override suspend fun saveServer(server: ServerAddress?) {
                writes.incrementAndGet()
                if (failWrite) { failWrite = false; throw IOException("Test storage failure") }
                saved.value = saved.value.copy(server = server)
            }
            override suspend fun saveTheme(theme: ThemePreference) { saved.value = saved.value.copy(theme = theme) }
        }
        return SettingsViewModel(store)
    }

    @Test fun initialReadFailureKeepsNavigationAndSessionInactiveUntilRetry() {
        val failRead = java.util.concurrent.atomic.AtomicBoolean(true)
        val store = object : SettingsStore {
            override val settings = flow {
                if (failRead.get()) throw IOException("fixture")
                emit(AppSettings())
            }
            override suspend fun saveServer(server: ServerAddress?) = Unit
            override suspend fun saveTheme(theme: ThemePreference) = Unit
        }
        val model = SettingsViewModel(store)
        val sessions = TestSessions()
        val auth = AuthViewModel(sessions)
        compose.setContent { Box(Modifier.size(320.dp, 480.dp)) { ChitChatApp(model, auth) } }
        compose.waitUntil(5000) { !model.state.value.loading }
        compose.onNodeWithText("Settings").assertDoesNotExist()
        compose.onNodeWithText("Save server").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0L, sessions.state.value.generation) }
        failRead.set(false)
        compose.onNodeWithText("Try again").assertIsDisplayed().performClick()
        compose.onNodeWithText("Settings").assertIsDisplayed()
        compose.onNodeWithText("Save server").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("This development build supports sign-in and foreground room conversations. Direct messages and notifications are not available yet.")
            .performScrollTo().assertIsDisplayed()
    }

    @Test fun storageRetryPreservesTypedAddress() {
        val writes = AtomicInteger()
        val model = model(failFirstWrite = true, reloadDelayMillis = 200, writes = writes)
        compose.setContent { Box(Modifier.size(320.dp, 480.dp)) { ChitChatApp(model, remember { AuthViewModel(TestSessions()) }) } }
        compose.waitUntil(5000) { !model.state.value.loading }
        compose.onNodeWithText("Server address").performTextInput("chat.example.org")
        saveServer()
        compose.runOnIdle { assertEquals("First write must reach the store", 1, writes.get()) }
        compose.onNodeWithText("Try again").performClick()
        compose.onNodeWithText("Server address").performScrollTo().assertTextContains("chat.example.org")
        saveServer()
        compose.runOnIdle { assertEquals("Retry save must reach the store", 2, writes.get()) }
        compose.waitUntil(5000) { model.state.value.settings.server != null }
        compose.onNodeWithText("https://chat.example.org/").assertIsDisplayed()
    }

    @Test fun rejectsCleartextAndAllowsCorrection() {
        val model = model()
        compose.setContent { Box(Modifier.size(320.dp, 480.dp)) { ChitChatApp(model, remember { AuthViewModel(TestSessions()) }) } }
        compose.onNodeWithText("Server address").performTextInput("http://chat.example.org")
        compose.onNodeWithText("Server address").assertTextContains("http://chat.example.org")
        saveServer()
        compose.runOnIdle { assertEquals(AddressError.HTTPS, model.state.value.addressError) }
        compose.onNodeWithText("Your server must use HTTPS.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Server address").performTextReplacement("https://chat.example.org")
        saveServer()
        compose.onNodeWithText("https://chat.example.org/").assertIsDisplayed()
    }

    @Test fun savesAddressChangesThemeAndForgetsServer() {
        val model = model()
        compose.setContent { Box(Modifier.size(320.dp, 480.dp)) { ChitChatApp(model, remember { AuthViewModel(TestSessions()) }) } }
        compose.onNodeWithText("Server address").performTextInput("chat.example.org")
        saveServer()
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

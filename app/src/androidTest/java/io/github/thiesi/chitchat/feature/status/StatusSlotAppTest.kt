package io.github.thiesi.chitchat.feature.status

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import io.github.thiesi.chitchat.R
import io.github.thiesi.chitchat.app.SettingsViewModel
import io.github.thiesi.chitchat.core.server.*
import io.github.thiesi.chitchat.core.session.SessionPhase
import io.github.thiesi.chitchat.core.settings.*
import io.github.thiesi.chitchat.feature.account.TestChitChatApp
import io.github.thiesi.chitchat.feature.auth.AuthViewModel
import io.github.thiesi.chitchat.feature.auth.TestSessions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test

/** The real app shell with no realtime controller: session notices still reach the top slot (PR223 review). */
class StatusSlotAppTest {
    @get:Rule val compose = createComposeRule()
    private val sessions = TestSessions()
    private val store = ViewModelStore()
    private val auth = AuthViewModel(sessions).also { store.put("auth", it) }
    private val settings = SettingsViewModel(object : SettingsStore {
        override val settings = MutableStateFlow(AppSettings(server = (ServerAddress.parse("chat.example.test") as AddressResult.Valid).address))
        override suspend fun saveServer(server: ServerAddress?) { settings.value = settings.value.copy(server = server) }
        override suspend fun savePureBlack(enabled: Boolean) = Unit
        override suspend fun saveTheme(theme: ThemePreference) { settings.value = settings.value.copy(theme = theme) }
    }).also { store.put("settings", it) }
    @After fun close() = store.clear()

    @Test fun withoutRealtimeARefreshNeededSessionStillShowsRefreshSession() {
        compose.setContent { TestChitChatApp(settings, auth) }
        compose.waitUntil(5_000) { sessions.state.value.phase == SessionPhase.ANONYMOUS }
        compose.runOnIdle { runBlocking { sessions.signIn("Viewer", "fixture-password") } }
        compose.waitUntil(5_000) { sessions.state.value.phase == SessionPhase.AUTHENTICATED }
        compose.runOnIdle { sessions.state.value = sessions.state.value.copy(refreshRequired = true) }
        val refresh = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.refresh_session)
        compose.onNode(hasText(refresh) and hasAnyAncestor(hasTestTag("status_slot"))).assertExists()
    }
}

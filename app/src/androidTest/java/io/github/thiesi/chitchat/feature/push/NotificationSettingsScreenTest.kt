package io.github.thiesi.chitchat.feature.push

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import io.github.thiesi.chitchat.R
import io.github.thiesi.chitchat.core.network.SessionUser
import io.github.thiesi.chitchat.core.push.*
import io.github.thiesi.chitchat.core.server.AddressResult
import io.github.thiesi.chitchat.core.server.ServerAddress
import io.github.thiesi.chitchat.core.session.SessionPhase
import io.github.thiesi.chitchat.core.session.SessionState
import io.github.thiesi.chitchat.core.theme.ChitChatTheme
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** The real settings screen and view model against a fake Web Push API; no network or device push. */
class NotificationSettingsScreenTest {
    @get:Rule val compose = createComposeRule()
    private val ops = Fake()
    private val model = NotificationSettingsViewModel(ops)
    private val store = ViewModelStore().also { it.put("push", model) }
    @After fun stop() = store.clear()
    private fun text(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    private fun show(direct: Boolean?) {
        ops.direct = direct
        val signedIn = ops.session.value // Frozen before composition, like the root's rendered session.
        compose.setContent { ChitChatTheme(dark = false) {
            Column(Modifier.verticalScroll(rememberScrollState())) { NotificationSettingsScreen(model, signedIn) }
        } }
        compose.waitUntil(5_000) { model.state.value.settings != null }
    }

    @Test fun unofferedDmChoiceIsHiddenNotShownAsOff() {
        show(direct = null)
        compose.onNodeWithTag("push_mentions").assertExists()
        compose.onNodeWithTag("push_direct").assertDoesNotExist()
        compose.onNodeWithText(text(R.string.push_status_off)).assertExists()
    }

    @Test fun aChoiceIsSentOnceAndShownOnlyAfterTheServerConfirms() {
        show(direct = false)
        compose.onNodeWithTag("push_direct").performScrollTo()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.Off)).performClick()
        compose.waitUntil(5_000) { model.state.value.settings?.preferences?.direct == true }
        compose.onNodeWithTag("push_direct").assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.On))
        compose.onNodeWithText(text(R.string.push_direct_from_now)).assertExists()
        assertEquals(1, ops.writes.get())
        assertNull("Quiet hours stay off, sent explicitly", ops.lastWrite?.quietHours)
    }

    @Test fun removeAsksFirstAndRevokesById() {
        show(direct = null)
        compose.onNodeWithTag("push_remove:9").performScrollTo().performClick()
        compose.onNodeWithTag("push_remove_dialog").assertExists()
        compose.onNodeWithTag("push_remove_confirm").performClick()
        compose.waitUntil(5_000) { ops.revoked == listOf(9L) }
        compose.onNodeWithTag("push_device:9").assertDoesNotExist()
    }

    private class Fake : PushOperations {
        override val session = MutableStateFlow(SessionState(generation = 1, phase = SessionPhase.AUTHENTICATED,
            server = (ServerAddress.parse("https://fixture.example/") as AddressResult.Valid).address,
            user = SessionUser(7, "Viewer", listOf("member"), 1, false)))
        var direct: Boolean? = null
        val writes = AtomicInteger(); @Volatile var lastWrite: PushPreferences? = null
        @Volatile var revoked = emptyList<Long>()
        private var devices = listOf(PushDevice(9, "Mozilla/5.0 (X11; Linux x86_64) Firefox/128.0", null, false,
            "2026-07-18T09:12:00+00:00", "2026-08-01T21:03:00+00:00"))
        override suspend fun settings(expected: SessionState) = PushSettings(PushPreferences(true, true, direct, null), devices)
        override suspend fun update(expected: SessionState, preferences: PushPreferences): PushPreferences {
            writes.incrementAndGet(); lastWrite = preferences; return preferences }
        override suspend fun revoke(expected: SessionState, deviceId: Long): List<PushDevice> {
            revoked = revoked + deviceId; devices = devices.filterNot { it.id == deviceId }; return devices }
    }
}

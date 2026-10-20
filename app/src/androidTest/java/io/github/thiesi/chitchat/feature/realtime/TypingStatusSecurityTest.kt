package io.github.thiesi.chitchat.feature.realtime

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.platform.app.InstrumentationRegistry
import io.github.thiesi.chitchat.core.conversation.ChatPolicy
import io.github.thiesi.chitchat.core.conversation.DisplayPreferences
import io.github.thiesi.chitchat.core.network.SessionUser
import io.github.thiesi.chitchat.core.session.SessionPhase
import io.github.thiesi.chitchat.core.session.SessionState
import io.github.thiesi.chitchat.core.typing.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class TypingStatusSecurityTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Before fun verifyRequestedConfiguration() {
        val args = InstrumentationRegistry.getArguments()
        val config = compose.activity.resources.configuration
        args.getString("expectedOrientation")?.toInt()?.let { assertEquals(it, config.orientation) }
        args.getString("expectedFontScale")?.toFloat()?.let { assertEquals(it, config.fontScale, 0.01f) }
    }
    @Test fun staleSourceCannotDisplayAnotherAccountOrAnUnconfirmedPrivacyPreference() {
        val current = SessionState(generation = 2, phase = SessionPhase.AUTHENTICATED,
            user = SessionUser(9, "Second", listOf("member"), 1, false), shareTyping = true,
            chatPolicy = ChatPolicy(emptySet(), null, DisplayPreferences()))
        val session = MutableStateFlow(current)
        val controller = StaleSource()
        compose.setContent { val actual by session.collectAsState()
            MaterialTheme { TypingStatus(controller, actual, TypingTarget.Room(42), true, false) }
        }
        compose.onNodeWithTag("typing_status").assertDoesNotExist()
        compose.runOnIdle { controller.state.value = TypingState(TypingTarget.Room(42),
            listOf(TypingPerson(8, "Peer")), current.typingAccount()) }
        compose.onNodeWithTag("typing_status").assertIsDisplayed().assertTextEquals("Peer is typing…")
        compose.runOnIdle { session.value = current.copy(chatPolicy = ChatPolicy(setOf(8), null, DisplayPreferences())) }
        compose.onNodeWithTag("typing_status").assertDoesNotExist()
        compose.runOnIdle { session.value = current }
        compose.onNodeWithTag("typing_status").assertIsDisplayed()
        compose.runOnIdle { session.value = current.copy(shareTyping = null) }
        compose.onNodeWithTag("typing_status").assertDoesNotExist()
        compose.runOnIdle { session.value = current.copy(phase = SessionPhase.SIGNED_OUT) }
        compose.onNodeWithTag("typing_status").assertDoesNotExist()
    }
    /** Deliberately never clears old hints, to test the UI fence independently of the real reducer. */
    private class StaleSource : TypingController {
        override val state = MutableStateFlow(TypingState(TypingTarget.Room(42), listOf(TypingPerson(8, "Old account")),
            TypingAccount(null, 1, 7, 1)))
        override fun select(expected: SessionState, target: TypingTarget?, viewing: Boolean, publishing: Boolean) = Unit
        override fun release(expected: SessionState, target: TypingTarget) = Unit
        override fun input(expected: SessionState, target: TypingTarget, hasContent: Boolean) = Unit
        override fun sent(expected: SessionState, target: TypingTarget) = Unit
    }
}

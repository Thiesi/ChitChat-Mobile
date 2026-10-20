package io.github.thiesi.chitchat.feature.account

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.app.InstrumentationRegistry
import io.github.thiesi.chitchat.core.account.TypingPreferenceOperations
import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.network.FailureKind
import io.github.thiesi.chitchat.core.network.SessionUser
import io.github.thiesi.chitchat.core.session.SessionPhase
import io.github.thiesi.chitchat.core.session.SessionState
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class TypingPreferenceJourneyTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val fake = Preferences()
    private val model = TypingPreferenceViewModel(fake)
    private var refreshes = 0
    @Before fun verifyRequestedConfiguration() {
        val args = InstrumentationRegistry.getArguments()
        val config = compose.activity.resources.configuration
        args.getString("expectedOrientation")?.toInt()?.let { assertEquals(it, config.orientation) }
        args.getString("expectedFontScale")?.toFloat()?.let { assertEquals(it, config.fontScale, 0.01f) }
    }
    @After fun close() { compose.runOnIdle { model.cancel() } }
    private fun show(shared: Boolean?) {
        fake.session.value = fake.session.value.copy(shareTyping = shared)
        compose.setContent {
            val session by fake.session.collectAsState()
            MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
                TypingPreferenceCard(model, session) { refreshes++ }
            } }
        }
    }
    @Test fun confirmedSwitchHasOneAccessibleTargetAndPersistsOnlyOnExplicitTap() {
        show(true)
        compose.onNodeWithTag("typing_switch").performScrollTo().assertIsOn().assertIsEnabled().performClick()
        compose.onNodeWithTag("typing_switch").assertIsOff()
        compose.runOnIdle { assertEquals(1, fake.calls); assertEquals(false, fake.session.value.shareTyping) }
        if (InstrumentationRegistry.getArguments().getString("captureTyping") == "true") {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val file = java.io.File(context.getExternalFilesDir(null), "typing-privacy.png")
            file.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap()
                .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
    @Test fun unknownPreferenceDoesNotRenderAnEnabledDefaultSwitch() {
        show(null)
        compose.onNodeWithTag("typing_switch").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, fake.calls) }
        compose.onNodeWithText("Turn off typing sharing").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithTag("typing_switch").performScrollTo().assertIsOff()
        compose.runOnIdle { assertEquals(1, fake.calls) }
    }
    @Test fun failureExposesRefreshAndNeverReplays() {
        fake.failure = true; show(true)
        compose.onNodeWithTag("typing_switch").performScrollTo().performClick()
        compose.onNodeWithText("Turn off typing sharing").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Refresh session").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, fake.calls); assertEquals(1, refreshes) }
        compose.onNodeWithText("The change was not confirmed and will not be retried automatically. Refresh before trying again.")
            .performScrollTo().assertIsDisplayed()
    }
    private class Preferences : TypingPreferenceOperations {
        override val session = MutableStateFlow(SessionState(generation = 1, phase = SessionPhase.AUTHENTICATED,
            user = SessionUser(7, "Viewer", listOf("member"), 1, false)))
        var calls = 0
        var failure = false
        override suspend fun change(expected: SessionState, shared: Boolean) {
            calls++
            session.value = session.value.copy(shareTyping = null)
            if (failure) throw ApiFailure(FailureKind.NETWORK)
            session.value = session.value.copy(shareTyping = shared)
        }
    }
}

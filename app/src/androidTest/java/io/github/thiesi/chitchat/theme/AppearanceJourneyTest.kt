package io.github.thiesi.chitchat.theme

import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import io.github.thiesi.chitchat.R
import io.github.thiesi.chitchat.app.SettingsViewModel
import io.github.thiesi.chitchat.core.server.ServerAddress
import io.github.thiesi.chitchat.core.settings.*
import io.github.thiesi.chitchat.core.theme.Lantern
import io.github.thiesi.chitchat.feature.account.TestChitChatApp
import io.github.thiesi.chitchat.feature.auth.AuthViewModel
import io.github.thiesi.chitchat.feature.auth.TestSessions
import io.github.thiesi.chitchat.testing.assertMinTouchTarget
import io.github.thiesi.chitchat.testing.performNativeTap
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*

/** Real root and setting consumer; fake local persistence/session, no server or input activation. */
@OptIn(ExperimentalTestApi::class)
class AppearanceJourneyTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val sessions=TestSessions()
    private val store=object: SettingsStore {
        override val settings=MutableStateFlow(AppSettings(theme=ThemePreference.LIGHT))
        override suspend fun saveServer(server: ServerAddress?) { settings.value=settings.value.copy(server=server) }
        override suspend fun saveTheme(theme: ThemePreference) { settings.value=settings.value.copy(theme=theme) }
        override suspend fun savePureBlack(enabled: Boolean) { settings.value=settings.value.copy(pureBlack=enabled) }
    }
    private lateinit var model: SettingsViewModel
    private fun render(activity: ComponentActivity) {
        model=ViewModelProvider(activity,SettingsViewModel.factory(store))[SettingsViewModel::class.java]
        val auth=ViewModelProvider(activity,AuthViewModel.factory(sessions))[AuthViewModel::class.java]
        activity.setContent { TestChitChatApp(model,auth) }
    }
    private fun ground(expected: Color) {
        compose.waitForIdle()
        if(Build.VERSION.SDK_INT>=28) {
            val pixels=compose.onRoot().captureToImage().toPixelMap()
            assertEquals(expected.toArgb(),pixels[1,1].toArgb())
        }
    }
    @Test fun appearanceChangesLiveWithoutRecreationAndPureBlackSurvivesConfiguration() {
        if(Build.VERSION.SDK_INT>=34) compose.enableAccessibilityChecks()
        compose.activityRule.scenario.onActivity(::render)
        val settings=InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.settings)
        compose.onNodeWithText(settings).assertIsDisplayed().performNativeTap()
        val original=compose.activity
        compose.onNodeWithTag("theme_pure_black").performScrollTo().assertIsOff().assertMinTouchTarget(compose.density).performNativeTap()
        compose.onNodeWithTag("theme_pure_black").assertIsOn()
        ground(Lantern.light.ground)
        compose.runOnIdle { model.setTheme(ThemePreference.DARK) }
        ground(Lantern.amoledBlack.ground)
        assertSame(original,compose.activity)
        compose.runOnIdle { model.setPureBlack(false) }
        ground(Lantern.dark.ground)
        assertSame(original,compose.activity)
        compose.runOnIdle { model.setPureBlack(true) }
        compose.activityRule.scenario.recreate()
        compose.activityRule.scenario.onActivity(::render)
        ground(Lantern.amoledBlack.ground)
        compose.runOnIdle { assertTrue(model.state.value.settings.pureBlack);assertEquals(ThemePreference.DARK,model.state.value.settings.theme) }
        if(Build.VERSION.SDK_INT>=34) compose.onRoot().tryPerformAccessibilityChecks()
    }
}

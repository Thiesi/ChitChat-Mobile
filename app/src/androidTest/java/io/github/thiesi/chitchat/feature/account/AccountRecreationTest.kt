package io.github.thiesi.chitchat.feature.account

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import io.github.thiesi.chitchat.app.ChitChatApp
import io.github.thiesi.chitchat.app.SettingsViewModel
import io.github.thiesi.chitchat.core.account.VerificationMethods
import io.github.thiesi.chitchat.core.server.AddressResult
import io.github.thiesi.chitchat.core.server.ServerAddress
import io.github.thiesi.chitchat.core.settings.AppSettings
import io.github.thiesi.chitchat.core.settings.SettingsStore
import io.github.thiesi.chitchat.core.settings.ThemePreference
import io.github.thiesi.chitchat.feature.auth.AuthViewModel
import io.github.thiesi.chitchat.feature.auth.TestSessions
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class AccountRecreationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val sessions = TestSessions()
    private val auth = AuthViewModel(sessions)
    private val fake = TestAccounts(sessions.state)
    private lateinit var account: AccountViewModel
    private var writes = 0
    private val settings = SettingsViewModel(object : SettingsStore {
        override val settings = MutableStateFlow(AppSettings(server =
            (ServerAddress.parse("chat.example.org") as AddressResult.Valid).address))
        override suspend fun saveServer(server: ServerAddress?) { settings.value = settings.value.copy(server = server) }
        override suspend fun saveTheme(theme: ThemePreference) { settings.value = settings.value.copy(theme = theme) }
    })
    @Before fun bindRetainedModel() { compose.runOnUiThread {
        account = ViewModelProvider(compose.activity, AccountViewModel.factory(fake))[AccountViewModel::class.java]
    } }
    private fun installContent(activity: ComponentActivity) {
        // Use the same composition call site before/after recreation, as MainActivity does.
        activity.setContent { ChitChatApp(settings, auth, account) }
    }
    private fun start(destination: ExportDestination = ExportDestination { _, current -> current(); writes++ }) {
        compose.runOnUiThread { installContent(compose.activity) }
        compose.onNodeWithText("Username").performScrollTo().performTextInput("Alice")
        compose.onNodeWithText("Password").performScrollTo().performTextInput("fixture-password")
        compose.onNodeWithText("Sign in").performScrollTo().performClick()
        compose.onNodeWithText("Account").performClick()
        compose.runOnIdle {
            assertTrue(account.chooseDestination())
            account.destinationSelected(destination, sessions.state.value.generation)
        }
    }
    private fun verifyRecovery() {
        compose.onNodeWithText("Use an emergency recovery code").performScrollTo().performClick()
        compose.onNodeWithText("Recovery code").performScrollTo().performTextInput("UNUSED-FIXTURE-CODE")
        compose.onNodeWithText("Verify and continue").performClick()
        compose.waitUntil(5_000) { fake.recoveries == 1 && account.state.value.verificationBusy }
    }
    private fun recreate() {
        val previous = compose.activity
        val expected = account.state.value.stage
        compose.activityRule.scenario.recreate()
        assertEquals("Operation must survive the old Activity stopping", expected, account.state.value.stage)
        compose.activityRule.scenario.onActivity { activity ->
            assertNotSame(previous, activity)
            assertSame(account, ViewModelProvider(activity, AccountViewModel.factory(fake))[AccountViewModel::class.java])
            installContent(activity)
        }
        compose.waitForIdle()
    }
    private fun saved() {
        compose.waitUntil(5_000) { account.state.value.stage == ExportStage.SAVED }
        compose.onNodeWithText("Your export was saved to the location you chose.").performScrollTo().assertIsDisplayed()
    }
    private fun visibleAfterRecreation(text: String, scroll: Boolean = false) {
        // ActivityScenario is resumed before its replacement window is necessarily drawn.
        compose.waitUntil(5_000) {
            runCatching {
                val node = compose.onNodeWithText(text, substring = true)
                if (scroll) node.performScrollTo()
                node.assertIsDisplayed()
            }.isSuccess
        }
    }

    @Test fun recreationDuringRecoveryKeepsOneVerificationAndTheAccountDestination() {
        fake.methods = VerificationMethods(true, true, 8)
        val gate = CompletableDeferred<Unit>()
        fake.recoveryGate = gate
        start(); verifyRecovery(); recreate()
        compose.runOnIdle { assertEquals(ExportStage.VERIFYING, account.state.value.stage) }
        visibleAfterRecreation("Confirm personal-data export")
        compose.runOnIdle { assertTrue(account.state.value.verificationBusy); gate.complete(Unit) }
        saved()
        compose.runOnIdle { assertEquals(1, fake.recoveries); assertEquals(2, fake.exports); assertEquals(1, writes) }
    }

    @Test fun recreationDuringWriteKeepsTheRequestedExportWithoutResubmitting() {
        val gate = CompletableDeferred<Unit>()
        start(ExportDestination { _, current -> writes++; gate.await(); current() })
        compose.onNodeWithText("Current password").performTextInput("fixture-password")
        compose.onNodeWithText("Verify and continue").performClick()
        compose.waitUntil(5_000) { writes == 1 }
        recreate()
        compose.runOnIdle { assertEquals(ExportStage.RUNNING, account.state.value.stage) }
        visibleAfterRecreation("Preparing and saving your export", scroll = true)
        compose.runOnIdle { assertEquals(ExportStage.RUNNING, account.state.value.stage); gate.complete(Unit) }
        saved()
        compose.runOnIdle { assertEquals(1, writes); assertEquals(2, fake.exports); assertEquals(1, fake.passwordChecks) }
    }

    @Test fun realStopStillCancelsRecoveryAndNeverRetriesTheAction() {
        fake.methods = VerificationMethods(true, true, 8)
        val gate = CompletableDeferred<Unit>()
        fake.recoveryGate = gate
        start(); verifyRecovery()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.waitUntil(5_000) { account.state.value.stage == ExportStage.CANCELLED }
        assertTrue(account.state.value.recoveryMayBeSpent)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.runOnIdle { gate.complete(Unit) }
        compose.runOnIdle { assertEquals(1, fake.recoveries); assertEquals(1, fake.exports); assertEquals(0, writes) }
    }
}

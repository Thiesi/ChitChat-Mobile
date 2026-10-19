package io.github.thiesi.chitchat.feature.account

import android.app.Activity
import android.content.Intent
import android.content.ContentValues
import android.provider.MediaStore
import androidx.test.filters.SdkSuppress
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityOptionsCompat
import io.github.thiesi.chitchat.core.account.VerificationMethods
import io.github.thiesi.chitchat.core.network.SessionUser
import io.github.thiesi.chitchat.core.session.SessionPhase
import io.github.thiesi.chitchat.core.session.SessionState
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class AccountJourneyTest {
    @get:Rule val compose = createComposeRule()
    private val session = MutableStateFlow(SessionState(generation = 1, phase = SessionPhase.AUTHENTICATED,
        user = SessionUser(7, "Alice", listOf("member"), 1, false)))
    private val fake = TestAccounts(session)
    private val model = AccountViewModel(fake)
    private var writes = 0
    private val destination = ExportDestination { bytes, current -> current(); check(bytes.isNotEmpty()); writes++ }
    private val empty = SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(""))
    @After fun tearDown() { model.cancel() }

    @Composable private fun FixtureScreen() {
        val current by session.collectAsState()
        AccountScreen(model, current, {})
    }

    private fun content() { compose.setContent { MaterialTheme {
        Box(Modifier.size(320.dp, 480.dp)) { Column(Modifier.verticalScroll(rememberScrollState())) {
            FixtureScreen()
        } }
    } } }
    private fun prompt(methods: VerificationMethods = VerificationMethods(false, true, 8)) {
        fake.methods = methods
        content()
        compose.runOnIdle { assertTrue(model.chooseDestination()); model.destinationSelected(destination, 1) }
        compose.onNodeWithText("Confirm personal-data export").assertIsDisplayed()
    }
    private fun awaitExport() {
        compose.waitUntil(5_000) { model.state.value.stage in setOf(ExportStage.SAVED, ExportStage.FAILED, ExportStage.CANCELLED) }
        compose.runOnIdle { assertEquals("Export stage; error kind=${model.state.value.error?.kind}", ExportStage.SAVED, model.state.value.stage) }
    }

    @Test fun wrongPasswordIsVisibleClearedAndCorrectableWithoutActionReplay() {
        fake.failPassword = true
        prompt()
        compose.onNodeWithText("Current password").performTextInput("wrong-fixture-password")
        compose.onNodeWithText("Verify and continue").performClick()
        compose.onNodeWithText("The current password is incorrect.").assertIsDisplayed()
        compose.onNodeWithText("Current password").assert(empty)
        compose.onNodeWithText("Verify and continue").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(1, fake.exports); fake.failPassword = false }
        compose.onNodeWithText("Current password").performTextInput("correct-fixture-password")
        compose.onNodeWithText("Verify and continue").performClick()
        awaitExport()
        compose.onNodeWithText("Your export was saved to the location you chose.").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals(2, fake.exports); assertEquals(2, fake.passwordChecks); assertEquals(1, writes) }
    }

    @Test fun mfaRequiresExplicitEmergencyChoiceAndNeverOffersPassword() {
        prompt(VerificationMethods(true, true, 8))
        compose.onNodeWithText("Current password").assertDoesNotExist()
        compose.onNodeWithText("Recovery code").assertDoesNotExist()
        compose.onNodeWithText("Use an emergency recovery code").performScrollTo().performClick()
        compose.onNodeWithText("This is an emergency fallback", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Recovery code").performScrollTo().performTextInput("UNUSED-FIXTURE-CODE")
        compose.onNodeWithText("Verify and continue").performClick()
        awaitExport()
        compose.onNodeWithText("Your export was saved to the location you chose.").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, fake.recoveries); assertEquals(0, fake.passwordChecks); assertEquals(1, writes) }
    }

    @Test fun passwordlessAndExhaustedRecoveryAccountsExplainUnsupportedMethods() {
        prompt(VerificationMethods(false, false, 0))
        compose.onNodeWithText("This account has no password", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Current password").assertDoesNotExist()
        compose.onNodeWithText("Verify and continue").assertDoesNotExist()
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle {
            fake.methods = VerificationMethods(true, false, 0)
            assertTrue(model.chooseDestination()); model.destinationSelected(destination, 1)
        }
        compose.onNodeWithText("This account requires a passkey", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Use an emergency recovery code").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, fake.passwordChecks); assertEquals(0, fake.recoveries); assertEquals(0, writes) }
    }

    @Test fun cancelDoesNotRetryOrSavePrivateContent() {
        prompt()
        compose.onNodeWithText("Current password").performTextInput("unsent-fixture-password")
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Confirm personal-data export").assertDoesNotExist()
        compose.onNodeWithText("Export cancelled. A chosen file may be empty or incomplete.").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, fake.exports); assertEquals(0, fake.passwordChecks); assertEquals(0, writes) }
    }

    @Test fun savedStateDoesNotRetainStepUpPassword() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { MaterialTheme { FixtureScreen() } }
        compose.runOnIdle { assertTrue(model.chooseDestination()); model.destinationSelected(destination, 1) }
        compose.onNodeWithText("Current password").performTextInput("unsaved-fixture-password")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Current password").assert(empty)
        compose.runOnIdle { assertEquals(0, fake.passwordChecks) }
    }

    @Test fun logoutWhileDialogIsOpenClearsPromptAndPreventsLateConfirmation() {
        prompt()
        compose.onNodeWithText("Current password").performTextInput("unsent-fixture-password")
        compose.runOnIdle { session.value = SessionState(generation = 2, phase = SessionPhase.SIGNED_OUT) }
        compose.onNodeWithText("Confirm personal-data export").assertDoesNotExist()
        compose.runOnIdle { model.verify("late-fixture", false); assertEquals(0, fake.passwordChecks); assertEquals(0, writes) }
    }

    @Test fun systemPickerUsesCreateDocumentAndCancellationNeverDownloads() {
        var requested: Intent? = null
        val registry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I,
                options: ActivityOptionsCompat?) {
                requested = contract.createIntent(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext, input)
                dispatchResult(requestCode, Activity.RESULT_CANCELED, null)
            }
        }
        val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }
        compose.setContent { CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
            MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) { FixtureScreen() } }
        } }
        compose.onNodeWithText("Export personal data").performScrollTo().performClick()
        compose.onNodeWithText("Export cancelled. A chosen file may be empty or incomplete.").performScrollTo().assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(Intent.ACTION_CREATE_DOCUMENT, requested!!.action)
            assertEquals("application/json", requested.type)
            assertEquals(0, fake.exports)
        }
    }

    @SdkSuppress(minSdkVersion = 29)
    @Test fun selectedProviderDocumentIsWrittenOnlyAfterExplicitVerification() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "chitchat-journey-fixture-${UUID.randomUUID()}.json")
            put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/ChitChatFixtureTests")
        }) ?: error("Unable to create fixture document")
        try {
            resolver.openOutputStream(uri, "wt")!!.use { }
            val registry = object : ActivityResultRegistry() {
                override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I,
                    options: ActivityOptionsCompat?) {
                    assertEquals(Intent.ACTION_CREATE_DOCUMENT, contract.createIntent(context, input).action)
                    dispatchResult(requestCode, Activity.RESULT_OK, Intent().setData(uri))
                }
            }
            val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }
            compose.setContent { CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) { FixtureScreen() } }
            } }
            compose.onNodeWithText("Export personal data").performScrollTo().performClick()
            compose.onNodeWithText("Confirm personal-data export").assertIsDisplayed()
            compose.runOnIdle { assertEquals(0, resolver.openInputStream(uri)!!.use { it.readBytes().size }) }
            compose.onNodeWithText("Current password").performTextInput("fixture-password")
            compose.onNodeWithText("Verify and continue").performClick()
            awaitExport()
            compose.onNodeWithText("Your export was saved to the location you chose.").performScrollTo().assertIsDisplayed()
            compose.runOnIdle {
                val saved = resolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() }
                assertTrue(saved.contains("\"format\""))
                assertTrue(saved.contains("\"id\":7"))
                assertFalse(saved.contains("\"filename\""))
                assertEquals(2, fake.exports)
            }
        } finally { resolver.delete(uri, null, null) }
    }
}

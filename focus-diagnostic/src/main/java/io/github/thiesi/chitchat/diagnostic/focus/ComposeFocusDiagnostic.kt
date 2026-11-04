package io.github.thiesi.chitchat.diagnostic.focus

import android.view.inputmethod.InputMethodManager
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import io.github.thiesi.chitchat.testing.performNativeTap
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** One opt-in diagnostic case in its own test APK. No account, server, app navigation or tracing. */
class ComposeFocusDiagnostic {
    @get:Rule val compose = createComposeRule()
    @Volatile private var imeBottom = 0
    @Volatile private var windowFocused = false

    @Test fun onePhysicalTapAcquiresWindowFocusAndImeBeforeTextInjection() {
        val arguments = InstrumentationRegistry.getArguments()
        check(arguments.getString("focusDiagnostic") == "true" &&
            arguments.getString("expectSoftKeyboard") == "true") { "Explicit diagnostic and strict IME arguments required" }
        var stage = "COMPOSE"
        var originalFailure: Throwable? = null
        var fixtureView: android.view.View? = null
        try {
            compose.setContent {
                var value by remember { mutableStateOf("") }
                val view = LocalView.current
                val bottom = WindowInsets.ime.getBottom(LocalDensity.current)
                val focused = LocalWindowInfo.current.isWindowFocused
                SideEffect { fixtureView = view; imeBottom = bottom; windowFocused = focused }
                MaterialTheme {
                    Column(Modifier.fillMaxSize()) {
                        OutlinedTextField(value, { value = it }, label = { Text("Diagnostic input") },
                            modifier = Modifier.testTag("focus_diagnostic_input"))
                    }
                }
            }
            val input = compose.onNodeWithTag("focus_diagnostic_input")
            stage = "NATIVE_TAP"
            input.assertIsDisplayed().performNativeTap()
            stage = "FOCUS_IME"
            compose.waitUntil(5_000) { windowFocused && imeBottom > 0 &&
                runCatching { input.assertIsFocused().assertIsDisplayed() }.isSuccess }
            stage = "TEXT_PROOF"
            val text = "Complete fixture text\nsecond line"
            input.performTextInput(text)
            assertEquals(text, input.fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
            reportFocus(JSONObject().put("kind", "success").put("stage", stage)
                .put("physicalTapFocusImeProved", true).put("completeTextProved", true))
        } catch (failure: Throwable) {
            originalFailure = failure
            runCatching { reportFocus(captureFocusFailure(stage, failure)) }.exceptionOrNull()?.let(failure::addSuppressed)
        } finally {
            // Cleanup belongs to this fixture and completes while its host still exists.
            val cleanup = runCatching {
                compose.runOnUiThread {
                    fixtureView?.let { view ->
                        (view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                            .hideSoftInputFromWindow(view.windowToken, 0)
                    }
                }
                compose.waitUntil(5_000) { imeBottom == 0 }
            }.exceptionOrNull()
            if (cleanup != null) {
                originalFailure?.addSuppressed(cleanup) ?: run { originalFailure = cleanup }
            }
            val reportFailure = runCatching {
                reportFocus(JSONObject().put("kind", "cleanup").put("completed", cleanup == null)
                    .put("failureClass", cleanup?.javaClass?.simpleName ?: JSONObject.NULL))
            }.exceptionOrNull()
            if (reportFailure != null) {
                originalFailure?.addSuppressed(reportFailure) ?: run { originalFailure = reportFailure }
            }
        }
        originalFailure?.let { throw it }
    }
}

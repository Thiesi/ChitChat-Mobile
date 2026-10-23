package io.github.thiesi.chitchat.testing

import android.os.Bundle
import android.widget.EditText
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** A bounded comparison, not a replacement for any failed native gate. */
internal fun imeComparisonRounds(raw: String?): Int {
    if (raw == null) return 1
    require(raw.matches(Regex("[0-9]{1,2}"))) { "Invalid comparison round count" }
    return raw.toInt().also { require(it in 1..25) { "Comparison requires1..25 rounds" } }
}

class NativeInputComparisonPlanTest {
    @Test fun defaultIsOneAndDiagnosticRepetitionsAreExplicitlyBounded() {
        assertEquals(1, imeComparisonRounds(null))
        assertEquals(20, imeComparisonRounds("20"))
        assertEquals(25, imeComparisonRounds("25"))
    }

    @Test fun invalidPlansFailBeforeAnyDeviceInteraction() {
        listOf("", "0", "26", "100", "-1", " 1", "private-canary").forEach {
            assertThrows(IllegalArgumentException::class.java) { imeComparisonRounds(it) }
        }
    }
}

/** Same Activity, owner, size, real tap and strict deadline for platform/Compose/observer paths.
 * One round is normal regression coverage. Explicit diagnostic invocations can use up to25.
 * Each case stops at its FIRST failure; no failed trial is retried or replaced by a later pass.
 */
class NativeInputComparisonTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Volatile private var ime = 0
    @Volatile private var focused = false
    private val records = mutableListOf<String>()
    private var caseName = "UNSTARTED"

    @After fun retireFixtureAndPublishOnlyScalarOutcomes() {
        try { Espresso.closeSoftKeyboard() }
        finally {
            InstrumentationRegistry.getInstrumentation().addResults(Bundle().apply {
                putString("imeComparison$caseName", records.joinToString("\n"))
            })
        }
    }

    @Test fun platformEditTextSingleTapShowsTheKeyboard() = compare(Kind.NATIVE)
    @Test fun legacyBasicTextFieldSingleTapShowsTheKeyboard() = compare(Kind.BASIC)
    @Test fun legacyMaterialTextFieldSingleTapShowsTheKeyboard() = compare(Kind.OUTLINED)
    @Test fun observedLegacyMaterialTextFieldSingleTapShowsTheKeyboard() = compare(Kind.TRACED)

    private enum class Kind { NATIVE, BASIC, OUTLINED, TRACED }

    private fun compare(kind: Kind) {
        val rounds = imeComparisonRounds(InstrumentationRegistry.getArguments().getString("imeComparisonRounds"))
        caseName = kind.name
        var epoch by mutableIntStateOf(0)
        focused = false
        compose.setContent {
            val bottom = WindowInsets.ime.getBottom(LocalDensity.current)
            SideEffect { ime = bottom }
            MaterialTheme { Box(Modifier.fillMaxSize()) {
                key(epoch) {
                    if (kind == Kind.TRACED) ObserveNativeTextInput { ComparisonField(kind) }
                    else ComparisonField(kind)
                }
            } }
        }
        repeat(rounds) { round ->
            // Successful-trial retirement, not a rescue/retry of a failed tap. Match the existing
            // control's closed-IME boundary before replacing the field for the next planned trial.
            if (round > 0) {
                try {
                    Espresso.closeSoftKeyboard()
                    compose.waitUntil(5_000) { ime == 0 }
                } catch (failure: Throwable) {
                    records += "kind=$kind,round=$round,phase=RETIRE,error=${failure.javaClass.simpleName}"
                    throw failure
                }
                compose.runOnIdle { focused = false; epoch++ }
            }
            val node = compose.onNodeWithTag("comparison_input")
            compose.waitUntil(5_000) { runCatching { node.assertIsDisplayed() }.isSuccess }
            val before = nativeInputWindow()
            val imeBefore = ime
            try {
                node.performNativeTap()
                compose.waitUntil(5_000) {
                    focused && ime > 0 && runCatching {
                        node.assertIsDisplayed()
                        if (kind != Kind.NATIVE) node.assertIsFocused()
                    }.isSuccess
                }
                val after = nativeInputWindow()
                assertEquals(true, after.windowFocused)
                assertEquals(true, after.viewFocused)
                assertEquals(true, after.textEditor)
                assertEquals(true, after.imeVisible)
                assertTrue((after.imeBottom ?: 0) > 0)
                records += "kind=$kind,round=$round,passed=true,imeBefore=$imeBefore,before=$before,after=$after"
            } catch (failure: Throwable) {
                if (failure is VirtualMachineError || failure is ThreadDeath) throw failure
                val after = nativeInputWindow()
                val session = NativeTextInputTrace.describe()
                val server = nativeImeServerAfterFailure()
                val record = "kind=$kind,round=$round,passed=false,imeBefore=$imeBefore,imeAfter=$ime," +
                    "focused=$focused,before=$before,after=$after,sessions=$session,server=$server"
                records += record
                // Preserve the first negative outcome and all prior rounds; never continue this
                // case until a later success. No editor text, raw dumps or arbitrary error tails.
                throw AssertionError(records.joinToString("\n"), failure)
            }
        }
    }

    @Composable private fun ComparisonField(kind: Kind) {
        val modifier = Modifier.size(280.dp, 56.dp).testTag("comparison_input")
        when (kind) {
            Kind.NATIVE -> AndroidView(factory = { context ->
                EditText(context).apply {
                    setText("Input fixture")
                    isSaveEnabled = false
                    onFocusChangeListener = android.view.View.OnFocusChangeListener { _, hasFocus -> focused = hasFocus }
                }
            }, modifier = modifier)
            Kind.BASIC -> BasicTextField("Input fixture", {}, modifier = modifier.onFocusChanged { focused = it.isFocused })
            Kind.OUTLINED, Kind.TRACED -> OutlinedTextField("Input fixture", {},
                modifier = modifier.onFocusChanged { focused = it.isFocused })
        }
    }
}

package io.github.thiesi.chitchat.testing

import android.graphics.Rect as AndroidRect
import android.os.Bundle
import android.os.SystemClock
import android.widget.EditText
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import kotlin.math.roundToInt

/** No Compose test rule, composition, AndroidView or Compose owner. Geometry value types and the
 * existing single-injection helper are reused, not the Compose input pipeline or its inset state.
 */
class ViewOnlyInputComparisonTest {
    @get:Rule val activity = ActivityScenarioRule(ComponentActivity::class.java)
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private var editor: EditText? = null
    private var caseName = "VIEW_ONLY_UNSTARTED"
    private val records = mutableListOf<String>()

    @After fun retireBeforeActivityDestructionAndPublishScalarOutcomes() {
        try { Espresso.closeSoftKeyboard() }
        finally {
            editor = null
            instrumentation.addResults(Bundle().apply {
                putString("imeComparison$caseName", records.joinToString("\n"))
            })
        }
    }

    @Test fun detachedViewCannotBecomeASystemTapTarget() {
        caseName = "VIEW_ONLY_GUARD"
        activity.scenario.onActivity { editor = EditText(it) }
        assertThrows(IllegalStateException::class.java) { tapPoint() }
        // Only coordinate validation was called; no input event was constructed or injected.
        records += "kind=$caseName,detachedTargetRejected=true"
    }

    @Test fun nativeEditTextWithoutComposeOwnerShowsKeyboardAfterOneTap() {
        caseName = "VIEW_ONLY"
        val rounds = imeComparisonRounds(InstrumentationRegistry.getArguments().getString("imeComparisonRounds"))
        repeat(rounds) { round ->
            var phase = "SETUP"
            var tapAttempted = false
            var before: NativeInputWindow? = null
            try {
                if (round > 0) {
                    phase = "RETIRE"
                    Espresso.closeSoftKeyboard()
                    awaitState { it.imeVisible == false && it.imeBottom == 0 }
                }
                phase = "SETUP"
                activity.scenario.onActivity { owner ->
                    val density = owner.resources.displayMetrics.density
                    val parent = FrameLayout(owner)
                    val field = EditText(owner).apply {
                        setText("Input fixture")
                        isSaveEnabled = false
                    }
                    parent.addView(field, FrameLayout.LayoutParams(
                        (280 * density).roundToInt(), (56 * density).roundToInt()))
                    owner.setContentView(parent)
                    editor = field
                }
                phase = "DISPLAY"
                awaitState { it.attached && it.shown }
                before = nativeInputWindow()
                val point = tapPoint()
                phase = "TAP"
                tapAttempted = true
                injectNativeTap(point, SystemClock::uptimeMillis) {
                    instrumentation.uiAutomation.injectInputEvent(it, true)
                }
                phase = "IME"
                val state = awaitState { it.shown && it.focused &&
                    it.imeVisible == true && (it.imeBottom ?: 0) > 0 }
                val after = nativeInputWindow()
                assertEquals(true, after.windowFocused)
                assertEquals(true, after.viewFocused)
                assertEquals(true, after.textEditor)
                assertEquals(true, after.imeVisible)
                assertTrue((after.imeBottom ?: 0) > 0)
                records += "kind=$caseName,round=$round,passed=true,before=$before,after=$after,state=$state"
            } catch (failure: Throwable) {
                if (failure is VirtualMachineError || failure is ThreadDeath) throw failure
                val after = nativeInputWindow()
                val server = nativeImeServerAfterFailure()
                records += "kind=$caseName,round=$round,passed=false,phase=$phase,tapAttempted=$tapAttempted," +
                    "before=$before,after=$after,server=$server"
                // No retry of a failed tap, hidden keyboard repair or continuation to a later green.
                throw AssertionError(records.joinToString("\n"), failure)
            }
        }
    }

    /** Real View and native root insets only; no listener installation or IMM call during polls. */
    private data class ViewState(val attached: Boolean, val shown: Boolean, val focused: Boolean,
        val imeVisible: Boolean?, val imeBottom: Int?)

    private fun sample(): ViewState = onMain {
        val field = checkNotNull(editor) { "View-only fixture is not installed" }
        val visible = AndroidRect()
        val insets = ViewCompat.getRootWindowInsets(field.rootView)
        ViewState(field.isAttachedToWindow, field.isShown && field.getLocalVisibleRect(visible) && !visible.isEmpty,
            field.hasFocus(), insets?.isVisible(WindowInsetsCompat.Type.ime()),
            insets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom)
    }

    /** Preserve the five-second wall-clock contract; no test clock, focus/show or readiness repair. */
    private fun awaitState(predicate: (ViewState) -> Boolean): ViewState {
        val started = SystemClock.uptimeMillis()
        while (true) {
            val state = sample()
            check(SystemClock.uptimeMillis() - started <= 5_000) { "View-only predicate exceeded5000ms" }
            if (predicate(state)) return state
            SystemClock.sleep(10)
        }
    }

    private fun tapPoint(): Offset = onMain {
        val field = checkNotNull(editor) { "View-only fixture is not installed" }
        check(field.isAttachedToWindow && field.isShown) { "View-only tap target is detached or hidden" }
        val visible = AndroidRect()
        check(field.getLocalVisibleRect(visible) && !visible.isEmpty) { "View-only tap target is clipped" }
        val window = IntArray(2).also(field::getLocationInWindow)
        val screen = IntArray(2).also(field::getLocationOnScreen)
        nativeTapPoint(Rect((visible.left + window[0]).toFloat(), (visible.top + window[1]).toFloat(),
            (visible.right + window[0]).toFloat(), (visible.bottom + window[1]).toFloat()),
            Offset(window[0].toFloat(), window[1].toFloat()),
            Offset(screen[0].toFloat(), screen[1].toFloat()), field.display?.displayId)
    }

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return checkNotNull(result).getOrThrow()
    }
}

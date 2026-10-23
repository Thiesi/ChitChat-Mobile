package io.github.thiesi.chitchat.testing

import android.accessibilityservice.AccessibilityService
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
import java.util.ArrayDeque
import kotlin.math.roundToInt

internal enum class ViewOnlyCleanup { ESPRESSO, BACK }
internal enum class ViewOnlyHierarchy { REPLACE, RETAIN }
internal fun viewOnlyCleanup(raw: String?): ViewOnlyCleanup = when (raw) {
    null, "espresso" -> ViewOnlyCleanup.ESPRESSO
    "global-back" -> ViewOnlyCleanup.BACK
    else -> throw IllegalArgumentException("Unknown View-only cleanup plan")
}
internal fun viewOnlyHierarchy(raw: String?): ViewOnlyHierarchy = when (raw) {
    null, "replace" -> ViewOnlyHierarchy.REPLACE
    "retain" -> ViewOnlyHierarchy.RETAIN
    else -> throw IllegalArgumentException("Unknown View-only hierarchy plan")
}

class ViewOnlyPlanTest {
    @Test fun defaultsRetainPublishedEspressoReplacementContract() {
        assertEquals(ViewOnlyCleanup.ESPRESSO, viewOnlyCleanup(null))
        assertEquals(ViewOnlyCleanup.ESPRESSO, viewOnlyCleanup("espresso"))
        assertEquals(ViewOnlyHierarchy.REPLACE, viewOnlyHierarchy(null))
        assertEquals(ViewOnlyHierarchy.REPLACE, viewOnlyHierarchy("replace"))
        assertEquals(ViewOnlyCleanup.BACK, viewOnlyCleanup("global-back"))
        assertEquals(ViewOnlyHierarchy.RETAIN, viewOnlyHierarchy("retain"))
    }
    @Test fun unknownPlansFailInsteadOfSilentlyChangingTheComparison() {
        listOf("", "BACK", "private-canary").forEach {
            assertThrows(IllegalArgumentException::class.java) { viewOnlyCleanup(it) }
            assertThrows(IllegalArgumentException::class.java) { viewOnlyHierarchy(it) }
        }
    }
}

internal data class ViewOnlyState(val attached: Boolean, val shown: Boolean, val focused: Boolean,
    val windowFocused: Boolean, val imeVisible: Boolean?, val imeBottom: Int?)
internal data class ViewOnlyTransition(val receivedUptimeMillis: Long, val state: ViewOnlyState)
internal data class ViewOnlyTraceSnapshot(val polls: Int, val knownImePolls: Int, val visibleImePolls: Int,
    val maximumImeBottom: Int?, val discardedTransitions: Int, val transitions: List<ViewOnlyTransition>)

/** Test-thread scalar observation, not a View/connection/content retention or input interceptor. */
internal class ViewOnlyTrace(private val capacity: Int = 32,
    private val uptime: () -> Long = SystemClock::uptimeMillis) {
    private var polls = 0
    private var knownIme = 0
    private var visibleIme = 0
    private var maximumBottom: Int? = null
    private var discarded = 0
    private val transitions = ArrayDeque<ViewOnlyTransition>()
    init { require(capacity in 1..32) }
    fun observe(state: ViewOnlyState) {
        polls++
        if (state.imeVisible != null) knownIme++
        if (state.imeVisible == true) visibleIme++
        state.imeBottom?.let { maximumBottom = maxOf(maximumBottom ?: it, it) }
        if (transitions.peekLast()?.state != state) {
            if (transitions.size == capacity) { transitions.removeFirst(); discarded++ }
            transitions.addLast(ViewOnlyTransition(uptime(), state))
        }
    }
    fun snapshot() = ViewOnlyTraceSnapshot(polls, knownIme, visibleIme, maximumBottom,
        discarded, transitions.toList())
}

class ViewOnlyTraceTest {
    @Test fun duplicateStatesDoNotGrowTheBoundedTransitionHistory() {
        var clock = 0L
        val trace = ViewOnlyTrace(3) { ++clock }
        val initial = ViewOnlyState(true, true, false, true, false, 0)
        repeat(7) { trace.observe(initial) }
        assertEquals(1, trace.snapshot().transitions.size)
        repeat(6) { trace.observe(initial.copy(focused = it % 2 == 0)) }
        val result = trace.snapshot()
        assertEquals(13, result.polls)
        assertEquals(13, result.knownImePolls)
        assertEquals(0, result.visibleImePolls)
        assertEquals(3, result.transitions.size)
        assertEquals(4, result.discardedTransitions)
        assertEquals(listOf(5L, 6L, 7L), result.transitions.map { it.receivedUptimeMillis })
    }
    @Test fun unknownInsetsStayUnknownAndInvalidBoundsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { ViewOnlyTrace(0) }
        assertThrows(IllegalArgumentException::class.java) { ViewOnlyTrace(33) }
        val trace = ViewOnlyTrace { 1L }
        val unknown = ViewOnlyState(true, true, true, true, null, null)
        trace.observe(unknown)
        assertEquals(0, trace.snapshot().knownImePolls)
        assertNull(trace.snapshot().maximumImeBottom)
        assertNull(trace.snapshot().transitions.single().state.imeVisible)
        trace.observe(unknown.copy(imeVisible = false, imeBottom = 0))
        trace.observe(unknown.copy(imeVisible = true, imeBottom = 42))
        assertEquals(2, trace.snapshot().knownImePolls)
        assertEquals(1, trace.snapshot().visibleImePolls)
        assertEquals(42, trace.snapshot().maximumImeBottom)
    }
}

/** No Compose test rule, composition, AndroidView or Compose owner. Geometry value types and the
 * existing single-injection helper are reused, not the Compose input pipeline or its inset state.
 */
class ViewOnlyInputComparisonTest {
    @get:Rule val activity = ActivityScenarioRule(ComponentActivity::class.java)
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private var editor: EditText? = null
    private var caseName = "VIEW_ONLY_UNSTARTED"
    private val records = mutableListOf<String>()
    private var cleanup = ViewOnlyCleanup.ESPRESSO
    private data class RetirementObservation(var before: ViewOnlyState? = null, var actionAccepted: Boolean? = null)

    @After fun retireBeforeActivityDestructionAndPublishScalarOutcomes() {
        try {
            if (cleanup == ViewOnlyCleanup.ESPRESSO) Espresso.closeSoftKeyboard()
            else {
                val state = sample()
                if (state.imeVisible == true) closeImeAfterSuccessfulTrial()
                else check(state.imeVisible == false && state.imeBottom == 0) { "Unknown View-only cleanup state" }
            }
        } catch (failure: Throwable) {
            records += "kind=$caseName,phase=FINAL_RETIRE,error=${failure.javaClass.simpleName}"
            throw failure
        }
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
        val arguments = InstrumentationRegistry.getArguments()
        cleanup = viewOnlyCleanup(arguments.getString("imeComparisonCleanup"))
        val hierarchy = viewOnlyHierarchy(arguments.getString("imeComparisonHierarchy"))
        caseName = (if (cleanup == ViewOnlyCleanup.BACK) "VIEW_ONLY_BACK" else "VIEW_ONLY") +
            (if (hierarchy == ViewOnlyHierarchy.RETAIN) "_RETAINED" else "")
        val rounds = imeComparisonRounds(arguments.getString("imeComparisonRounds"))
        repeat(rounds) { round ->
            var phase = "SETUP"
            var tapAttempted = false
            var before: NativeInputWindow? = null
            val trace = ViewOnlyTrace()
            val retirement = RetirementObservation()
            try {
                if (round > 0) {
                    phase = "RETIRE"
                    closeImeAfterSuccessfulTrial(retirement)
                }
                phase = "SETUP"
                if (round == 0 || hierarchy == ViewOnlyHierarchy.REPLACE) activity.scenario.onActivity { owner ->
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
                val state = awaitState(trace) { it.shown && it.focused &&
                    it.imeVisible == true && (it.imeBottom ?: 0) > 0 }
                phase = "VERIFY"
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
                    "before=$before,after=$after,retirement=$retirement,observations=${trace.snapshot()},server=$server"
                // No retry of a failed tap, hidden keyboard repair or continuation to a later green.
                throw AssertionError(records.joinToString("\n"), failure)
            }
        }
    }

    /** Real View and native root insets only; no listener installation or IMM call during polls. */
    private fun sample(): ViewOnlyState = onMain {
        val field = checkNotNull(editor) { "View-only fixture is not installed" }
        val visible = AndroidRect()
        val insets = ViewCompat.getRootWindowInsets(field.rootView)
        ViewOnlyState(field.isAttachedToWindow, field.isShown && field.getLocalVisibleRect(visible) && !visible.isEmpty,
            field.hasFocus(), field.hasWindowFocus(), insets?.isVisible(WindowInsetsCompat.Type.ime()),
            insets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom)
    }

    /** Preserve the five-second wall-clock contract; no test clock, focus/show or readiness repair. */
    private fun awaitState(trace: ViewOnlyTrace? = null, predicate: (ViewOnlyState) -> Boolean): ViewOnlyState {
        val started = SystemClock.uptimeMillis()
        while (true) {
            val state = sample()
            trace?.observe(state)
            check(SystemClock.uptimeMillis() - started <= 5_000) { "View-only predicate exceeded5000ms" }
            if (predicate(state)) return state
            SystemClock.sleep(10)
        }
    }

    /** A diagnostic alternative ONLY between successful trials, never an active-input rescue. */
    private fun closeImeAfterSuccessfulTrial(observation: RetirementObservation? = null) {
        if (cleanup == ViewOnlyCleanup.ESPRESSO) Espresso.closeSoftKeyboard()
        else {
            val state = sample()
            observation?.before = state
            check(state.windowFocused && state.imeVisible == true && (state.imeBottom ?: 0) > 0) {
                "System Back retirement requires the owned focused keyboard"
            }
            val accepted = instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            observation?.actionAccepted = accepted
            check(accepted) { "System rejected global Back retirement" }
        }
        awaitState { it.imeVisible == false && it.imeBottom == 0 }
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

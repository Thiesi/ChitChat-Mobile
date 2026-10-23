package io.github.thiesi.chitchat.testing

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
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
internal fun viewOnlyPrivateDevice(raw: String?): Boolean = when (raw) {
    null, "false" -> false
    "true" -> true
    else -> throw IllegalArgumentException("Invalid private-device input plan")
}
internal fun viewOnlyPrivatePlanAllowed(cleanup: ViewOnlyCleanup, hierarchy: ViewOnlyHierarchy) =
    cleanup == ViewOnlyCleanup.ESPRESSO && hierarchy == ViewOnlyHierarchy.REPLACE
internal fun NativeInputWindow.provesOwnedPrivateInputWindow() =
    resumedActivities == 1 && attached == true && windowFocused == true

/** Structural point ownership only, never an IME/focus-readiness predicate. */
internal fun ViewOnlyGeometry.provesPointOutsideSystemUi(): Boolean {
    val left = safeLeft ?: return false
    val top = safeTop ?: return false
    val right = safeRight ?: return false
    val bottom = safeBottom ?: return false
    if (!pointX.isFinite() || !pointY.isFinite() || rootWidth <= 0 || rootHeight <= 0 ||
        minOf(left, top, right, bottom) < 0 ||
        left.toLong() + right >= rootWidth || top.toLong() + bottom >= rootHeight) return false
    return pointX >= rootScreenLeft.toLong() + left && pointX < rootScreenLeft.toLong() + rootWidth - right &&
        pointY >= rootScreenTop.toLong() + top && pointY < rootScreenTop.toLong() + rootHeight - bottom
}

class ViewOnlyPlanTest {
    @Test fun defaultsRetainPublishedEspressoReplacementContract() {
        assertEquals(ViewOnlyCleanup.ESPRESSO, viewOnlyCleanup(null))
        assertEquals(ViewOnlyCleanup.ESPRESSO, viewOnlyCleanup("espresso"))
        assertEquals(ViewOnlyHierarchy.REPLACE, viewOnlyHierarchy(null))
        assertEquals(ViewOnlyHierarchy.REPLACE, viewOnlyHierarchy("replace"))
        assertEquals(ViewOnlyCleanup.BACK, viewOnlyCleanup("global-back"))
        assertEquals(ViewOnlyHierarchy.RETAIN, viewOnlyHierarchy("retain"))
        assertFalse(viewOnlyPrivateDevice(null))
        assertFalse(viewOnlyPrivateDevice("false"))
        assertTrue(viewOnlyPrivateDevice("true"))
        assertTrue(viewOnlyPrivatePlanAllowed(ViewOnlyCleanup.ESPRESSO, ViewOnlyHierarchy.REPLACE))
        assertFalse(viewOnlyPrivatePlanAllowed(ViewOnlyCleanup.BACK, ViewOnlyHierarchy.REPLACE))
        assertFalse(viewOnlyPrivatePlanAllowed(ViewOnlyCleanup.ESPRESSO, ViewOnlyHierarchy.RETAIN))
        val phone = ViewOnlyGeometry(0, 525f, 105f, 0, 0, 1050, 210, 0, 0, 1050, 210,
            0, 0, 1440, 3120, 0, 149, 0, 56)
        assertFalse(phone.provesPointOutsideSystemUi()) // Recorded original phone point.
        assertTrue(phone.copy(pointY = 254f, fieldScreenTop = 149).provesPointOutsideSystemUi())
        assertTrue(phone.copy(pointY = 149f).provesPointOutsideSystemUi())
        assertFalse(phone.copy(pointY = 3064f).provesPointOutsideSystemUi())
    }
    @Test fun unknownPlansFailInsteadOfSilentlyChangingTheComparison() {
        listOf("", "BACK", "private-canary").forEach {
            assertThrows(IllegalArgumentException::class.java) { viewOnlyCleanup(it) }
            assertThrows(IllegalArgumentException::class.java) { viewOnlyHierarchy(it) }
            assertThrows(IllegalArgumentException::class.java) { viewOnlyPrivateDevice(it) }
        }
        val owned = NativeInputWindow(uptimeMillis = 0, resumedActivities = 1, attached = true, windowFocused = true)
        assertTrue(owned.provesOwnedPrivateInputWindow())
        assertFalse(owned.copy(windowFocused = null).provesOwnedPrivateInputWindow())
        assertFalse(owned.copy(resumedActivities = 0).provesOwnedPrivateInputWindow())
        val phone = ViewOnlyGeometry(0, 525f, 254f, 0, 149, 1050, 210, 0, 0, 1050, 210,
            0, 0, 1440, 3120, 0, 149, 0, 56)
        assertFalse(phone.copy(safeTop = null).provesPointOutsideSystemUi())
        assertFalse(phone.copy(safeTop = -1).provesPointOutsideSystemUi())
        assertFalse(phone.copy(rootWidth = 0).provesPointOutsideSystemUi())
        assertFalse(phone.copy(safeBottom = 3120).provesPointOutsideSystemUi())
        assertFalse(phone.copy(pointX = Float.NaN).provesPointOutsideSystemUi())
        assertFalse(phone.copy(pointY = Float.POSITIVE_INFINITY).provesPointOutsideSystemUi())
    }
}

internal data class ViewOnlyState(val attached: Boolean, val shown: Boolean, val focused: Boolean,
    val windowFocused: Boolean, val imeVisible: Boolean?, val imeBottom: Int?)
internal data class ViewOnlyTransition(val receivedUptimeMillis: Long, val state: ViewOnlyState)
internal data class ViewOnlyTraceSnapshot(val polls: Int, val knownImePolls: Int, val visibleImePolls: Int,
    val maximumImeBottom: Int?, val discardedTransitions: Int, val transitions: List<ViewOnlyTransition>)

/** Only this fixture's coordinates/insets. No View, text, other window or input interception. */
internal data class ViewOnlyGeometry(val receivedUptimeMillis: Long, val pointX: Float, val pointY: Float,
    val fieldScreenLeft: Int, val fieldScreenTop: Int, val fieldWidth: Int, val fieldHeight: Int,
    val clippedLeft: Int, val clippedTop: Int, val clippedRight: Int, val clippedBottom: Int,
    val rootScreenLeft: Int, val rootScreenTop: Int, val rootWidth: Int, val rootHeight: Int,
    val safeLeft: Int?, val safeTop: Int?, val safeRight: Int?, val safeBottom: Int?)

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
    private val privateDevice = viewOnlyPrivateDevice(
        InstrumentationRegistry.getArguments().getString("nativeInputPrivateDevice"))
    private var geometryOnly = false
    private data class RetirementObservation(var before: ViewOnlyState? = null, var actionAccepted: Boolean? = null)

    @After fun retireBeforeActivityDestructionAndPublishScalarOutcomes() {
        try {
            // The observation never injects input or explicitly controls IME. The detached guard
            // cannot own an input session. Never hide another window's keyboard on a private phone.
            if (!geometryOnly && !(privateDevice && caseName == "VIEW_ONLY_GUARD")) {
                if (privateDevice) check(onMain { editor?.let {
                    it.isAttachedToWindow && it.hasWindowFocus()
                } == true }) { "Private fixture no longer owns cleanup; no keyboard action" }
                if (cleanup == ViewOnlyCleanup.ESPRESSO) Espresso.closeSoftKeyboard()
                else {
                    val state = sample()
                    if (state.imeVisible == true) closeImeAfterSuccessfulTrial()
                    else check(state.imeVisible == false && state.imeBottom == 0) { "Unknown View-only cleanup state" }
                }
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

    /** Observe fixture geometry; this is deliberately NOT a keyboard trial. */
    @Test fun observeFixtureTapGeometryWithoutInputInjection() {
        geometryOnly = true
        caseName = "VIEW_ONLY_GEOMETRY"
        installFixture()
        awaitState { it.attached && it.shown }
        val before = nativeInputWindow()
        assertPrivateWindowOwned(before)
        val geometry = onMain {
            val field = checkNotNull(editor)
            check(field.isAttachedToWindow && field.isShown) { "Geometry fixture detached or hidden" }
            if (privateDevice) check(field.hasWindowFocus()) { "Geometry fixture window not owned" }
            val visible = AndroidRect()
            check(field.getLocalVisibleRect(visible) && !visible.isEmpty) { "Geometry fixture clipped" }
            val window = IntArray(2).also(field::getLocationInWindow)
            val screen = IntArray(2).also(field::getLocationOnScreen)
            val point = nativeTapPoint(Rect((visible.left + window[0]).toFloat(), (visible.top + window[1]).toFloat(),
                (visible.right + window[0]).toFloat(), (visible.bottom + window[1]).toFloat()),
                Offset(window[0].toFloat(), window[1].toFloat()),
                Offset(screen[0].toFloat(), screen[1].toFloat()), field.display?.displayId)
            geometryOf(field, visible, point, screen)
        }
        records += "kind=$caseName,inputInjected=false,before=$before,geometry=$geometry"
        assertTrue("Fixture tap point overlaps system UI or its bounds are unknown", geometry.provesPointOutsideSystemUi())
    }

    @Test fun nativeEditTextWithoutComposeOwnerShowsKeyboardAfterOneTap() {
        val arguments = InstrumentationRegistry.getArguments()
        val requestedCleanup = viewOnlyCleanup(arguments.getString("imeComparisonCleanup"))
        val hierarchy = viewOnlyHierarchy(arguments.getString("imeComparisonHierarchy"))
        require(!privateDevice || viewOnlyPrivatePlanAllowed(requestedCleanup, hierarchy)) {
            "Private-device fixture requires default cleanup and hierarchy"
        }
        cleanup = requestedCleanup
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
                if (round == 0 || hierarchy == ViewOnlyHierarchy.REPLACE) installFixture()
                phase = "DISPLAY"
                awaitState { it.attached && it.shown }
                before = nativeInputWindow()
                if (privateDevice) {
                    phase = "OWNERSHIP"
                    assertPrivateWindowOwned(before)
                }
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
                val server = if (privateDevice) "omitted for private-device privacy" else nativeImeServerAfterFailure().toString()
                records += "kind=$caseName,round=$round,passed=false,phase=$phase,tapAttempted=$tapAttempted," +
                    "before=$before,after=$after,retirement=$retirement,observations=${trace.snapshot()},server=$server"
                // No retry of a failed tap, hidden keyboard repair or continuation to a later green.
                throw AssertionError(records.joinToString("\n"), failure)
            }
        }
    }

    private fun installFixture() = activity.scenario.onActivity { owner ->
        val density = owner.resources.displayMetrics.density
        val parent = FrameLayout(owner)
        ViewCompat.setOnApplyWindowInsetsListener(parent) { view, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            insets // Preserve IME/native inset observations; no keyboard type is consumed.
        }
        val field = EditText(owner).apply { setText("Input fixture"); isSaveEnabled = false }
        parent.addView(field, FrameLayout.LayoutParams(
            (280 * density).roundToInt(), (56 * density).roundToInt()))
        owner.setContentView(parent)
        editor = field
    }

    private fun assertPrivateWindowOwned(before: NativeInputWindow) {
        if (privateDevice) {
            check(before.provesOwnedPrivateInputWindow()) { "Fixture window not owned; no input action" }
            val locked = onMain {
                val guard = checkNotNull(checkNotNull(editor).context.getSystemService(KeyguardManager::class.java))
                guard.isDeviceLocked || guard.isKeyguardLocked
            }
            check(!locked) { "Private device locked; no input action" }
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
        if (privateDevice) check(field.hasWindowFocus()) { "Fixture no longer owns its window; no tap injected" }
        val visible = AndroidRect()
        check(field.getLocalVisibleRect(visible) && !visible.isEmpty) { "View-only tap target is clipped" }
        val window = IntArray(2).also(field::getLocationInWindow)
        val screen = IntArray(2).also(field::getLocationOnScreen)
        val point = nativeTapPoint(Rect((visible.left + window[0]).toFloat(), (visible.top + window[1]).toFloat(),
            (visible.right + window[0]).toFloat(), (visible.bottom + window[1]).toFloat()),
            Offset(window[0].toFloat(), window[1].toFloat()),
            Offset(screen[0].toFloat(), screen[1].toFloat()), field.display?.displayId)
        if (privateDevice) check(geometryOf(field, visible, point, screen).provesPointOutsideSystemUi()) {
            "Private fixture tap is outside its system-UI-safe bounds; no input injected"
        }
        point
    }

    /** Main-thread own-root values only, not a global window query or control request. */
    private fun geometryOf(field: EditText, visible: AndroidRect, point: Offset, screen: IntArray): ViewOnlyGeometry {
        val root = field.rootView
        val origin = IntArray(2).also(root::getLocationOnScreen)
        val safe = ViewCompat.getRootWindowInsets(root)?.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        return ViewOnlyGeometry(SystemClock.uptimeMillis(), point.x, point.y,
            screen[0], screen[1], field.width, field.height,
            visible.left, visible.top, visible.right, visible.bottom,
            origin[0], origin[1], root.width, root.height,
            safe?.left, safe?.top, safe?.right, safe?.bottom)
    }

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return checkNotNull(result).getOrThrow()
    }
}

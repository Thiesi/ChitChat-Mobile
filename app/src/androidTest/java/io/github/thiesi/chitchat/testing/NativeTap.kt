package io.github.thiesi.chitchat.testing

import android.os.SystemClock
import android.view.Display
import android.view.InputDevice
import android.view.MotionEvent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.test.platform.app.InstrumentationRegistry

/** One system-routed finger tap, not a semantics click or direct Compose-root View dispatch.
 * Default-display instrumentation only. Never requests focus/IME, waits for readiness, or retries.
 */
internal fun SemanticsNodeInteraction.performNativeTap(): SemanticsNodeInteraction {
    assertIsDisplayed()
    val node = fetchSemanticsNode()
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    var point = Offset.Unspecified
    instrumentation.runOnMainSync {
        // The clipped window bounds choose a visible point; the two origins account for dialogs,
        // system bars and offset windows. Detached nodes have empty bounds and must not be tapped.
        val display = (node.root as? ViewRootForTest)?.view?.display?.displayId
        point = nativeTapPoint(node.boundsInWindow, node.positionInWindow, node.positionOnScreen, display)
    }
    injectNativeTap(point, SystemClock::uptimeMillis) { instrumentation.uiAutomation.injectInputEvent(it, true) }
    return this
}

internal fun nativeTapPoint(bounds: Rect, positionInWindow: Offset, positionOnScreen: Offset,
    displayId: Int? = Display.DEFAULT_DISPLAY): Offset {
    require(displayId == Display.DEFAULT_DISPLAY) { "Native tap requires an attached default-display owner" }
    require(listOf(bounds.left, bounds.top, bounds.right, bounds.bottom,
        positionInWindow.x, positionInWindow.y, positionOnScreen.x, positionOnScreen.y).all { it.isFinite() } &&
        bounds.width > 0 && bounds.height > 0) { "Native tap target is detached, clipped or non-finite" }
    return (bounds.center + (positionOnScreen - positionInWindow)).also {
        require(it.x.isFinite() && it.y.isFinite()) { "Native tap point is non-finite" }
    }
}

/** Inject once. A failed down/up remains a failure; cancel only retires a possibly held pointer. */
internal fun injectNativeTap(point: Offset, uptime: () -> Long, inject: (MotionEvent) -> Boolean) {
    require(point.x.isFinite() && point.y.isFinite()) { "Native tap point is non-finite" }
    val downTime = uptime()
    fun send(action: Int) {
        val event = MotionEvent.obtain(downTime, uptime(), action, 1,
            arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER }),
            arrayOf(MotionEvent.PointerCoords().apply {
                x = point.x; y = point.y; pressure = if (action == MotionEvent.ACTION_DOWN) 1f else 0f; size = 1f
            }), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        try { check(inject(event)) { "System rejected native input action=$action" } }
        finally { event.recycle() }
    }
    try {
        send(MotionEvent.ACTION_DOWN)
        send(MotionEvent.ACTION_UP)
    } catch (failure: Throwable) {
        // No second down/up and no keyboard/focus repair. Preserve the original error even if the
        // OS also rejects cancellation; do not leave a possibly injected down held between tests.
        runCatching { send(MotionEvent.ACTION_CANCEL) }.exceptionOrNull()?.let(failure::addSuppressed)
        throw failure
    }
}

package io.github.thiesi.chitchat.testing

import android.os.Build
import android.os.SystemClock
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.annotation.RequiresApi
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage

/** Test-only scalar metadata. Never retain an Activity/View, text, editor contents or window title. */
internal data class NativeInputWindow(
    val uptimeMillis: Long = SystemClock.uptimeMillis(),
    val resumedActivities: Int? = null,
    val windowFocused: Boolean? = null,
    val attached: Boolean? = null,
    val viewFocused: Boolean? = null,
    val inTouchMode: Boolean? = null,
    val textEditor: Boolean? = null,
    val imeVisible: Boolean? = null,
    val imeBottom: Int? = null,
    val softInputMode: Int? = null,
    val imeControllable: Boolean? = null,
    val configurationOrientation: Int? = null,
    val configurationWidthDp: Int? = null,
    val configurationHeightDp: Int? = null,
)

/** Observe, don't request focus/show/hide, restart input, or call IMM getters that flush focus. */
internal fun nativeInputWindow(): NativeInputWindow {
    var result = NativeInputWindow()
    runCatching {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            result = runCatching {
                val resumed = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                val activity = resumed.singleOrNull()
                val decor = activity?.window?.decorView
                val focused = decor?.findFocus()
                val insets = decor?.let(ViewCompat::getRootWindowInsets)
                val configuration = activity?.resources?.configuration
                NativeInputWindow(resumedActivities = resumed.size, windowFocused = decor?.hasWindowFocus(),
                    attached = decor?.isAttachedToWindow, viewFocused = decor?.hasFocus(),
                    inTouchMode = decor?.isInTouchMode,
                    textEditor = focused?.onCheckIsTextEditor(),
                    imeVisible = insets?.isVisible(WindowInsetsCompat.Type.ime()),
                    imeBottom = insets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom,
                    softInputMode = activity?.window?.attributes?.softInputMode,
                    imeControllable = if (Build.VERSION.SDK_INT >= 30 && decor != null)
                        observeImeControl(decor) else null,
                    configurationOrientation = configuration?.orientation,
                    configurationWidthDp = configuration?.screenWidthDp,
                    configurationHeightDp = configuration?.screenHeightDp)
            }.getOrDefault(NativeInputWindow())
        }
    }
    return result // Missing/ambiguous metadata stays null, never a fabricated "IME hidden".
}

/** Failure-only target owner, which can be a Dialog rather than the resumed Activity.
 * Never fall back to another window, retain the node/View, or use this as tap readiness.
 * No controllability listener is installed; these later scalars are not deadline proof.
 */
internal fun nativeInputTargetWindow(node: SemanticsNode?): NativeInputWindow {
    var result = NativeInputWindow()
    runCatching {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            result = runCatching {
                val view = (node?.root as? ViewRootForTest)?.view
                val focused = view?.findFocus()
                val insets = view?.let(ViewCompat::getRootWindowInsets)
                val configuration = view?.resources?.configuration
                NativeInputWindow(windowFocused = view?.hasWindowFocus(),
                    attached = view?.isAttachedToWindow, viewFocused = view?.hasFocus(),
                    inTouchMode = view?.isInTouchMode, textEditor = focused?.onCheckIsTextEditor(),
                    imeVisible = insets?.isVisible(WindowInsetsCompat.Type.ime()),
                    imeBottom = insets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom,
                    softInputMode = (view?.rootView?.layoutParams as? WindowManager.LayoutParams)?.softInputMode,
                    configurationOrientation = configuration?.orientation,
                    configurationWidthDp = configuration?.screenWidthDp,
                    configurationHeightDp = configuration?.screenHeightDp)
            }.getOrDefault(NativeInputWindow())
        }
    }
    return result
}

/** Main-thread, bounded observation only. No callback received means unknown, not uncontrollable.
 * Controllability is NOT a readiness predicate or a guarantee that the IME will show.
 */
@RequiresApi(30)
private fun observeImeControl(decor: View): Boolean? = runCatching {
    val controller = decor.windowInsetsController ?: return@runCatching null
    var observed: Boolean? = null
    val listener = WindowInsetsController.OnControllableInsetsChangedListener { _, types ->
        observed = types and WindowInsets.Type.ime() != 0
    }
    try {
        controller.addOnControllableInsetsChangedListener(listener)
        observed
    } finally {
        controller.removeOnControllableInsetsChangedListener(listener)
    }
}.getOrNull()

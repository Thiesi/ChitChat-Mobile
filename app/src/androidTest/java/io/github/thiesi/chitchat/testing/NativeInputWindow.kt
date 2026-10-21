package io.github.thiesi.chitchat.testing

import android.os.SystemClock
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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
    val textEditor: Boolean? = null,
    val imeVisible: Boolean? = null,
    val imeBottom: Int? = null,
    val softInputMode: Int? = null,
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
                NativeInputWindow(resumedActivities = resumed.size, windowFocused = decor?.hasWindowFocus(),
                    attached = decor?.isAttachedToWindow, viewFocused = decor?.hasFocus(),
                    textEditor = focused?.onCheckIsTextEditor(),
                    imeVisible = insets?.isVisible(WindowInsetsCompat.Type.ime()),
                    imeBottom = insets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom,
                    softInputMode = activity?.window?.attributes?.softInputMode)
            }.getOrDefault(NativeInputWindow())
        }
    }
    return result // Missing/ambiguous metadata stays null, never a fabricated "IME hidden".
}

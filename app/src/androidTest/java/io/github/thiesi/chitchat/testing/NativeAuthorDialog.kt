package io.github.thiesi.chitchat.testing

import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry

/** Observe the actual editor window AFTER the user's tap; never readiness or a focus/IME request. */
internal fun ComposeTestRule.nativeAuthorImeBottom(): Int {
    val node = onNodeWithTag("author_edit_body").fetchSemanticsNode()
    var bottom = 0
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
        val view = (node.root as? ViewRootForTest)?.view
        val insets = view?.let(ViewCompat::getRootWindowInsets)
        if (insets?.isVisible(WindowInsetsCompat.Type.ime()) == true)
            bottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
    }
    return bottom // Missing target-window information fails closed.
}

internal class NativeAuthorSaveGeometry(val pointY: Float, val keyboardTop: Int?, val details: String)

/** Own fixture windows/scalars only. No retained Views, titles, dumps, text or synthetic readiness. */
internal fun ComposeTestRule.nativeAuthorSaveGeometry(activity: ComponentActivity, activityIme: Int): NativeAuthorSaveGeometry {
    val node = onNodeWithTag("author_change_confirm").fetchSemanticsNode()
    var result: NativeAuthorSaveGeometry? = null
    runOnIdle {
        val view = (node.root as? ViewRootForTest)?.view
        val dialogFrame = android.graphics.Rect(); view?.getWindowVisibleDisplayFrame(dialogFrame)
        val decor = activity.window.decorView
        val activityFrame = android.graphics.Rect(); decor.getWindowVisibleDisplayFrame(activityFrame)
        val activityLocation = IntArray(2); decor.getLocationOnScreen(activityLocation)
        val dialogLocation = IntArray(2); view?.getLocationOnScreen(dialogLocation)
        val activityInsets = ViewCompat.getRootWindowInsets(decor)
        val dialogInsets = view?.let(ViewCompat::getRootWindowInsets)
        val point = nativeTapPoint(node.boundsInWindow, node.positionInWindow, node.positionOnScreen, view?.display?.displayId)
        val keyboardTop = dialogInsets?.takeIf { it.isVisible(WindowInsetsCompat.Type.ime()) }
            ?.getInsets(WindowInsetsCompat.Type.ime())?.bottom?.let { dialogLocation[1] + checkNotNull(view).height - it }
        val details = "bounds=${node.boundsInWindow}, point=$point, dialogView=(${dialogLocation[0]},${dialogLocation[1]},${view?.width},${view?.height}), " +
            "dialogFrame=$dialogFrame, dialogIme=${dialogInsets?.isVisible(WindowInsetsCompat.Type.ime())}/${dialogInsets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom}, " +
            "activityView=(${activityLocation[0]},${activityLocation[1]},${decor.width},${decor.height}), activityFrame=$activityFrame, " +
            "activityIme=${activityInsets?.isVisible(WindowInsetsCompat.Type.ime())}/${activityInsets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom}, composeIme=$activityIme"
        result = NativeAuthorSaveGeometry(point.y, keyboardTop, details)
    }
    return checkNotNull(result)
}

/**
 * #70/#284: the editor window's keyboard visibility, recorded as transitions on its layout passes. Observation only: a
 * global-layout listener, which never replaces the dialog's own insets handling, requests focus or touches the IME.
 * [transitions] holds each new visibility (true = shown) after the state at attach time.
 */
internal class AuthorImeTransitions(private val view: android.view.View) : android.view.ViewTreeObserver.OnGlobalLayoutListener {
    private var last: Boolean? = null
    val transitions = java.util.concurrent.CopyOnWriteArrayList<Boolean>()
    fun visible() = ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) == true
    override fun onGlobalLayout() {
        val now = visible()
        if (last != null && now != last) transitions += now
        last = now
    }
    fun attach() = InstrumentationRegistry.getInstrumentation().runOnMainSync {
        last = visible(); view.viewTreeObserver.addOnGlobalLayoutListener(this)
    }
    fun detach() = InstrumentationRegistry.getInstrumentation().runOnMainSync {
        if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnGlobalLayoutListener(this)
    }
}

/** The editor window's transitions recorder, attached now (its window is the author body's). */
internal fun ComposeTestRule.recordAuthorImeTransitions(): AuthorImeTransitions {
    val view = checkNotNull((onNodeWithTag("author_edit_body").fetchSemanticsNode().root as? ViewRootForTest)?.view) { "No editor window" }
    return AuthorImeTransitions(view).also { it.attach() }
}

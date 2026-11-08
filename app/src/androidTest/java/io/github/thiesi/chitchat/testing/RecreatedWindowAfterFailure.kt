package io.github.thiesi.chitchat.testing

import android.os.Build
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.WindowInsets as ComposeInsets
import androidx.compose.ui.unit.Density
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage

/** Only post-failure scalars; missing/ambiguous/private metadata stays unknown. No retained Views. */
internal data class RecreatedWindowAfterFailure(
    val uptimeMillis: Long = SystemClock.uptimeMillis(),
    val activity: Int? = null, val window: Int? = null, val composeRoots: Int? = null,
    val windowHeight: Int? = null, val decorHeight: Int? = null,
    val contentHeight: Int? = null, val rootHeight: Int? = null,
    val root: Int? = null, val rootAttached: Boolean? = null, val focusedRootMatches: Boolean? = null,
    val layoutSystemUiFlags: Int? = null, val contentFitsSystemWindows: Boolean? = null,
    val rootFitsSystemWindows: Boolean? = null, val softInputMode: Int? = null,
    val nativeRootImeBottom: Int? = null, val holderImeBottom: Int? = null,
    val savedDispatchedImeBottom: Int? = null, val savedDispatchPresent: Boolean? = null,
    val listenerPrepared: Boolean? = null, val listenerRunningAnimation: Boolean? = null,
)

@Suppress("DEPRECATION")
internal fun recreatedWindowAfterFailure(): RecreatedWindowAfterFailure {
    var result = RecreatedWindowAfterFailure()
    runCatching { InstrumentationRegistry.getInstrumentation().runOnMainSync {
        result = runCatching snapshot@{
            val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).singleOrNull()
                ?: return@snapshot RecreatedWindowAfterFailure()
            val decor = activity.window.decorView
            val content = decor.findViewById<View>(android.R.id.content)
            val candidates = ArrayList<View>()
            var visited = 0
            var truncated = false
            fun visit(view: View, depth: Int) {
                if (++visited > 128 || depth > 16) { truncated = true; return }
                if (view.javaClass.name == "androidx.compose.ui.platform.AndroidComposeView") candidates.add(view)
                if (view is ViewGroup) for (index in 0 until view.childCount) visit(view.getChildAt(index), depth + 1)
            }
            if (content != null) visit(content, 0)
            val roots = candidates.takeUnless { truncated }
            val root = roots?.singleOrNull()
            val holder = root?.let { view -> runCatching {
                val type = Class.forName("androidx.compose.foundation.layout.WindowInsetsHolder")
                val map = type.getDeclaredField("viewMap").apply { isAccessible = true }.get(null) as Map<*, *>
                synchronized(map) { map[view] }
            }.getOrNull() }
            fun field(owner: Any?, name: String): Any? = owner?.let { value -> runCatching {
                value.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(value)
            }.getOrNull() }
            val listener = field(holder, "insetsListener")
            // Pinned Compose saves the most recent onApplyWindowInsets argument here, but
            // clears it on onEnd. Null therefore does not mean a zero/absent dispatch.
            val savedRead = listener?.let { value -> runCatching {
                value.javaClass.getDeclaredField("savedInsets").apply { isAccessible = true }.get(value) as WindowInsetsCompat?
            } }
            val saved = savedRead?.getOrNull()
            val density = Density(activity.resources.displayMetrics.density, activity.resources.configuration.fontScale)
            RecreatedWindowAfterFailure(activity = System.identityHashCode(activity), window = System.identityHashCode(activity.window),
                composeRoots = roots?.size, windowHeight = if (Build.VERSION.SDK_INT >= 30) activity.windowManager.currentWindowMetrics.bounds.height() else null,
                decorHeight = decor.height, contentHeight = content?.height, rootHeight = root?.height,
                root = root?.let(System::identityHashCode), rootAttached = root?.isAttachedToWindow,
                focusedRootMatches = root?.let { decor.findFocus() === it },
                layoutSystemUiFlags = decor.systemUiVisibility and (View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION),
                contentFitsSystemWindows = content?.fitsSystemWindows, rootFitsSystemWindows = root?.fitsSystemWindows,
                softInputMode = activity.window.attributes.softInputMode,
                nativeRootImeBottom = root?.let(ViewCompat::getRootWindowInsets)?.getInsets(WindowInsetsCompat.Type.ime())?.bottom,
                holderImeBottom = (field(holder, "ime") as? ComposeInsets)?.getBottom(density),
                savedDispatchedImeBottom = saved?.getInsets(WindowInsetsCompat.Type.ime())?.bottom,
                savedDispatchPresent = savedRead?.takeIf { it.isSuccess }?.let { it.getOrNull() != null },
                listenerPrepared = field(listener, "prepared") as? Boolean,
                listenerRunningAnimation = field(listener, "runningAnimation") as? Boolean)
        }.getOrDefault(RecreatedWindowAfterFailure())
    } }
    return result
}

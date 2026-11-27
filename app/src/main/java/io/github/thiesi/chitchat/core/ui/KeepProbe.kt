package io.github.thiesi.chitchat.core.ui

/** DIAGNOSTIC (never merged), #378: log-only probe to logcat (tag CCMKeepProbe), fixture runs only. */
object KeepProbe {
    var outer: androidx.compose.foundation.ScrollState? = null
    var inner: androidx.compose.foundation.ScrollState? = null
    var dispatch = 0
    @Volatile var lastIme = -1
    var lastHeldUi: Boolean? = null
    var lastHeldFd: Boolean? = null
    var margin = 0f
    /** Each record's dispatch, mode and scroller values, keyed by the Seen it produced (before()'s source). */
    val meta = java.util.WeakHashMap<Any, String>()
    fun newDispatch() { dispatch++; log("DISP") }
    fun source(seen: Any?): String = if (seen == null) "none" else (meta[seen] ?: "unregistered")
    fun log(event: String) {
        android.util.Log.i("CCMKeepProbe", "${android.os.SystemClock.uptimeMillis()} f=${android.view.animation.AnimationUtils.currentAnimationTimeMillis()} d=$dispatch $event")
    }
}

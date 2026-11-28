package io.github.thiesi.chitchat.core.ui

/** DIAGNOSTIC (never merged), #378: log-only probe to logcat (tag CCMKeepProbe), fixture runs only. */
object KeepProbe {
    var outer: androidx.compose.foundation.ScrollState? = null
    var inner: androidx.compose.foundation.ScrollState? = null
    var dispatch = 0
    @Volatile var lastIme = -1
    var lastHeldUi: Boolean? = null
    var lastHeldFd: Boolean? = null
    /** PL item 7: counted down at the first placement mid-slide (the inset strictly between source and target). */
    @Volatile var midSlide: java.util.concurrent.CountDownLatch? = null
    fun imeObserved(ime: Int, src: Int, tgt: Int) { lastIme = ime; if (src != tgt && ime != src && ime != tgt) midSlide?.countDown() }
    var margin = 0f
    /** #372 mechanism diag: the variant under test (main, D1, D2, D4, D5), set by the probe before composing. */
    @Volatile var variant = "main"
    /** D1: the nudge in px on the body's first child (1 for one frame, then 0). */
    val nudge = androidx.compose.runtime.mutableIntStateOf(0)
    /** Each record's dispatch, mode and scroller values, keyed by the Seen it produced (before()'s source). */
    val meta = java.util.WeakHashMap<Any, String>()
    fun newDispatch() { dispatch++; log("DISP") }
    fun source(seen: Any?): String = if (seen == null) "none" else (meta[seen] ?: "unregistered")
    fun log(event: String) {
        // JVM unit tests have no android.os (the stub throws): the probe logs nothing there.
        try {
            android.util.Log.i("CCMKeepProbe", "${android.os.SystemClock.uptimeMillis()} f=${android.view.animation.AnimationUtils.currentAnimationTimeMillis()} d=$dispatch $event")
        } catch (_: RuntimeException) { }
    }
}

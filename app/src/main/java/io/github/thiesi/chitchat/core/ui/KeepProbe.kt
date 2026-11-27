package io.github.thiesi.chitchat.core.ui

/** DIAGNOSTIC (never merged), #378: log-only probe to logcat (tag CCMKeepProbe), fixture runs only. */
object KeepProbe {
    /** The dialog's scroll states, set from a position callback (references only; read in callbacks, never in composition). */
    var outer: androidx.compose.foundation.ScrollState? = null
    var inner: androidx.compose.foundation.ScrollState? = null
    /** Incremented first by the outer content's position callback, on every call (TL): one value per dispatch. */
    var dispatch = 0
    fun newDispatch() { dispatch++; log("DISP") }
    fun log(event: String) {
        android.util.Log.i("CCMKeepProbe", "${android.os.SystemClock.uptimeMillis()} f=${android.view.animation.AnimationUtils.currentAnimationTimeMillis()} d=$dispatch $event")
    }
}

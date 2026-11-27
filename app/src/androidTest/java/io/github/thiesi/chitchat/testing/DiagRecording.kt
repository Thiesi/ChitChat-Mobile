package io.github.thiesi.chitchat.testing

import androidx.test.platform.app.InstrumentationRegistry
import io.github.thiesi.chitchat.core.ui.KeepProbe

/**
 * DIAGNOSTIC (never merged), #378: a rep with animations on (scales read, restored, read back and logged) recorded to
 * /data/local/tmp, a one-frame sync strip on the first activity resumed after arming (its drawn uptime logged), and an
 * optional hide at a case's end.
 */
object DiagRecording {
    private val ui get() = InstrumentationRegistry.getInstrumentation().uiAutomation
    private val scales = listOf("window_animation_scale", "transition_animation_scale", "animator_duration_scale")
    /** Called by a case at its end, before its scenario closes: hides the keyboard (set by the wrappers). */
    var beforeClose: (() -> Unit)? = null

    fun shell(command: String): String = android.os.ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand(command))
        .use { it.readBytes().toString(Charsets.UTF_8).trim() }

    private var current: String? = null
    /** A capture point inside a case (no-op outside a recorded case). */
    fun capture(point: String) { current?.let { capture(it, point) } }

    fun run(name: String, animations: Boolean, hide: () -> Unit, body: () -> Unit) {
        current = name
        KeepProbe.log("CASE start $name animations=$animations")
        beforeClose = {
            capture(name, "arrival-end")
            hide()
            capture(name, "hide-end")
        }
        try {
            val before = scales.associateWith { shell("settings get global $it") }
            android.util.Log.i("CCMDiagScales", "before $name: $before")
            try {
                if (animations) scales.forEach { shell("settings put global $it 1") }
                android.util.Log.i("CCMDiagScales", "during $name: ${scales.associateWith { shell("settings get global $it") }}")
                val video = ui.executeShellCommand("screenrecord --time-limit 60 --bit-rate 6000000 /data/local/tmp/$name.mp4")
                val done = java.util.concurrent.CountDownLatch(1)
                Thread { android.os.ParcelFileDescriptor.AutoCloseInputStream(video).use { it.readBytes() }; done.countDown() }.start()
                body()
                capture(name, "case-end")
                KeepProbe.log("CASE body done $name")
                // screenrecord stops at its limit; end it now so the next case can record (SIGINT finalises the file).
                shell("pkill -INT screenrecord")
                check(done.await(30, java.util.concurrent.TimeUnit.SECONDS)) { "screenrecord did not end" }
            } finally {
                before.forEach { (key, value) -> shell("settings put global $key ${value.ifEmpty { "0" }}") }
                android.util.Log.i("CCMDiagScales", "after restoring $name: ${scales.associateWith { shell("settings get global $it") }}")
            }
        } finally {
            beforeClose = null
            current = null
            KeepProbe.log("CASE end $name")
        }
    }

    /**
     * The sync strip (TL): a magenta ColorDrawable (x 0..12, y 300..340 of the focused window) on that window's decor-view
     * ViewOverlay for one drawn frame, right before a case's first native tap. No View is added: nothing measures, lays
     * out or takes focus. The frame that drew it logs its uptime; the call returns after the frame that removed it.
     */
    fun flash() {
        val name = current ?: return
        val latch = java.util.concurrent.CountDownLatch(1)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val decor = android.view.inspector.WindowInspector.getGlobalWindowViews().last { it.hasWindowFocus() }
            val strip = android.graphics.drawable.ColorDrawable(android.graphics.Color.MAGENTA).apply { setBounds(0, 300, 12, 340) }
            decor.overlay.add(strip)
            decor.invalidate()
            decor.viewTreeObserver.registerFrameCommitCallback {
                KeepProbe.log("SYNC shown $name window=${decor.javaClass.simpleName} w=${decor.width} h=${decor.height}")
                decor.overlay.remove(strip)
                decor.invalidate()
                decor.viewTreeObserver.registerFrameCommitCallback { KeepProbe.log("SYNC removed $name"); latch.countDown() }
            }
        }
        check(latch.await(5, java.util.concurrent.TimeUnit.SECONDS)) { "the sync strip wasn't drawn" }
    }

    /** A screencap for the PL's end captures; the settled state, after Compose is idle. */
    private fun capture(name: String, point: String) {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        KeepProbe.log("CAPTURE $name $point")
        shell("screencap -p /data/local/tmp/ccm-cap-$name-$point.png")
    }

    /** The sync strip (x 0..12, y 300..340) for one drawn frame on the next activity resumed; that frame's uptime is logged. */
    private fun armFlash(name: String) {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as android.app.Application
        app.registerActivityLifecycleCallbacks(object : android.app.Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: android.app.Activity) {
                app.unregisterActivityLifecycleCallbacks(this)
                val decor = activity.window.decorView
                val strip = android.graphics.drawable.ColorDrawable(android.graphics.Color.MAGENTA).apply { setBounds(0, 300, 12, 340) }
                // TL (2026-11-27): only once the window has focus and has drawn a second frame, so the frame carrying
                // the strip reaches the screen (proof 1's strip, on the first drawn frame, never did).
                fun show() {
                    decor.overlay.add(strip)
                    decor.invalidate()
                    decor.viewTreeObserver.registerFrameCommitCallback {
                        KeepProbe.log("SYNC shown $name")
                        decor.overlay.remove(strip)
                        decor.invalidate()
                        decor.viewTreeObserver.registerFrameCommitCallback { KeepProbe.log("SYNC removed $name") }
                    }
                }
                fun afterTwoFrames() {
                    decor.invalidate()
                    decor.viewTreeObserver.registerFrameCommitCallback {
                        decor.invalidate()
                        decor.viewTreeObserver.registerFrameCommitCallback { decor.post { show() } }
                    }
                }
                if (decor.hasWindowFocus()) afterTwoFrames()
                else decor.viewTreeObserver.addOnWindowFocusChangeListener(object : android.view.ViewTreeObserver.OnWindowFocusChangeListener {
                    override fun onWindowFocusChanged(hasFocus: Boolean) {
                        if (!hasFocus) return
                        decor.viewTreeObserver.removeOnWindowFocusChangeListener(this)
                        afterTwoFrames()
                    }
                })
            }
            override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: android.os.Bundle?) = Unit
            override fun onActivityStarted(activity: android.app.Activity) = Unit
            override fun onActivityPaused(activity: android.app.Activity) = Unit
            override fun onActivityStopped(activity: android.app.Activity) = Unit
            override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: android.os.Bundle) = Unit
            override fun onActivityDestroyed(activity: android.app.Activity) = Unit
        })
    }
}

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

    fun run(name: String, animations: Boolean, hide: () -> Unit, body: () -> Unit) {
        KeepProbe.log("CASE start $name animations=$animations")
        beforeClose = hide
        try {
            val before = scales.associateWith { shell("settings get global $it") }
            android.util.Log.i("CCMDiagScales", "before $name: $before")
            try {
                if (animations) scales.forEach { shell("settings put global $it 1") }
                android.util.Log.i("CCMDiagScales", "during $name: ${scales.associateWith { shell("settings get global $it") }}")
                val video = ui.executeShellCommand("screenrecord --time-limit 60 --bit-rate 6000000 /data/local/tmp/$name.mp4")
                val done = java.util.concurrent.CountDownLatch(1)
                Thread { android.os.ParcelFileDescriptor.AutoCloseInputStream(video).use { it.readBytes() }; done.countDown() }.start()
                armFlash(name)
                body()
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
            KeepProbe.log("CASE end $name")
        }
    }

    /** The sync strip (x 0..12, y 300..340) for one drawn frame on the next activity resumed; that frame's uptime is logged. */
    private fun armFlash(name: String) {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as android.app.Application
        app.registerActivityLifecycleCallbacks(object : android.app.Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: android.app.Activity) {
                app.unregisterActivityLifecycleCallbacks(this)
                val decor = activity.window.decorView
                val strip = android.graphics.drawable.ColorDrawable(android.graphics.Color.MAGENTA).apply { setBounds(0, 300, 12, 340) }
                decor.post {
                    decor.overlay.add(strip)
                    decor.invalidate()
                    decor.viewTreeObserver.registerFrameCommitCallback {
                        KeepProbe.log("SYNC shown $name")
                        decor.overlay.remove(strip)
                        decor.invalidate()
                    }
                }
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

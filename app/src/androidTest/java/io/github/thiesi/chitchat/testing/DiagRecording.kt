package io.github.thiesi.chitchat.testing

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import io.github.thiesi.chitchat.core.ui.KeepProbe

/** DIAGNOSTIC (never merged), #378: a recorded rep with animations on, and a one-frame sync strip at a logged uptime. */
object DiagRecording {
    private val ui get() = InstrumentationRegistry.getInstrumentation().uiAutomation
    private val scales = listOf("window_animation_scale", "transition_animation_scale", "animator_duration_scale")

    fun shell(command: String): String = android.os.ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand(command))
        .use { it.readBytes().toString(Charsets.UTF_8).trim() }

    fun record(name: String, seconds: Int, body: () -> Unit) {
        val before = scales.associateWith { shell("settings get global $it") }
        android.util.Log.i("CCMDiagScales", "before $name: $before")
        try {
            scales.forEach { shell("settings put global $it 1") }
            android.util.Log.i("CCMDiagScales", "during $name: ${scales.associateWith { shell("settings get global $it") }}")
            val video = ui.executeShellCommand("screenrecord --time-limit $seconds --bit-rate 6000000 /data/local/tmp/$name.mp4")
            val done = java.util.concurrent.CountDownLatch(1)
            Thread { android.os.ParcelFileDescriptor.AutoCloseInputStream(video).use { it.readBytes() }; done.countDown() }.start()
            KeepProbe.log("REC start $name")
            body()
            KeepProbe.log("REC body done $name")
            check(done.await(seconds + 15L, java.util.concurrent.TimeUnit.SECONDS)) { "screenrecord did not end" }
        } finally {
            before.forEach { (key, value) -> shell("settings put global $key ${value.ifEmpty { "0" }}") }
            android.util.Log.i("CCMDiagScales", "after restoring $name: ${scales.associateWith { shell("settings get global $it") }}")
        }
    }

    /** A magenta strip (x 0..12, y 300..340) for one drawn frame on the resumed activity; that frame's uptime is logged. */
    fun flash() {
        val latch = java.util.concurrent.CountDownLatch(1)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).first()
            val decor = activity.window.decorView
            val strip = android.graphics.drawable.ColorDrawable(android.graphics.Color.MAGENTA).apply { setBounds(0, 300, 12, 340) }
            decor.overlay.add(strip)
            decor.invalidate()
            decor.viewTreeObserver.registerFrameCommitCallback {
                KeepProbe.log("SYNC shown")
                decor.overlay.remove(strip)
                decor.invalidate()
                decor.viewTreeObserver.registerFrameCommitCallback { KeepProbe.log("SYNC removed"); latch.countDown() }
            }
        }
        check(latch.await(5, java.util.concurrent.TimeUnit.SECONDS)) { "the sync marker wasn't drawn" }
    }
}

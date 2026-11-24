package io.github.thiesi.chitchat.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import io.github.thiesi.chitchat.ChitChatApplication
import io.github.thiesi.chitchat.MainActivity
import io.github.thiesi.chitchat.core.server.displayHost
import io.github.thiesi.chitchat.testing.ForeignTaskCallerActivity
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Server prefill P1 (TL): chitchat://connect enters through LinkEntryActivity, a no-UI trampoline into MainActivity with
 * the push tap's flags. One MainActivity, in the app's own task, whatever the caller does; the link is handed over once
 * and a restored activity never replays it. MainActivity's launch mode is unchanged.
 */
class LinkEntryTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val app get() = context.applicationContext as ChitChatApplication

    private fun link(host: String) =
        Intent(Intent.ACTION_VIEW, Uri.parse("chitchat://connect?server=$host")).setPackage(context.packageName)

    private fun inStage(stage: Stage): List<Activity> {
        var found = emptyList<Activity>()
        instrumentation.runOnMainSync { found = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(stage).toList() }
        return found
    }

    private fun mains() = inStage(Stage.RESUMED).filterIsInstance<MainActivity>()

    /** Waits (bounded, idle-synced) for a condition on the app's activities: launching is asynchronous. */
    private fun <T> await(what: String, read: () -> T?): T {
        repeat(100) {
            instrumentation.waitForIdleSync()
            read()?.let { return it }
            Thread.sleep(100)
        }
        throw AssertionError("Timed out waiting for $what (resumed: ${inStage(Stage.RESUMED).map { it.javaClass.simpleName }})")
    }

    private fun prefillOf(main: MainActivity): String? {
        var value: String? = null
        instrumentation.runOnMainSync { value = ViewModelProvider(main)[ServerLinkViewModel::class.java].editorPrefill.value?.address?.displayHost() }
        return value
    }

    private fun oneMain(): MainActivity = await("one resumed MainActivity") { mains().singleOrNull() }

    /**
     * The exact consumer (TL): with no saved server the app is on Welcome, so the link waits for step 2 in
     * [ServerLinkViewModel.editorPrefill]. Awaited for this host (bounded, idle-synced): starting an activity is
     * asynchronous, and "nothing pending" is also true before the trampoline has even offered the link (run 45322426256
     * read it that early: the whole case took 550 ms).
     */
    private fun awaitHeldForStepTwo(main: MainActivity, host: String) =
        await("the link held for step 2 (Welcome)") { if (prefillOf(main) == host) true else null }

    /**
     * Pinned starting state (TL): no saved server in the app's real settings store, so a launch shows Welcome, signed
     * out. Nothing in this suite writes that store (these cases never tap Continue) and CI's emulator is fresh; a device
     * that has one fails here loudly rather than being changed or skipped (its debug data is never touched).
     */
    @Before fun noSavedServer() {
        val saved = kotlinx.coroutines.runBlocking { app.settingsRepository.settings.first().server }
        check(saved == null) { "LinkEntryTest needs the debug app with no saved server (Welcome); this device has one" }
        app.serverLinks.state.value?.let { app.serverLinks.take(it) }
    }

    @After fun finishEverything() {
        instrumentation.runOnMainSync {
            val monitor = ActivityLifecycleMonitorRegistry.getInstance()
            listOf(Stage.RESUMED, Stage.PAUSED, Stage.STOPPED, Stage.CREATED, Stage.STARTED)
                .flatMap { monitor.getActivitiesInStage(it) }.forEach { it.finish() }
        }
        instrumentation.waitForIdleSync()
        app.serverLinks.state.value?.let { app.serverLinks.take(it) }
    }

    @Test fun aColdLinkStartsOneMainActivityAndHandsTheLinkOverOnce() {
        context.startActivity(link("cold.example.org").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val main = oneMain()
        awaitHeldForStepTwo(main, "cold.example.org")
        assertNull("Taken once", app.serverLinks.state.value)
        assertTrue("No trampoline left", inStage(Stage.RESUMED).none { it is LinkEntryActivity })
        assertEquals(1, mains().size)
    }

    @Test fun aLinkWhileRunningReachesTheSameMainActivity() {
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val first = oneMain()
        context.startActivity(link("running.example.org").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        awaitHeldForStepTwo(first, "running.example.org")
        assertNull("Taken once", app.serverLinks.state.value)
        val now = oneMain()
        assertSame("onNewIntent on the running instance, no second MainActivity", first, now)
        assertEquals(1, mains().size)
    }

    @Test fun aLinkFromAnotherAppsTaskWithoutNewTaskStillOpensTheAppsOwnTask() {
        context.startActivity(Intent(context, ForeignTaskCallerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val caller = await("the caller") { inStage(Stage.RESUMED).filterIsInstance<ForeignTaskCallerActivity>().singleOrNull() }
        instrumentation.runOnMainSync { caller.startActivity(link("foreign.example.org")) } // No NEW_TASK, like a messenger.
        val main = oneMain()
        assertNotEquals("MainActivity never lands in the caller's task", caller.taskId, main.taskId)
        assertEquals(1, mains().size)
        awaitHeldForStepTwo(main, "foreign.example.org")
        assertNull("Taken once", app.serverLinks.state.value)
    }

    @Test fun aRestoredActivityNeverReplaysAnOldLink() {
        context.startActivity(link("once.example.org").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val main = oneMain()
        awaitHeldForStepTwo(main, "once.example.org")
        instrumentation.runOnMainSync { main.recreate() }
        val restored = await("the recreated MainActivity") { mains().singleOrNull()?.takeIf { it !== main } }
        instrumentation.waitForIdleSync()
        assertNull("Nothing re-offered from the old intent", app.serverLinks.state.value)
        assertEquals(1, mains().size)
        assertTrue(restored.intent?.data == null) // MainActivity's own intent never carries the link.
    }
}

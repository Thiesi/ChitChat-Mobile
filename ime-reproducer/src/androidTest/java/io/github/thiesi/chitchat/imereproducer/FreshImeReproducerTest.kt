package io.github.thiesi.chitchat.imereproducer

import android.os.SystemClock
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import io.github.thiesi.chitchat.testing.NativeInputWindow
import io.github.thiesi.chitchat.testing.nativeInputWindow
import io.github.thiesi.chitchat.testing.performNativeTap
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test

/** Fixed fifty independent cases; a failed tap is never retried. Existing gates never run this class. */
class FreshImeReproducerTest {
    @get:Rule val compose = createEmptyComposeRule()

    private fun cycle(number: Int) {
        val arguments = InstrumentationRegistry.getArguments()
        check(arguments.getString("imeReproducer") == "true" && arguments.getString("expectSoftKeyboard") == "true")
        var scenario: ActivityScenario<ImeReproducerActivity>? = null
        var host: ImeReproducerActivity? = null
        var failure: Throwable? = null
        var before: NativeInputWindow? = null
        var stage = "LAUNCH"
        var tapped = false
        var proved = false
        var cleanupComplete = false
        val started = SystemClock.uptimeMillis()
        try {
            scenario = ActivityScenario.launch(ImeReproducerActivity::class.java)
            scenario.onActivity { host = it }
            val input = compose.onNodeWithTag("reproducer_input")
            input.assertIsDisplayed()
            before = nativeInputWindow()
            stage = "NATIVE_TAP"
            tapped = true
            input.performNativeTap()
            stage = "FOCUS_IME"
            compose.waitUntil(5_000) { checkNotNull(host).imeBottom > 0 &&
                runCatching { input.assertIsFocused().assertIsDisplayed() }.isSuccess }
            proved = true
        } catch (original: Throwable) {
            failure = original
            runCatching {
                reportCycle(failureObservation(stage, original, before).put("kind", "failure").put("cycle", number))
            }.exceptionOrNull()?.let(original::addSuppressed)
        } finally {
            try {
                if (host != null) {
                    Espresso.closeSoftKeyboard()
                    compose.waitUntil(5_000) { checkNotNull(host).imeBottom == 0 && nativeInputWindow().imeVisible == false }
                }
                cleanupComplete = true
            } catch (cleanup: Throwable) {
                failure?.addSuppressed(cleanup) ?: run { failure = cleanup }
            } finally {
                try { scenario?.close() }
                catch (close: Throwable) { failure?.addSuppressed(close) ?: run { failure = close } }
                host = null
                runCatching {
                    reportCycle(JSONObject().put("kind", "cycle").put("cycle", number)
                        .put("startedUptimeMillis", started).put("finishedUptimeMillis", SystemClock.uptimeMillis())
                        .put("tapAttempted", tapped).put("focusImeProved", proved).put("cleanupComplete", cleanupComplete)
                        .put("deadlineMillis", 5000).put("failureClass", failure?.javaClass?.simpleName ?: JSONObject.NULL))
                }.exceptionOrNull()?.let { failure?.addSuppressed(it) ?: run { failure = it } }
            }
        }
        failure?.let { throw it }
    }

    @Test fun cycle01() = cycle(1)
    @Test fun cycle02() = cycle(2)
    @Test fun cycle03() = cycle(3)
    @Test fun cycle04() = cycle(4)
    @Test fun cycle05() = cycle(5)
    @Test fun cycle06() = cycle(6)
    @Test fun cycle07() = cycle(7)
    @Test fun cycle08() = cycle(8)
    @Test fun cycle09() = cycle(9)
    @Test fun cycle10() = cycle(10)
    @Test fun cycle11() = cycle(11)
    @Test fun cycle12() = cycle(12)
    @Test fun cycle13() = cycle(13)
    @Test fun cycle14() = cycle(14)
    @Test fun cycle15() = cycle(15)
    @Test fun cycle16() = cycle(16)
    @Test fun cycle17() = cycle(17)
    @Test fun cycle18() = cycle(18)
    @Test fun cycle19() = cycle(19)
    @Test fun cycle20() = cycle(20)
    @Test fun cycle21() = cycle(21)
    @Test fun cycle22() = cycle(22)
    @Test fun cycle23() = cycle(23)
    @Test fun cycle24() = cycle(24)
    @Test fun cycle25() = cycle(25)
    @Test fun cycle26() = cycle(26)
    @Test fun cycle27() = cycle(27)
    @Test fun cycle28() = cycle(28)
    @Test fun cycle29() = cycle(29)
    @Test fun cycle30() = cycle(30)
    @Test fun cycle31() = cycle(31)
    @Test fun cycle32() = cycle(32)
    @Test fun cycle33() = cycle(33)
    @Test fun cycle34() = cycle(34)
    @Test fun cycle35() = cycle(35)
    @Test fun cycle36() = cycle(36)
    @Test fun cycle37() = cycle(37)
    @Test fun cycle38() = cycle(38)
    @Test fun cycle39() = cycle(39)
    @Test fun cycle40() = cycle(40)
    @Test fun cycle41() = cycle(41)
    @Test fun cycle42() = cycle(42)
    @Test fun cycle43() = cycle(43)
    @Test fun cycle44() = cycle(44)
    @Test fun cycle45() = cycle(45)
    @Test fun cycle46() = cycle(46)
    @Test fun cycle47() = cycle(47)
    @Test fun cycle48() = cycle(48)
    @Test fun cycle49() = cycle(49)
    @Test fun cycle50() = cycle(50)
}

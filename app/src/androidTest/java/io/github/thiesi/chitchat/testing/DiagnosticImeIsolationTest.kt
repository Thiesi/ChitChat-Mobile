package io.github.thiesi.chitchat.testing

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class DiagnosticImeIsolationTest {
    @Test fun testOnlyServiceRequiresTheSystemImeBindingPermissionAndSeparateProcess() {
        val context = InstrumentationRegistry.getInstrumentation().context
        @Suppress("DEPRECATION")
        val info = context.packageManager.getServiceInfo(ComponentName(context, DiagnosticImeService::class.java),
            PackageManager.GET_META_DATA)
        assertTrue(info.exported)
        assertEquals(Manifest.permission.BIND_INPUT_METHOD, info.permission)
        assertEquals(context.packageName + ":diagnosticIme", info.processName)
        assertTrue(info.metaData.getInt("android.view.im") != 0)
    }

    @Test fun productionApplicationHasNoDiagnosticImeService() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertThrows(PackageManager.NameNotFoundException::class.java) {
            @Suppress("DEPRECATION")
            context.packageManager.getServiceInfo(ComponentName(context.packageName, DiagnosticImeService::class.java.name), 0)
        }
    }

    @Test fun scalarCallbackBudgetReportsItsLimitOnceAndThenStops() {
        val rows = mutableListOf<String>()
        val events = DiagnosticImeEvents(limit = 2, uptime = { 42L }, emit = rows::add)
        repeat(100) { events.record(DiagnosticImeEvent.WINDOW_SHOWN) }
        assertEquals(listOf("n=1 t=42 e=WINDOW_SHOWN", "n=2 t=42 e=WINDOW_SHOWN", "n=3 t=42 e=LIMIT"), rows)
        assertThrows(IllegalArgumentException::class.java) { DiagnosticImeEvents(limit = 0) }
        assertThrows(IllegalArgumentException::class.java) { DiagnosticImeEvents(limit = 1025) }
    }
}

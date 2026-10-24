package io.github.thiesi.chitchat.testing

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import androidx.test.platform.app.InstrumentationRegistry
import dalvik.system.PathClassLoader
import org.junit.Assert.*
import org.junit.Test

class DiagnosticImeIsolationTest {
    @Test fun standaloneServiceHelpersLoadWithoutTheTargetApplicationClasspath() {
        val context = InstrumentationRegistry.getInstrumentation().context
        // A service launched by Android has no instrumentation target classpath. AGP removes
        // target-provided dependencies from the test APK; a normal test loader conceals that.
        val loader = PathClassLoader(context.applicationInfo.sourceDir, ClassLoader.getSystemClassLoader().parent)
        val type = Class.forName("io.github.thiesi.chitchat.testing.DiagnosticImeEvents", true, loader)
        assertSame(loader, type.classLoader)
        type.declaredConstructors // Resolve helper ABI types using the standalone APK only.
        val constructor = type.getDeclaredConstructor().apply { isAccessible = true }
        assertNotNull(constructor.newInstance())
        Class.forName("io.github.thiesi.chitchat.testing.DiagnosticImeEvent", true, loader)
        Class.forName("io.github.thiesi.chitchat.testing.DiagnosticImeService", true, loader).declaredMethods
    }

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
        val events = DiagnosticImeEvents(2, { 42L }, { rows.add(it) })
        repeat(100) { events.record(DiagnosticImeEvent.WINDOW_SHOWN) }
        assertEquals(listOf("n=1 t=42 e=WINDOW_SHOWN", "n=2 t=42 e=WINDOW_SHOWN", "n=3 t=42 e=LIMIT"), rows)
        assertThrows(IllegalArgumentException::class.java) { DiagnosticImeEvents(0) }
        assertThrows(IllegalArgumentException::class.java) { DiagnosticImeEvents(1025) }
    }
}

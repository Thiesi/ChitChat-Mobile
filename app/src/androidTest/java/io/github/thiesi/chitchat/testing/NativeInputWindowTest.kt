package io.github.thiesi.chitchat.testing

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class NativeInputWindowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Volatile private var ime = 0
    @After fun closeImeBeforeActivityDestruction() { Espresso.closeSoftKeyboard() }

    @Test fun snapshotContainsNativeScalarsNotEditableContentsOrViewObjects() {
        compose.setContent { MaterialTheme { OutlinedTextField("private-window-canary", {}) } }
        compose.waitUntil(5_000) { nativeInputWindow().windowFocused == true }
        val snapshot = nativeInputWindow()
        assertEquals(1, snapshot.resumedActivities)
        assertEquals(true, snapshot.attached)
        assertNotNull(snapshot.imeVisible); assertNotNull(snapshot.imeBottom)
        assertTrue(snapshot.imeBottom!! >= 0)
        assertNotNull(snapshot.softInputMode)
        assertNotNull(snapshot.inTouchMode)
        if (android.os.Build.VERSION.SDK_INT < 30) assertNull(snapshot.imeControllable)
        assertFalse(snapshot.toString().contains("private-window-canary"))
        assertTrue(NativeInputWindow::class.java.declaredFields.none { field ->
            android.view.View::class.java.isAssignableFrom(field.type) ||
                android.app.Activity::class.java.isAssignableFrom(field.type)
        })
        val scalarTypes = setOf(java.lang.Boolean::class.java, java.lang.Integer::class.java,
            java.lang.Integer.TYPE, java.lang.Long.TYPE)
        assertTrue(NativeInputWindow::class.java.declaredFields.filterNot { it.isSynthetic }.all {
            it.type in scalarTypes
        })
    }

    @Test fun samplingDoesNotAcquireFocusAndStillObservesTheUserFocusedEditor() {
        compose.setContent { MaterialTheme {
            val bottom = WindowInsets.ime.getBottom(LocalDensity.current)
            SideEffect { ime = bottom }
            OutlinedTextField("private-window-canary", {}, modifier = Modifier.testTag("native_window_input"))
        } }
        compose.waitUntil(5_000) { nativeInputWindow().windowFocused == true }
        val before = nativeInputWindow()
        val observed = nativeInputWindow()
        assertEquals(before.viewFocused, observed.viewFocused)
        assertEquals(before.textEditor, observed.textEditor)
        assertEquals(before.imeVisible, observed.imeVisible)
        assertEquals(before.imeBottom, observed.imeBottom)
        assertEquals(before.inTouchMode, observed.inTouchMode)
        compose.tapNativeInput("native_window_input", { ime }).assertIsFocused()
        val focused = nativeInputWindow()
        assertEquals(true, focused.windowFocused)
        assertEquals(true, focused.viewFocused)
        assertEquals(true, focused.textEditor)
        assertNotNull(focused.inTouchMode)
        if (InstrumentationRegistry.getArguments().getString("expectSoftKeyboard") == "true") {
            assertEquals(true, focused.imeVisible)
            assertTrue(focused.imeBottom!! > 0)
        }
        if (android.os.Build.VERSION.SDK_INT < 30) assertNull(focused.imeControllable)
        assertFalse(focused.toString().contains("private-window-canary"))
    }

    @Test fun dialogWindowFocusIsNotMistakenForActivityWindowFocus() {
        var dialog by mutableStateOf(true)
        compose.setContent { MaterialTheme {
            Text("Underlying native fixture")
            if (dialog) AlertDialog(onDismissRequest = { dialog = false },
                title = { Text("Diagnostic fixture") },
                confirmButton = { TextButton(onClick = { dialog = false }) { Text("Close diagnostic dialog") } })
        } }
        compose.onNodeWithText("Close diagnostic dialog").assertIsDisplayed()
        compose.waitUntil(5_000) { nativeInputWindow().windowFocused == false }
        assertEquals(1, nativeInputWindow().resumedActivities)
        compose.onNodeWithText("Close diagnostic dialog").performClick()
        compose.waitUntil(5_000) { nativeInputWindow().windowFocused == true }
        assertEquals(true, nativeInputWindow().windowFocused)
    }
}

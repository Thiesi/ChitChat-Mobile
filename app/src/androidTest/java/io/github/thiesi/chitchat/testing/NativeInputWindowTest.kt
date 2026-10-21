package io.github.thiesi.chitchat.testing

import androidx.activity.ComponentActivity
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.espresso.Espresso
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class NativeInputWindowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
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
        assertFalse(snapshot.toString().contains("private-window-canary"))
        assertTrue(NativeInputWindow::class.java.declaredFields.none { field ->
            android.view.View::class.java.isAssignableFrom(field.type) ||
                android.app.Activity::class.java.isAssignableFrom(field.type)
        })
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

package io.github.thiesi.chitchat.testing

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso
import io.github.thiesi.chitchat.core.ui.MessageInput
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Existing production editor, no authentication/server/provider; native routing for every tap. */
class NativeInputDialogJourneyTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Volatile private var ime = 0
    @Before fun matchApplicationWindow() {
        compose.activityRule.scenario.onActivity {
            it.enableEdgeToEdge()
            @Suppress("DEPRECATION")
            it.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }
    @After fun closeKeyboardBeforeActivityDestruction() { Espresso.closeSoftKeyboard() }

    @Test fun hundredNativeInputDialogCyclesPreserveBodyWithoutInventingEdits() {
        var edits = 0
        compose.setContent {
            var modal by remember { mutableStateOf(false) }
            val focus = LocalFocusManager.current
            val bottom = WindowInsets.ime.getBottom(LocalDensity.current)
            SideEffect { ime = bottom }
            MaterialTheme {
                Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                    Text("Native input handoff fixture")
                    Spacer(Modifier.weight(1f))
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        IconButton(onClick = { focus.clearFocus(); modal = true },
                            modifier = Modifier.size(48.dp).testTag("input_menu")) { Text("Menu") }
                        MessageInput("Kept fixture body", { edits++ }, !modal, "Message", false,
                            Modifier.weight(1f).testTag("native_composer"))
                        Spacer(Modifier.width(48.dp))
                    }
                }
                if (modal) AlertDialog(onDismissRequest = { modal = false }, title = { Text("Input action") },
                    confirmButton = { TextButton(onClick = { modal = false },
                        modifier = Modifier.testTag("input_close")) { Text("Close") } })
            }
        }
        repeat(100) { cycle ->
            compose.tapNativeInput("native_composer", { ime }, { "nativeInputDialogCycle=$cycle" })
                .assert(SemanticsMatcher("whole unchanged fixture body") {
                    it.config[SemanticsProperties.EditableText].text == "Kept fixture body"
                })
            Espresso.closeSoftKeyboard(); compose.waitUntil(5_000) { ime == 0 }
            compose.onNodeWithTag("input_menu").performNativeTap()
            compose.onNodeWithTag("input_close").performNativeTap()
        }
        compose.runOnIdle { assertEquals(0, edits) }
    }
}

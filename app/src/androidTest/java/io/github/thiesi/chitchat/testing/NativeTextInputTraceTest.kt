package io.github.thiesi.chitchat.testing

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.espresso.Espresso
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class InputTraceRecorderTest {
    @Test fun boundedRecordsContainOnlyFixedKindsAndNumericSessionMetadata() {
        val recorder = InputTraceRecorder(capacity = 3, uptime = { 42L })
        repeat(6) { recorder.record(recorder.nextSession(), InputTraceKind.START) }
        val records = recorder.snapshot()
        assertEquals(listOf(4L, 5L, 6L), records.map { it.session })
        assertTrue(records.all { it.uptime == 42L && it.kind == InputTraceKind.START })
        assertTrue(InputTraceEvent::class.java.declaredFields.filterNot {
            it.isSynthetic || java.lang.reflect.Modifier.isStatic(it.modifiers)
        }.all {
            it.type == java.lang.Long.TYPE || it.type == InputTraceKind::class.java
        })
    }
    @Test fun oldRootDisposalCannotRetireTheCurrentRootRecorder() {
        val old = InputTraceRecorder(uptime = { 1L })
        val current = InputTraceRecorder(uptime = { 2L })
        try {
            NativeTextInputTrace.enter(old); old.record(1, InputTraceKind.START)
            NativeTextInputTrace.enter(current); current.record(1, InputTraceKind.CONNECTION_CREATED)
            NativeTextInputTrace.leave(old)
            assertEquals("observed[2#1:CONNECTION_CREATED]", NativeTextInputTrace.describe())
        } finally { NativeTextInputTrace.leave(current); NativeTextInputTrace.leave(old) }
        assertEquals("not installed", NativeTextInputTrace.describe())
    }
}

class NativeTextInputTraceJourneyTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Volatile private var ime = 0
    @After fun closeImeBeforeActivityDestruction() { Espresso.closeSoftKeyboard() }

    @Test fun delegationCreatesARealConnectionWithoutRestartingItOnRecomposition() {
        val recorder = InputTraceRecorder()
        var version by mutableIntStateOf(0)
        compose.setContent {
            val renderedVersion = version // Recompose the parent, not only the Text child.
            ObserveNativeTextInput(recorder) { MaterialTheme {
            val bottom = WindowInsets.ime.getBottom(LocalDensity.current)
            SideEffect { ime = bottom }
            Text("Observed version $renderedVersion")
            OutlinedTextField("private-session-canary", {}, modifier = Modifier.testTag("trace_input"))
        } } }
        compose.tapNativeInput("trace_input", { ime }).assertIsFocused()
        compose.waitUntil(5_000) { recorder.snapshot().any { it.kind == InputTraceKind.CONNECTION_CREATED } }
        val before = recorder.snapshot()
        assertEquals(1, before.count { it.kind == InputTraceKind.START })
        assertEquals(0, before.count { it.kind == InputTraceKind.END })
        compose.runOnIdle { version++ }
        compose.waitForIdle()
        // The OS may request multiple connections within one session; only session restart is forbidden.
        val lifetime = setOf(InputTraceKind.START, InputTraceKind.END)
        assertEquals(before.filter { it.kind in lifetime }, recorder.snapshot().filter { it.kind in lifetime })
        compose.onNodeWithTag("trace_input").assertIsFocused()
        assertFalse(NativeTextInputTrace.describe().contains("private-session-canary"))
    }
}

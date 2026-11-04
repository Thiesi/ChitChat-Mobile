package io.github.thiesi.chitchat.testing.focus

import org.junit.Assert.*
import org.junit.Test

class FocusMetadataTest {
    @Test fun recognizesOnlyExactHostComponentPositions() {
        val state = focusMetadata("""
            mCurrentFocus=Window{abc u0 io.github.thiesi.chitchat.debug/androidx.activity.ComponentActivity}
            mFocusedApp=ActivityRecord{def u0 io.github.thiesi.chitchat.debug/androidx.activity.ComponentActivity t7}
        """.trimIndent())
        assertEquals(FocusOwner.TARGET_HOST, state.window)
        assertEquals(FocusOwner.TARGET_HOST, state.app)
    }
    @Test fun reportsSystemUiSeparatelyFromResumedTargetApp() {
        val state = focusMetadata("""
            mCurrentFocus=Window{abc u0 com.android.systemui/.SomeWindow}
            mFocusedApp=ActivityRecord{def u0 io.github.thiesi.chitchat.debug/androidx.activity.ComponentActivity t7}
        """.trimIndent())
        assertEquals(FocusOwner.SYSTEM_UI, state.window)
        assertEquals(FocusOwner.TARGET_HOST, state.app)
    }
    @Test fun arbitraryTitlesCannotImpersonateTheHostOrSystemUi() {
        listOf("Error: io.github.thiesi.chitchat.debug/androidx.activity.ComponentActivity",
            "Private text com.android.systemui/.SomeWindow", "com.android.systemui").forEach { title ->
            val state = focusMetadata("mCurrentFocus=Window{abc u0 $title}")
            assertNull(state.window)
            assertFalse(state.toString().contains(title))
        }
    }
    @Test fun foreignNamesAreDiscarded() {
        val state = focusMetadata("mCurrentFocus=Window{abc u12 private.example/.SecretActivity}")
        assertEquals(FocusOwner.OTHER_COMPONENT, state.window)
        assertFalse(state.toString().contains("private.example"))
        assertFalse(state.toString().contains("SecretActivity"))
        assertFalse(state.toString().contains("u12"))
    }
    @Test fun explicitNullDiffersFromMissingAndMalformed() {
        assertEquals(FocusOwner.NO_WINDOW, focusMetadata("mCurrentFocus=null").window)
        assertNull(focusMetadata("").window)
        assertNull(focusMetadata("mCurrentFocus=unexpected").window)
        assertNull(focusMetadata("mCurrentFocus=null extra").window)
    }
    @Test fun multipleDisplaysAndDuplicateFieldsAreUnknownEvenWhenEqual() {
        val line = "mCurrentFocus=Window{abc u0 com.android.systemui/.SomeWindow}"
        val state = focusMetadata("$line\n$line")
        assertNull(state.window)
        assertEquals(2, state.windowFields)
        val app = "mFocusedApp=ActivityRecord{def u0 io.github.thiesi.chitchat.debug/androidx.activity.ComponentActivity t7}"
        assertNull(focusMetadata("$line\n$line\n$app").app)
        assertNull(focusMetadata("$line\n$app\n$app").window)
    }
    @Test fun truncationCannotProduceFalseCertainty() {
        val state = focusMetadata("mCurrentFocus=null\nmFocusedApp=null", truncated = true)
        assertNull(state.window)
        assertNull(state.app)
    }
    @Test fun unknownAndUnrelatedDumpFieldsAreIgnored() {
        val state = focusMetadata("mOtherFocus=null\ntext=mCurrentFocus=null\nmCurrentFocus=null\nmOwnerUid=123")
        assertEquals(FocusOwner.NO_WINDOW, state.window)
        assertNull(state.app)
        assertEquals(1, state.windowFields)
        assertEquals(0, state.appFields)
    }
}

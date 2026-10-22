package io.github.thiesi.chitchat.testing

import android.os.SystemClock
import org.junit.Assert.*
import org.junit.Test

class NativeImeServerTest {
    @Test fun keepsOnlyExactBooleanAndNumericFlags() {
        assertEquals(listOf("mInputShown=false", "mWindowVisible=true", "mImeWindowVis=0x3"),
            imeServerFlags("mInputShown=false mWindowVisible=true, mImeWindowVis=0x3}"))
    }

    @Test fun dropsTitlesEditorContentsIdentifiersAndUnknownFields() {
        val raw = "Window{private-title-canary} package=private-account-canary " +
            "EditorInfo{surroundingText=private-body-canary} mInputShown=true " +
            "mUnknown=private-body-canary mShowRequested=false"
        assertEquals(listOf("mInputShown=true", "mShowRequested=false"), imeServerFlags(raw))
        assertFalse(imeServerFlags(raw).toString().contains("canary"))
    }

    @Test fun rejectsPartialNamesMalformedValuesAndPropertySuffixes() {
        assertNull(imeServerFlags("xmInputShown=true other.mInputShown=true " +
            "mInputShown=truePrivate mShowRequested=TRUE mWindowVisible=1 " +
            "mImeWindowVis=0x123456789 mImeWindowVis=12345678901 " +
            "mImeWindowVis=3private"))
    }

    @Test fun unavailableIsNotAnInventedHiddenState() {
        assertNull(imeServerFlags("permission denied private-body-canary"))
        assertNull(imeServerFlags(""))
        assertNull(imeServerFlags("mInputShown=null"))
    }

    @Test fun boundsAcceptedRecordsWithoutRetainingAnArbitraryTail() {
        val flags = imeServerFlags("mInputShown=false ".repeat(100) + "private-body-canary")!!
        assertEquals(40, flags.size)
        assertTrue(flags.all { it == "mInputShown=false" })
    }

    @Test fun actualCaptureReturnsOnlyFixedFlagsOrUnavailableMetadata() {
        val before = SystemClock.uptimeMillis()
        val snapshot = nativeImeServerAfterFailure()
        assertTrue(snapshot.inputMethod.startedUptimeMillis >= before)
        assertTrue(snapshot.imeWindow.startedUptimeMillis >= snapshot.inputMethod.finishedUptimeMillis)
        listOf(snapshot.inputMethod, snapshot.imeWindow).forEach {
            assertTrue(it.finishedUptimeMillis >= it.startedUptimeMillis)
            assertTrue(it.flags == null || it.flags.size in 1..40)
        }
        snapshot.inputMethod.flags?.forEach { assertEquals(listOf(it), imeServerFlags(it)) }
        // Different OS/IME versions may omit all known fields; that must remain unknown.
        println("Fixed-field IME server capture: $snapshot")
    }

    @Test fun keepsOnlyImeTypeWindowBooleansAndKnownDrawStates() {
        assertEquals(listOf("mGivenInsetsPending=false", "mHasSurface=true", "mDrawState=HAS_DRAWN"),
            imeWindowFlags("""
                Window #0 Window{private-title-canary}
                  mAttrs={(0,0)(fillxwrap) ty=INPUT_METHOD fmt=TRANSLUCENT}
                  mGivenInsetsPending=false mHasSurface=true mDrawState=HAS_DRAWN
            """.trimIndent()))
    }

    @Test fun otherWindowTitleAndInputMethodDialogNeverSelectAnImeWindow() {
        assertNull(imeWindowFlags("""
            Window #0 Window{private-title-canary ty=INPUT_METHOD}
              mAttrs={(0,0) ty=BASE_APPLICATION}
              mHasSurface=true mDrawState=HAS_DRAWN
            Window #1 Window{private-other-canary}
              mAttrs={(0,0) ty=INPUT_METHOD_DIALOG}
              mHasSurface=true
        """.trimIndent()))
        assertEquals(listOf("mHasSurface=false"), imeWindowFlags("""
            Window #0 Window{private-title-canary}
              mAttrs={(0,0) ty=BASE_APPLICATION}
              mHasSurface=true
            Window #1 Window{private-ime-title-canary}
              mAttrs={(0,0) ty=2011}
              mHasSurface=false
        """.trimIndent()))
    }

    @Test fun ambiguousOrMissingWindowMetadataStaysUnknown() {
        val window = "Window #0 Window{private-title-canary}\n mAttrs={ty=INPUT_METHOD}\n mHasSurface=true\n"
        assertNull(imeWindowFlags(window + window))
        assertNull(imeWindowFlags("private-body-canary mHasSurface=true"))
        assertNull(imeWindowFlags("Window #0 Window{private-title-canary}\n mAttrs={ty=INPUT_METHOD}\n"))
    }

    @Test fun unknownOrMalformedWindowValuesCannotLeakIntoTheSnapshot() {
        assertNull(imeWindowFlags("""
            Window #0 Window{private-title-canary}
              mAttrs={ty=INPUT_METHOD}
              mHasSurface=truePrivate mDrawState=PRIVATE_BODY_CANARY
              mDrawState=HAS_DRAWN_PRIVATE other.mLastHidden=true xmHasSurface=false
              isReadyForDisplay()=1 mGivenInsetsPending=null mUnknown=private-body-canary
        """.trimIndent()))
    }

    @Test fun boundsImeWindowFieldsAndDiscardsFollowingWindowFlags() {
        val raw = "Window #0 Window{private-title-canary}\n mAttrs={ty=INPUT_METHOD}\n" +
            "mHasSurface=false ".repeat(100) +
            "\nWindow #1 Window{private-other-canary}\n mAttrs={ty=BASE_APPLICATION}\n mHasSurface=true"
        val flags = imeWindowFlags(raw)!!
        assertEquals(40, flags.size)
        assertTrue(flags.all { it == "mHasSurface=false" })
    }
}

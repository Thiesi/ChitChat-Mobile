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
        assertTrue(snapshot.startedUptimeMillis >= before)
        assertTrue(snapshot.finishedUptimeMillis >= snapshot.startedUptimeMillis)
        snapshot.flags?.forEach { assertEquals(listOf(it), imeServerFlags(it)) }
        // Different OS/IME versions may omit all known fields; that must remain unknown.
        assertTrue(snapshot.flags == null || snapshot.flags.size in 1..40)
        println("Fixed-field IME server capture: $snapshot")
    }
}

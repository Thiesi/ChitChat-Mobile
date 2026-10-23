package io.github.thiesi.chitchat.testing

import android.os.SystemClock
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.io.Reader
import java.io.StringReader

class NativeImeServerTest {
    @Test fun streamsFixedFlagsBeyondTheOldPrefixWithoutRetainingPrivateLines() {
        val prefix = "private-body-canary\n".repeat(5000)
        val result = readImeServerFields(StringReader(prefix + "mInputShown=false mImeWindowVis=3\n"))
        assertTrue(result.charactersRead > 65_536)
        assertEquals(listOf("mInputShown=false", "mImeWindowVis=3"), result.flags)
        assertFalse(result.limitReached)
        assertFalse(result.toString().contains("canary"))
    }

    @Test fun preservesTokensAcrossSingleCharacterAndChunkBoundaries() {
        val source = StringReader("mShowRequested=true\nmImeWindowVis=0x3")
        val tiny = object : Reader() {
            override fun read(buffer: CharArray, offset: Int, length: Int) = source.read(buffer, offset, minOf(1, length))
            override fun close() = source.close()
        }
        assertEquals(listOf("mShowRequested=true", "mImeWindowVis=0x3"), readImeServerFields(tiny).flags)
    }

    @Test fun capSplitOrExactlyCappedUnterminatedValuesStayUnknown() {
        val valid = "mInputShown=false\n"
        val incomplete = "mWindowVisible=true"
        val result = readImeServerFields(StringReader(valid + incomplete + "Private\n"), valid.length + incomplete.length)
        assertEquals(listOf("mInputShown=false"), result.flags)
        assertTrue(result.limitReached)
        assertEquals(valid.length + incomplete.length, result.charactersRead)
        val exact = readImeServerFields(StringReader(incomplete), incomplete.length)
        assertNull(exact.flags)
        assertTrue(exact.limitReached)
    }

    @Test fun discardsEntireOverlongLinesIncludingTheirApparentlyValidSuffix() {
        val result = readImeServerFields(StringReader("x".repeat(5000) + " mInputShown=true\n" +
            "mInputShown=false\nprivate-body-canary\n"))
        assertEquals(listOf("mInputShown=false"), result.flags)
        assertEquals(1, result.discardedLines)
        assertFalse(result.toString().contains("canary"))
    }

    @Test fun streamBoundsBothFlagsAndTotalCharacters() {
        val result = readImeServerFields(StringReader("mInputShown=false\n".repeat(1000)), 2048)
        assertEquals(40, result.flags!!.size)
        assertTrue(result.flags.all { it == "mInputShown=false" })
        assertEquals(2048, result.charactersRead)
        assertTrue(result.limitReached)
    }

    @Test fun timeoutObservationDoesNotRetainRawServiceOrErrorText() {
        val result = readImeServerFields(StringReader("*** private-body-canary DUMP TIMEOUT EXPIRED ***\n"))
        assertTrue(result.timedOut)
        assertNull(result.flags)
        assertFalse(result.toString().contains("canary"))
    }

    @Test fun streamErrorsPropagateToTheCaptureBoundaryRatherThanInventingFlags() {
        val failing = object : Reader() {
            override fun read(buffer: CharArray, offset: Int, length: Int): Int = throw IOException("private-body-canary")
            override fun close() = Unit
        }
        assertThrows(IOException::class.java) { readImeServerFields(failing) }
    }

    @Test fun aNonProgressingReaderCannotLoopIndefinitely() {
        val stalled = object : Reader() {
            override fun read(buffer: CharArray, offset: Int, length: Int) = 0
            override fun close() = Unit
        }
        assertThrows(IllegalStateException::class.java) { readImeServerFields(stalled) }
    }

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

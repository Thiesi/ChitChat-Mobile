package io.github.thiesi.chitchat.imereproducer

import org.junit.Assert.*
import org.junit.Test

class MainThreadFramesTest {
    @Test fun onlyMainFrameNamesSurvive() {
        val raw = "\"main\" prio=5 tid=1 Native\n" +
            "  at android.os.MessageQueue.nativePollOnce(Native Method)\n" +
            "  at android.os.Looper.loopOnce(Looper.java:123 private-canary)\n" +
            "\"worker-private-canary\" prio=5 tid=2\n" +
            "  at private.canary.Method.secret(File.java:7)\n"
        assertEquals(listOf("android.os.MessageQueue.nativePollOnce", "android.os.Looper.loopOnce"), mainThreadFrames(raw))
    }
    @Test fun missingAmbiguousAndTruncatedStacksStayUnknown() {
        val one = "\"main\" prio=5 tid=1 Native\n  at android.os.Looper.loop(Native Method)\n"
        assertNull(mainThreadFrames("root is required"))
        assertNull(mainThreadFrames(one + one))
        assertNull(mainThreadFrames(one, true))
        assertNull(mainThreadFrames("\"main\" prio=5 tid=2\n  at android.os.Looper.loop(Native Method)\n"))
    }
    @Test fun boundedFrameListDropsRawOrMalformedLines() {
        val raw = "\"main\" prio=5 tid=1 Native\n" +
            "  at private-canary bad(frame)\n" +
            (1..100).joinToString("\n") { "  at android.os.Looper.loop(Private.java:$it)" }
        assertEquals(List(64) { "android.os.Looper.loop" }, mainThreadFrames(raw))
    }
}

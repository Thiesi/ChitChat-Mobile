package io.github.thiesi.chitchat.core.media

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PickedAttachmentReadTest {
    private class TrackedStream(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        var closed = false
        var consumed = 0
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) consumed += it }
        override fun read(): Int = super.read().also { if (it >= 0) consumed++ }
        override fun close() { closed = true; super.close() }
    }

    @Test fun readsOnceClosesAndFreezesBytesWithoutGrantingPhotoReadiness() = runTest {
        val bytes = byteArrayOf(0, 1, 127, -1)
        val stream = TrackedStream(bytes)
        var opens = 0
        val read = PickedAttachmentRead("private-name.bin", null) { opens++; stream }
        val snapshot = read.snapshot(4)
        bytes.fill(9)
        assertArrayEquals(byteArrayOf(0, 1, 127, -1), snapshot.bytes.toByteArray())
        assertEquals("private-name.bin", snapshot.filename)
        assertNull(snapshot.mimeType)
        assertTrue(stream.closed)
        assertEquals(1, opens)
        assertFalse(snapshot.toString().contains("private-name"))
        assertFalse(read.toString().contains("private-name"))
        expect(PreparationFailureKind.ALREADY_USED) { read.snapshot(4) }
        assertEquals(1, opens)
    }

    @Test fun exactBudgetSucceedsOverflowConsumesOnlyOneExtraByteAndEmptyFails() = runTest {
        for (length in listOf(0, 3, 5)) {
            val stream = TrackedStream(ByteArray(length) { 7 })
            val read = PickedAttachmentRead("fixture.bin", "application/octet-stream") { stream }
            when (length) {
                0 -> expect(PreparationFailureKind.EMPTY) { read.snapshot(3) }
                3 -> assertEquals(3, read.snapshot(3).bytes.size)
                else -> expect(PreparationFailureKind.CLIENT_BUDGET) { read.snapshot(3) }
            }
            assertEquals(minOf(length, 4), stream.consumed)
            assertTrue(stream.closed)
        }
    }

    @Test fun invalidClientBudgetsOpenNothingAndDoNotBecomeAnAdvertisedServerLimit() = runTest {
        var opens = 0
        val read = PickedAttachmentRead("fixture.bin", null) { opens++; ByteArrayInputStream(byteArrayOf(1)) }
        for (budget in listOf(-1L, 0L, MAX_ATTACHMENT_SNAPSHOT_BYTES + 1, Long.MAX_VALUE)) {
            try { read.snapshot(budget); fail() } catch (_: IllegalArgumentException) { }
        }
        assertEquals(0, opens)
        assertEquals(1, read.snapshot(1).bytes.size)
        assertEquals(1, opens)
    }

    @Test fun zeroLengthBulkReadsMakeBoundedProgressInsteadOfSpinning() = runTest {
        var position = 0
        var closed = false
        val stream = object : InputStream() {
            override fun read(b: ByteArray, off: Int, len: Int) = 0
            override fun read() = if (position < 3) position++ else -1
            override fun close() { closed = true }
        }
        val snapshot = PickedAttachmentRead("fixture.bin", null) { stream }.snapshot(3)
        assertArrayEquals(byteArrayOf(0, 1, 2), snapshot.bytes.toByteArray())
        assertTrue(closed)
    }

    @Test fun openReadAndCloseFailuresAreSafeAndNeverAuthorizeAReopen() = runTest {
        val secret = "content://private-provider/private-name"
        for (phase in listOf("open", "read", "close")) {
            var opens = 0
            var closes = 0
            val read = PickedAttachmentRead("private-name", null) {
                opens++
                if (phase == "open") throw SecurityException(secret)
                object : ByteArrayInputStream(byteArrayOf(1)) {
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        if (phase == "read") throw IOException(secret)
                        return super.read(b, off, len)
                    }
                    override fun close() { closes++; if (phase == "close") throw IOException(secret) }
                }
            }
            try { read.snapshot(4); fail() }
            catch (e: AttachmentPreparationFailure) {
                assertEquals(PreparationFailureKind.READ_FAILED, e.kind)
                assertNull(e.cause)
                assertFalse(e.toString().contains(secret))
            }
            expect(PreparationFailureKind.ALREADY_USED) { read.snapshot(4) }
            assertEquals(1, opens)
            assertEquals(if (phase == "open") 0 else 1, closes)
        }
    }

    @Test fun cancellationDiscardsTheLateChunkAndClosesWhenTheProviderReturns() = runTest {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = AtomicBoolean()
        val published = AtomicBoolean()
        val read = PickedAttachmentRead("fixture.bin", null) {
            object : InputStream() {
                override fun read() = error("Bulk read expected")
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    started.countDown()
                    assertTrue(release.await(5, TimeUnit.SECONDS))
                    b[off] = 1
                    return 1
                }
                override fun close() { closed.set(true) }
            }
        }
        val job = launch { read.snapshot(4); published.set(true) }
        runCurrent()
        assertTrue(started.await(5, TimeUnit.SECONDS))
        job.cancel(); release.countDown(); job.join()
        assertTrue(closed.get())
        assertFalse(published.get())
        expect(PreparationFailureKind.ALREADY_USED) { read.snapshot(4) }
    }

    @Test fun simultaneousConsumersCannotOpenTheSameSelectionTwice() = runTest {
        val opened = CountDownLatch(1)
        val release = CountDownLatch(1)
        val opens = AtomicInteger()
        val read = PickedAttachmentRead("fixture.bin", null) {
            opens.incrementAndGet(); opened.countDown()
            assertTrue(release.await(5, TimeUnit.SECONDS))
            ByteArrayInputStream(byteArrayOf(1))
        }
        val first = async { read.snapshot(4) }
        runCurrent(); assertTrue(opened.await(5, TimeUnit.SECONDS))
        expect(PreparationFailureKind.ALREADY_USED) { read.snapshot(4) }
        release.countDown()
        assertEquals(1, first.await().bytes.size)
        assertEquals(1, opens.get())
    }

    private suspend fun expect(kind: PreparationFailureKind, action: suspend () -> Unit) {
        try { action(); fail("Expected safe preparation failure") }
        catch (e: AttachmentPreparationFailure) { assertEquals(kind, e.kind) }
    }
}

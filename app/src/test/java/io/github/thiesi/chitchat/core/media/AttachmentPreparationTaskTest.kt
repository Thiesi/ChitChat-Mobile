package io.github.thiesi.chitchat.core.media

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import okio.Buffer
import okio.ByteString
import okio.ByteString.Companion.decodeHex
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AttachmentPreparationTaskTest {
    @Before fun setUp() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun tearDown() { Dispatchers.resetMain() }
    private val capacity = 8L * 1024 * 1024
    private fun read(bytes: ByteArray) = PickedAttachmentRead("private-file", null) { ByteArrayInputStream(bytes) }
    private fun task(read: PickedAttachmentRead, budget: AttachmentMemoryBudget, limit: Long = 8192) =
        AttachmentPreparationTask(read, AttachmentPickSource.FILE, budget, limit)

    @Test fun availableMemoryAllowsAnotherSmallFileBesideARetainedDraft() = runTest {
        val budget = AttachmentMemoryBudget(capacity)
        val retained = mutableListOf<RetainedAttachmentPreparation>()
        try {
            repeat(2) {
                AttachmentPreparationTask.forAvailableMemory(read(byteArrayOf(1)), AttachmentPickSource.FILE, budget)
                    .start(this) { _, outcome ->
                        retained += (outcome as AttachmentPreparationOutcome.Prepared).retained
                        true
                    }.join()
            }
            assertEquals(2, retained.size)
            assertEquals(retained.sumOf { it.reservedBytes }, budget.reservedBytes)
            assertTrue(budget.reservedBytes < capacity)
        } finally { retained.forEach { it.close() } }
        assertEquals(0L, budget.reservedBytes)
    }

    @Test fun adoptedFinalBytesKeepAConservativeChargeAfterTheProducerEnds() = runTest {
        val budget = AttachmentMemoryBudget(capacity)
        val bytes = "opaque fixture".encodeUtf8()
        val original = task(read(bytes.toByteArray()), budget)
        var retained: RetainedAttachmentPreparation? = null
        original.start(this) { source, result ->
            assertSame(original, source)
            retained = (result as AttachmentPreparationOutcome.Prepared).retained
            true
        }.join()
        val handle = retained!!
        val ready = handle.preparation as AttachmentPreparation.Ready
        assertEquals(bytes, ready.payload.bytes)
        assertEquals(AttachmentPhotoPrivacy.NON_PHOTO, ready.privacy)
        assertTrue(budget.reservedBytes in 1 until 2 * 1024 * 1024L)
        assertEquals(budget.reservedBytes, handle.reservedBytes)
        handle.close(); handle.close()
        assertNull(handle.preparation)
        assertEquals(0L, handle.reservedBytes)
        assertEquals(0L, budget.reservedBytes)
    }

    @Test fun unsupportedImageRetainsTheOriginalChoiceWithoutARawReadyPayload() = runTest {
        val budget = AttachmentMemoryBudget(capacity)
        var handle: RetainedAttachmentPreparation? = null
        task(read("GIF89a".toByteArray()), budget).start(this) { _, outcome ->
            handle = (outcome as AttachmentPreparationOutcome.Prepared).retained
            true
        }.join()
        val choice = handle!!.preparation as AttachmentPreparation.NeedsChoice
        assertEquals(PhotoPrivacyFailure.UNSUPPORTED_IMAGE, choice.kind)
        assertEquals("GIF89a".encodeUtf8(), choice.original.bytes)
        assertSame(choice, handle!!.preparation)
        assertTrue(budget.reservedBytes > 0)
        handle!!.close()
        assertEquals(0L, budget.reservedBytes)
    }

    @Test fun adoptedPhotoContainsOnlyCleanedFinalBytesAndTheirDigest() = runTest {
        fun chunk(kind: String, bytes: ByteString): ByteString {
            val crc = CRC32().apply { update(kind.encodeUtf8().toByteArray()); update(bytes.toByteArray()) }
            return Buffer().writeInt(bytes.size).writeUtf8(kind).write(bytes).writeInt(crc.value.toInt()).readByteString()
        }
        val prefix = "89504e470d0a1a0a".decodeHex()
        val header = chunk("IHDR", Buffer().writeInt(1).writeInt(1).writeByte(8).writeByte(2)
            .writeByte(0).writeByte(0).writeByte(0).readByteString())
        val coded = ByteArrayOutputStream().also { out ->
            DeflaterOutputStream(out).use { it.write(byteArrayOf(0, 12, 34, 56)) }
        }.toByteArray().toByteString()
        val end = chunk("IEND", ByteString.EMPTY)
        val original = Buffer().write(prefix).write(header)
            .write(chunk("tEXt", "Location\u0000private fixture".encodeUtf8()))
            .write(chunk("IDAT", coded)).write(end).readByteString()
        val expected = Buffer().write(prefix).write(header).write(chunk("IDAT", coded)).write(end).readByteString()
        val budget = AttachmentMemoryBudget(capacity)
        var handle: RetainedAttachmentPreparation? = null
        task(read(original.toByteArray()), budget).start(this) { _, outcome ->
            handle = (outcome as AttachmentPreparationOutcome.Prepared).retained
            true
        }.join()
        val ready = handle!!.preparation as AttachmentPreparation.Ready
        assertEquals(AttachmentPhotoPrivacy.LOCATION_REMOVED, ready.privacy)
        assertEquals(expected, ready.payload.bytes)
        assertEquals(expected.sha256().hex(), ready.payload.sha256)
        assertNotEquals(original.sha256().hex(), ready.payload.sha256)
        assertNull(ready.payload.mimeType)
        handle!!.close()
        assertEquals(0L, budget.reservedBytes)
    }

    @Test fun supportedCleanerFailureIsAChoiceRatherThanAutomaticOriginalFallback() = runTest {
        val budget = AttachmentMemoryBudget(capacity)
        var handle: RetainedAttachmentPreparation? = null
        task(read("ffd8ff".decodeHex().toByteArray()), budget).start(this) { _, outcome ->
            handle = (outcome as AttachmentPreparationOutcome.Prepared).retained
            true
        }.join()
        assertEquals(PhotoPrivacyFailure.CLEANING_FAILED,
            (handle!!.preparation as AttachmentPreparation.NeedsChoice).kind)
        handle!!.close()
        assertEquals(0L, budget.reservedBytes)
    }

    @Test fun anUnadoptedResultIsDiscardedWithItsEntireCharge() = runTest {
        val budget = AttachmentMemoryBudget(capacity)
        var rejected: RetainedAttachmentPreparation? = null
        task(read(byteArrayOf(1)), budget).start(this) { _, outcome ->
            rejected = (outcome as AttachmentPreparationOutcome.Prepared).retained
            false
        }.join()
        assertNull(rejected!!.preparation)
        assertEquals(0L, budget.reservedBytes)
    }

    @Test fun localFailuresReleaseTheLeaseAndExposeOnlyTheirSafeKind() = runTest {
        val sources = listOf(
            read(byteArrayOf()) to PreparationFailureKind.EMPTY,
            read(byteArrayOf(1, 2)) to PreparationFailureKind.CLIENT_BUDGET,
            PickedAttachmentRead("private-file", null) { throw IOException("private-provider-uri") } to
                PreparationFailureKind.READ_FAILED,
        )
        for ((source, expected) in sources) {
            val budget = AttachmentMemoryBudget(capacity)
            var outcome: AttachmentPreparationOutcome? = null
            task(source, budget, 1).start(this) { _, value -> outcome = value; false }.join()
            assertEquals(expected, (outcome as AttachmentPreparationOutcome.Failed).kind)
            assertFalse(outcome.toString().contains("private"))
            assertEquals(0L, budget.reservedBytes)
        }
    }

    @Test fun deniedCapacityNeverOpensTheProviderOrStartsAnAutomaticRetry() = runTest {
        val budget = AttachmentMemoryBudget(1)
        var opens = 0
        val source = PickedAttachmentRead("private", null) { opens++; ByteArrayInputStream(byteArrayOf(1)) }
        val original = task(source, budget)
        var calls = 0
        original.start(this) { _, outcome ->
            calls++
            assertEquals(PreparationFailureKind.CLIENT_BUDGET, (outcome as AttachmentPreparationOutcome.Failed).kind)
            false
        }.join()
        assertEquals(0, opens)
        assertEquals(1, calls)
        try { original.start(this) { _, _ -> false }; fail() } catch (_: IllegalStateException) { }
        assertEquals(0L, budget.reservedBytes)
    }

    @Test fun cancellationBeforeWorkerEntryAllocatesAndOpensNothing() = runTest {
        val budget = AttachmentMemoryBudget(capacity)
        var opens = 0
        var delivered = false
        val source = PickedAttachmentRead("private", null) { opens++; ByteArrayInputStream(byteArrayOf(1)) }
        val job = task(source, budget).start(this) { _, _ -> delivered = true; true }
        job.cancelAndJoin()
        assertEquals(0, opens)
        assertFalse(delivered)
        assertEquals(0L, budget.reservedBytes)
    }

    @Test fun cancellingAHeldProviderKeepsItsChargeUntilTheActualProducerEnds() = runTest {
        val budget = AttachmentMemoryBudget(capacity)
        val started = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val closed = AtomicBoolean()
        var delivered = false
        val source = PickedAttachmentRead("private", null) {
            object : ByteArrayInputStream(byteArrayOf(1)) {
                override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                    started.countDown()
                    check(finish.await(5, TimeUnit.SECONDS))
                    return super.read(bytes, offset, length)
                }
                override fun close() { closed.set(true); super.close() }
            }
        }
        val job = task(source, budget, 2L * 1024 * 1024).start(this) { _, _ -> delivered = true; true }
        try {
            runCurrent()
            withContext(Dispatchers.IO) { assertTrue(started.await(5, TimeUnit.SECONDS)) }
            job.cancel()
            runCurrent()
            assertFalse(job.isCompleted)
            assertEquals(capacity, budget.reservedBytes)
            assertNull(budget.reserve(1))
            assertFalse(closed.get())
        } finally {
            finish.countDown()
            job.cancelAndJoin()
        }
        assertTrue(closed.get())
        assertFalse(delivered)
        assertEquals(0L, budget.reservedBytes)
    }

    @Test fun aThrowingAdoptionCallbackCannotLeakOrReceiveASecondOutcome() = runTest {
        val budget = AttachmentMemoryBudget(capacity)
        val failures = mutableListOf<Throwable>()
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Main + CoroutineExceptionHandler { _, e -> failures += e })
        var handle: RetainedAttachmentPreparation? = null
        var calls = 0
        try {
            task(read(byteArrayOf(1)), budget).start(owner) { _, outcome ->
                calls++
                handle = (outcome as AttachmentPreparationOutcome.Prepared).retained
                throw IllegalStateException("fixture rejection")
            }.join()
            assertEquals(1, calls)
            assertEquals(1, failures.size)
            assertTrue(failures.single() is IllegalStateException)
            assertNull(handle!!.preparation)
            assertEquals(0L, budget.reservedBytes)
        } finally { owner.cancel() }
    }

    @Test fun anAlreadyRetiredOwnerNeverStartsTheProvider() = runTest {
        val budget = AttachmentMemoryBudget(capacity)
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Main).also { it.cancel() }
        var opens = 0
        var delivered = false
        val source = PickedAttachmentRead("private", null) { opens++; ByteArrayInputStream(byteArrayOf(1)) }
        task(source, budget).start(owner) { _, _ -> delivered = true; true }.join()
        assertEquals(0, opens)
        assertFalse(delivered)
        assertEquals(0L, budget.reservedBytes)
    }
}

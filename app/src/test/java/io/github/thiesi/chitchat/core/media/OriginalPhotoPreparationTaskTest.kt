package io.github.thiesi.chitchat.core.media

import java.io.ByteArrayInputStream
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okio.ByteString
import okio.ByteString.Companion.decodeHex
import okio.ByteString.Companion.encodeUtf8
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OriginalPhotoPreparationTaskTest {
    @Before fun start() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun stop() { Dispatchers.resetMain() }
    private val budget = AttachmentMemoryBudget(8L * 1024 * 1024)

    private suspend fun original(scope: CoroutineScope, bytes: ByteString = "GIF89a".encodeUtf8()): RetainedAttachmentPreparation {
        var opens = 0
        var handle: RetainedAttachmentPreparation? = null
        val read = PickedAttachmentRead("private-file", "image/fixture") {
            check(++opens == 1) { "No provider reopen" }
            ByteArrayInputStream(bytes.toByteArray())
        }
        AttachmentPreparationTask(read, AttachmentPickSource.FILE, budget, 8192).start(scope) { _, outcome ->
            handle = (outcome as AttachmentPreparationOutcome.Prepared).retained
            true
        }.join()
        assertEquals(1, opens)
        return handle!!
    }

    @Test fun bothPrivacyFailuresRequireOneExplicitTaskAndKeepTheExactUnchangedDigest() = runTest {
        for ((bytes, reason) in listOf("GIF89a".encodeUtf8() to PhotoPrivacyFailure.UNSUPPORTED_IMAGE,
            "ffd8ff".decodeHex() to PhotoPrivacyFailure.CLEANING_FAILED)) {
            val source = original(this, bytes)
            val choice = source.preparation as AttachmentPreparation.NeedsChoice
            assertEquals(reason, choice.kind)
            val task = OriginalPhotoPreparationTask(source, choice)
            var result: RetainedAttachmentPreparation? = null
            task.start(this) { completed, outcome ->
                assertSame(task, completed)
                result = (outcome as AttachmentPreparationOutcome.Prepared).retained
                true
            }.join()
            source.close() // A stale handle cannot release its exclusive successor.
            assertNull(source.preparation)
            val ready = result!!.preparation as AttachmentPreparation.Ready
            assertEquals(bytes, ready.payload.bytes)
            assertEquals(bytes.sha256().hex(), ready.payload.sha256)
            assertEquals("private-file", ready.payload.filename)
            assertEquals("image/fixture", ready.payload.mimeType)
            assertEquals(AttachmentPhotoPrivacy.UNCHANGED_AFTER_CHOICE, ready.privacy)
            assertEquals(result!!.reservedBytes, budget.reservedBytes)
            assertTrue(budget.reservedBytes > 0)
            try { task.start(this) { _, _ -> false }; fail() } catch (_: IllegalStateException) { }
            result!!.close(); result!!.close()
            assertEquals(0L, budget.reservedBytes)
        }
    }

    @Test fun capacityDenialBeforeHashIsOneSafeFailureAndNeverProducesRawReadyBytes() = runTest {
        val source = original(this)
        val blocker = budget.reserve(4L * 1024 * 1024)!!
        var calls = 0
        try {
            OriginalPhotoPreparationTask(source, source.preparation as AttachmentPreparation.NeedsChoice)
                .start(this) { _, outcome ->
                    calls++
                    assertEquals(PreparationFailureKind.CLIENT_BUDGET, (outcome as AttachmentPreparationOutcome.Failed).kind)
                    false
                }.join()
            assertEquals(1, calls)
            assertNull(source.preparation)
            assertEquals(blocker.reservedBytes, budget.reservedBytes)
        } finally { source.close(); blocker.close() }
        assertEquals(0L, budget.reservedBytes)
    }

    @Test fun rejectionClosesTheFinalHandleAndTheMovedSourceExactlyOnce() = runTest {
        val source = original(this)
        var result: RetainedAttachmentPreparation? = null
        OriginalPhotoPreparationTask(source, source.preparation as AttachmentPreparation.NeedsChoice)
            .start(this) { _, outcome -> result = (outcome as AttachmentPreparationOutcome.Prepared).retained; false }.join()
        assertNull(result!!.preparation)
        assertNull(source.preparation)
        assertEquals(0L, budget.reservedBytes)
    }

    @Test fun throwingAdoptionCannotLeakOrDeliverASecondResult() = runTest {
        val source = original(this)
        val failures = mutableListOf<Throwable>()
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Main + CoroutineExceptionHandler { _, error -> failures += error })
        var result: RetainedAttachmentPreparation? = null
        var calls = 0
        try {
            OriginalPhotoPreparationTask(source, source.preparation as AttachmentPreparation.NeedsChoice)
                .start(owner) { _, outcome ->
                    calls++; result = (outcome as AttachmentPreparationOutcome.Prepared).retained
                    throw IllegalStateException("fixture rejection")
                }.join()
            assertEquals(1, calls); assertEquals(1, failures.size)
            assertTrue(failures.single() is IllegalStateException)
            assertNull(result!!.preparation); assertEquals(0L, budget.reservedBytes)
        } finally { owner.cancel(); source.close() }
    }

    @Test fun cancellationBeforeEntryReleasesTheUnmovedSourceWithoutDelivery() = runTest {
        val source = original(this)
        var calls = 0
        val job = OriginalPhotoPreparationTask(source, source.preparation as AttachmentPreparation.NeedsChoice)
            .start(this) { _, _ -> calls++; true }
        assertTrue(budget.reservedBytes > 0)
        job.cancelAndJoin()
        assertEquals(0, calls); assertNull(source.preparation); assertEquals(0L, budget.reservedBytes)
    }

    @Test fun anAlreadyRetiredScopeReleasesTheSourceEvenWhenTheCoroutineBodyNeverRuns() = runTest {
        val source = original(this)
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Main).also { it.cancel() }
        var calls = 0
        OriginalPhotoPreparationTask(source, source.preparation as AttachmentPreparation.NeedsChoice)
            .start(owner) { _, _ -> calls++; true }.join()
        assertEquals(0, calls); assertNull(source.preparation); assertEquals(0L, budget.reservedBytes)
    }

    @Test fun onlyTheExactChoiceCanMoveAndAnOldHandleCannotReleaseTheNewRetainedLease() = runTest {
        val source = original(this)
        val choice = source.preparation as AttachmentPreparation.NeedsChoice
        val other = AttachmentPreparation.NeedsChoice(choice.original, choice.kind)
        assertNull(source.takeOriginal(other))
        assertSame(choice, source.preparation)
        val input = source.takeOriginal(choice)!!
        assertNull(source.takeOriginal(choice)); source.close()
        assertTrue(input.reservedBytes > 0)
        val payload = PreparedAttachmentPayload.create(choice.original.filename, choice.original.mimeType, choice.original.bytes)
        val retained = input.retain(AttachmentPreparation.Ready(payload, AttachmentPhotoPrivacy.UNCHANGED_AFTER_CHOICE))
        input.close(); source.close()
        assertTrue(retained.reservedBytes > 0)
        assertEquals(retained.reservedBytes, budget.reservedBytes)
        retained.close(); assertEquals(0L, budget.reservedBytes)
    }
}

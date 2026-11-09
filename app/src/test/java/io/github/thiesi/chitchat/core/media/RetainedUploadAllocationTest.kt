package io.github.thiesi.chitchat.core.media

import kotlinx.coroutines.test.runTest
import okio.ByteString.Companion.encodeUtf8
import org.junit.Assert.*
import org.junit.Test

class RetainedUploadAllocationTest {
    private suspend fun ready() = AttachmentPreparation.Ready(
        PreparedAttachmentPayload.create("private-fixture", null, "private-payload".encodeUtf8()),
        AttachmentPhotoPrivacy.NON_PHOTO)

    @Test fun retiredDraftCannotAdmitAnotherAllocationBeforeItsUploadEnds() = runTest {
        val budget = AttachmentMemoryBudget(128)
        val ready = ready()
        val draft = RetainedAttachmentPreparation(ready, checkNotNull(budget.reserve(128)))
        val upload = checkNotNull(draft.holdUpload(ready))
        draft.close()
        assertNull(draft.preparation)
        assertSame(ready.payload, upload.payload)
        assertEquals(128L, budget.reservedBytes)
        assertNull(budget.reserve(1))
        upload.close()
        assertEquals(0L, budget.reservedBytes)
        val successor = checkNotNull(budget.reserve(128))
        draft.close(); upload.close()
        assertEquals(128L, budget.reservedBytes)
        successor.close()
    }

    @Test fun completedAttemptKeepsTheDraftAndItsStaleCloseCannotReleaseANewerHold() = runTest {
        val budget = AttachmentMemoryBudget(128)
        val ready = ready()
        val draft = RetainedAttachmentPreparation(ready, checkNotNull(budget.reserve(128)))
        val first = checkNotNull(draft.holdUpload(ready))
        first.close()
        assertSame(ready, draft.preparation)
        assertEquals(128L, budget.reservedBytes)
        val second = checkNotNull(draft.holdUpload(ready))
        draft.close(); first.close()
        assertSame(ready.payload, second.payload)
        assertEquals(128L, budget.reservedBytes)
        second.close()
        assertEquals(0L, budget.reservedBytes)
    }

    @Test fun wrongOriginalAndConcurrentOrRetiredHoldsGrantNoByteAccess() = runTest {
        val budget = AttachmentMemoryBudget(128)
        val ready = ready()
        val draft = RetainedAttachmentPreparation(ready, checkNotNull(budget.reserve(128)))
        assertNull(draft.holdUpload(AttachmentPreparation.Ready(ready.payload, ready.privacy)))
        val upload = checkNotNull(draft.holdUpload(ready))
        assertNull(draft.holdUpload(ready))
        assertFalse(upload.toString().contains("private-fixture"))
        assertFalse(draft.toString().contains("private-payload"))
        upload.close()
        try { upload.payload; fail("Closed allocation must not expose bytes") } catch (_: IllegalStateException) { }
        draft.close()
        assertNull(draft.holdUpload(ready))
        assertEquals(0L, budget.reservedBytes)
    }

    @Test fun unchosenOriginalCannotBecomeAnUploadHoldAndStillMovesExclusively() = runTest {
        val budget = AttachmentMemoryBudget(128)
        val choice = AttachmentPreparation.NeedsChoice(
            PickedAttachmentSnapshot("private-fixture", null, "GIF89a".encodeUtf8()), PhotoPrivacyFailure.UNSUPPORTED_IMAGE)
        val draft = RetainedAttachmentPreparation(choice, checkNotNull(budget.reserve(128)))
        assertNull(draft.holdUpload(ready()))
        val moved = checkNotNull(draft.takeOriginal(choice))
        draft.close()
        assertEquals(128L, budget.reservedBytes)
        assertEquals("GIF89a", moved.original.bytes.utf8())
        moved.close()
        assertEquals(0L, budget.reservedBytes)
    }
}

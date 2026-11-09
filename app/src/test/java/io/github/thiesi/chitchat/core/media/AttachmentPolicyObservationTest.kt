package io.github.thiesi.chitchat.core.media

import org.junit.Assert.*
import org.junit.Test

class AttachmentPolicyObservationTest {
    private fun known(max: Long = 10, types: Set<String> = setOf("image/jpeg")) =
        AttachmentPolicy.Known(max, types, emptySet(), null)

    @Test fun missingUnavailableAndInvalidStayDistinctWithoutInventedRestrictions() {
        assertSame(AttachmentPolicyObservation.Unadvertised,
            observeAttachmentPolicy(AttachmentPolicy.Unknown, Long.MAX_VALUE, "image/png"))
        assertSame(AttachmentPolicyObservation.AwaitingFinalBytes,
            observeAttachmentPolicy(AttachmentPolicy.Unknown, null, "image/png"))
        assertSame(AttachmentPolicyObservation.Unavailable,
            observeAttachmentPolicy(AttachmentPolicy.Unavailable, 1, null))
        assertSame(AttachmentPolicyObservation.Invalid,
            observeAttachmentPolicy(AttachmentPolicy.Invalid, null, null))
    }

    @Test fun explicitZeroIsDisabledBeforeReadinessSizeOrTypeChecks() {
        for (size in listOf(null, 1L)) assertSame(AttachmentPolicyObservation.UploadsDisabled,
            observeAttachmentPolicy(known(0), size, "image/png"))
    }

    @Test fun exactFinalSizeIsAllowedAndLargerKeepsTheActualServerNumbers() {
        assertSame(AttachmentPolicyObservation.WithinAdvertisedFacts, observeAttachmentPolicy(known(), 10, "image/jpeg"))
        val tooLarge = observeAttachmentPolicy(known(), 11, "image/jpeg") as AttachmentPolicyObservation.TooLarge
        assertEquals(11L, tooLarge.sizeBytes); assertEquals(10L, tooLarge.maxBytes)
        assertSame(AttachmentPolicyObservation.WithinAdvertisedFacts,
            observeAttachmentPolicy(known(Long.MAX_VALUE), Long.MAX_VALUE, "image/jpeg"))
    }

    @Test fun absentOrOctetStreamMimeDefersToServerAndKnownHintsAreCaseInsensitive() {
        for (mime in listOf(null, "application/octet-stream", "APPLICATION/OCTET-STREAM"))
            assertSame(AttachmentPolicyObservation.WithinAdvertisedFacts, observeAttachmentPolicy(known(types=emptySet()), 1, mime))
        assertSame(AttachmentPolicyObservation.WithinAdvertisedFacts, observeAttachmentPolicy(known(), 1, "IMAGE/JPEG"))
        val blocked = observeAttachmentPolicy(known(), 1, "IMAGE/PNG") as AttachmentPolicyObservation.TypeNotAllowed
        assertEquals("image/png", blocked.mimeType)
    }

    @Test fun originalPhotoSizeIsNotAReadySizeButItsFrozenTypeHintCanBeChecked() {
        assertSame(AttachmentPolicyObservation.AwaitingFinalBytes, observeAttachmentPolicy(known(), null, "image/jpeg"))
        val blocked = observeAttachmentPolicy(known(), null, "image/gif") as AttachmentPolicyObservation.TypeNotAllowed
        assertEquals("image/gif", blocked.mimeType)
        // When both final facts violate policy, keep the transport's existing size-first order.
        assertTrue(observeAttachmentPolicy(known(), 11, "image/gif") is AttachmentPolicyObservation.TooLarge)
    }
}

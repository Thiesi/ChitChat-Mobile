package io.github.thiesi.chitchat.core.media

import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class AttachmentPickingTest {
    private fun known(vararg types: String, maxBytes: Long = 10_000_000) =
        AttachmentPolicy.Known(maxBytes, types.toSet(), emptySet(), null)

    @Test fun missingPolicyDoesNotFilterOrHidePhotos() {
        val filters = attachmentPickFilters(AttachmentPolicy.Unknown)
        assertEquals(listOf("*/*"), filters.documentTypes)
        assertSame(PhotoPickerFilter.Images, filters.photo)
    }

    @Test fun knownPolicyFiltersToAllowedTypesAndOmitsPhotoWithoutImages() {
        val none = attachmentPickFilters(known("application/pdf", "text/plain"))
        assertEquals(listOf("application/pdf", "text/plain"), none.documentTypes)
        assertNull(none.photo)
        val one = attachmentPickFilters(known("IMAGE/PNG", "application/pdf")).photo
        assertEquals("image/png", (one as PhotoPickerFilter.Single).mimeType)
        assertSame(PhotoPickerFilter.Images, attachmentPickFilters(known("image/png", "image/jpeg")).photo)
    }

    @Test fun providerNamesBecomeValidBasenamesWithoutInventedTypes() {
        assertEquals("report.pdf", pickedAttachmentFilename(" report.pdf "))
        assertEquals("a_b_c.txt", pickedAttachmentFilename("a/b\\c.txt"))
        assertEquals("x_y", pickedAttachmentFilename("x\u0000y"))
        listOf(null, "", "   ", ".", "..").forEach { assertEquals("attachment", pickedAttachmentFilename(it)) }
        val long = "n".repeat(300) + ".jpeg"
        val cut = pickedAttachmentFilename(long)
        assertEquals(255, cut.length); assertTrue(cut.endsWith(".jpeg"))
        validateAttachmentMetadata(cut, null)
        val surrogate = pickedAttachmentFilename("a".repeat(254) + "📎" + "b")
        assertFalse(surrogate.last().isHighSurrogate())
        validateAttachmentMetadata(surrogate, null)
    }

    @Test fun onlyConcreteProviderTypesAreKept() {
        assertEquals("image/png", pickedAttachmentMimeType(" Image/PNG "))
        listOf(null, "", "image/*", "*/*", "not a type").forEach { assertNull(pickedAttachmentMimeType(it)) }
    }

    @Test fun sizesUseDecimalUnits() {
        fun text(bytes: Long) = attachmentSizeText(bytes, Locale.US).let { "${it.number} ${it.unit}" }
        assertEquals("999 BYTES", text(999))
        assertEquals("2.4 MB", text(2_400_000))
        assertEquals("10 MB", text(10_000_000))
        assertEquals("140 MB", text(140_400_000))
        assertEquals("1.2 GB", text(1_200_000_000))
    }

    @Test fun refusedSizeNeverReadsLikeTheLimit() {
        val (size, limit) = attachmentSizeComparison(10_440_000, 10_400_000, Locale.US)
        assertNotEquals(size.number, limit.number)
        assertEquals("10.44", size.number); assertEquals("10.4", limit.number)
        val (bigger, cap) = attachmentSizeComparison(14_000_000, 10_000_000, Locale.US)
        assertEquals("14", bigger.number); assertEquals("10", cap.number)
        val (near, edge) = attachmentSizeComparison(10_000_001, 10_000_000, Locale.US)
        assertNotEquals("${near.number} ${near.unit}", "${edge.number} ${edge.unit}")
    }

    @Test fun refusedKindsUseHonestShortNames() {
        assertEquals("HEIC", (attachmentKindText("image/heic", "x.heic") as AttachmentKindText.Images).name)
        assertEquals("MP4", (attachmentKindText("video/mp4", "clip") as AttachmentKindText.Videos).name)
        assertEquals("SVG", (attachmentKindText("image/svg+xml", "x") as AttachmentKindText.Images).name)
        assertEquals("ZIP", (attachmentKindText("application/zip", "pack.zip") as AttachmentKindText.Files).extension)
        assertNull(attachmentKindText("application/octet-stream", "noextension"))
        assertNull(attachmentExtension(".hidden"))
    }

    @Test fun rateWindowsArePhrasedFromTheAdvertisedWindowOnly() {
        assertSame(UploadRateWindow.Hour, uploadRateWindow(3_600))
        assertEquals(15L, (uploadRateWindow(900) as UploadRateWindow.Minutes).minutes)
        assertEquals(120L, (uploadRateWindow(7_200) as UploadRateWindow.Minutes).minutes)
        assertEquals(90L, (uploadRateWindow(90) as UploadRateWindow.Seconds).seconds)
    }
}

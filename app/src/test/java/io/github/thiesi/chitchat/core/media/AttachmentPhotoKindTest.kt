package io.github.thiesi.chitchat.core.media

import kotlinx.coroutines.test.runTest
import okio.Buffer
import okio.ByteString
import okio.ByteString.Companion.decodeHex
import okio.ByteString.Companion.encodeUtf8
import org.junit.Assert.*
import org.junit.Test

class AttachmentPhotoKindTest {
    @Test fun supportedSignaturesWinOverEveryPickerAndMimeHint() = runTest {
        for ((bytes, expected) in listOf(
            "ffd8ffe0".decodeHex() to AttachmentPhotoKind.JPEG,
            "89504e470d0a1a0a".decodeHex() to AttachmentPhotoKind.PNG,
            "524946460400000057454250".decodeHex() to AttachmentPhotoKind.WEBP,
        )) for (source in AttachmentPickSource.entries) for (mime in listOf(null, "text/plain", "image/other")) {
            assertEquals(expected, attachmentPhotoKind(bytes, mime, source))
        }
    }

    @Test fun unsupportedBinaryImagesCannotBeDowngradedByNonImageMime() = runTest {
        val headers = listOf("GIF87a".encodeUtf8(), "GIF89a".encodeUtf8(), bmp(40),
            "49492a00".decodeHex(), "4d4d002a".decodeHex(), "49492b00".decodeHex(), "4d4d002b".decodeHex(),
            "4949524f".decodeHex(), "49495253".decodeHex(), "4d4d4f52".decodeHex(), "4d4d5352".decodeHex(),
            "49495500".decodeHex(), "4d4d0055".decodeHex(), "FUJIFILMCCD-RAW".encodeUtf8())
        for (header in headers) assertEquals(AttachmentPhotoKind.UNSUPPORTED_IMAGE,
            attachmentPhotoKind(header, "application/octet-stream", AttachmentPickSource.FILE))
    }

    @Test fun everyRegisteredImageBrandIsRecognizedAsMajorOrCompatible() = runTest {
        for (brand in listOf("mif1", "msf1", "heic", "heix", "hevc", "hevx", "heim", "heis", "hevm", "hevs", "avif", "avis")) {
            for (extended in listOf(false, true)) {
                assertEquals(AttachmentPhotoKind.UNSUPPORTED_IMAGE, kind(ftyp(brand, extended = extended)))
                assertEquals(AttachmentPhotoKind.UNSUPPORTED_IMAGE, kind(ftyp("isom", listOf("zzzz", brand), extended)))
            }
        }
    }

    @Test fun compatibleBrandsAreNotLimitedToASmallSniffPrefix() = runTest {
        assertEquals(AttachmentPhotoKind.UNSUPPORTED_IMAGE,
            kind(ftyp("isom", List(2048) { "zzzz" } + "avif")))
    }

    @Test fun minorVersionAndBytesAfterTheFileTypeBoxAreNotBrands() = runTest {
        val minor = Buffer().writeInt(16).writeUtf8("ftypisomavif").readByteString()
        assertEquals(AttachmentPhotoKind.NON_PHOTO, kind(minor))
        val after = Buffer().write(ftyp("isom")).writeInt(12).writeUtf8("freeheic").readByteString()
        assertEquals(AttachmentPhotoKind.NON_PHOTO, kind(after))
    }

    @Test fun unknownNonImageFilesStayUnchangedWithoutAnExtraPrivacyPrompt() = runTest {
        for (mime in listOf(null, "application/octet-stream", "application/pdf", "text/plain"))
            assertEquals(AttachmentPhotoKind.NON_PHOTO,
                attachmentPhotoKind("opaque file".encodeUtf8(), mime, AttachmentPickSource.FILE))
    }

    @Test fun bitmapDibFamiliesAreRecognizedEvenWithATruncatedBody() = runTest {
        for (size in listOf(12, 108, 124, 42, 46) + (16..64 step 4)) {
            val bytes = bmp(size)
            for (source in AttachmentPickSource.entries) for (mime in listOf(null, "text/plain", "image/bmp"))
                assertEquals(AttachmentPhotoKind.UNSUPPORTED_IMAGE, attachmentPhotoKind(bytes, mime, source))
        }
        for (size in listOf(0, 11, 13, 17, 41, 65, -1, Int.MAX_VALUE)) {
            val bytes = bmp(size)
            assertEquals(AttachmentPhotoKind.NON_PHOTO, kind(bytes))
            assertEquals(AttachmentPhotoKind.UNSUPPORTED_IMAGE,
                attachmentPhotoKind(bytes, "image/bmp", AttachmentPickSource.FILE))
            assertEquals(AttachmentPhotoKind.UNSUPPORTED_IMAGE,
                attachmentPhotoKind(bytes, null, AttachmentPickSource.PHOTO_PICKER))
        }
    }

    @Test fun ordinaryBmPrefixedFilesDoNotGetAPhotoWarning() = runTest {
        for (bytes in listOf("BMI report: measurements".encodeUtf8(), "BM".encodeUtf8(),
            "BMF\u0003not a bitmap font image".encodeUtf8(),
            Buffer().writeUtf8("BM").write(ByteArray(12)).writeIntLe(0).readByteString())) {
            for (mime in listOf(null, "text/plain", "application/octet-stream"))
                assertEquals(AttachmentPhotoKind.NON_PHOTO,
                    attachmentPhotoKind(bytes, mime, AttachmentPickSource.FILE))
        }
    }

    @Test fun imageMimeAndPhotoPickerOnlyEscalateUnknownHeaders() = runTest {
        val bytes = "%PDF-opaque".encodeUtf8()
        assertEquals(AttachmentPhotoKind.UNSUPPORTED_IMAGE,
            attachmentPhotoKind(bytes, "IMAGE/HEIC", AttachmentPickSource.FILE))
        assertEquals(AttachmentPhotoKind.UNSUPPORTED_IMAGE,
            attachmentPhotoKind(bytes, "application/pdf", AttachmentPickSource.PHOTO_PICKER))
        assertEquals(AttachmentPhotoKind.UNSUPPORTED_IMAGE,
            attachmentPhotoKind(ByteString.EMPTY, null, AttachmentPickSource.PHOTO_PICKER))
    }

    @Test fun everyTruncatedSignatureHasDefinedBoundedBehavior() = runTest {
        for ((header, fullKind) in listOf(
            "ffd8ff".decodeHex() to AttachmentPhotoKind.JPEG,
            "89504e470d0a1a0a".decodeHex() to AttachmentPhotoKind.PNG,
            "524946460400000057454250".decodeHex() to AttachmentPhotoKind.WEBP,
            "GIF89a".encodeUtf8() to AttachmentPhotoKind.UNSUPPORTED_IMAGE,
            bmp(40) to AttachmentPhotoKind.UNSUPPORTED_IMAGE,
            "49492a00".decodeHex() to AttachmentPhotoKind.UNSUPPORTED_IMAGE,
        )) for (cut in 0..header.size) {
            val bytes = header.substring(0, cut)
            assertEquals(if (cut == header.size) fullKind else AttachmentPhotoKind.NON_PHOTO, kind(bytes))
            assertEquals(if (cut == header.size) fullKind else AttachmentPhotoKind.UNSUPPORTED_IMAGE,
                attachmentPhotoKind(bytes, "image/unknown", AttachmentPickSource.FILE))
        }
    }

    @Test fun truncatedIsoHeadersEscalateAsSoonAsAnImageMajorBrandIsKnown() = runTest {
        for (extended in listOf(false, true)) {
            val bytes = ftyp("avif", listOf("mif1"), extended)
            val completeBrand = if (extended) 20 else 12
            for (cut in 0..bytes.size) assertEquals(
                if (cut >= completeBrand) AttachmentPhotoKind.UNSUPPORTED_IMAGE else AttachmentPhotoKind.NON_PHOTO,
                kind(bytes.substring(0, cut)))
        }
    }

    @Test fun garbageOrOtherRiffAndIsoContainersAreNotGuessedToBePhotos() = runTest {
        for (bytes in listOf("GIF00a".encodeUtf8(), "xGIF89a".encodeUtf8(), "heic".encodeUtf8(),
            "524946460400000057415645".decodeHex(), ftyp("isom", listOf("mp41")),
            Buffer().writeInt(20).writeUtf8("junkavif").writeLong(0).readByteString())) {
            assertEquals(AttachmentPhotoKind.NON_PHOTO, kind(bytes))
        }
    }

    @Test fun corruptSizesNeverDowngradeAKnownImageMajorBrand() = runTest {
        for (size in listOf(0, 4, -1)) {
            val bytes = Buffer().writeInt(size).writeUtf8("ftypheic").readByteString()
            assertEquals(AttachmentPhotoKind.UNSUPPORTED_IMAGE, kind(bytes))
        }
        val huge = Buffer().writeInt(1).writeUtf8("ftyp").writeLong(Long.MIN_VALUE)
            .writeUtf8("isom").writeInt(0).writeUtf8("avif").readByteString()
        assertEquals(AttachmentPhotoKind.UNSUPPORTED_IMAGE, kind(huge))
    }

    private fun bmp(dibSize: Int) = Buffer().writeUtf8("BM").write(ByteArray(12)).writeIntLe(dibSize).readByteString()
    private suspend fun kind(bytes: ByteString) = attachmentPhotoKind(bytes, null, AttachmentPickSource.FILE)
    private fun ftyp(major: String, compatible: List<String> = emptyList(), extended: Boolean = false): ByteString {
        val size = (if (extended) 24 else 16) + compatible.size * 4
        return Buffer().apply {
            writeInt(if (extended) 1 else size); writeUtf8("ftyp")
            if (extended) writeLong(size.toLong())
            writeUtf8(major); writeInt(0); compatible.forEach { writeUtf8(it) }
        }.readByteString()
    }
}

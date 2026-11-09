package io.github.thiesi.chitchat.core.media

import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import okio.Buffer
import okio.ByteString
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.decodeHex
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import org.junit.Assert.*
import org.junit.Test

class AttachmentPreparationTest {
    private val privateText = "private-location-fixture"
    private fun picked(bytes: ByteString, mime: String? = null) = PickedAttachmentSnapshot("private-file", mime, bytes)

    @Test fun unknownNonPhotoFileKeepsExactBytesAndUnknownMimeWithoutAChoice() = runTest {
        val input = picked(privateText.encodeUtf8())
        val result = prepareAttachment(input, AttachmentPickSource.FILE) as AttachmentPreparation.Ready
        assertEquals(AttachmentPhotoPrivacy.NON_PHOTO, result.privacy)
        assertSame(input.bytes, result.payload.bytes)
        assertNull(result.payload.mimeType)
        assertEquals(input.bytes.sha256().hex(), result.payload.sha256)
    }

    @Test fun unsupportedImagesAndImageHintedUnknownsReturnOnlyTheirOriginalChoice() = runTest {
        for ((bytes, mime, source) in listOf(
            Triple("GIF89a".encodeUtf8(), "text/plain", AttachmentPickSource.FILE),
            Triple(Buffer().writeUtf8("BM").write(ByteArray(12)).writeIntLe(40).readByteString(), null, AttachmentPickSource.FILE),
            Triple("49492a00".decodeHex(), null, AttachmentPickSource.FILE),
            Triple(Buffer().writeInt(16).writeUtf8("ftypavif").writeInt(0).readByteString(), null, AttachmentPickSource.FILE),
            Triple(privateText.encodeUtf8(), "image/heic", AttachmentPickSource.FILE),
            Triple(privateText.encodeUtf8(), null, AttachmentPickSource.PHOTO_PICKER),
        )) {
            val original = picked(bytes, mime)
            val result = prepareAttachment(original, source) as AttachmentPreparation.NeedsChoice
            assertSame(original, result.original)
            assertEquals(PhotoPrivacyFailure.UNSUPPORTED_IMAGE, result.kind)
        }
    }

    @Test fun malformedSupportedPhotosNeverFallThroughToUnchangedFiles() = runTest {
        for (bytes in listOf("ffd8ff".decodeHex(), "89504e470d0a1a0a".decodeHex(),
            "524946460400000057454250".decodeHex())) {
            val original = picked(bytes, "application/octet-stream")
            val result = prepareAttachment(original, AttachmentPickSource.FILE) as AttachmentPreparation.NeedsChoice
            assertSame(original, result.original)
            assertEquals(PhotoPrivacyFailure.CLEANING_FAILED, result.kind)
        }
    }

    @Test fun animatedSupportedContainersRequireChoiceInsteadOfFlattening() = runTest {
        val animatedWebp = Buffer().writeUtf8("RIFF").writeIntLe(22).writeUtf8("WEBP")
            .write(webpChunk("VP8X", Buffer().writeByte(2).write(ByteArray(9)).readByteString())).readByteString()
        val animatedPng = Buffer().write("89504e470d0a1a0a".decodeHex())
            .write(chunk("IHDR", Buffer().writeInt(1).writeInt(1).writeByte(8).writeByte(2)
                .writeByte(0).writeByte(0).writeByte(0).readByteString()))
            .write(chunk("acTL", Buffer().writeInt(1).writeInt(0).readByteString())).readByteString()
        for (bytes in listOf(animatedPng, animatedWebp)) {
            val original = picked(bytes, "application/octet-stream")
            val result = prepareAttachment(original, AttachmentPickSource.FILE) as AttachmentPreparation.NeedsChoice
            assertSame(original, result.original)
            assertEquals(PhotoPrivacyFailure.CLEANING_FAILED, result.kind)
        }
    }

    @Test fun jpegMagicRoutesToJpegCleaningDespiteTheWrongMimeHint() = runTest {
        val originalBytes = Buffer().write(BASE_JPEG, 0, 20)
            .writeByte(0xff).writeByte(0xfe).writeShort(privateText.length + 2).writeUtf8(privateText)
            .write(BASE_JPEG, 20, BASE_JPEG.size - 20).readByteString()
        val result = prepareAttachment(picked(originalBytes, "image/png"), AttachmentPickSource.FILE) as AttachmentPreparation.Ready
        assertEquals(AttachmentPhotoPrivacy.LOCATION_REMOVED, result.privacy)
        assertEquals(BASE_JPEG, result.payload.bytes)
        assertEquals("image/png", result.payload.mimeType) // Classification never invents transport/policy facts.
        assertNotEquals(originalBytes.sha256().hex(), result.payload.sha256)
        assertEquals(BASE_JPEG.sha256().hex(), result.payload.sha256)
    }

    @Test fun pngMagicRemovesTextBeforeHashingWithoutPromotingUnknownMime() = runTest {
        val head = chunk("IHDR", Buffer().writeInt(1).writeInt(1).writeByte(8).writeByte(2)
            .writeByte(0).writeByte(0).writeByte(0).readByteString())
        val out = ByteArrayOutputStream()
        DeflaterOutputStream(out).use { it.write(byteArrayOf(0, 12, 34, 56)) }
        val coded = chunk("IDAT", out.toByteArray().toByteString())
        val end = chunk("IEND", ByteString.EMPTY)
        val prefix = "89504e470d0a1a0a".decodeHex()
        val original = Buffer().write(prefix).write(head).write(chunk("tEXt", ("Location\u0000" + privateText).encodeUtf8()))
            .write(coded).write(end).readByteString()
        val expected = Buffer().write(prefix).write(head).write(coded).write(end).readByteString()
        val result = prepareAttachment(picked(original), AttachmentPickSource.FILE) as AttachmentPreparation.Ready
        assertEquals(AttachmentPhotoPrivacy.LOCATION_REMOVED, result.privacy)
        assertEquals(expected, result.payload.bytes)
        assertEquals(expected.sha256().hex(), result.payload.sha256)
        assertNull(result.payload.mimeType)
    }

    @Test fun webpMagicUsesTheStaticCleanerAndHashesOnlyTheCleanedContainer() = runTest {
        val tiff = Buffer().writeUtf8("II").writeShortLe(42).writeIntLe(8).writeShortLe(1)
            .writeShortLe(0x0112).writeShortLe(3).writeIntLe(1).writeShortLe(1).writeShortLe(0).writeIntLe(0).readByteString()
        val image = webpChunk("VP8L", "2f0000000000".decodeHex()) // Structural fixture, not decoder/pixel proof.
        fun extended(flags: Int) = webpChunk("VP8X", Buffer().writeByte(flags).write(ByteArray(9)).readByteString())
        fun riff(parts: ByteString) = Buffer().writeUtf8("RIFF").writeIntLe(parts.size + 4).writeUtf8("WEBP").write(parts).readByteString()
        val original = riff(Buffer().write(extended(0x0c)).write(image).write(webpChunk("EXIF", tiff))
            .write(webpChunk("XMP ", privateText.encodeUtf8())).readByteString())
        val expected = riff(Buffer().write(extended(0)).write(image).readByteString())
        val result = prepareAttachment(picked(original, "image/jpeg"), AttachmentPickSource.PHOTO_PICKER) as AttachmentPreparation.Ready
        assertEquals(AttachmentPhotoPrivacy.LOCATION_REMOVED, result.privacy)
        assertEquals(expected, result.payload.bytes)
        assertEquals(expected.sha256().hex(), result.payload.sha256)
    }

    @Test fun equalBytesInASuccessorSelectionDoNotReplaceTheOriginalChoiceReference() = runTest {
        val first = picked("GIF89a".encodeUtf8())
        val successor = picked(first.bytes)
        val result = prepareAttachment(first, AttachmentPickSource.FILE) as AttachmentPreparation.NeedsChoice
        assertSame(first, result.original)
        assertNotSame(successor, result.original)
    }

    @Test fun emptyAndOverBudgetImagesAreLocalFailuresNotConsentChoices() = runTest {
        for ((bytes, kind) in listOf(ByteString.EMPTY to PreparationFailureKind.EMPTY,
            ByteArray(MAX_ATTACHMENT_SNAPSHOT_BYTES.toInt() + 1).toByteString() to PreparationFailureKind.CLIENT_BUDGET)) {
            try { prepareAttachment(picked(bytes, "image/gif"), AttachmentPickSource.PHOTO_PICKER); fail() }
            catch (failure: AttachmentPreparationFailure) { assertEquals(kind, failure.kind); assertNull(failure.cause) }
        }
    }

    @Test fun retiredCallerCannotPublishAReadyOrChoiceResult() = runTest {
        var entered = false
        var published = false
        val retired = Job().also { it.cancel() }
        val pending = CoroutineScope(coroutineContext + retired).async(start = CoroutineStart.UNDISPATCHED) {
            entered = true // UNDISPATCHED reaches the helper even though its caller is already retired.
            prepareAttachment(picked(privateText.encodeUtf8()), AttachmentPickSource.FILE).also { published = true }
        }
        assertTrue(entered)
        assertTrue(pending.isCancelled)
        try { pending.await(); fail() } catch (_: CancellationException) { }
        assertFalse(published)
    }

    @Test fun resultDebugStringsContainNoPrivateFilenameOrBytes() = runTest {
        for (result in listOf(prepareAttachment(picked(privateText.encodeUtf8()), AttachmentPickSource.FILE),
            prepareAttachment(picked(privateText.encodeUtf8()), AttachmentPickSource.PHOTO_PICKER))) {
            assertFalse(result.toString().contains("private-file"))
            assertFalse(result.toString().contains(privateText))
        }
    }

    private fun chunk(kind: String, bytes: ByteString): ByteString {
        val crc = CRC32().apply { update(kind.encodeUtf8().toByteArray()); update(bytes.toByteArray()) }
        return Buffer().writeInt(bytes.size).writeUtf8(kind).write(bytes).writeInt(crc.value.toInt()).readByteString()
    }
    private fun webpChunk(kind: String, bytes: ByteString): ByteString = Buffer().writeUtf8(kind).writeIntLe(bytes.size).write(bytes)
        .apply { if (bytes.size % 2 != 0) writeByte(0) }.readByteString()

    companion object {
        // Own 8x4 RGB JPEG from JpegPhotoPrivacyTest; inserted metadata is synthetic.
        private val BASE_JPEG = "/9j/4AAQSkZJRgABAQEAYABgAAD/2wBDAAMCAgMCAgMDAwMEAwMEBQgFBQQEBQoHBwYIDAoMDAsKCwsNDhIQDQ4RDgsLEBYQERMUFRUVDA8XGBYUGBIUFRT/2wBDAQMEBAUEBQkFBQkUDQsNFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBT/wAARCAAEAAgDASIAAhEBAxEB/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUFBAQAAAF9AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVWV1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uHi4+Tl5ufo6erx8vP09fb3+Pn6/8QAHwEAAwEBAQEBAQEBAQAAAAAAAAECAwQFBgcICQoL/8QAtREAAgECBAQDBAcFBAQAAQJ3AAECAxEEBSExBhJBUQdhcRMiMoEIFEKRobHBCSMzUvAVYnLRChYkNOEl8RcYGRomJygpKjU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6goOEhYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6wsPExcbHyMnK0tPU1dbX2Nna4uPk5ebn6Onq8vP09fb3+Pn6/9oADAMBAAIRAxEAPwBnwZ/Zx8C/uP8AiVencf4UUUV4eCxNb2K99/ez3+Bczx/9jU/38/8AwKX+Z//Z".decodeBase64()!!
    }
}

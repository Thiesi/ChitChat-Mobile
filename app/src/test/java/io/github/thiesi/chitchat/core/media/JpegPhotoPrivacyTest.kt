package io.github.thiesi.chitchat.core.media

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.test.runTest
import okio.Buffer
import okio.ByteString
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.decodeHex
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import org.junit.Assert.*
import org.junit.Test

class JpegPhotoPrivacyTest {
    private val privateText = "private-location-fixture"
    private val xmp get() = segment(0xe1, ("http://ns.adobe.com/xap/1.0/\u0000" + privateText).encodeUtf8())
    private val comment get() = segment(0xfe, privateText.encodeUtf8())

    @Test fun removesLocationContainersWithoutChangingTheRealJpegCodestream() = runTest {
        val source = insert(exif(1), xmp, comment, segment(0xed, privateText.encodeUtf8()), segment(0xe7, privateText.encodeUtf8()))
        val result = cleaned(source)
        assertEquals(base, result.bytes)
        assertEquals(1, result.exifOrientation)
        assertTrue(source.utf8().contains(privateText))
        assertFalse(result.bytes.utf8().contains(privateText))
        assertFalse(result.toString().contains(privateText))
    }

    @Test fun allEightLittleAndBigEndianOrientationsRetainOnlyTheRequiredValue() = runTest {
        for (little in listOf(true, false)) for (orientation in 1..8) {
            val result = cleaned(insert(exif(orientation, little), xmp))
            assertEquals(orientation, result.exifOrientation)
            val expected = if (orientation == 1) base else insert(minimal(orientation))
            assertEquals(expected, result.bytes)
            if (orientation != 1) {
                // Independent TIFF field read: exactly one SHORT/1 orientation, no GPS directory.
                val tiff = ByteBuffer.wrap(result.bytes.toByteArray()).order(ByteOrder.LITTLE_ENDIAN)
                val start = JFIF_END + 10
                assertEquals(8, tiff.getInt(start + 4))
                assertEquals(1, tiff.getShort(start + 8).toInt())
                assertEquals(0x0112, tiff.getShort(start + 10).toInt())
                assertEquals(3, tiff.getShort(start + 12).toInt())
                assertEquals(1, tiff.getInt(start + 14))
                assertEquals(orientation, tiff.getShort(start + 18).toInt())
                assertEquals(0, tiff.getInt(start + 22))
            }
        }
    }

    @Test fun preservesRecognizedColourDataAndRemovesEmbeddedThumbnailMetadata() = runTest {
        val icc = segment(0xe2, Buffer().writeUtf8("ICC_PROFILE\u0000").writeByte(1).writeByte(1)
            .writeUtf8("colour-profile-fixture").readByteString())
        val adobe = segment(0xee, Buffer().writeUtf8("Adobe").writeShort(100).writeShort(0).writeShort(0)
            .writeByte(1).readByteString())
        val thumbnail = segment(0xe0, Buffer().writeUtf8("JFXX\u0000").writeByte(16)
            .write(insert(exif(6), xmp)).readByteString())
        val result = cleaned(insert(icc, exif(1), adobe, thumbnail, comment))
        assertEquals(insert(icc, adobe), result.bytes)
        assertFalse(result.bytes.utf8().contains(privateText))
    }

    @Test fun walksEveryScanIncludingStuffedAndRestartBytesAndLateMetadata() = runTest {
        // Synthetic multi-scan container, not a decoder/valid-progressive-pixel proof.
        val bytes = base.toByteArray()
        val sof = base.indexOf("ffc0".decodeHex())
        bytes[sof + 1] = 0xc2.toByte()
        val progressive = bytes.toByteString()
        val sos = base.indexOf("ffda".decodeHex())
        val headerEnd = sos + 2 + ((base[sos + 2].toInt() and 255) shl 8) + (base[sos + 3].toInt() and 255)
        val second = Buffer().write(base, sos, headerEnd - sos).write("11ff0022ffd033".decodeHex()).readByteString()
        val withEarlyExif = insertInto(progressive, exif(1))
        val source = Buffer().write(withEarlyExif, 0, withEarlyExif.size - 2).write(comment).write(xmp)
            .write(second).write("ffd9".decodeHex()).readByteString()
        val expected = Buffer().write(progressive, 0, progressive.size - 2).write(second)
            .write("ffd9".decodeHex()).readByteString()
        assertEquals(expected, cleaned(source).bytes)
    }

    @Test fun everyTruncatedPrefixIsRejectedWithoutLeakingOrThrowingAnIndexError() = runTest {
        val source = insert(exif(8), xmp, comment)
        for (end in 0 until source.size) {
            val result = cleanJpegPhoto(source.substring(0, end))
            assertTrue("prefix length=$end", result is JpegPhotoCleaning.Failed)
            assertFalse(result.toString().contains(privateText))
        }
    }

    @Test fun malformedOrAmbiguousExifNeverInventsOrientation() = runTest {
        for (bad in listOf(exif(0), exif(9), exif(6, type = 4), exif(6, count = 2),
            exif(6, offset = -1), exif(6, offset = 7),
            segment(0xe1, "ExifBAD-private-location-fixture".encodeUtf8()),
            segment(0xe1, "45786966000049492a00080000000000".decodeHex()))) {
            assertTrue(cleanJpegPhoto(insert(bad)) is JpegPhotoCleaning.Failed)
        }
        assertFailed(JpegCleaningFailure.UNSUPPORTED_LAYOUT, insert(exif(6), exif(6)))
    }

    @Test fun unprovenXmpOrientationMultiPictureAndOpaqueTrailersRequireFailureConsent() = runTest {
        assertFailed(JpegCleaningFailure.UNSUPPORTED_LAYOUT, insert(xmp))
        assertFailed(JpegCleaningFailure.UNSUPPORTED_LAYOUT, insert(segment(0xe2, "MPF\u0000private".encodeUtf8())))
        assertFailed(JpegCleaningFailure.UNSUPPORTED_LAYOUT, Buffer().write(base).write(base).readByteString())
        assertFailed(JpegCleaningFailure.UNSUPPORTED_LAYOUT, Buffer().write(base).writeUtf8(privateText).readByteString())
        assertFailed(JpegCleaningFailure.UNSUPPORTED_LAYOUT, insert(segment(0xf0, privateText.encodeUtf8())))
    }

    @Test fun markerAndInputBudgetsAreEnforcedBeforeAnUnboundedRewrite() = runTest {
        val markerFlood = Buffer().write(base, 0, JFIF_END)
        repeat(8193) { markerFlood.write(segment(0xfe, ByteString.EMPTY)) }
        markerFlood.write(base, JFIF_END, base.size - JFIF_END)
        assertFailed(JpegCleaningFailure.UNSUPPORTED_LAYOUT, markerFlood.readByteString())
        val large = Buffer()
        val chunk = ByteArray(8192)
        repeat((MAX_ATTACHMENT_SNAPSHOT_BYTES / chunk.size + 1).toInt()) { large.write(chunk) }
        assertFailed(JpegCleaningFailure.CLIENT_BUDGET, large.snapshot())
    }

    @Test fun aCleanedJpegIsIdempotentAndMissingExifOrientationUsesTheNormalDefault() = runTest {
        assertEquals(base, cleaned(insert(exif(null), comment)).bytes)
        val first = cleaned(insert(exif(7), xmp, comment))
        val second = cleaned(first.bytes)
        assertEquals(first.bytes, second.bytes)
        assertEquals(7, second.exifOrientation)
        assertEquals(base, cleaned(Buffer().write(base).writeByte(0).writeByte(255).readByteString()).bytes)
    }

    @Test fun malformedColourMarkersAreNotSilentlyDroppedOrReinterpreted() = runTest {
        val bad = listOf(segment(0xe0, "JFIF\u0000".encodeUtf8()),
            segment(0xe2, "ICC_PROFILE\u0000".encodeUtf8()), segment(0xee, "Adobe-bad-private".encodeUtf8()))
        for (marker in bad) assertTrue(cleanJpegPhoto(insert(marker)) is JpegPhotoCleaning.Failed)
    }

    @Test fun exifOnlyNonSrgbColourCannotBeDiscardedAsIfOnlyOrientationMattered() = runTest {
        for (space in listOf(2, 65535))
            assertFailed(JpegCleaningFailure.UNSUPPORTED_LAYOUT, insert(colourExif(space)))
    }

    @Test fun anSrgbColourDirectoryMustBeValidatedBeforeItIsDiscarded() = runTest {
        assertEquals(base, cleaned(insert(colourExif(1))).bytes)
        assertTrue(cleanJpegPhoto(insert(colourExif(1, -1))) is JpegPhotoCleaning.Failed)
        assertTrue(cleanJpegPhoto(insert(colourExif(1, 8))) is JpegPhotoCleaning.Failed)
    }

    @Test fun calibrationAndNonSrgbInteroperabilityNeedAColourPreservingPath() = runTest {
        val whitePoint = Buffer().writeUtf8("Exif\u0000\u0000II").writeShortLe(42).writeIntLe(8).writeShortLe(2)
            .writeShortLe(0x0112).writeShortLe(3).writeIntLe(1).writeShortLe(1).writeShortLe(0)
            .writeShortLe(0x013e).writeShortLe(5).writeIntLe(2).writeIntLe(38).writeIntLe(0)
            .writeIntLe(3127).writeIntLe(10000).writeIntLe(3290).writeIntLe(10000).readByteString()
        assertFailed(JpegCleaningFailure.UNSUPPORTED_LAYOUT, insert(segment(0xe1, whitePoint)))
        assertFailed(JpegCleaningFailure.UNSUPPORTED_LAYOUT, insert(colourExif(1, gamma = true)))
        assertFailed(JpegCleaningFailure.UNSUPPORTED_LAYOUT, insert(colourExif(1, interop = "R03\u0000")))
        assertEquals(base, cleaned(insert(colourExif(1, interop = "R98\u0000"))).bytes)
    }

    private suspend fun cleaned(bytes: ByteString): JpegPhotoCleaning.Cleaned {
        val result = cleanJpegPhoto(bytes)
        assertTrue(result is JpegPhotoCleaning.Cleaned)
        return result as JpegPhotoCleaning.Cleaned
    }
    private suspend fun assertFailed(kind: JpegCleaningFailure, bytes: ByteString) {
        val result = cleanJpegPhoto(bytes)
        assertTrue(result is JpegPhotoCleaning.Failed)
        assertEquals(kind, (result as JpegPhotoCleaning.Failed).kind)
    }
    private fun insert(vararg metadata: ByteString) = insertInto(base, *metadata)
    private fun insertInto(jpeg: ByteString, vararg metadata: ByteString): ByteString {
        val buffer = Buffer().write(jpeg, 0, JFIF_END)
        metadata.forEach { buffer.write(it) }
        return buffer.write(jpeg, JFIF_END, jpeg.size - JFIF_END).readByteString()
    }
    private fun segment(marker: Int, payload: ByteString) = Buffer().writeByte(255).writeByte(marker)
        .writeShort(payload.size + 2).write(payload).readByteString()
    private fun minimal(orientation: Int) = ("ffe1002245786966000049492a000800000001001201030001000000" +
        orientation.toString(16).padStart(2, '0') + "00000000000000").decodeHex()

    private fun colourExif(space: Int, pointer: Int = 38, gamma: Boolean = false, interop: String? = null): ByteString {
        val fields = 1 + (if (gamma) 1 else 0) + (if (interop == null) 0 else 1)
        val extra = 38 + 2 + 12 * fields + 4
        val payload = Buffer().writeUtf8("Exif\u0000\u0000II").writeShortLe(42).writeIntLe(8).writeShortLe(2)
            .writeShortLe(0x0112).writeShortLe(3).writeIntLe(1).writeShortLe(1).writeShortLe(0)
            .writeShortLe(0x8769).writeShortLe(4).writeIntLe(1).writeIntLe(pointer).writeIntLe(0)
            .writeShortLe(fields).writeShortLe(0xa001).writeShortLe(3).writeIntLe(1).writeShortLe(space).writeShortLe(0)
        if (interop != null) payload.writeShortLe(0xa005).writeShortLe(4).writeIntLe(1).writeIntLe(extra)
        if (gamma) payload.writeShortLe(0xa500).writeShortLe(5).writeIntLe(1).writeIntLe(extra)
        payload.writeIntLe(0)
        if (interop != null) payload.writeShortLe(1).writeShortLe(1).writeShortLe(2).writeIntLe(4).writeUtf8(interop).writeIntLe(0)
        if (gamma) payload.writeIntLe(22).writeIntLe(10)
        return segment(0xe1, payload.readByteString())
    }

    private fun exif(orientation: Int?, little: Boolean = true, type: Int = 3, count: Int = 1, offset: Int = 8): ByteString {
        val buffer = Buffer().writeUtf8("Exif\u0000\u0000").writeUtf8(if (little) "II" else "MM")
        fun short(value: Int) { if (little) buffer.writeShortLe(value) else buffer.writeShort(value) }
        fun long(value: Int) { if (little) buffer.writeIntLe(value) else buffer.writeInt(value) }
        short(42); long(offset)
        val entries = if (orientation == null) 1 else 2
        val gps = 8 + 2 + 12 * entries + 4
        short(entries)
        if (orientation != null) { short(0x0112); short(type); long(count); short(orientation); short(0) }
        short(0x8825); short(4); long(1); long(gps)
        long(0)
        // GPS latitude reference and three rationals in a separate IFD, both byte orders.
        short(2)
        short(1); short(2); long(2); buffer.writeByte('N'.code).writeByte(0).writeShort(0)
        short(2); short(5); long(3); long(gps + 30)
        long(0)
        for (value in listOf(51, 30, 0)) { long(value); long(1) }
        buffer.writeUtf8(privateText)
        return segment(0xe1, buffer.readByteString())
    }

    companion object {
        private const val JFIF_END = 20
        // Own 8x4 RGB fixture, generated in memory with Windows GDI+ JPEG encoding.
        // RGB=(x*31,y*63,(x+y)*17); no external photo or on-disk fixture is used.
        private val base = "/9j/4AAQSkZJRgABAQEAYABgAAD/2wBDAAMCAgMCAgMDAwMEAwMEBQgFBQQEBQoHBwYIDAoMDAsKCwsNDhIQDQ4RDgsLEBYQERMUFRUVDA8XGBYUGBIUFRT/2wBDAQMEBAUEBQkFBQkUDQsNFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBT/wAARCAAEAAgDASIAAhEBAxEB/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUFBAQAAAF9AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVWV1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uHi4+Tl5ufo6erx8vP09fb3+Pn6/8QAHwEAAwEBAQEBAQEBAQAAAAAAAAECAwQFBgcICQoL/8QAtREAAgECBAQDBAcFBAQAAQJ3AAECAxEEBSExBhJBUQdhcRMiMoEIFEKRobHBCSMzUvAVYnLRChYkNOEl8RcYGRomJygpKjU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6goOEhYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6wsPExcbHyMnK0tPU1dbX2Nna4uPk5ebn6Onq8vP09fb3+Pn6/9oADAMBAAIRAxEAPwBnwZ/Zx8C/uP8AiVencf4UUUV4eCxNb2K99/ez3+Bczx/9jU/38/8AwKX+Z//Z".decodeBase64()!!
    }
}

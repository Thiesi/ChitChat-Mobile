package io.github.thiesi.chitchat.core.media

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.test.runTest
import okio.Buffer
import okio.ByteString
import okio.ByteString.Companion.decodeHex
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import org.junit.Assert.*
import org.junit.Test

class WebpPhotoPrivacyTest {
    private val privateText = "private-location-fixture"
    private val xmp get() = chunk("XMP ", privateText.encodeUtf8())
    // These JVM bitstreams test bounded container framing, NOT valid pixel decoding.
    // A separate Android test generates real codec output and decodes it before/after.
    private val lossless get() = chunk("VP8L", Buffer().writeByte(0x2f).writeIntLe(1 or (1 shl 14))
        .writeByte(0).readByteString())
    private val lossy get() = chunk("VP8 ", "1000009d012a0200020000".decodeHex())

    @Test fun simpleLossyAndLosslessBitstreamsRemainByteIdentical() = runTest {
        for (image in listOf(lossy, lossless)) {
            val source = riff(image)
            assertEquals(source, cleaned(source).bytes)
            assertEquals(1, cleaned(source).exifOrientation)
        }
        val colour = Buffer().writeUtf8("II").writeShortLe(42).writeIntLe(8).writeShortLe(1)
            .writeShortLe(0x8769).writeShortLe(4).writeIntLe(1).writeIntLe(26).writeIntLe(0)
            .writeShortLe(1).writeShortLe(0xa001).writeShortLe(3).writeIntLe(1)
            .writeShortLe(65535).writeShortLe(0).writeIntLe(0).readByteString()
        val withColour = riff(extended(8), lossless, chunk("EXIF", colour))
        assertEquals(withColour, cleaned(withColour).bytes)
    }

    @Test fun bothEndianAndExifFramingsRetainAllOrientationsWithoutPrivateMetadata() = runTest {
        for (little in listOf(true, false)) for (prefixed in listOf(true, false)) for (orientation in 1..8) {
            val exif = exif(orientation, little, prefixed)
            val source = riff(extended(12), lossless, exif, xmp)
            val result = cleaned(source)
            val expectedFlags = if (orientation == 1) 0 else 8
            assertEquals(orientation, result.exifOrientation)
            assertEquals(expectedFlags, chunks(result.bytes).getValue("VP8X")[0].toInt())
            assertEquals(chunks(source).getValue("VP8L"), chunks(result.bytes).getValue("VP8L"))
            assertFalse(result.bytes.utf8().contains(privateText))
            if (orientation == 1) assertFalse(chunks(result.bytes).containsKey("EXIF"))
            else {
                val data = chunks(result.bytes).getValue("EXIF")
                val offset = if (prefixed) 6 else 0
                if (prefixed) assertEquals("Exif\u0000\u0000".encodeUtf8(), data.substring(0, 6))
                val tiff = ByteBuffer.wrap(data.substring(offset).toByteArray()).order(ByteOrder.LITTLE_ENDIAN)
                assertEquals(26, tiff.capacity())
                assertEquals(8, tiff.getInt(4)); assertEquals(1, tiff.getShort(8).toInt())
                assertEquals(0x0112, tiff.getShort(10).toInt()); assertEquals(orientation, tiff.getShort(18).toInt())
                assertEquals(0, tiff.getInt(22))
            }
            assertEquals(result.bytes, cleaned(result.bytes).bytes)
        }
    }

    @Test fun iccExternalAlphaAndMetadataInterleavingPreserveReconstructionOrderAndBytes() = runTest {
        val icc = chunk("ICCP", "opaque-profile-fixture".encodeUtf8())
        val alpha = chunk("ALPH", "0000ff8040".decodeHex())
        val source = riff(extended(0x20 or 0x10 or 12), exif(6), icc, xmp, alpha, lossy)
        val result = cleaned(source)
        val values = chunks(result.bytes)
        assertEquals(listOf("VP8X", "EXIF", "ICCP", "ALPH", "VP8 "), values.keys.toList())
        assertEquals(0x20 or 0x10 or 8, values.getValue("VP8X")[0].toInt())
        for (type in listOf("ICCP", "ALPH", "VP8 ")) assertEquals(chunks(source).getValue(type), values.getValue(type))
    }

    @Test fun unparsedXmpRequiresAnExplicitOrientationAndCannotCreateOne() = runTest {
        failed(WebpCleaningFailure.UNSUPPORTED_LAYOUT, riff(extended(4), lossless, xmp))
        val noOrientation = chunk("EXIF", "49492a0008000000000000000000".decodeHex())
        failed(WebpCleaningFailure.UNSUPPORTED_LAYOUT, riff(extended(12), noOrientation, lossless, xmp))
        assertEquals(1, cleaned(riff(extended(12), exif(1), lossless, xmp)).exifOrientation)
    }

    @Test fun flagsMustDescribeOriginalMetadataAndAreNeverRepairedIntoAnotherImage() = runTest {
        for (source in listOf(riff(extended(8), lossless), riff(extended(4), lossless),
            riff(extended(0x20), lossless), riff(extended(0), lossless, exif(6)),
            riff(extended(8), lossless, exif(6), xmp), riff(extended(0), chunk("ICCP", "icc".encodeUtf8()), lossless),
            riff(extended(0x10), lossy), riff(extended(0), chunk("ALPH", "0001020304".decodeHex()), lossy)))
            failed(WebpCleaningFailure.INVALID_WEBP, source)
    }

    @Test fun duplicateImagesExifColourAndAlphaAreRejected() = runTest {
        val icc = chunk("ICCP", "icc".encodeUtf8())
        val alpha = chunk("ALPH", "0001020304".decodeHex())
        for (source in listOf(riff(extended(0), lossless, lossless), riff(extended(0), extended(0), lossless), riff(extended(0), lossy, lossless),
            riff(extended(8), exif(6), lossless, exif(6)), riff(extended(0x20), icc, icc, lossless),
            riff(extended(0x10), alpha, alpha, lossy), riff(extended(0x10), alpha, lossless),
            riff(extended(4), xmp, xmp, lossless))) failed(WebpCleaningFailure.INVALID_WEBP, source)
    }

    @Test fun malformedLengthsPaddingCanvasAndHeaderFactsFailWithoutIndexErrors() = runTest {
        val source = riff(extended(0), lossless)
        val wrongSize = source.toByteArray().also { it[4] = 0 }.toByteString()
        val hugeChunk = source.toByteArray().also { for (i in 16..19) it[i] = -1 }.toByteString()
        val odd = chunk("VP8 ", "1000009d012a0200020000".decodeHex()).toByteArray().also { it[it.lastIndex] = 7 }.toByteString()
        for (bad in listOf(wrongSize, hugeChunk, riff(odd), riff(extended(0, 3), lossless),
            riff(extended(0), chunk("VP8L", "2f0000".decodeHex())),
            riff(extended(0), chunk("VP8 ", "10000000000002000200".decodeHex())),
            riff(extended(0x10), chunk("ALPH", "0001".decodeHex()), lossy),
            riff(extended(0), lossless, chunk("ICCP", "icc".encodeUtf8()))))
            failed(WebpCleaningFailure.INVALID_WEBP, bad)
    }

    @Test fun everyTruncatedPrefixFailsWithoutLeakingPrivateMetadata() = runTest {
        val source = riff(extended(12), lossless, exif(8, false, true), xmp)
        for (end in 0 until source.size) {
            val result = cleanWebpPhoto(source.substring(0, end))
            assertTrue("prefix=$end", result is WebpPhotoCleaning.Failed)
            assertFalse(result.toString().contains(privateText))
        }
    }

    @Test fun animationUnknownExtensionsReservedLayoutsAndOversizedExifStayUnsupported() = runTest {
        val reserved = extended(1)
        for (source in listOf(riff(extended(2), lossless), riff(reserved, lossless),
            riff(extended(0), chunk("ANIM", ByteString.EMPTY), lossless),
            riff(extended(0), chunk("ANMF", privateText.encodeUtf8()), lossless),
            riff(extended(0), lossless, chunk("JUNK", privateText.encodeUtf8())),
            riff(extended(8), lossless, chunk("EXIF", ByteArray(MAX_PHOTO_EXIF_BYTES + 1).toByteString())),
            riff(lossless, exif(6)))) failed(WebpCleaningFailure.UNSUPPORTED_LAYOUT, source)
    }

    @Test fun memoryBudgetPrecedesParsingUntrustedLargeInput() = runTest {
        failed(WebpCleaningFailure.CLIENT_BUDGET, ByteArray(MAX_ATTACHMENT_SNAPSHOT_BYTES.toInt() + 1).toByteString())
    }

    private suspend fun cleaned(source: ByteString): WebpPhotoCleaning.Cleaned {
        val result = cleanWebpPhoto(source)
        assertTrue(result is WebpPhotoCleaning.Cleaned)
        return result as WebpPhotoCleaning.Cleaned
    }
    private suspend fun failed(kind: WebpCleaningFailure, source: ByteString) {
        val result = cleanWebpPhoto(source)
        assertTrue(result is WebpPhotoCleaning.Failed)
        assertEquals(kind, (result as WebpPhotoCleaning.Failed).kind)
    }
    private fun riff(vararg chunks: ByteString): ByteString {
        val data = Buffer().writeUtf8("WEBP").apply { chunks.forEach { write(it) } }.readByteString()
        return Buffer().writeUtf8("RIFF").writeIntLe(data.size).write(data).readByteString()
    }
    private fun chunk(type: String, bytes: ByteString): ByteString = Buffer().writeUtf8(type).writeIntLe(bytes.size)
        .write(bytes).apply { if (bytes.size % 2 != 0) writeByte(0) }.readByteString()
    private fun extended(flags: Int, width: Int = 2) = chunk("VP8X", Buffer().writeByte(flags).writeByte(0).writeShortLe(0)
        .writeByte(width - 1).writeShortLe(0).writeByte(1).writeShortLe(0).readByteString())
    private fun exif(orientation: Int, little: Boolean = true, prefixed: Boolean = false): ByteString {
        val b = Buffer()
        if (prefixed) b.writeUtf8("Exif\u0000\u0000")
        b.writeUtf8(if (little) "II" else "MM")
        fun short(value: Int) { if (little) b.writeShortLe(value) else b.writeShort(value) }
        fun long(value: Int) { if (little) b.writeIntLe(value) else b.writeInt(value) }
        short(42); long(8); short(2)
        short(0x0112); short(3); long(1); short(orientation); short(0)
        short(0x8825); short(4); long(1); long(38); long(0)
        short(2); short(1); short(2); long(2); b.writeByte('N'.code).writeByte(0).writeShort(0)
        short(2); short(5); long(3); long(68); long(0)
        for (value in listOf(51, 30, 0)) { long(value); long(1) }
        b.writeUtf8(privateText)
        return chunk("EXIF", b.readByteString())
    }
    private fun chunks(bytes: ByteString): Map<String, ByteString> {
        assertEquals(bytes.size - 8, ByteBuffer.wrap(bytes.toByteArray()).order(ByteOrder.LITTLE_ENDIAN).getInt(4))
        val result = LinkedHashMap<String, ByteString>()
        var at = 12
        while (at < bytes.size) {
            val length = ByteBuffer.wrap(bytes.toByteArray()).order(ByteOrder.LITTLE_ENDIAN).getInt(at + 4)
            result[bytes.substring(at, at + 4).utf8()] = bytes.substring(at + 8, at + 8 + length)
            at += 8 + length + length % 2
        }
        assertEquals(bytes.size, at)
        return result
    }
}

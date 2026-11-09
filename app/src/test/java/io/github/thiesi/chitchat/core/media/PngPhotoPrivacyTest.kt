package io.github.thiesi.chitchat.core.media

import java.awt.color.ColorSpace
import java.awt.color.ICC_Profile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import javax.imageio.ImageIO
import kotlinx.coroutines.test.runTest
import okio.Buffer
import okio.ByteString
import okio.ByteString.Companion.decodeHex
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import org.junit.Assert.*
import org.junit.Test

class PngPhotoPrivacyTest {
    private val privateText = "private-location-fixture"
    private val signature = "89504e470d0a1a0a".decodeHex()
    private val rgb = image(2, byteArrayOf(0, -1, 0, 0, 0, 0, -1))
    private val rgba = image(6, byteArrayOf(0, -1, 0, 0, -1, 0, 0, -1, 0))
    private val text get() = chunk("tEXt", ("Location\u0000" + privateText).encodeUtf8())

    @Test fun removesAllTextAndTimeWithoutChangingCodedOrDecodedPixels() = runTest {
        val compressed = chunk("zTXt", Buffer().writeUtf8("Comment\u0000").writeByte(0)
            .write(deflate(privateText.encodeToByteArray())).readByteString())
        val international = chunk("iTXt", ("Description\u0000\u0000\u0000\u0000\u0000" + privateText).encodeUtf8())
        val time = chunk("tIME", "07e90b090c0000".decodeHex())
        for (base in listOf(rgb, rgba)) {
            val source = insert(base, text, compressed, international, time)
            val result = cleaned(source)
            assertEquals(base, result.bytes)
            assertEquals(1, result.exifOrientation)
            assertFalse(result.bytes.utf8().contains(privateText))
            assertFalse(result.toString().contains(privateText))
            samePixels(source, result.bytes)
        }
    }

    @Test fun bothEndianExifKeepsAllOrientationsAndColourWhileRemovingGpsAndMakerNotes() = runTest {
        for (little in listOf(true, false)) for (orientation in 1..8) {
            val source = insert(rgb, chunk("eXIf", exif(orientation, little)), text)
            val result = cleaned(source)
            assertEquals(orientation, result.exifOrientation)
            assertEquals(chunks(rgb).getValue("IDAT"), chunks(result.bytes).getValue("IDAT"))
            assertFalse(result.bytes.utf8().contains(privateText))
            val tiff = ByteBuffer.wrap(chunks(result.bytes).getValue("eXIf").toByteArray()).order(ByteOrder.LITTLE_ENDIAN)
            fun directory(at: Int): Map<Int, Int> {
                val count = tiff.getShort(at).toInt() and 65535
                val result = (0 until count).associate { i ->
                    val entry = at + 2 + 12 * i
                    (tiff.getShort(entry).toInt() and 65535) to tiff.getInt(entry + 8)
                }
                assertEquals(count, result.size)
                assertEquals(0, tiff.getInt(at + 2 + 12 * count))
                return result
            }
            val root = directory(tiff.getInt(4))
            assertEquals(if (orientation == 1) setOf(0x8769) else setOf(0x0112, 0x8769), root.keys)
            if (orientation != 1) assertEquals(orientation, root[0x0112])
            assertEquals(mapOf(0xa001 to 65535), directory(root.getValue(0x8769)))
            assertEquals(result.bytes, cleaned(result.bytes).bytes)
        }
    }

    @Test fun indexedPaletteTransparencyBackgroundAndPixelAspectSurvive() = runTest {
        val base = image(3, byteArrayOf(0, 0, 1),
            chunk("sBIT", "080808".decodeHex()), chunk("PLTE", "ff00000000ff".decodeHex()),
            chunk("tRNS", "ff00".decodeHex()), chunk("bKGD", "00".decodeHex()),
            chunk("pHYs", Buffer().writeInt(2).writeInt(1).writeByte(0).readByteString()))
        val source = insert(base, text)
        val result = cleaned(source)
        assertEquals(base, result.bytes)
        samePixels(source, result.bytes)
        val decoded = ImageIO.read(ByteArrayInputStream(result.bytes.toByteArray()))
        assertEquals(255, decoded.getRGB(0, 0) ushr 24)
        assertEquals(0, decoded.getRGB(1, 0) ushr 24)
    }

    @Test fun iccAndAllRecognizedColourAndHdrChunksAreRetainedInOrder() = runTest {
        val profile = ICC_Profile.getInstance(ColorSpace.CS_sRGB).data
        val icc = chunk("iCCP", Buffer().writeUtf8("fixture\u0000").writeByte(0).write(deflate(profile)).readByteString())
        val gamma = chunk("gAMA", Buffer().writeInt(45455).readByteString())
        val chromaticity = chunk("cHRM", Buffer().apply { repeat(8) { writeInt(10000 + it) } }.readByteString())
        val hdr = arrayOf(chunk("cICP", "09100001".decodeHex()),
            chunk("mDCV", Buffer().apply { repeat(8) { writeShort(10000 + it) } }
                .writeInt(10000000).writeInt(1000).readByteString()),
            chunk("cLLI", Buffer().writeInt(10000000).writeInt(4000000).readByteString()))
        val expected = insert(rgb, icc, gamma, chromaticity, chunk("sBIT", "080808".decodeHex()), *hdr)
        // HDR fields exercise exact container retention, not a calibrated HDR decoder claim.
        assertEquals(expected, cleaned(insert(expected, text)).bytes)
        for (intent in 0..3) {
            val srgb = insert(rgb, chunk("sRGB", byteArrayOf(intent.toByte()).toByteString()), gamma)
            assertEquals(srgb, cleaned(insert(srgb, text)).bytes)
        }
    }

    @Test fun unparsedXmpOrRawProfileOrientationRequiresAnExplicitExifValue() = runTest {
        for (keyword in listOf("XML:com.adobe.xmp", "Raw profile type exif", "Raw profile type xmp")) {
            val metadata = chunk("iTXt", (keyword + "\u0000\u0000\u0000\u0000\u0000" + privateText).encodeUtf8())
            failed(PngCleaningFailure.UNSUPPORTED_LAYOUT, insert(rgb, metadata))
            failed(PngCleaningFailure.UNSUPPORTED_LAYOUT,
                insert(rgb, metadata, chunk("eXIf", "49492a0008000000000000000000".decodeHex())))
            val source = insert(rgb, metadata, chunk("eXIf", exif(1, true)))
            assertEquals(1, cleaned(source).exifOrientation)
        }
    }

    @Test fun corruptLengthsCrcHeadersOrderAndDuplicateFactsFailSafely() = runTest {
        val damagedCrc = rgb.toByteArray().also { it[29] = (it[29].toInt() xor 1).toByte() }.toByteString()
        val hugeLength = rgb.toByteArray().also { for (i in 8..11) it[i] = -1 }.toByteString()
        val header = chunks(rgb).getValue("IHDR")
        val badHeader = header.toByteArray().also { it[8] = 1 }.toByteString()
        val idat = chunk("IDAT", chunks(rgb).getValue("IDAT"))
        val ihdr = chunk("IHDR", header)
        val end = chunk("IEND", ByteString.EMPTY)
        val cases = listOf(damagedCrc, hugeLength, png(text, ihdr, idat, end),
            png(ihdr, ihdr, idat, end), png(chunk("IHDR", badHeader), idat, end),
            png(ihdr, idat, text, idat, end), png(ihdr, end),
            png(ihdr, idat, chunk("PLTE", "ff00000000ff".decodeHex()), end),
            png(ihdr, chunk("IDAT", ByteString.EMPTY), end),
            image(3, byteArrayOf(0, 0, 1)),
            image(3, byteArrayOf(0, 0, 1), chunk("PLTE", "ff00000000ff".decodeHex()), chunk("tRNS", "ff0000".decodeHex())),
            insert(rgb, chunk("eXIf", exif(1, true)), chunk("eXIf", exif(1, false))),
            insert(rgb, chunk("gAMA", ByteString.EMPTY)),
            insert(rgb, chunk("sRGB", "00".decodeHex()), chunk("iCCP", "x\u0000\u0000z".encodeUtf8())),
            insert(rgb, chunk("mDCV", ByteArray(24).toByteString())),
            insert(rgba, chunk("tRNS", "000000000000".decodeHex())),
            insert(rgb, chunk("pHYs", Buffer().writeInt(1).writeInt(1).writeByte(2).readByteString())))
        for (source in cases) failed(PngCleaningFailure.INVALID_PNG, source)
    }

    @Test fun everyTruncatedPrefixFailsWithoutPrivateDataOrIndexExceptions() = runTest {
        val source = insert(rgb, text, chunk("eXIf", exif(8, false)))
        for (end in 0 until source.size) {
            val result = cleanPngPhoto(source.substring(0, end))
            assertTrue("prefix=$end", result is PngPhotoCleaning.Failed)
            assertFalse(result.toString().contains(privateText))
        }
    }

    @Test fun animationUnknownExtensionsOpaqueTrailersAndOversizedExifAreNotSilentlyDropped() = runTest {
        val animation = chunk("acTL", Buffer().writeInt(1).writeInt(0).readByteString())
        for (metadata in listOf(animation, chunk("ABCD", privateText.encodeUtf8()),
            chunk("abCd", privateText.encodeUtf8()), chunk("eXIf", ByteArray(MAX_PHOTO_EXIF_BYTES + 1).toByteString())))
            failed(PngCleaningFailure.UNSUPPORTED_LAYOUT, insert(rgb, metadata))
        failed(PngCleaningFailure.UNSUPPORTED_LAYOUT, Buffer().write(rgb).writeByte(0).readByteString())
    }

    @Test fun chunkAndClientBudgetsPreventUnboundedPreparation() = runTest {
        val many = Buffer().write(signature).write(chunk("IHDR", chunks(rgb).getValue("IHDR")))
        repeat(8193) { many.write(chunk("tEXt", "A\u0000".encodeUtf8())) }
        many.write(chunk("IDAT", chunks(rgb).getValue("IDAT"))).write(chunk("IEND", ByteString.EMPTY))
        failed(PngCleaningFailure.UNSUPPORTED_LAYOUT, many.readByteString())
        failed(PngCleaningFailure.CLIENT_BUDGET, ByteArray(MAX_ATTACHMENT_SNAPSHOT_BYTES.toInt() + 1).toByteString())
    }

    private suspend fun cleaned(input: ByteString): PngPhotoCleaning.Cleaned {
        val result = cleanPngPhoto(input)
        assertTrue(result is PngPhotoCleaning.Cleaned)
        return result as PngPhotoCleaning.Cleaned
    }
    private suspend fun failed(kind: PngCleaningFailure, input: ByteString) {
        val result = cleanPngPhoto(input)
        assertTrue(result is PngPhotoCleaning.Failed)
        assertEquals(kind, (result as PngPhotoCleaning.Failed).kind)
    }
    private fun samePixels(before: ByteString, after: ByteString) {
        val first = ImageIO.read(ByteArrayInputStream(before.toByteArray()))
        val second = ImageIO.read(ByteArrayInputStream(after.toByteArray()))
        assertEquals(2, first.width); assertEquals(1, first.height)
        assertEquals(first.width, second.width); assertEquals(first.height, second.height)
        assertArrayEquals(first.getRGB(0, 0, 2, 1, null, 0, 2), second.getRGB(0, 0, 2, 1, null, 0, 2))
    }
    private fun deflate(bytes: ByteArray): ByteString {
        val output = ByteArrayOutputStream()
        DeflaterOutputStream(output).use { it.write(bytes) }
        return output.toByteArray().toByteString()
    }
    private fun image(type: Int, scanline: ByteArray, vararg metadata: ByteString): ByteString = png(
        chunk("IHDR", Buffer().writeInt(2).writeInt(1).writeByte(8).writeByte(type)
            .writeByte(0).writeByte(0).writeByte(0).readByteString()), *metadata,
        chunk("IDAT", deflate(scanline)), chunk("IEND", ByteString.EMPTY))
    private fun png(vararg values: ByteString): ByteString = Buffer().write(signature)
        .apply { values.forEach { write(it) } }.readByteString()
    private fun insert(png: ByteString, vararg metadata: ByteString): ByteString = Buffer().write(png, 0, 33)
        .apply { metadata.forEach { write(it) } }.write(png, 33, png.size - 33).readByteString()
    private fun chunk(name: String, bytes: ByteString): ByteString {
        val crc = CRC32().also { it.update(name.encodeToByteArray()); it.update(bytes.toByteArray()) }.value
        return Buffer().writeInt(bytes.size).writeUtf8(name).write(bytes).writeInt(crc.toInt()).readByteString()
    }
    private fun chunks(png: ByteString): Map<String, ByteString> {
        var position = 8
        val values = LinkedHashMap<String, ByteString>()
        while (position < png.size) {
            val buffer = ByteBuffer.wrap(png.toByteArray()).order(ByteOrder.BIG_ENDIAN)
            val length = buffer.getInt(position)
            val name = png.substring(position + 4, position + 8).utf8()
            val bytes = png.substring(position + 8, position + 8 + length)
            val crc = CRC32().also { it.update(name.encodeToByteArray()); it.update(bytes.toByteArray()) }.value
            assertEquals(crc.toInt(), buffer.getInt(position + 8 + length))
            values[name] = bytes
            position += length + 12
        }
        return values
    }
    private fun exif(orientation: Int, little: Boolean): ByteString {
        val b = ByteBuffer.allocate(256).order(if (little) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN)
        b.put(if (little) "II".encodeToByteArray() else "MM".encodeToByteArray()).putShort(42).putInt(8).putShort(3)
        fun entry(tag: Int, type: Int, count: Int, value: Int) {
            b.putShort(tag.toShort()).putShort(type.toShort()).putInt(count)
            if (type == 3) b.putShort(value.toShort()).putShort(0) else b.putInt(value)
        }
        entry(0x0112, 3, 1, orientation); entry(0x8769, 4, 1, 50); entry(0x8825, 4, 1, 80); b.putInt(0)
        b.putShort(2); entry(0xa001, 3, 1, 65535); entry(0x927c, 7, privateText.length, 134); b.putInt(0)
        b.putShort(2); entry(1, 2, 2, if (little) 'N'.code else 'N'.code shl 24)
        entry(2, 5, 3, 110); b.putInt(0)
        for (value in listOf(51, 30, 0)) b.putInt(value).putInt(1)
        b.put(privateText.encodeToByteArray())
        return b.array().copyOf(b.position()).toByteString()
    }
}

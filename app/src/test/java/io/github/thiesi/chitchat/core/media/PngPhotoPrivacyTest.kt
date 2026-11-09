package io.github.thiesi.chitchat.core.media

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream
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

    @Test fun removesAllTextAndTimeWithoutChangingCodedOrInflatedPixels() = runTest {
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
        assertArrayEquals(intArrayOf(0xffff0000.toInt(), 0x000000ff), fixturePixels(result.bytes))
    }

    @Test fun iccAndAllRecognizedColourAndHdrChunksAreRetainedInOrder() = runTest {
        // Opaque profile bytes prove exact retention, not profile validity/decoder acceptance.
        val profile = "opaque-icc-profile-fixture".encodeToByteArray()
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

    @Test fun textualProfilesNeverLoseUnprovenOrientationOrColourInformation() = runTest {
        // APP1 is the original review regression; generic/ICC/Photoshop profiles can
        // contain further rendering facts that a separate orientation alone cannot prove.
        for (keyword in listOf("Raw profile type APP1", "Raw profile type generic", "Raw profile type exif",
            "Raw profile type xmp", "Raw profile type icc", "Raw profile type icm", "Raw profile type 8bim",
            "rAW pROFILE tYPE app1")) for (type in listOf("tEXt", "zTXt", "iTXt")) {
            val payload = Buffer().writeUtf8(keyword).writeByte(0)
            when (type) {
                "tEXt" -> payload.writeUtf8(privateText)
                "zTXt" -> payload.writeByte(0).write(deflate(privateText.encodeToByteArray()))
                else -> payload.writeByte(0).writeByte(0).writeByte(0).writeByte(0).writeUtf8(privateText)
            }
            val metadata = chunk(type, payload.readByteString())
            failed(PngCleaningFailure.UNSUPPORTED_LAYOUT, insert(rgb, metadata))
            failed(PngCleaningFailure.UNSUPPORTED_LAYOUT, insert(rgb, metadata, chunk("eXIf", exif(1, true))))
        }
        val xmp = chunk("iTXt", ("XML:com.adobe.xmp\u0000\u0000\u0000\u0000\u0000" + privateText).encodeUtf8())
        failed(PngCleaningFailure.UNSUPPORTED_LAYOUT, insert(rgb, xmp))
        failed(PngCleaningFailure.UNSUPPORTED_LAYOUT,
            insert(rgb, xmp, chunk("eXIf", "49492a0008000000000000000000".decodeHex())))
        assertEquals(1, cleaned(insert(rgb, xmp, chunk("eXIf", exif(1, true)))).exifOrientation)
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
        assertArrayEquals(fixturePixels(before), fixturePixels(after))
    }
    private fun fixturePixels(png: ByteString): IntArray {
        // Independent inflater for these own 2x1/filter-zero/8-bit fixtures only.
        // It is not a general PNG decoder or production code.
        val values = chunks(png)
        val header = ByteBuffer.wrap(values.getValue("IHDR").toByteArray())
        assertEquals(2, header.getInt(0)); assertEquals(1, header.getInt(4))
        assertEquals(8, header.get(8).toInt()); assertEquals(0, header.get(12).toInt())
        val type = header.get(9).toInt()
        val channels = when (type) { 2 -> 3; 6 -> 4; 3 -> 1; else -> error("Unexpected fixture") }
        val row = InflaterInputStream(ByteArrayInputStream(values.getValue("IDAT").toByteArray())).use { it.readBytes() }
        assertEquals(1 + 2 * channels, row.size); assertEquals(0, row[0].toInt())
        fun byte(at: Int) = row[at].toInt() and 255
        return IntArray(2) { pixel ->
            val at = 1 + pixel * channels
            if (type == 3) {
                val palette = values.getValue("PLTE")
                val index = byte(at)
                val alpha = values["tRNS"]?.takeIf { index < it.size }?.get(index)?.toInt()?.and(255) ?: 255
                (alpha shl 24) or ((palette[index * 3].toInt() and 255) shl 16) or
                    ((palette[index * 3 + 1].toInt() and 255) shl 8) or (palette[index * 3 + 2].toInt() and 255)
            } else {
                val alpha = if (type == 6) byte(at + 3) else 255
                (alpha shl 24) or (byte(at) shl 16) or (byte(at + 1) shl 8) or byte(at + 2)
            }
        }
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

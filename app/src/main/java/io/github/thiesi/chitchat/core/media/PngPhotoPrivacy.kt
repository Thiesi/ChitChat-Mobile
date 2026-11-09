package io.github.thiesi.chitchat.core.media

import java.util.zip.CRC32
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okio.Buffer
import okio.ByteString
import okio.ByteString.Companion.decodeHex
import okio.ByteString.Companion.encodeUtf8

/** Static PNG container privacy only; never inflates/re-encodes pixels, text or profiles. */
internal suspend fun cleanPngPhoto(input: ByteString): PngPhotoCleaning = withContext(Dispatchers.Default) {
    try {
        if (input.size.toLong() > MAX_ATTACHMENT_SNAPSHOT_BYTES) rejectPng(PngCleaningFailure.CLIENT_BUDGET)
        PngRewrite(input).clean()
    } catch (failure: PngRejected) {
        PngPhotoCleaning.Failed(failure.kind)
    }
}

internal sealed interface PngPhotoCleaning {
    class Cleaned(val bytes: ByteString, val exifOrientation: Int) : PngPhotoCleaning
    class Failed(val kind: PngCleaningFailure) : PngPhotoCleaning
}
internal enum class PngCleaningFailure { INVALID_PNG, UNSUPPORTED_LAYOUT, CLIENT_BUDGET }
private class PngRejected(val kind: PngCleaningFailure) : Exception()
private fun rejectPng(kind: PngCleaningFailure = PngCleaningFailure.INVALID_PNG): Nothing = throw PngRejected(kind)

private class PngRewrite(private val input: ByteString) {
    private var position = 8
    private var chunks = 0
    private val seen = HashSet<String>()
    private val output = Buffer()
    private var colourType = -1
    private var depth = 0
    private var paletteEntries = 0
    private var dataStarted = false
    private var dataClosed = false
    private var dataBytes = 0L
    private var orientation: Int? = null
    private var unparsedOrientation = false

    suspend fun clean(): PngPhotoCleaning.Cleaned {
        if (input.size < 8 || !input.rangeEquals(0, SIGNATURE, 0, 8)) rejectPng()
        output.write(SIGNATURE)
        while (position <= input.size - 12) {
            coroutineContext.ensureActive()
            if (++chunks > 8192) rejectPng(PngCleaningFailure.UNSUPPORTED_LAYOUT)
            val start = position
            val size = u32(start)
            if (size > input.size - start - 12L) rejectPng()
            val length = size.toInt()
            val payload = start + 8
            val end = payload + length
            for (at in start + 4 until start + 8)
                if (u8(at) !in 65..90 && u8(at) !in 97..122) rejectPng()
            val type = input.substring(start + 4, start + 8).utf8() // Four format bytes, never body/keyword text.
            if (chunks == 1 && type != "IHDR") rejectPng()
            if (crc(start + 4, end) != u32(end)) rejectPng()
            if (dataStarted && type != "IDAT") dataClosed = true
            var replacement: ByteString? = null
            var retain = true
            when (type) {
                "IHDR" -> {
                    unique(type)
                    if (chunks != 1 || length != 13 || u32(payload) !in 1..0x7fffffffL ||
                        u32(payload + 4) !in 1..0x7fffffffL) rejectPng()
                    depth = u8(payload + 8); colourType = u8(payload + 9)
                    val depths = when (colourType) {
                        0 -> setOf(1, 2, 4, 8, 16)
                        2, 4, 6 -> setOf(8, 16)
                        3 -> setOf(1, 2, 4, 8)
                        else -> rejectPng()
                    }
                    if (depth !in depths || u8(payload + 10) != 0 || u8(payload + 11) != 0 || u8(payload + 12) !in 0..1)
                        rejectPng()
                }
                "PLTE" -> {
                    unique(type)
                    if (dataStarted || colourType in setOf(0, 4) || "tRNS" in seen || "bKGD" in seen ||
                        length !in 3..768 || length % 3 != 0) rejectPng()
                    paletteEntries = length / 3
                    if (colourType == 3 && paletteEntries > (1 shl depth)) rejectPng()
                }
                "IDAT" -> {
                    if (dataClosed || (colourType == 3 && paletteEntries == 0)) rejectPng()
                    dataStarted = true; dataBytes += length
                }
                "IEND" -> {
                    if (length != 0 || !dataStarted || dataBytes == 0L) rejectPng()
                    if (end + 4 != input.size) rejectPng(PngCleaningFailure.UNSUPPORTED_LAYOUT)
                    if ("mDCV" in seen && "cICP" !in seen) rejectPng()
                    if (unparsedOrientation && orientation == null) rejectPng(PngCleaningFailure.UNSUPPORTED_LAYOUT)
                    output.write(input, start, end + 4 - start)
                    return PngPhotoCleaning.Cleaned(output.snapshot(), orientation ?: 1)
                }
                "eXIf" -> {
                    unique(type)
                    if (length > MAX_PHOTO_EXIF_BYTES) rejectPng(PngCleaningFailure.UNSUPPORTED_LAYOUT)
                    val result = when (val exif = cleanPhotoExif(input.substring(payload, end))) {
                        is PhotoExifCleaning.Cleaned -> exif
                        is PhotoExifCleaning.Failed -> rejectPng(if (exif.kind == PhotoExifFailure.INVALID)
                            PngCleaningFailure.INVALID_PNG else PngCleaningFailure.UNSUPPORTED_LAYOUT)
                    }
                    orientation = result.orientation
                    retain = false
                    result.tiff?.let { replacement = exifChunk(it) }
                }
                "tEXt", "zTXt", "iTXt" -> {
                    // Strip every text chunk, not only a GPS-named property, without decompression.
                    var separator = payload
                    while (separator < end && separator - payload < 80 && u8(separator) != 0) separator++
                    if (separator == payload || separator == end || separator - payload > 79) rejectPng()
                    val keyword = input.substring(payload, separator)
                    if (keyword in ORIENTATION_KEYWORDS) unparsedOrientation = true
                    retain = false
                }
                "tIME", "hIST", "sPLT" -> retain = false
                in COLOUR_CHUNKS -> {
                    unique(type)
                    if (dataStarted || paletteEntries != 0) rejectPng()
                    when (type) {
                        "gAMA" -> if (length != 4 || u32(payload) == 0L) rejectPng()
                        "cHRM" -> if (length != 32) rejectPng()
                        "sRGB" -> if (length != 1 || u8(payload) !in 0..3 || "iCCP" in seen) rejectPng()
                        "iCCP" -> {
                            if ("sRGB" in seen) rejectPng()
                            var separator = payload
                            while (separator < end && separator - payload < 80 && u8(separator) != 0) separator++
                            if (separator == payload || separator - payload > 79 || separator > end - 3 || u8(separator + 1) != 0)
                                rejectPng()
                        }
                        "sBIT" -> {
                            val count = when (colourType) { 0 -> 1; 2, 3 -> 3; 4 -> 2; else -> 4 }
                            if (length != count) rejectPng()
                            for (at in payload until end) if (u8(at) !in 1..(if (colourType == 3) 8 else depth)) rejectPng()
                        }
                        "cICP" -> if (length != 4 || u8(payload + 2) != 0 || u8(payload + 3) !in 0..1) rejectPng()
                        "mDCV" -> if (length != 24) rejectPng()
                        "cLLI" -> if (length != 8) rejectPng()
                    }
                }
                "tRNS", "bKGD", "pHYs" -> {
                    unique(type)
                    if (dataStarted) rejectPng()
                    val expected = when (type) {
                        "pHYs" -> 9
                        else -> when (colourType) {
                            0 -> 2
                            2 -> 6
                            3 -> if (paletteEntries == 0) rejectPng() else if (type == "bKGD") 1 else length
                            4 -> if (type == "bKGD") 2 else rejectPng()
                            else -> if (type == "bKGD") 6 else rejectPng()
                        }
                    }
                    if (length != expected) rejectPng()
                    if (type == "tRNS" && colourType == 3 && length !in 1..paletteEntries) rejectPng()
                    if (type == "bKGD" && colourType == 3 && u8(payload) >= paletteEntries) rejectPng()
                    if (type == "pHYs" && u8(payload + 8) !in 0..1) rejectPng()
                }
                // APNG and unknown extensions need their own preservation proof, never flatten/drop.
                else -> rejectPng(PngCleaningFailure.UNSUPPORTED_LAYOUT)
            }
            coroutineContext.ensureActive()
            if (replacement != null) output.write(replacement)
            else if (retain) output.write(input, start, end + 4 - start)
            position = end + 4
        }
        rejectPng()
    }

    private fun unique(type: String) { if (!seen.add(type)) rejectPng() }
    private fun u8(at: Int) = input[at].toInt() and 255
    private fun u32(at: Int): Long = (u8(at).toLong() shl 24) or (u8(at + 1).toLong() shl 16) or
        (u8(at + 2).toLong() shl 8) or u8(at + 3).toLong()

    private suspend fun crc(start: Int, end: Int): Long {
        val crc = CRC32()
        var at = start
        while (at < end) {
            coroutineContext.ensureActive()
            val next = minOf(end, at + 8192)
            crc.update(input.substring(at, next).toByteArray())
            at = next
        }
        return crc.value
    }

    companion object {
        private val SIGNATURE = "89504e470d0a1a0a".decodeHex()
        private val COLOUR_CHUNKS = setOf("gAMA", "cHRM", "iCCP", "sRGB", "sBIT", "cICP", "mDCV", "cLLI")
        private val ORIENTATION_KEYWORDS = setOf("XML:com.adobe.xmp", "Raw profile type exif", "Raw profile type xmp")
            .map { it.encodeUtf8() }.toSet()

        private fun exifChunk(tiff: ByteString): ByteString {
            val type = "eXIf".encodeUtf8()
            val crc = CRC32().also { it.update(type.toByteArray()); it.update(tiff.toByteArray()) }.value
            return Buffer().writeInt(tiff.size).write(type).write(tiff).writeInt(crc.toInt()).readByteString()
        }
    }
}

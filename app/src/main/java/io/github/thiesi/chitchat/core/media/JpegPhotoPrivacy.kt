package io.github.thiesi.chitchat.core.media

import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okio.Buffer
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8

/** Ordinary single-frame Huffman JPEG container rewrite only; no pixel decode/re-encode,
 * provider access, upload, fallback or consent. Other photo formats follow separately.
 */
internal suspend fun cleanJpegPhoto(input: ByteString): JpegPhotoCleaning = withContext(Dispatchers.Default) {
    try {
        if (input.size.toLong() > MAX_ATTACHMENT_SNAPSHOT_BYTES) rejectJpeg(JpegCleaningFailure.CLIENT_BUDGET)
        JpegRewrite(input).clean()
    } catch (failure: JpegRejected) {
        JpegPhotoCleaning.Failed(failure.kind)
    }
}

internal sealed interface JpegPhotoCleaning {
    class Cleaned(val bytes: ByteString, val exifOrientation: Int) : JpegPhotoCleaning
    class Failed(val kind: JpegCleaningFailure) : JpegPhotoCleaning
}
internal enum class JpegCleaningFailure { INVALID_JPEG, UNSUPPORTED_LAYOUT, CLIENT_BUDGET }
private class JpegRejected(val kind: JpegCleaningFailure) : Exception()
private fun rejectJpeg(kind: JpegCleaningFailure = JpegCleaningFailure.INVALID_JPEG): Nothing = throw JpegRejected(kind)

private class JpegRewrite(private val input: ByteString) {
    // Ranges share immutable source segments; never decode private metadata into strings.
    private class Part(val start: Int, val end: Int, val replacement: ByteString? = null)
    private val parts = ArrayList<Part>()
    private var position = 2
    private var markers = 0
    private var frameComponents: Set<Int>? = null
    private var scan = false
    private var exif = false
    private var orientation: Int? = null
    private var xmp = false

    suspend fun clean(): JpegPhotoCleaning.Cleaned {
        if (input.size < 4 || u8(0) != 0xff || u8(1) != 0xd8) rejectJpeg()
        while (position < input.size) {
            coroutineContext.ensureActive()
            if (++markers > 8192) rejectJpeg(JpegCleaningFailure.UNSUPPORTED_LAYOUT)
            val start = position
            if (u8(position++) != 0xff) rejectJpeg()
            while (position < input.size && u8(position) == 0xff) {
                if (position % 8192 == 0) coroutineContext.ensureActive()
                position++
            }
            need(position, 1)
            val marker = u8(position++)
            if (marker == 0xd9) {
                if (frameComponents == null || !scan) rejectJpeg()
                // Extra images/gain maps or opaque trailers must not be silently discarded.
                for (offset in position until input.size) {
                    if (offset % 8192 == 0) coroutineContext.ensureActive()
                    if (u8(offset) != 0 && u8(offset) != 0xff) rejectJpeg(JpegCleaningFailure.UNSUPPORTED_LAYOUT)
                }
                if (xmp && orientation == null) rejectJpeg(JpegCleaningFailure.UNSUPPORTED_LAYOUT)
                parts.add(Part(start, position))
                val output = Buffer().write(input, 0, 2)
                for (part in parts) {
                    coroutineContext.ensureActive()
                    if (part.replacement != null) output.write(part.replacement)
                    else output.write(input, part.start, part.end - part.start)
                }
                return JpegPhotoCleaning.Cleaned(output.snapshot(), orientation ?: 1)
            }
            if (marker !in CORE_MARKERS && marker !in 0xe0..0xef)
                rejectJpeg(JpegCleaningFailure.UNSUPPORTED_LAYOUT)
            need(position, 2)
            val length = u16(position)
            if (length < 2) rejectJpeg()
            need(position, length)
            val payload = position + 2
            val end = position + length
            when (marker) {
                in 0xc0..0xc2 -> {
                    if (frameComponents != null) rejectJpeg(JpegCleaningFailure.UNSUPPORTED_LAYOUT)
                    if (length < 11) rejectJpeg()
                    val precision = u8(payload)
                    if (precision != 8 && (marker == 0xc0 || precision != 12)) rejectJpeg()
                    if (u16(payload + 1) == 0 || u16(payload + 3) == 0) rejectJpeg()
                    val components = u8(payload + 5)
                    if (components !in 1..4 || length != 8 + 3 * components) rejectJpeg()
                    val ids = (0 until components).map { u8(payload + 6 + 3 * it) }.toSet()
                    if (ids.size != components) rejectJpeg()
                    frameComponents = ids
                    parts.add(Part(start, end))
                }
                0xda -> {
                    val frameIds = frameComponents ?: rejectJpeg()
                    if (length < 8 || u8(payload) !in 1..frameIds.size || length != 6 + 2 * u8(payload)) rejectJpeg()
                    val ids = (0 until u8(payload)).map { u8(payload + 1 + 2 * it) }.toSet()
                    if (ids.size != u8(payload) || !frameIds.containsAll(ids)) rejectJpeg()
                    parts.add(Part(start, end))
                    position = end
                    val entropy = position
                    scanEnd()
                    if (position == entropy) rejectJpeg()
                    parts.add(Part(entropy, position))
                    scan = true
                    continue
                }
                0xe0 -> if (prefix(payload, end, JFIF)) {
                    if (end - payload < 14 || end - payload != 14 + 3 * u8(payload + 12) * u8(payload + 13)) rejectJpeg()
                    parts.add(Part(start, end))
                }
                0xe1 -> {
                    if (prefix(payload, end, EXIF)) {
                        if (exif) rejectJpeg(JpegCleaningFailure.UNSUPPORTED_LAYOUT)
                        exif = true
                        val fields = exifFields(payload + EXIF.size, end)
                        orientation = fields.orientation
                        minimalExif(fields)?.let { parts.add(Part(start, end, it)) }
                    } else {
                        if (prefix(payload, end, EXIF_NAME)) rejectJpeg()
                        if (prefix(payload, end, XMP) || prefix(payload, end, EXTENDED_XMP)) xmp = true
                    }
                }
                0xe2 -> {
                    if (prefix(payload, end, MPF)) rejectJpeg(JpegCleaningFailure.UNSUPPORTED_LAYOUT)
                    if (prefix(payload, end, ICC)) {
                        if (end - payload < ICC.size + 2) rejectJpeg()
                        parts.add(Part(start, end))
                    }
                }
                0xee -> if (prefix(payload, end, ADOBE)) {
                    if (end - payload != 12) rejectJpeg()
                    parts.add(Part(start, end))
                }
                0xfe -> Unit
                else -> if (marker !in 0xe0..0xef) parts.add(Part(start, end))
            }
            position = end
        }
        rejectJpeg()
    }

    private suspend fun scanEnd() {
        while (position < input.size) {
            if (position % 8192 == 0) coroutineContext.ensureActive()
            if (u8(position) != 0xff) { position++; continue }
            var next = position + 1
            while (next < input.size && u8(next) == 0xff) {
                if (next % 8192 == 0) coroutineContext.ensureActive()
                next++
            }
            need(next, 1)
            val marker = u8(next)
            if (marker == 0 || marker in 0xd0..0xd7) position = next + 1
            else return
        }
        rejectJpeg()
    }

    private class ExifFields(
        val orientation: Int?,
        val colourSpace: Int?,
        val interoperability: ByteString?,
        val interoperabilityVersion: Boolean,
    )

    private fun exifFields(tiff: Int, end: Int): ExifFields {
        if (end - tiff < 8) rejectJpeg()
        val little = when {
            u8(tiff) == 0x49 && u8(tiff + 1) == 0x49 -> true
            u8(tiff) == 0x4d && u8(tiff + 1) == 0x4d -> false
            else -> rejectJpeg()
        }
        fun short(at: Int): Int {
            if (at < tiff || at > end - 2) rejectJpeg()
            return if (little) u8(at) or (u8(at + 1) shl 8) else u16(at)
        }
        fun long(at: Int): Long {
            if (at < tiff || at > end - 4) rejectJpeg()
            var value = 0L
            for (i in 0..3) value = value or (u8(at + i).toLong() shl (8 * if (little) i else 3 - i))
            return value
        }
        if (short(tiff + 2) != 42) rejectJpeg()
        val visited = HashSet<Long>()
        fun directory(offset: Long): IntProgression {
            if (offset < 8 || offset > end - tiff - 6) rejectJpeg()
            if (!visited.add(offset)) rejectJpeg(JpegCleaningFailure.UNSUPPORTED_LAYOUT)
            val start = tiff + offset.toInt()
            val count = short(start)
            if (count > (end - start - 6) / 12) rejectJpeg()
            return start + 2 until start + 2 + 12 * count step 12
        }
        fun pointer(entry: Int): Long {
            if (short(entry + 2) != 4 || long(entry + 4) != 1L) rejectJpeg()
            return long(entry + 8)
        }
        fun colour(entry: Int) {
            when (short(entry)) {
                // A colour-space tag in another IFD must not be relocated/reinterpreted.
                0xa001 -> rejectJpeg(JpegCleaningFailure.UNSUPPORTED_LAYOUT)
                0x0213 -> {
                    if (short(entry + 2) != 3 || long(entry + 4) != 1L) rejectJpeg()
                    if (short(entry + 8) != 1) rejectJpeg(JpegCleaningFailure.UNSUPPORTED_LAYOUT)
                }
                in EXIF_CALIBRATION_TAGS -> rejectJpeg(JpegCleaningFailure.UNSUPPORTED_LAYOUT)
            }
        }
        var value: Int? = null
        var colourSpace: Int? = null
        var interoperability: ByteString? = null
        var interoperabilityVersion = false
        var exifPointer: Long? = null
        for (entry in directory(long(tiff + 4))) {
            colour(entry)
            if (short(entry) == 0x0112) {
                if (value != null || short(entry + 2) != 3 || long(entry + 4) != 1L) rejectJpeg()
                val parsed = short(entry + 8)
                if (parsed !in 1..8) rejectJpeg()
                value = parsed
            }
            if (short(entry) == 0x8769) {
                if (exifPointer != null) rejectJpeg()
                exifPointer = pointer(entry)
            }
        }
        exifPointer?.let { offset ->
            var interopPointer: Long? = null
            for (entry in directory(offset)) {
                if (short(entry) == 0xa001) {
                    if (colourSpace != null || short(entry + 2) != 3 || long(entry + 4) != 1L) rejectJpeg()
                    // Preserve the exact unsigned value, including uncalibrated/vendor values.
                    // The cleaner never guesses a profile or converts the pixels to sRGB.
                    colourSpace = short(entry + 8)
                } else colour(entry)
                if (short(entry) == 0xa005) {
                    if (interopPointer != null) rejectJpeg()
                    interopPointer = pointer(entry)
                }
            }
            interopPointer?.let { interop ->
                for (entry in directory(interop)) {
                    colour(entry)
                    when (short(entry)) {
                        1 -> {
                            if (interoperability != null || short(entry + 2) != 2 || long(entry + 4) != 4L) rejectJpeg()
                            val index = input.substring(entry + 8, entry + 12)
                            if (index != SRGB_INTEROP && index != ADOBE_INTEROP)
                                rejectJpeg(JpegCleaningFailure.UNSUPPORTED_LAYOUT)
                            interoperability = index
                        }
                        2 -> {
                            if (interoperabilityVersion || short(entry + 2) != 7 || long(entry + 4) != 4L) rejectJpeg()
                            if (!input.rangeEquals(entry + 8, INTEROP_VERSION, 0, 4))
                                rejectJpeg(JpegCleaningFailure.UNSUPPORTED_LAYOUT)
                            interoperabilityVersion = true
                        }
                    }
                }
            }
        }
        return ExifFields(value, colourSpace, interoperability, interoperabilityVersion)
    }

    private fun need(at: Int, length: Int) { if (at < 0 || length < 0 || at > input.size || length > input.size - at) rejectJpeg() }
    private fun u8(at: Int) = input[at].toInt() and 0xff
    private fun u16(at: Int) = (u8(at) shl 8) or u8(at + 1)
    private fun prefix(at: Int, end: Int, bytes: ByteString) = bytes.size <= end - at && input.rangeEquals(at, bytes, 0, bytes.size)

    companion object {
        private val CORE_MARKERS = setOf(0xc0, 0xc1, 0xc2, 0xc4, 0xdb, 0xdd, 0xda, 0xfe)
        private val EXIF_CALIBRATION_TAGS = setOf(0x012d, 0x013e, 0x013f, 0x0211, 0x0214, 0xa500)
        private val SRGB_INTEROP = "R98\u0000".encodeUtf8()
        private val ADOBE_INTEROP = "R03\u0000".encodeUtf8()
        private val INTEROP_VERSION = "0100".encodeUtf8()
        private val JFIF = "JFIF\u0000".encodeUtf8()
        private val EXIF = "Exif\u0000\u0000".encodeUtf8()
        private val EXIF_NAME = "Exif".encodeUtf8()
        private val XMP = "http://ns.adobe.com/xap/1.0/\u0000".encodeUtf8()
        private val EXTENDED_XMP = "http://ns.adobe.com/xmp/extension/\u0000".encodeUtf8()
        private val ICC = "ICC_PROFILE\u0000".encodeUtf8()
        private val MPF = "MPF\u0000".encodeUtf8()
        private val ADOBE = "Adobe".encodeUtf8()

        private fun minimalExif(fields: ExifFields): ByteString? {
            val rotated = fields.orientation?.takeIf { it != 1 }
            val interopCount = (if (fields.interoperability != null) 1 else 0) +
                (if (fields.interoperabilityVersion) 1 else 0)
            val exifCount = (if (fields.colourSpace != null) 1 else 0) + (if (interopCount > 0) 1 else 0)
            val rootCount = (if (rotated != null) 1 else 0) + (if (exifCount > 0) 1 else 0)
            if (rootCount == 0) return null
            val exifOffset = 8 + 2 + 12 * rootCount + 4
            val interopOffset = exifOffset + 2 + 12 * exifCount + 4
            val payload = Buffer().write(EXIF).writeUtf8("II").writeShortLe(42).writeIntLe(8)
            fun field(tag: Int, type: Int, count: Int, inline: Int) {
                payload.writeShortLe(tag).writeShortLe(type).writeIntLe(count).writeIntLe(inline)
            }
            payload.writeShortLe(rootCount)
            if (rotated != null) field(0x0112, 3, 1, rotated)
            if (exifCount > 0) field(0x8769, 4, 1, exifOffset)
            payload.writeIntLe(0) // No next IFD/thumbnail, GPS or other original pointer survives.
            if (exifCount > 0) {
                payload.writeShortLe(exifCount)
                fields.colourSpace?.let { field(0xa001, 3, 1, it) }
                if (interopCount > 0) field(0xa005, 4, 1, interopOffset)
                payload.writeIntLe(0)
            }
            if (interopCount > 0) {
                payload.writeShortLe(interopCount)
                fields.interoperability?.let {
                    payload.writeShortLe(1).writeShortLe(2).writeIntLe(4).write(it)
                }
                if (fields.interoperabilityVersion)
                    payload.writeShortLe(2).writeShortLe(7).writeIntLe(4).write(INTEROP_VERSION)
                payload.writeIntLe(0)
            }
            val bytes = payload.readByteString()
            return Buffer().writeByte(0xff).writeByte(0xe1).writeShort(bytes.size + 2).write(bytes).readByteString()
        }
    }
}

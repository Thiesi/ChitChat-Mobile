package io.github.thiesi.chitchat.core.media

import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okio.Buffer
import okio.ByteString
import okio.ByteString.Companion.decodeHex
import okio.ByteString.Companion.encodeUtf8

/** Static WebP container privacy; no pixel decode/re-encode, provider, upload or consent. */
internal suspend fun cleanWebpPhoto(input: ByteString): WebpPhotoCleaning = withContext(Dispatchers.Default) {
    try {
        if (input.size.toLong() > MAX_ATTACHMENT_SNAPSHOT_BYTES) rejectWebp(WebpCleaningFailure.CLIENT_BUDGET)
        WebpRewrite(input).clean()
    } catch (failure: WebpRejected) {
        WebpPhotoCleaning.Failed(failure.kind)
    }
}

internal sealed interface WebpPhotoCleaning {
    class Cleaned(val bytes: ByteString, val exifOrientation: Int) : WebpPhotoCleaning
    class Failed(val kind: WebpCleaningFailure) : WebpPhotoCleaning
}
internal enum class WebpCleaningFailure { INVALID_WEBP, UNSUPPORTED_LAYOUT, CLIENT_BUDGET }
private class WebpRejected(val kind: WebpCleaningFailure) : Exception()
private fun rejectWebp(kind: WebpCleaningFailure = WebpCleaningFailure.INVALID_WEBP): Nothing = throw WebpRejected(kind)

private class WebpRewrite(private val input: ByteString) {
    private class Part(val start: Int, val end: Int, var replacement: ByteString? = null)
    private val parts = ArrayList<Part>()
    private var position = 12
    private var chunks = 0
    private var extended: Int? = null
    private var extendedPart = -1
    private var flags = 0
    private var width = 0
    private var height = 0
    private var image = false
    private var lossless = false
    private var alpha = false
    private var icc = false
    private var exif = false
    private var xmp = false
    private var cleanedExif = false
    private var orientation: Int? = null

    suspend fun clean(): WebpPhotoCleaning.Cleaned {
        if (input.size < 20 || !prefix(0, input.size, RIFF) || !prefix(8, input.size, WEBP)) rejectWebp()
        if (u32(4) != input.size - 8L) rejectWebp()
        while (position <= input.size - 8) {
            coroutineContext.ensureActive()
            if (++chunks > 8192) rejectWebp(WebpCleaningFailure.UNSUPPORTED_LAYOUT)
            val start = position
            val lengthLong = u32(start + 4)
            if (lengthLong > input.size - start - 8L) rejectWebp()
            val length = lengthLong.toInt()
            val payload = start + 8
            val end = payload + length
            val padded = end + length % 2
            if (padded > input.size || (padded != end && u8(end) != 0)) rejectWebp()
            val type = input.substring(start, start + 4)
            var retain = true
            var replacement: ByteString? = null
            when (type) {
                VP8X -> {
                    if (chunks != 1 || extended != null || length != 10) rejectWebp()
                    flags = u8(payload)
                    if ((flags and 0xc1) != 0 || u24(payload + 1) != 0)
                        rejectWebp(WebpCleaningFailure.UNSUPPORTED_LAYOUT)
                    if ((flags and 2) != 0) rejectWebp(WebpCleaningFailure.UNSUPPORTED_LAYOUT)
                    width = u24(payload + 4) + 1; height = u24(payload + 7) + 1
                    if (width.toLong() * height > 0xffffffffL) rejectWebp()
                    extended = payload
                    extendedPart = parts.size
                }
                ICCP -> {
                    requireExtended()
                    if (icc || image || alpha || length == 0) rejectWebp()
                    icc = true
                }
                ALPH -> {
                    requireExtended()
                    if (alpha || image || length == 0) rejectWebp()
                    val header = u8(payload)
                    if ((header and 0xc0) != 0 || (header and 3) > 1 || ((header shr 4) and 3) > 1)
                        rejectWebp(WebpCleaningFailure.UNSUPPORTED_LAYOUT)
                    if ((header and 3) == 0 && lengthLong != 1 + width.toLong() * height) rejectWebp()
                    if ((header and 3) == 1 && length < 2) rejectWebp()
                    alpha = true
                }
                VP8, VP8L -> {
                    if (image || (extended == null && chunks != 1)) rejectWebp()
                    val dimensions = if (type == VP8) {
                        if (length < 10 || (u8(payload) and 1) != 0 || !prefix(payload + 3, end, VP8_SIGNATURE)) rejectWebp()
                        (u16(payload + 6) and 0x3fff) to (u16(payload + 8) and 0x3fff)
                    } else {
                        if (alpha || length < 6 || u8(payload) != 0x2f) rejectWebp()
                        val header = u32(payload + 1)
                        if ((header shr 29) != 0L) rejectWebp(WebpCleaningFailure.UNSUPPORTED_LAYOUT)
                        lossless = true
                        ((header and 0x3fff).toInt() + 1) to (((header shr 14) and 0x3fff).toInt() + 1)
                    }
                    if (dimensions.first == 0 || dimensions.second == 0) rejectWebp()
                    if (extended != null && (dimensions.first != width || dimensions.second != height)) rejectWebp()
                    image = true
                }
                EXIF -> {
                    requireExtended()
                    if (exif) rejectWebp()
                    exif = true
                    val idLength = if (prefix(payload, end, EXIF_ID)) EXIF_ID.size else 0
                    if (length - idLength > MAX_PHOTO_EXIF_BYTES) rejectWebp(WebpCleaningFailure.UNSUPPORTED_LAYOUT)
                    val fields = when (val result = cleanPhotoExif(input.substring(payload + idLength, end))) {
                        is PhotoExifCleaning.Cleaned -> result
                        is PhotoExifCleaning.Failed -> rejectWebp(if (result.kind == PhotoExifFailure.INVALID)
                            WebpCleaningFailure.INVALID_WEBP else WebpCleaningFailure.UNSUPPORTED_LAYOUT)
                    }
                    orientation = fields.orientation
                    fields.tiff?.let {
                        val data = if (idLength == 0) it else Buffer().write(EXIF_ID).write(it).readByteString()
                        replacement = exifChunk(data)
                        cleanedExif = true
                    }
                    retain = false
                }
                XMP -> {
                    requireExtended()
                    if (xmp) rejectWebp()
                    xmp = true
                    retain = false
                }
                else -> rejectWebp(WebpCleaningFailure.UNSUPPORTED_LAYOUT)
            }
            if (replacement != null) parts.add(Part(start, padded, replacement))
            else if (retain) parts.add(Part(start, padded))
            position = padded
        }
        if (position != input.size || !image) rejectWebp()
        if (xmp && orientation == null) rejectWebp(WebpCleaningFailure.UNSUPPORTED_LAYOUT)
        extended?.let { payload ->
            if (((flags and 0x20) != 0) != icc || ((flags and 8) != 0) != exif || ((flags and 4) != 0) != xmp)
                rejectWebp()
            if (!lossless && ((flags and 0x10) != 0) != alpha) rejectWebp()
            val cleanedFlags = (flags and 0xf3) or (if (cleanedExif) 8 else 0)
            parts[extendedPart].replacement = Buffer().write(VP8X).writeIntLe(10).writeByte(cleanedFlags)
                .write(input, payload + 1, 9).readByteString()
        }
        val size = 4L + parts.sumOf { it.replacement?.size?.toLong() ?: (it.end - it.start).toLong() }
        if (size + 8 > MAX_ATTACHMENT_SNAPSHOT_BYTES) rejectWebp(WebpCleaningFailure.CLIENT_BUDGET)
        val output = Buffer().write(RIFF).writeIntLe(size.toInt()).write(WEBP)
        for (part in parts) {
            coroutineContext.ensureActive()
            val replacement = part.replacement
            if (replacement != null) output.write(replacement)
            else output.write(input, part.start, part.end - part.start)
        }
        return WebpPhotoCleaning.Cleaned(output.snapshot(), orientation ?: 1)
    }

    private fun requireExtended() { if (extended == null) rejectWebp(WebpCleaningFailure.UNSUPPORTED_LAYOUT) }
    private fun u8(at: Int) = input[at].toInt() and 255
    private fun u16(at: Int) = u8(at) or (u8(at + 1) shl 8)
    private fun u24(at: Int) = u16(at) or (u8(at + 2) shl 16)
    private fun u32(at: Int) = u24(at).toLong() or (u8(at + 3).toLong() shl 24)
    private fun prefix(at: Int, end: Int, value: ByteString) = value.size <= end - at && input.rangeEquals(at, value, 0, value.size)

    companion object {
        private val RIFF = "RIFF".encodeUtf8()
        private val WEBP = "WEBP".encodeUtf8()
        private val VP8X = "VP8X".encodeUtf8()
        private val VP8 = "VP8 ".encodeUtf8()
        private val VP8L = "VP8L".encodeUtf8()
        private val VP8_SIGNATURE = "9d012a".decodeHex()
        private val ALPH = "ALPH".encodeUtf8()
        private val ICCP = "ICCP".encodeUtf8()
        private val EXIF = "EXIF".encodeUtf8()
        private val EXIF_ID = "Exif\u0000\u0000".encodeUtf8()
        private val XMP = "XMP ".encodeUtf8()
        private fun exifChunk(data: ByteString): ByteString = Buffer().write(EXIF).writeIntLe(data.size).write(data)
            .apply { if (data.size % 2 != 0) writeByte(0) }.readByteString()
    }
}

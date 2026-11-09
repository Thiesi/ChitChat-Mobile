package io.github.thiesi.chitchat.core.media

import okio.Buffer
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8

/** Bounded classic TIFF payload only, with no JPEG/PNG/WebP framing or metadata logging. */
internal const val MAX_PHOTO_EXIF_BYTES = 65527
internal fun cleanPhotoExif(input: ByteString): PhotoExifCleaning = try {
    if (input.size > MAX_PHOTO_EXIF_BYTES) rejectExif(PhotoExifFailure.UNSUPPORTED)
    PhotoExifRewrite(input).clean()
} catch (failure: ExifRejected) {
    PhotoExifCleaning.Failed(failure.kind)
}

internal sealed interface PhotoExifCleaning {
    class Cleaned(val tiff: ByteString?, val orientation: Int?) : PhotoExifCleaning
    class Failed(val kind: PhotoExifFailure) : PhotoExifCleaning
}
internal enum class PhotoExifFailure { INVALID, UNSUPPORTED }
private class ExifRejected(val kind: PhotoExifFailure) : Exception()
private fun rejectExif(kind: PhotoExifFailure = PhotoExifFailure.INVALID): Nothing = throw ExifRejected(kind)

private class PhotoExifRewrite(private val input: ByteString) {
    fun clean(): PhotoExifCleaning.Cleaned {
        val fields = exifFields(0, input.size)
        return PhotoExifCleaning.Cleaned(minimalExif(fields), fields.orientation)
    }

    private class ExifFields(
        val orientation: Int?,
        val colourSpace: Int?,
        val interoperability: ByteString?,
        val interoperabilityVersion: Boolean,
    )

    private fun exifFields(tiff: Int, end: Int): ExifFields {
        if (end - tiff < 8) rejectExif()
        val little = when {
            u8(tiff) == 0x49 && u8(tiff + 1) == 0x49 -> true
            u8(tiff) == 0x4d && u8(tiff + 1) == 0x4d -> false
            else -> rejectExif()
        }
        fun short(at: Int): Int {
            if (at < tiff || at > end - 2) rejectExif()
            return if (little) u8(at) or (u8(at + 1) shl 8) else u16(at)
        }
        fun long(at: Int): Long {
            if (at < tiff || at > end - 4) rejectExif()
            var value = 0L
            for (i in 0..3) value = value or (u8(at + i).toLong() shl (8 * if (little) i else 3 - i))
            return value
        }
        if (short(tiff + 2) != 42) rejectExif()
        val visited = HashSet<Long>()
        fun directory(offset: Long): IntProgression {
            if (offset < 8 || offset > end - tiff - 6) rejectExif()
            if (!visited.add(offset)) rejectExif(PhotoExifFailure.UNSUPPORTED)
            val start = tiff + offset.toInt()
            val count = short(start)
            if (count > (end - start - 6) / 12) rejectExif()
            return start + 2 until start + 2 + 12 * count step 12
        }
        fun pointer(entry: Int): Long {
            if (short(entry + 2) != 4 || long(entry + 4) != 1L) rejectExif()
            return long(entry + 8)
        }
        fun colour(entry: Int) {
            when (short(entry)) {
                // A colour-space tag in another IFD must not be relocated/reinterpreted.
                0xa001 -> rejectExif(PhotoExifFailure.UNSUPPORTED)
                0x0213 -> {
                    if (short(entry + 2) != 3 || long(entry + 4) != 1L) rejectExif()
                    if (short(entry + 8) != 1) rejectExif(PhotoExifFailure.UNSUPPORTED)
                }
                in EXIF_CALIBRATION_TAGS -> rejectExif(PhotoExifFailure.UNSUPPORTED)
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
                if (value != null || short(entry + 2) != 3 || long(entry + 4) != 1L) rejectExif()
                val parsed = short(entry + 8)
                if (parsed !in 1..8) rejectExif()
                value = parsed
            }
            if (short(entry) == 0x8769) {
                if (exifPointer != null) rejectExif()
                exifPointer = pointer(entry)
            }
        }
        exifPointer?.let { offset ->
            var interopPointer: Long? = null
            for (entry in directory(offset)) {
                if (short(entry) == 0xa001) {
                    if (colourSpace != null || short(entry + 2) != 3 || long(entry + 4) != 1L) rejectExif()
                    // Preserve the exact unsigned value, including uncalibrated/vendor values.
                    // The cleaner never guesses a profile or converts the pixels to sRGB.
                    colourSpace = short(entry + 8)
                } else colour(entry)
                if (short(entry) == 0xa005) {
                    if (interopPointer != null) rejectExif()
                    interopPointer = pointer(entry)
                }
            }
            interopPointer?.let { interop ->
                for (entry in directory(interop)) {
                    colour(entry)
                    when (short(entry)) {
                        1 -> {
                            if (interoperability != null || short(entry + 2) != 2 || long(entry + 4) != 4L) rejectExif()
                            val index = input.substring(entry + 8, entry + 12)
                            if (index != SRGB_INTEROP && index != ADOBE_INTEROP)
                                rejectExif(PhotoExifFailure.UNSUPPORTED)
                            interoperability = index
                        }
                        2 -> {
                            if (interoperabilityVersion || short(entry + 2) != 7 || long(entry + 4) != 4L) rejectExif()
                            if (!input.rangeEquals(entry + 8, INTEROP_VERSION, 0, 4))
                                rejectExif(PhotoExifFailure.UNSUPPORTED)
                            interoperabilityVersion = true
                        }
                    }
                }
            }
        }
        return ExifFields(value, colourSpace, interoperability, interoperabilityVersion)
    }

    private fun u8(at: Int) = input[at].toInt() and 0xff
    private fun u16(at: Int) = (u8(at) shl 8) or u8(at + 1)

    companion object {
        private val EXIF_CALIBRATION_TAGS = setOf(0x012d, 0x013e, 0x013f, 0x0211, 0x0214, 0xa500)
        private val SRGB_INTEROP = "R98\u0000".encodeUtf8()
        private val ADOBE_INTEROP = "R03\u0000".encodeUtf8()
        private val INTEROP_VERSION = "0100".encodeUtf8()
        private fun minimalExif(fields: ExifFields): ByteString? {
            val rotated = fields.orientation?.takeIf { it != 1 }
            val interopCount = (if (fields.interoperability != null) 1 else 0) +
                (if (fields.interoperabilityVersion) 1 else 0)
            val exifCount = (if (fields.colourSpace != null) 1 else 0) + (if (interopCount > 0) 1 else 0)
            val rootCount = (if (rotated != null) 1 else 0) + (if (exifCount > 0) 1 else 0)
            if (rootCount == 0) return null
            val exifOffset = 8 + 2 + 12 * rootCount + 4
            val interopOffset = exifOffset + 2 + 12 * exifCount + 4
            val payload = Buffer().writeUtf8("II").writeShortLe(42).writeIntLe(8)
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
            return bytes
        }
    }
}

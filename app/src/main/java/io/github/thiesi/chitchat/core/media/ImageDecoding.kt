package io.github.thiesi.chitchat.core.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream

/** A decoded image held for display, with its real memory cost. No string form of its content. */
class DecodedImage internal constructor(val bitmap: Bitmap?, val width: Int, val height: Int, val byteCount: Long) {
    override fun toString() = "DecodedImage"
}

/** What decoding found (received files F2). */
internal sealed interface DecodeResult {
    class Decoded(val image: DecodedImage) : DecodeResult
    /** Beyond the 16-megapixel guard (TL caps): never decoded, even downsampled. */
    data object TooLarge : DecodeResult
    /** Not a decodable image (corrupt or unsupported bytes). */
    data object Unreadable : DecodeResult
    /** The memory budget refused the lease a step needed: nothing was allocated for it. */
    data object NoMemory : DecodeResult
}

/**
 * Decodes downsampled to the frame. [lease] sets the caller's memory charge to the given total before each allocation
 * (M5g3h: reserve before allocating) and returns false when it can't. [orient] applies the file's EXIF orientation: true
 * for originals only, since the server's previews are already in display orientation.
 */
internal fun interface ImageDecoding {
    fun decode(bytes: ByteArray, maxWidth: Int, maxHeight: Int, orient: Boolean, lease: (Long) -> Boolean): DecodeResult
}

/**
 * [BitmapFactory] with the bounds first, the 16 Mpx guard, a power-of-two sample size picked against the oriented frame,
 * and EXIF orientation applied with a [Matrix] (all eight values; TL, #308).
 */
internal object AndroidImageDecoding : ImageDecoding {
    override fun decode(bytes: ByteArray, maxWidth: Int, maxHeight: Int, orient: Boolean, lease: (Long) -> Boolean): DecodeResult {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0) return DecodeResult.Unreadable
        // Orientation-independent: the pixel count is the same either way.
        if (width.toLong() * height > AttachmentDownloads.DISPLAY_MAX_PIXELS) return DecodeResult.TooLarge
        val orientation = if (orient) orientation(bytes) else ExifInterface.ORIENTATION_NORMAL
        val swaps = orientation in SWAPPING
        val sample = sampleSize(width, height, (if (swaps) maxHeight else maxWidth).coerceAtLeast(1),
            (if (swaps) maxWidth else maxHeight).coerceAtLeast(1))
        val raw = bytes.size.toLong()
        val estimate = ((width + sample - 1) / sample).toLong() * ((height + sample - 1) / sample) * 4
        if (!lease(raw + estimate)) return DecodeResult.NoMemory
        val options = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val decoded = try { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) } catch (_: OutOfMemoryError) { null }
            ?: return DecodeResult.Unreadable
        val image = if (orientation == ExifInterface.ORIENTATION_NORMAL) decoded else {
            // The rotation briefly holds two bitmaps: lease that peak before creating the second one.
            if (!lease(raw + decoded.allocationByteCount + decoded.width.toLong() * decoded.height * 4)) {
                decoded.recycle(); return DecodeResult.NoMemory
            }
            val rotated = try { Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix(orientation), true) }
                catch (_: OutOfMemoryError) { decoded.recycle(); return DecodeResult.NoMemory }
            if (rotated !== decoded) decoded.recycle()
            rotated
        }
        // The raw bytes are no longer needed: the charge becomes exactly the bitmap that's kept.
        lease(image.allocationByteCount.toLong())
        return DecodeResult.Decoded(DecodedImage(image, image.width, image.height, image.allocationByteCount.toLong()))
    }

    /** A hint only: missing, malformed or anything thrown means normal. Reads only the bytes already held. */
    internal fun orientation(bytes: ByteArray): Int = try {
        ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            .takeIf { it in 1..8 } ?: ExifInterface.ORIENTATION_NORMAL
    } catch (_: Exception) { ExifInterface.ORIENTATION_NORMAL }

    private val SWAPPING = setOf(ExifInterface.ORIENTATION_TRANSPOSE, ExifInterface.ORIENTATION_ROTATE_90,
        ExifInterface.ORIENTATION_TRANSVERSE, ExifInterface.ORIENTATION_ROTATE_270)

    internal fun matrix(orientation: Int) = Matrix().apply {
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(-90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
        }
    }

    /** The largest power of two that keeps both sides at least the target (never upscaling in memory). */
    internal fun sampleSize(width: Int, height: Int, maxWidth: Int, maxHeight: Int): Int {
        var sample = 1
        while (width / (sample * 2) >= maxWidth && height / (sample * 2) >= maxHeight) sample *= 2
        return sample
    }
}

package io.github.thiesi.chitchat.core.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory

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
}

/** Decodes downsampled to the frame or screen; the bounds are read first and the pixel guard applied before any pixels. */
internal fun interface ImageDecoding {
    fun decode(bytes: ByteArray, maxWidth: Int, maxHeight: Int): DecodeResult
}

/** [BitmapFactory] with bounds first and a power-of-two sample size, so a decode never allocates the full original. */
internal object AndroidImageDecoding : ImageDecoding {
    override fun decode(bytes: ByteArray, maxWidth: Int, maxHeight: Int): DecodeResult {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0) return DecodeResult.Unreadable
        if (width.toLong() * height > AttachmentDownloads.DISPLAY_MAX_PIXELS) return DecodeResult.TooLarge
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(width, height, maxWidth.coerceAtLeast(1), maxHeight.coerceAtLeast(1))
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = try { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) } catch (_: OutOfMemoryError) { null }
            ?: return DecodeResult.Unreadable
        return DecodeResult.Decoded(DecodedImage(bitmap, bitmap.width, bitmap.height, bitmap.allocationByteCount.toLong()))
    }

    /** The largest power of two that keeps both sides at least the target (never upscaling in memory). */
    internal fun sampleSize(width: Int, height: Int, maxWidth: Int, maxHeight: Int): Int {
        var sample = 1
        while (width / (sample * 2) >= maxWidth && height / (sample * 2) >= maxHeight) sample *= 2
        return sample
    }
}

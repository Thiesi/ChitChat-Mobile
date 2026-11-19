package io.github.thiesi.chitchat.core.media

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.CRC32
import org.junit.Assert.*
import org.junit.Test

/** Received files F2: the real decoder reads bounds first, guards pixels, and downsamples to the frame. */
class AndroidImageDecodingTest {
    private fun png(width: Int, height: Int): ByteArray = ByteArrayOutputStream().also {
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF336699.toInt()) }
            .compress(Bitmap.CompressFormat.PNG, 100, it)
    }.toByteArray()

    private fun chunk(type: String, data: ByteArray): ByteArray {
        val name = type.toByteArray()
        val crc = CRC32().apply { update(name); update(data) }.value.toInt()
        return ByteBuffer.allocate(4).putInt(data.size).array() + name + data + ByteBuffer.allocate(4).putInt(crc).array()
    }

    /**
     * A valid but huge PNG that costs almost nothing to build: 1-bit grayscale, all zero, so the deflated pixels are a few
     * KB. Skia reads its bounds; decoding it at ARGB would need about 80 MB, which the guard must prevent. (A header-only
     * stream has no readable bounds at all: #308.)
     */
    private fun hugePng(width: Int, height: Int): ByteArray {
        val header = ByteBuffer.allocate(13).putInt(width).putInt(height).put(1).put(0).put(0).put(0).put(0).array()
        val row = ByteArray(1 + (width + 7) / 8)
        val pixels = ByteArrayOutputStream().also { out ->
            java.util.zip.DeflaterOutputStream(out).use { deflate -> repeat(height) { deflate.write(row) } }
        }.toByteArray()
        return byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10) + chunk("IHDR", header) + chunk("IDAT", pixels) + chunk("IEND", ByteArray(0))
    }

    @Test fun aSmallImageDecodesWithinTheFrame() {
        val result = AndroidImageDecoding.decode(png(400, 300), 100, 75) as DecodeResult.Decoded
        assertTrue("Downsampled: ${result.image.width}x${result.image.height}", result.image.width in 100..200 && result.image.height in 75..150)
        assertEquals(result.image.bitmap!!.allocationByteCount.toLong(), result.image.byteCount)
    }

    @Test fun beyondSixteenMegapixelsNothingIsDecoded() {
        val png = hugePng(5000, 4000)
        assertTrue("Cheap to hold: ${png.size} bytes", png.size < 200_000)
        assertEquals(DecodeResult.TooLarge, AndroidImageDecoding.decode(png, 280, 320))
    }

    @Test fun aTruncatedHeaderIsUnreadableNeverTooLargeOrDecoded() {
        // TL (#308): only the signature and an IHDR claiming 5000x4000; Skia gives no bounds for it.
        val truncated = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10) +
            chunk("IHDR", ByteBuffer.allocate(13).putInt(5000).putInt(4000).put(8).put(6).put(0).put(0).put(0).array())
        assertEquals(DecodeResult.Unreadable, AndroidImageDecoding.decode(truncated, 280, 320))
    }

    @Test fun unreadableBytesAreUnreadable() {
        assertEquals(DecodeResult.Unreadable, AndroidImageDecoding.decode(ByteArray(64) { 7 }, 280, 320))
    }
}

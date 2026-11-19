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

    /** Only the signature and IHDR: enough for the bounds, with no pixels to decode. */
    private fun headerOnly(width: Int, height: Int): ByteArray {
        val data = ByteBuffer.allocate(13).putInt(width).putInt(height).put(8).put(6).put(0).put(0).put(0).array()
        val type = "IHDR".toByteArray()
        val crc = CRC32().apply { update(type); update(data) }.value.toInt()
        return byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10) + ByteBuffer.allocate(4).putInt(13).array() + type + data +
            ByteBuffer.allocate(4).putInt(crc).array()
    }

    @Test fun aSmallImageDecodesWithinTheFrame() {
        val result = AndroidImageDecoding.decode(png(400, 300), 100, 75) as DecodeResult.Decoded
        assertTrue("Downsampled: ${result.image.width}x${result.image.height}", result.image.width in 100..200 && result.image.height in 75..150)
        assertEquals(result.image.bitmap!!.allocationByteCount.toLong(), result.image.byteCount)
    }

    @Test fun beyondSixteenMegapixelsNothingIsDecoded() {
        assertEquals(DecodeResult.TooLarge, AndroidImageDecoding.decode(headerOnly(5000, 4000), 280, 320))
    }

    @Test fun unreadableBytesAreUnreadable() {
        assertEquals(DecodeResult.Unreadable, AndroidImageDecoding.decode(ByteArray(64) { 7 }, 280, 320))
    }
}

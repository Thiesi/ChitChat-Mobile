package io.github.thiesi.chitchat.core.media

import android.graphics.Bitmap
import android.graphics.Color
import androidx.exifinterface.media.ExifInterface
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.zip.CRC32
import org.junit.Assert.*
import org.junit.Test

/** Received files F2: the real decoder reads bounds first, guards pixels, leases before allocating, and orients originals. */
class AndroidImageDecodingTest {
    private val always: (Long) -> Boolean = { true }

    private fun png(width: Int, height: Int): ByteArray = ByteArrayOutputStream().also {
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF336699.toInt()) }
            .compress(Bitmap.CompressFormat.PNG, 100, it)
    }.toByteArray()

    /** A 40×20 JPEG, left half red and right half blue, carrying EXIF [orientation] (written by ExifInterface itself). */
    private fun jpeg(orientation: Int?): ByteArray {
        val bitmap = Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888)
        for (x in 0 until 40) for (y in 0 until 20) bitmap.setPixel(x, y, if (x < 20) Color.RED else Color.BLUE)
        val file = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "ccm-orientation-test.jpg")
        try {
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it) }
            if (orientation != null) ExifInterface(file).apply { setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString()); saveAttributes() }
            return file.readBytes()
        } finally { file.delete() }
    }

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

    private fun decoded(result: DecodeResult) = (result as DecodeResult.Decoded).image

    @Test fun aSmallImageDecodesWithinTheFrame() {
        val image = decoded(AndroidImageDecoding.decode(png(400, 300), 100, 75, false, always))
        assertTrue("Downsampled: ${image.width}x${image.height}", image.width in 100..200 && image.height in 75..150)
        assertEquals(image.bitmap!!.allocationByteCount.toLong(), image.byteCount)
    }

    @Test fun beyondSixteenMegapixelsNothingIsDecoded() {
        val png = hugePng(5000, 4000)
        assertTrue("Cheap to hold: ${png.size} bytes", png.size < 200_000)
        assertEquals(DecodeResult.TooLarge, AndroidImageDecoding.decode(png, 280, 320, true, always))
    }

    @Test fun aTruncatedHeaderIsUnreadableNeverTooLargeOrDecoded() {
        // TL (#308): only the signature and an IHDR claiming 5000x4000; Skia gives no bounds for it.
        val truncated = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10) +
            chunk("IHDR", ByteBuffer.allocate(13).putInt(5000).putInt(4000).put(8).put(6).put(0).put(0).put(0).array())
        assertEquals(DecodeResult.Unreadable, AndroidImageDecoding.decode(truncated, 280, 320, true, always))
    }

    @Test fun unreadableBytesAreUnreadable() {
        assertEquals(DecodeResult.Unreadable, AndroidImageDecoding.decode(ByteArray(64) { 7 }, 280, 320, true, always))
    }

    @Test fun anOrientationSixOriginalDecodesToItsOrientedSize() {
        // TL (#308): a portrait phone photo must not show sideways.
        val image = decoded(AndroidImageDecoding.decode(jpeg(ExifInterface.ORIENTATION_ROTATE_90), 1000, 1000, true, always))
        assertEquals(20, image.width); assertEquals(40, image.height)
    }

    @Test fun aFlippedOriginalIsMirrored() {
        val image = decoded(AndroidImageDecoding.decode(jpeg(ExifInterface.ORIENTATION_FLIP_HORIZONTAL), 1000, 1000, true, always))
        assertEquals(40, image.width)
        val left = image.bitmap!!.getPixel(2, 10)
        assertTrue("Blue now on the left: ${Integer.toHexString(left)}", Color.blue(left) > 200 && Color.red(left) < 60)
    }

    @Test fun aPreviewIsNeverRotated() {
        val image = decoded(AndroidImageDecoding.decode(jpeg(ExifInterface.ORIENTATION_ROTATE_90), 1000, 1000, false, always))
        assertEquals(40, image.width); assertEquals(20, image.height)
    }

    @Test fun aMalformedExifBlockShowsTheImageUnrotated() {
        // An APP1 "Exif" segment of garbage right after SOI: orientation unknown, never a failed display.
        val good = jpeg(null)
        val junk = byteArrayOf(0xFF.toByte(), 0xE1.toByte(), 0x00, 0x10, 'E'.code.toByte(), 'x'.code.toByte(), 'i'.code.toByte(),
            'f'.code.toByte(), 0, 0) + ByteArray(8) { 0x5A }
        val malformed = good.copyOfRange(0, 2) + junk + good.copyOfRange(2, good.size)
        val image = decoded(AndroidImageDecoding.decode(malformed, 1000, 1000, true, always))
        assertEquals(40, image.width); assertEquals(20, image.height)
    }

    @Test fun theRotationPeakIsLeasedBeforeItIsAllocatedThenShrunk() {
        val bytes = jpeg(ExifInterface.ORIENTATION_ROTATE_90)
        val charges = mutableListOf<Long>()
        val image = decoded(AndroidImageDecoding.decode(bytes, 1000, 1000, true) { charges += it; true })
        assertEquals("Decode, rotation peak, final: $charges", 3, charges.size)
        assertTrue("The peak holds two bitmaps: $charges", charges[1] > charges[0])
        assertEquals("Shrunk to exactly the kept bitmap", image.byteCount, charges[2])
        assertTrue(charges[2] < charges[1])
        // No lease for the peak: nothing is rotated, and the result is NoMemory.
        var calls = 0
        assertEquals(DecodeResult.NoMemory, AndroidImageDecoding.decode(bytes, 1000, 1000, true) { ++calls < 2 })
        assertEquals(2, calls)
    }
}

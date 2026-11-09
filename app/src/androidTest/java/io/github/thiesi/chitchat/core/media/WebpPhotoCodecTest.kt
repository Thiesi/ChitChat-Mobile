package io.github.thiesi.chitchat.core.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import okio.Buffer
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real codec-generated fixtures, not a UI/input journey or a provider/live acceptance claim. */
@RunWith(AndroidJUnit4::class)
class WebpPhotoCodecTest {
    @Suppress("DEPRECATION")
    @Test fun androidEncodedStaticPhotosKeepDecodedPixelsAndOrientationAfterPrivacyRewrite() = runBlocking {
        val bitmap = Bitmap.createBitmap(intArrayOf(0xffff0000.toInt(), 0x8000ff00.toInt(),
            0xff0000ff.toInt(), 0x00000000), 2, 2, Bitmap.Config.ARGB_8888)
        try {
            val formats = if (Build.VERSION.SDK_INT >= 30)
                listOf(Bitmap.CompressFormat.WEBP_LOSSY, Bitmap.CompressFormat.WEBP_LOSSLESS)
            else listOf(Bitmap.CompressFormat.WEBP)
            for (format in formats) {
                val stream = ByteArrayOutputStream()
                assertTrue(bitmap.compress(format, 75, stream))
                val original = stream.toByteArray().toByteString()
                for (little in listOf(true, false)) for (prefixed in listOf(true, false)) {
                    val source = decorate(original, little, prefixed)
                    val result = cleanWebpPhoto(source)
                    assertTrue("Generated codec layout must be supported", result is WebpPhotoCleaning.Cleaned)
                    result as WebpPhotoCleaning.Cleaned
                    assertEquals(6, result.exifOrientation)
                    assertFalse(result.bytes.utf8().contains("codec-private-location"))
                    assertArrayEquals(pixels(source), pixels(result.bytes))
                    val second = cleanWebpPhoto(result.bytes)
                    assertTrue(second is WebpPhotoCleaning.Cleaned)
                    assertEquals(result.bytes, (second as WebpPhotoCleaning.Cleaned).bytes)
                }
            }
        } finally { bitmap.recycle() }
    }

    private fun pixels(bytes: ByteString): IntArray {
        val array = bytes.toByteArray()
        val bitmap = checkNotNull(BitmapFactory.decodeByteArray(array, 0, array.size)) { "Fixture must decode" }
        try {
            assertEquals(2, bitmap.width); assertEquals(2, bitmap.height)
            return IntArray(4).also { bitmap.getPixels(it, 0, 2, 0, 0, 2, 2) }
        } finally { bitmap.recycle() }
    }

    private fun decorate(source: ByteString, little: Boolean, prefixed: Boolean): ByteString {
        // Keep every encoder-produced reconstruction/profile chunk; only add known metadata.
        var at = 12
        var header: ByteString? = null
        val image = Buffer()
        while (at < source.size) {
            val length = (source[at + 4].toInt() and 255) or ((source[at + 5].toInt() and 255) shl 8) or
                ((source[at + 6].toInt() and 255) shl 16) or ((source[at + 7].toInt() and 255) shl 24)
            val end = at + 8 + length + length % 2
            if (source.substring(at, at + 4) == "VP8X".encodeUtf8()) header = source.substring(at + 8, at + 8 + length)
            else image.write(source, at, end - at)
            at = end
        }
        val extended = if (header != null) Buffer().writeByte((header[0].toInt() and 255) or 12)
            .write(header, 1, 9).readByteString()
        else Buffer().writeByte(0x10 or 12).writeByte(0).writeShortLe(0)
            .writeByte(1).writeShortLe(0).writeByte(1).writeShortLe(0).readByteString()
        val payload = Buffer().writeUtf8("WEBP").write(chunk("VP8X", extended)).write(image.readByteString())
            .write(chunk("EXIF", exif(little, prefixed)))
            .write(chunk("XMP ", "codec-private-location".encodeUtf8())).readByteString()
        return Buffer().writeUtf8("RIFF").writeIntLe(payload.size).write(payload).readByteString()
    }
    private fun chunk(type: String, data: ByteString) = Buffer().writeUtf8(type).writeIntLe(data.size).write(data)
        .apply { if (data.size % 2 != 0) writeByte(0) }.readByteString()
    private fun exif(little: Boolean, prefixed: Boolean): ByteString {
        val b = Buffer()
        if (prefixed) b.writeUtf8("Exif\u0000\u0000")
        b.writeUtf8(if (little) "II" else "MM")
        fun short(value: Int) { if (little) b.writeShortLe(value) else b.writeShort(value) }
        fun long(value: Int) { if (little) b.writeIntLe(value) else b.writeInt(value) }
        short(42); long(8); short(2)
        short(0x0112); short(3); long(1); short(6); short(0)
        short(0x8825); short(4); long(1); long(38); long(0)
        short(2); short(1); short(2); long(2); b.writeByte('N'.code).writeByte(0).writeShort(0)
        short(2); short(5); long(3); long(68); long(0)
        for (value in listOf(51, 30, 0)) { long(value); long(1) }
        return b.writeUtf8("codec-private-location").readByteString()
    }
}

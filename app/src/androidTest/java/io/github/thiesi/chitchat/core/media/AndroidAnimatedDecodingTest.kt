package io.github.thiesi.chitchat.core.media

import android.graphics.drawable.AnimatedImageDrawable
import android.os.Build
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/** Received files F4a: the platform's animated decoding (API 28+) honours the screen size, the 16 Mpx guard and the lease. */
class AndroidAnimatedDecodingTest {
    @Before fun animatedDecodingExists() = assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)

    private fun le(value: Int) = byteArrayOf((value and 0xFF).toByte(), (value shr 8 and 0xFF).toByte())

    /** A minimal looping GIF89a: a [width]×[height] logical screen, two 1×1 frames, a two-colour global table. */
    private fun gif(width: Int = 2, height: Int = 2): ByteArray {
        val frame = byteArrayOf(0x21, 0xF9.toByte(), 0x04, 0x00, 0x0A, 0x00, 0x00, 0x00) + // Graphic control: 100 ms.
            byteArrayOf(0x2C, 0, 0, 0, 0) + le(1) + le(1) + byteArrayOf(0) + // Image descriptor: 1×1 at 0,0.
            byteArrayOf(0x02, 0x02, 0x44, 0x01, 0x00) // LZW: clear, index 0, end.
        val loop = byteArrayOf(0x21, 0xFF.toByte(), 0x0B) + "NETSCAPE2.0".toByteArray() + byteArrayOf(0x03, 0x01, 0, 0, 0)
        return "GIF89a".toByteArray() + le(width) + le(height) + byteArrayOf(0x80.toByte(), 0, 0) +
            byteArrayOf(0, 0, 0, -1, -1, -1) + loop + frame + frame + byteArrayOf(0x3B)
    }

    @Test fun aSmallGifDecodesToAnAnimationWithinTheTargetAndLeasesBytesPlusFrames() {
        val bytes = gif()
        val charges = mutableListOf<Long>()
        val result = AndroidAnimatedDecoding.decode(bytes, 1080, 1920) { charges += it; true }
        val animation = (result as AnimationResult.Decoded).animation
        assertTrue("An animated drawable", animation.drawable is AnimatedImageDrawable)
        assertTrue("${animation.width}x${animation.height}", animation.width in 1..2 && animation.height in 1..2)
        assertEquals("Leased once, before decoding: bytes plus two frame buffers",
            listOf(bytes.size + animation.width.toLong() * animation.height * 4 * 2), charges)
        animation.start(); animation.stop()
    }

    @Test fun beyondSixteenMegapixelsNothingIsDecodedOrLeased() {
        var leased = false
        assertEquals(AnimationResult.TooLarge, AndroidAnimatedDecoding.decode(gif(5000, 4000), 1080, 1920) { leased = true; true })
        assertFalse(leased)
    }

    @Test fun aRefusedLeaseIsNoMemoryAndUnreadableBytesAreUnreadable() {
        assertEquals(AnimationResult.NoMemory, AndroidAnimatedDecoding.decode(gif(), 1080, 1920) { false })
        assertEquals(AnimationResult.Unreadable, AndroidAnimatedDecoding.decode(ByteArray(64) { 7 }, 1080, 1920) { true })
    }
}

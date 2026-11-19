package io.github.thiesi.chitchat.core.media

import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.annotation.RequiresApi
import java.nio.ByteBuffer

/** The platform's animated decoding, or null below API 28, where a GIF shows still (PL, F4). */
internal fun platformAnimatedDecoding(): AnimatedDecoding? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) AndroidAnimatedDecoding else null

/**
 * `ImageDecoder` to an [AnimatedImageDrawable] at most the screen size (`setTargetSize`), within the 16 Mpx guard, read from
 * the header before anything is allocated. The lease covers the source bytes, which the drawable keeps for its whole life,
 * plus two frame buffers at the target size (TL, F4).
 */
@RequiresApi(Build.VERSION_CODES.P)
internal object AndroidAnimatedDecoding : AnimatedDecoding {
    private class Refused(val result: AnimationResult) : RuntimeException()

    override fun decode(bytes: ByteArray, maxWidth: Int, maxHeight: Int, lease: (Long) -> Boolean): AnimationResult {
        var width = 0
        var height = 0
        val drawable: Drawable = try {
            ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
                val sourceWidth = info.size.width
                val sourceHeight = info.size.height
                if (sourceWidth <= 0 || sourceHeight <= 0) throw Refused(AnimationResult.Unreadable)
                if (sourceWidth.toLong() * sourceHeight > AttachmentDownloads.DISPLAY_MAX_PIXELS) throw Refused(AnimationResult.TooLarge)
                val scale = minOf(1.0, maxWidth.coerceAtLeast(1).toDouble() / sourceWidth, maxHeight.coerceAtLeast(1).toDouble() / sourceHeight)
                width = (sourceWidth * scale).toInt().coerceAtLeast(1)
                height = (sourceHeight * scale).toInt().coerceAtLeast(1)
                if (!lease(bytes.size + width.toLong() * height * 4 * 2)) throw Refused(AnimationResult.NoMemory)
                decoder.setTargetSize(width, height)
            }
        } catch (refused: Refused) {
            return refused.result
        } catch (_: OutOfMemoryError) {
            return AnimationResult.NoMemory
        } catch (_: Exception) {
            return AnimationResult.Unreadable
        }
        val animation = drawable as? AnimatedImageDrawable
        val w = width; val h = height
        return AnimationResult.Decoded(object : DecodedAnimation {
            override val width = w
            override val height = h
            override val drawable: Any = drawable
            override fun start() { animation?.start() }
            override fun stop() { animation?.stop() }
            override fun toString() = "DecodedAnimation"
        })
    }
}

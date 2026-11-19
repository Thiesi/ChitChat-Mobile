package io.github.thiesi.chitchat.core.media

/** A transcript frame's fixed size in dp, decided from metadata before loading (files-media.md "In the transcript"). */
internal data class AttachmentFrameSize(val widthDp: Float, val heightDp: Float, val contain: Boolean)

internal const val FRAME_MAX_WIDTH_DP = 280f
internal const val FRAME_MIN_HEIGHT_DP = 96f
internal const val FRAME_MAX_HEIGHT_DP = 320f
internal const val FRAME_MIN_WIDTH_DP = 120f
internal const val FRAME_FALLBACK_WIDTH_DP = 240f
internal const val FRAME_FALLBACK_HEIGHT_DP = 180f

/**
 * With positive [width] and [height] (the image's oriented pixels) the frame takes the image's aspect ratio: the content
 * width, at most 280dp, never wider than the image at 1px per dp (no upscaling). Its height is clamped to 96–320dp; at
 * 320dp the width shrinks to keep the ratio, never below 120dp. Without them it's 240×180 (4:3). Whenever a clamp applies,
 * or there are no dimensions, the image is contain-fitted inside, so nothing is cropped. The size never changes when the
 * image loads, fails or decodes, so the transcript never jumps.
 */
internal fun attachmentFrameSize(width: Int?, height: Int?, contentWidthDp: Float): AttachmentFrameSize {
    val content = contentWidthDp.coerceAtLeast(1f)
    if (width == null || height == null || width <= 0 || height <= 0) {
        val scale = minOf(1f, content / FRAME_FALLBACK_WIDTH_DP)
        return AttachmentFrameSize(FRAME_FALLBACK_WIDTH_DP * scale, FRAME_FALLBACK_HEIGHT_DP * scale, contain = true)
    }
    val ratio = height.toFloat() / width
    val cap = minOf(content, FRAME_MAX_WIDTH_DP, width.toFloat())
    var frameWidth = cap
    var frameHeight = cap * ratio
    var contain = false
    if (frameHeight > FRAME_MAX_HEIGHT_DP) {
        frameHeight = FRAME_MAX_HEIGHT_DP
        frameWidth = FRAME_MAX_HEIGHT_DP / ratio
        val floor = minOf(FRAME_MIN_WIDTH_DP, cap)
        if (frameWidth < floor) { frameWidth = floor; contain = true }
    }
    if (frameHeight < FRAME_MIN_HEIGHT_DP) { frameHeight = FRAME_MIN_HEIGHT_DP; contain = true }
    return AttachmentFrameSize(frameWidth, frameHeight, contain)
}

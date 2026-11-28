package io.github.thiesi.chitchat.feature.auth

/** #390: what a tap on Create does with the in-flow consent line. */
internal sealed interface ConsentTap {
    /** Submit the registration. */
    data object Submit : ConsentTap
    /** The line fits but isn't fully visible: bring the block into view and announce (#275, unchanged). */
    data object BringFits : ConsentTap
    /** The line can't be shown with Create: scroll by [distance] so its top lands at the placement, announce, mark reviewed. */
    data class BringTop(val distance: Float) : ConsentTap
}

/**
 * #390 (owner's amended rule, 2026-11-28; PL placement): a tap on Create, all lengths in px in the screen viewport's own
 * layout coordinates.
 * - **Fits** (the line's height + [gap] + [button] at most the viewport's height): #275's rule, unchanged. The line wholly
 *   visible submits; otherwise the block is brought into view with the announcement.
 * - **Can't fit, not [reviewed]:** the line's top goes to [margin] below the viewport's top when its first text line
 *   ([firstLineBottom] from the line's top) is then whole inside the viewport, else flush with the top. Announce; no submit.
 * - **Can't fit, reviewed:** submit.
 */
internal fun consentTap(lineTop: Float, lineHeight: Float, firstLineBottom: Float, viewportHeight: Float, reviewed: Boolean,
    gap: Float, button: Float, margin: Float): ConsentTap {
    if (lineHeight + gap + button <= viewportHeight) {
        val visible = lineTop >= 0f && lineTop + lineHeight <= viewportHeight
        return if (visible) ConsentTap.Submit else ConsentTap.BringFits
    }
    if (reviewed) return ConsentTap.Submit
    val target = if (margin + firstLineBottom <= viewportHeight) margin else 0f
    return ConsentTap.BringTop(lineTop - target)
}

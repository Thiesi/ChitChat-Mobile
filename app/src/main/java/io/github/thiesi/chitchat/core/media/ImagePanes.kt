package io.github.thiesi.chitchat.core.media

/** What a pane drives: [AttachmentImages] in the app. */
internal interface ImageFrames {
    fun visible(ref: AttachmentRef, width: Int, height: Int)
    fun hidden(ref: AttachmentRef)
    fun show(ref: AttachmentRef)
    fun retry(ref: AttachmentRef)
    fun enteredConversation()
}

/**
 * The conversation screens' handles on the one app-scoped loader (received files F3; TL, #308). Main-confined.
 *
 * - **One pane per screen instance**, opened with that screen's owner token and its actual nav entry, the same rule as
 *   `leavePane(owner)`. Only the most recently opened pane is current: a late call from a retired screen is a no-op.
 * - **Visibility is the union over panes.** Each pane tracks the frames it marked visible; [Pane.release] (the screen
 *   stopped) un-marks only its own, so a late release from a retired pane never hides a frame its successor shows.
 * - **Hidden never cancels.** Un-marking only makes a held image evictable; a load already started finishes, so a
 *   rotation (release, then the new pane marks it visible) never downloads twice.
 * - **A new visit** is a pane for a different nav entry than the last one opened: each held preview may revalidate once
 *   more. A rotation reopens the same entry and isn't a new visit.
 */
internal class ImagePanes(private val frames: ImageFrames) {
    private data class FrameId(val direct: Boolean, val attachmentId: Long)
    private class Mark(val ref: AttachmentRef, val panes: MutableSet<Pane> = mutableSetOf())

    private val marks = HashMap<FrameId, Mark>()
    private var current: Pane? = null
    private var lastEntry: String? = null

    fun open(owner: Any, entry: String): Pane {
        val pane = Pane(owner, entry)
        current = pane
        if (entry != lastEntry) { lastEntry = entry; frames.enteredConversation() }
        return pane
    }

    inner class Pane internal constructor(val owner: Any, val entry: String) {
        private var released = false
        private val live get() = !released && current === this

        /** On screen at [width]×[height] px. Only the current pane marks frames. */
        fun visible(ref: AttachmentRef, width: Int, height: Int) {
            if (!live) return
            marks.getOrPut(ref.id()) { Mark(ref) }.panes += this
            frames.visible(ref, width, height)
        }

        /** Off screen (or no longer composed): un-marks this pane's own mark only. */
        fun hidden(ref: AttachmentRef) { unmark(ref.id()) }

        fun show(ref: AttachmentRef) { if (live) frames.show(ref) }
        fun retry(ref: AttachmentRef) { if (live) frames.retry(ref) }

        /** The screen stopped: everything it marked becomes evictable, unless another pane still shows it. */
        fun release() {
            if (released) return
            marks.entries.filter { this in it.value.panes }.map { it.key }.forEach(::unmark)
            released = true
            if (current === this) current = null
        }

        private fun unmark(id: FrameId) {
            val mark = marks[id] ?: return
            if (!mark.panes.remove(this)) return
            if (mark.panes.isEmpty()) { marks.remove(id); frames.hidden(mark.ref) }
        }

        override fun toString() = "ImagePane"
    }

    private fun AttachmentRef.id() = FrameId(direct, attachment.id)
}

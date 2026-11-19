package io.github.thiesi.chitchat.core.media

import io.github.thiesi.chitchat.core.conversation.MessageAttachment
import org.junit.Assert.*
import org.junit.Test

/** Received files F3a: the conversation screens' panes on the one app-scoped loader (TL, #308 and F3). */
class ImagePanesTest {
    private class Recorder : ImageFrames {
        val log = mutableListOf<String>()
        override fun visible(ref: AttachmentRef, width: Int, height: Int) { log += "visible:${ref.attachment.id}" }
        override fun hidden(ref: AttachmentRef) { log += "hidden:${ref.attachment.id}" }
        override fun show(ref: AttachmentRef) { log += "show:${ref.attachment.id}" }
        override fun retry(ref: AttachmentRef) { log += "retry:${ref.attachment.id}" }
        override fun enteredConversation() { log += "entered" }
    }
    private fun ref(id: Long) = AttachmentRef(MessageAttachment(id, "p.png", "image/png", 10, "0".repeat(64), true), direct = false)

    @Test fun aRetiredPaneIsANoOpAndItsLateReleaseNeverHidesTheSuccessorsFrames() {
        val frames = Recorder(); val panes = ImagePanes(frames)
        val old = panes.open(Any(), "entry-a")
        old.visible(ref(1), 100, 100)
        val next = panes.open(Any(), "entry-a") // A rotation: the same entry, a new screen instance.
        next.visible(ref(1), 100, 100)
        frames.log.clear()
        old.visible(ref(2), 100, 100); old.show(ref(1)); old.retry(ref(1))
        assertTrue("A retired pane drives nothing: ${frames.log}", frames.log.isEmpty())
        old.release()
        assertTrue("The successor still shows it: ${frames.log}", "hidden:1" !in frames.log)
        next.release()
        assertEquals(listOf("hidden:1"), frames.log)
    }

    @Test fun onlyANewEntryIsANewVisit() {
        val frames = Recorder(); val panes = ImagePanes(frames)
        panes.open(Any(), "room-a").release()
        panes.open(Any(), "room-a") // Rotation or back from the background: the same visit.
        panes.open(Any(), "room-b")
        panes.open(Any(), "room-a") // Back to A from B: a new visit.
        assertEquals(listOf("entered", "entered", "entered"), frames.log)
    }

    @Test fun hiddenUnmarksOnlyThatPanesOwnMarkAndAReleasedPaneStaysInert() {
        val frames = Recorder(); val panes = ImagePanes(frames)
        val pane = panes.open(Any(), "e")
        pane.visible(ref(1), 10, 10); pane.hidden(ref(1)); pane.hidden(ref(1))
        pane.release()
        pane.visible(ref(2), 10, 10); pane.show(ref(2)); pane.release()
        assertEquals(listOf("entered", "visible:1", "hidden:1"), frames.log)
    }

    @Test fun twoPanesOnOneFrameHideItOnlyWhenBothLetGo() {
        val frames = Recorder(); val panes = ImagePanes(frames)
        val first = panes.open(Any(), "e")
        first.visible(ref(1), 10, 10)
        val second = panes.open(Any(), "e")
        second.visible(ref(1), 10, 10)
        second.release()
        assertFalse("The first pane still marks it", "hidden:1" in frames.log)
        first.release()
        assertEquals(1, frames.log.count { it == "hidden:1" })
    }
}

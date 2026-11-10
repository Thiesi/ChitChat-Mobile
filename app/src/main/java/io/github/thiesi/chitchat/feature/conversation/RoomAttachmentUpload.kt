package io.github.thiesi.chitchat.feature.conversation

import io.github.thiesi.chitchat.core.conversation.ComposerReply
import io.github.thiesi.chitchat.core.media.RoomUploadReceipt
import io.github.thiesi.chitchat.core.media.UploadTransferFacts
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow

/** One explicit room send, main-confined and private. No payload, saved state or generated string.
 * The original slot/content/entry, not the current pane, owns every late outcome and callback.
 */
internal class RoomAttachmentUpload(
    val selection: RoomAttachmentSelection,
    val entryId: String,
    val content: Any,
    val caption: String,
    val reply: ComposerReply?,
    val sha256: String,
    val startedAtMillis: Long,
    isCurrentEntry: () -> Boolean,
) {
    var entryProbe: (() -> Boolean)? = isCurrentEntry
    var job: Job? = null
    var observer: Job? = null
    var facts: StateFlow<UploadTransferFacts>? = null
    var entered = false
    var cancelRequested = false
    var receipt: RoomUploadReceipt? = null
    override fun toString() = "RoomAttachmentUpload"
}

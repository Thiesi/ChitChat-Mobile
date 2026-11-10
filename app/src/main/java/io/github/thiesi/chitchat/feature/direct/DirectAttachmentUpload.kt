package io.github.thiesi.chitchat.feature.direct

import io.github.thiesi.chitchat.core.conversation.ComposerReply
import io.github.thiesi.chitchat.core.media.DirectUploadReceipt
import io.github.thiesi.chitchat.core.media.UploadTransferFacts
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow

/** One explicit private send; the original slot owns outcomes after navigation. No payload or saved state. */
internal class DirectAttachmentUpload(
    val selection: DirectAttachmentSelection,
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
    var receipt: DirectUploadReceipt? = null
    override fun toString() = "DirectAttachmentUpload"
}

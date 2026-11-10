package io.github.thiesi.chitchat.feature.direct

import io.github.thiesi.chitchat.core.conversation.ComposerReply
import io.github.thiesi.chitchat.core.media.AttachmentPhotoPrivacy
import io.github.thiesi.chitchat.core.media.PhotoPrivacyFailure
import io.github.thiesi.chitchat.core.media.PreparationFailureKind
import io.github.thiesi.chitchat.core.session.SessionState
import io.github.thiesi.chitchat.core.media.UploadTransferFacts
import io.github.thiesi.chitchat.core.network.ApiFailure

internal enum class DirectAttachmentPhase { PREPARING, READY, PRIVACY_CHOICE, FAILED, UPLOADING, FINISHING, CANCELLING, UNKNOWN, CONFIRMED }

/** Frozen private selection, never an upload request or generated private-data string. */
internal class DirectAttachmentSelection(private val owner: DirectIdentity, val peer: Long,
    val slot: Any, val sourceEntryId: String, val filename: String, val mimeType: String?) {
    fun matches(session: SessionState) = owner == session.directIdentity()
}

internal class DirectAttachmentDraft(val selection: DirectAttachmentSelection, val phase: DirectAttachmentPhase,
    val sizeBytes: Long? = null, val privacy: AttachmentPhotoPrivacy? = null,
    val privacyFailure: PhotoPrivacyFailure? = null, val failure: PreparationFailureKind? = null,
    val upload: DirectAttachmentUpload? = null, val transfer: UploadTransferFacts? = null,
    val uploadError: ApiFailure? = null, val policyBlocked: Boolean = false) {
    val filename: String get() = selection.filename
    val mimeType: String? get() = selection.mimeType
}

class DirectDraft internal constructor(private val owner: DirectIdentity,
    val peer: Long, val name: String?, val body: String, val uncertain: Boolean, val checked: Boolean,
    val composerReply: ComposerReply? = null, internal val attachment: DirectAttachmentDraft? = null,
    internal val slot: Any = Any(), internal val content: Any = Any()) {
    internal fun matches(session: SessionState) = owner == session.directIdentity()
    internal fun withAttachment(value: DirectAttachmentDraft) =
        DirectDraft(owner, peer, name, body, uncertain, checked, composerReply, value, slot, content)

    /** Preparation progress is not a new user draft. New text/reply/choice or slot is. */
    internal fun sameDiscardChoice(other: DirectDraft) = slot === other.slot && content === other.content && body == other.body &&
        uncertain == other.uncertain && checked == other.checked && composerReply === other.composerReply &&
        attachment?.selection === other.attachment?.selection && attachment?.upload === other.attachment?.upload
}

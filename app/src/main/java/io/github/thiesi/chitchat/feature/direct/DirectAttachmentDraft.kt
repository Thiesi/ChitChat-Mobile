package io.github.thiesi.chitchat.feature.direct

import io.github.thiesi.chitchat.core.conversation.ComposerReply
import io.github.thiesi.chitchat.core.media.AttachmentPhotoPrivacy
import io.github.thiesi.chitchat.core.media.PhotoPrivacyFailure
import io.github.thiesi.chitchat.core.media.PreparationFailureKind
import io.github.thiesi.chitchat.core.session.SessionState

internal enum class DirectAttachmentPhase { PREPARING, READY, PRIVACY_CHOICE, FAILED }

/** Frozen private selection, never an upload request or generated private-data string. */
internal class DirectAttachmentSelection(private val owner: DirectIdentity, val peer: Long,
    val slot: Any, val sourceEntryId: String, val filename: String, val mimeType: String?) {
    fun matches(session: SessionState) = owner == session.directIdentity()
}

internal class DirectAttachmentDraft(val selection: DirectAttachmentSelection, val phase: DirectAttachmentPhase,
    val sizeBytes: Long? = null, val privacy: AttachmentPhotoPrivacy? = null,
    val privacyFailure: PhotoPrivacyFailure? = null, val failure: PreparationFailureKind? = null) {
    val filename: String get() = selection.filename
    val mimeType: String? get() = selection.mimeType
}

class DirectDraft internal constructor(private val owner: DirectIdentity,
    val peer: Long, val name: String?, val body: String, val uncertain: Boolean, val checked: Boolean,
    val composerReply: ComposerReply? = null, internal val attachment: DirectAttachmentDraft? = null,
    internal val slot: Any = Any()) {
    internal fun matches(session: SessionState) = owner == session.directIdentity()
    internal fun withAttachment(value: DirectAttachmentDraft) =
        DirectDraft(owner, peer, name, body, uncertain, checked, composerReply, value, slot)

    /** Preparation progress is not a new user draft. New text/reply/choice or slot is. */
    internal fun sameDiscardChoice(other: DirectDraft) = slot === other.slot && body == other.body &&
        uncertain == other.uncertain && checked == other.checked && composerReply === other.composerReply &&
        attachment?.selection === other.attachment?.selection
}

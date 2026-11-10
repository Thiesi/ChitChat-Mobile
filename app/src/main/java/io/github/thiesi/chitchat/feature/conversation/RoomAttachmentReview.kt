package io.github.thiesi.chitchat.feature.conversation

import io.github.thiesi.chitchat.core.conversation.RoomMessage
import io.github.thiesi.chitchat.core.network.ApiFailure

enum class RoomAttachmentReviewPhase { CHECKING, EVIDENCE, DUPLICATE_RISK, FAILED }

/** Private, entry-bound results of one explicit GET. No generated content string or saved state. */
class RoomAttachmentReview internal constructor(
    internal val request: RoomAttachmentReviewRequest,
    val phase: RoomAttachmentReviewPhase,
    val evidence: List<RoomAttachmentEvidence> = emptyList(),
    val error: ApiFailure? = null,
) {
    override fun toString() = "RoomAttachmentReview"
}

/** A matching own message is evidence for a person, never automatic delivery resolution. */
class RoomAttachmentEvidence internal constructor(internal val original: RoomMessage) {
    val filename: String get() = checkNotNull(original.attachment).name
    val sentAt: String get() = original.createdAt
    override fun toString() = "RoomAttachmentEvidence"
}

internal class RoomAttachmentReviewRequest(val attempt: RoomAttachmentUpload, val entryId: String)

package io.github.thiesi.chitchat.feature.direct

import io.github.thiesi.chitchat.core.direct.DirectMessage
import io.github.thiesi.chitchat.core.network.ApiFailure

enum class DirectAttachmentReviewPhase { CHECKING, EVIDENCE, DUPLICATE_RISK, FAILED }

/** Private results of one explicit original-entry GET. Never saved or a generated content string. */
class DirectAttachmentReview internal constructor(
    internal val request: DirectAttachmentReviewRequest,
    val phase: DirectAttachmentReviewPhase,
    val evidence: List<DirectAttachmentEvidence> = emptyList(),
    val error: ApiFailure? = null,
) {
    override fun toString() = "DirectAttachmentReview"
}

/** A matching own DM is evidence for the person, never automatic delivery resolution. */
class DirectAttachmentEvidence internal constructor(internal val original: DirectMessage) {
    val filename: String get() = checkNotNull(original.attachment).name
    val sentAt: String get() = original.createdAt
    override fun toString() = "DirectAttachmentEvidence"
}

internal class DirectAttachmentReviewRequest(val attempt: DirectAttachmentUpload, val entryId: String)

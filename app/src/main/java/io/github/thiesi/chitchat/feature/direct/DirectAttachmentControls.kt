package io.github.thiesi.chitchat.feature.direct

import androidx.compose.runtime.Composable
import io.github.thiesi.chitchat.core.media.AttachmentPolicy
import io.github.thiesi.chitchat.core.media.AttachmentPolicyObservation
import io.github.thiesi.chitchat.core.session.SessionState
import io.github.thiesi.chitchat.core.ui.*

internal typealias DirectAttachmentControls = AttachmentControls<DirectAttachmentDraft>

internal fun DirectAttachmentDraft.cardFacts(observation: AttachmentPolicyObservation?, captionTooLong: Boolean) = AttachmentCardFacts(
    filename, mimeType, sizeBytes, when (phase) {
        DirectAttachmentPhase.PREPARING -> AttachmentCardPhase.PREPARING
        DirectAttachmentPhase.READY -> AttachmentCardPhase.READY
        DirectAttachmentPhase.PRIVACY_CHOICE -> AttachmentCardPhase.PRIVACY_CHOICE
        DirectAttachmentPhase.FAILED -> AttachmentCardPhase.FAILED
        DirectAttachmentPhase.UPLOADING -> AttachmentCardPhase.UPLOADING
        DirectAttachmentPhase.FINISHING -> AttachmentCardPhase.FINISHING
        DirectAttachmentPhase.CANCELLING -> AttachmentCardPhase.CANCELLING
        DirectAttachmentPhase.UNKNOWN -> AttachmentCardPhase.UNKNOWN
        DirectAttachmentPhase.CONFIRMED -> AttachmentCardPhase.CONFIRMED
    }, privacy, privacyFailure, failure, observation, sentBytes = transfer?.fileBytesBuffered,
    sendFailure = if (phase == DirectAttachmentPhase.READY) attachmentSendFailure(uploadError, policyBlocked) else null,
    captionTooLong = captionTooLong && phase == DirectAttachmentPhase.READY)

private val replaceablePhases = setOf(DirectAttachmentPhase.PREPARING, DirectAttachmentPhase.READY,
    DirectAttachmentPhase.PRIVACY_CHOICE, DirectAttachmentPhase.FAILED)

internal fun DirectAttachmentDraft.removable() = phase in replaceablePhases && upload == null

internal fun DirectAttachmentReviewPhase.shared() = when (this) {
    DirectAttachmentReviewPhase.CHECKING -> AttachmentReviewPhase.CHECKING
    DirectAttachmentReviewPhase.EVIDENCE -> AttachmentReviewPhase.EVIDENCE
    DirectAttachmentReviewPhase.DUPLICATE_RISK -> AttachmentReviewPhase.DUPLICATE_RISK
    DirectAttachmentReviewPhase.FAILED -> AttachmentReviewPhase.FAILED
}

/** DM binding of the shared host: admission only through the original-bound DM actor, which
 * also rechecks the current DM disclosure. The DM route has its own advertised rate limit.
 */
@Composable
internal fun DirectAttachmentHost(controls: DirectAttachmentControls, model: DirectThreadViewModel, session: SessionState,
    peer: Long, entryId: String, isCurrentEntry: () -> Boolean, interactive: () -> Boolean,
    settled: Boolean, waitingCanEnd: Boolean) = AttachmentPickerHost(controls, session.attachmentPolicy,
    (session.attachmentPolicy as? AttachmentPolicy.Known)?.rateLimits?.directMessage, interactive, settled, waitingCanEnd,
    admit = { model.selectAttachment(session, peer, entryId, it.read, it.source, it.original, isCurrentEntry) },
    stillReplaceable = { original -> model.visibleAttachment(session, peer, isCurrentEntry)?.let { it === original && it.removable() } == true })

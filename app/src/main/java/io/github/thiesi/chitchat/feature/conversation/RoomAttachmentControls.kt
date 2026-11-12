package io.github.thiesi.chitchat.feature.conversation

import androidx.compose.runtime.Composable
import io.github.thiesi.chitchat.core.media.AttachmentPolicy
import io.github.thiesi.chitchat.core.media.AttachmentPolicyObservation
import io.github.thiesi.chitchat.core.session.SessionState
import io.github.thiesi.chitchat.core.ui.*

internal typealias RoomAttachmentControls = AttachmentControls<RoomAttachmentDraft>

internal fun RoomAttachmentDraft.cardFacts(observation: AttachmentPolicyObservation?, captionTooLong: Boolean) = AttachmentCardFacts(
    filename, mimeType, sizeBytes, when (phase) {
        RoomAttachmentPhase.PREPARING -> AttachmentCardPhase.PREPARING
        RoomAttachmentPhase.READY -> AttachmentCardPhase.READY
        RoomAttachmentPhase.PRIVACY_CHOICE -> AttachmentCardPhase.PRIVACY_CHOICE
        RoomAttachmentPhase.FAILED -> AttachmentCardPhase.FAILED
        RoomAttachmentPhase.UPLOADING -> AttachmentCardPhase.UPLOADING
        RoomAttachmentPhase.FINISHING -> AttachmentCardPhase.FINISHING
        RoomAttachmentPhase.CANCELLING -> AttachmentCardPhase.CANCELLING
        RoomAttachmentPhase.UNKNOWN -> AttachmentCardPhase.UNKNOWN
        RoomAttachmentPhase.CONFIRMED -> AttachmentCardPhase.CONFIRMED
    }, privacy, privacyFailure, failure, observation, sentBytes = transfer?.fileBytesBuffered,
    sendFailure = if (phase == RoomAttachmentPhase.READY) attachmentSendFailure(uploadError, policyBlocked) else null,
    captionTooLong = captionTooLong && phase == RoomAttachmentPhase.READY)

private val replaceablePhases = setOf(RoomAttachmentPhase.PREPARING, RoomAttachmentPhase.READY,
    RoomAttachmentPhase.PRIVACY_CHOICE, RoomAttachmentPhase.FAILED)

/** READY may still carry a finished attempt (after a refusal or accepted duplicate risk); the VM
 * binds Remove/Replace to that exact attempt, so only the phase gates the control. */
internal fun RoomAttachmentDraft.removable() = phase in replaceablePhases

/** Room binding of the shared host: admission only through the original-bound room VM. */
@Composable
internal fun RoomAttachmentHost(controls: RoomAttachmentControls, model: ConversationViewModel, session: SessionState,
    roomId: Long, entryId: String, isCurrentEntry: () -> Boolean, interactive: () -> Boolean,
    settled: Boolean, waitingCanEnd: Boolean) = AttachmentPickerHost(controls, session.attachmentPolicy,
    (session.attachmentPolicy as? AttachmentPolicy.Known)?.rateLimits?.room, interactive, settled, waitingCanEnd,
    admit = { model.selectAttachment(session, roomId, entryId, it.read, it.source, it.original, isCurrentEntry) },
    stillReplaceable = { original -> model.visibleAttachment(session, roomId, isCurrentEntry)?.let { it === original && it.removable() } == true })

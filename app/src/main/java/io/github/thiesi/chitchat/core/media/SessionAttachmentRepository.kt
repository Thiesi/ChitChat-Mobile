package io.github.thiesi.chitchat.core.media

import io.github.thiesi.chitchat.core.session.SessionRepository
import io.github.thiesi.chitchat.core.session.SessionState

/** Explicit original-session write transport only; the future room/DM actor owns serialization,
 * pending/unknown delivery, privacy/write policy, reconciliation and every native callback.
 */
internal class SessionAttachmentRepository(private val sessions: SessionRepository) {
    suspend fun room(expected: SessionState, roomId: Long, upload: AttachmentUpload): RoomUploadReceipt {
        val access = sessions.openAccess(expected)
        return sessions.authorized(access) {
            checkPolicy(upload)
            uploadRoomAttachment(roomId, upload, access.csrf, access.userId)
        }
    }

    suspend fun direct(expected: SessionState, peerId: Long, upload: AttachmentUpload): DirectUploadReceipt {
        val access = sessions.openAccess(expected)
        return sessions.authorized(access) {
            checkPolicy(upload)
            uploadDirectAttachment(peerId, upload, access.csrf, access.userId)
        }
    }

    private fun checkPolicy(upload: AttachmentUpload) {
        val current = sessions.state.value
        check(current.user?.guest == false) { "Uploads unavailable" }
        when (observeAttachmentPolicy(current.attachmentPolicy, upload.sizeBytes, upload.mimeType)) {
            AttachmentPolicyObservation.Unadvertised, AttachmentPolicyObservation.WithinAdvertisedFacts -> Unit
            AttachmentPolicyObservation.Unavailable, AttachmentPolicyObservation.Invalid -> error("Uploads unavailable")
            AttachmentPolicyObservation.UploadsDisabled, is AttachmentPolicyObservation.TooLarge -> error("Upload exceeds policy")
            is AttachmentPolicyObservation.TypeNotAllowed -> error("Upload type outside policy")
            AttachmentPolicyObservation.AwaitingFinalBytes -> error("Upload not prepared")
        }
    }
}

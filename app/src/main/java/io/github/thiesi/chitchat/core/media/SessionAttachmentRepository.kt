package io.github.thiesi.chitchat.core.media

import io.github.thiesi.chitchat.core.session.SessionRepository
import io.github.thiesi.chitchat.core.session.SessionState

/** Explicit original-session write transport only; the future room/DM actor owns serialization,
 * pending/unknown delivery, privacy/write policy, reconciliation and every native callback.
 */
internal class SessionAttachmentRepository(private val sessions: SessionRepository) {
    suspend fun room(expected: SessionState, roomId: Long, retained: RetainedAttachmentPreparation,
        ready: AttachmentPreparation.Ready, caption: String, replyId: Long? = null): RoomUploadReceipt {
        return withRetained(retained, ready, caption, replyId) { room(expected, roomId, it) }
    }

    suspend fun direct(expected: SessionState, peerId: Long, retained: RetainedAttachmentPreparation,
        ready: AttachmentPreparation.Ready, caption: String, replyId: Long? = null): DirectUploadReceipt {
        return withRetained(retained, ready, caption, replyId) { direct(expected, peerId, it) }
    }

    private suspend fun <T> withRetained(retained: RetainedAttachmentPreparation, ready: AttachmentPreparation.Ready,
        caption: String, replyId: Long?, operation: suspend (AttachmentUpload) -> T): T {
        val allocation = checkNotNull(retained.holdUpload(ready)) { "Attachment preparation unavailable" }
        try {
            // Only the concrete original-session calls below use this helper. They do not return
            // on cancellation before the actual callback/writer ends. Even construction/precheck
            // failures close this hold; the private draft keeps its own hold unless retired.
            return operation(AttachmentUpload(allocation.payload, caption, replyId))
        } finally { allocation.close() }
    }

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

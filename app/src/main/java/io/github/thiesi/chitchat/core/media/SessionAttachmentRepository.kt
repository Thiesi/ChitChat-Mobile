package io.github.thiesi.chitchat.core.media

import io.github.thiesi.chitchat.core.session.SessionRepository
import io.github.thiesi.chitchat.core.session.SessionState
import io.github.thiesi.chitchat.core.direct.validatedDirectPrivacy
import kotlinx.coroutines.flow.StateFlow

/** Explicit original-session write transport only; the future room/DM actor owns serialization,
 * pending/unknown delivery, privacy/write policy, reconciliation and every native callback.
 */
internal class SessionAttachmentRepository(private val sessions: SessionRepository) : AttachmentUploadOperations {
    override suspend fun room(expected: SessionState, roomId: Long, retained: RetainedAttachmentPreparation,
        ready: AttachmentPreparation.Ready, caption: String, replyId: Long?,
        onTransfer: (StateFlow<UploadTransferFacts>) -> Unit): RoomUploadReceipt {
        return withRetained(retained, ready, caption, replyId, onTransfer) { room(expected, roomId, it) }
    }

    override suspend fun direct(expected: SessionState, peerId: Long, retained: RetainedAttachmentPreparation,
        ready: AttachmentPreparation.Ready, caption: String, replyId: Long?,
        onTransfer: (StateFlow<UploadTransferFacts>) -> Unit): DirectUploadReceipt {
        return withRetained(retained, ready, caption, replyId, onTransfer) { direct(expected, peerId, it) }
    }

    private suspend fun <T> withRetained(retained: RetainedAttachmentPreparation, ready: AttachmentPreparation.Ready,
        caption: String, replyId: Long?, onTransfer: (StateFlow<UploadTransferFacts>) -> Unit,
        operation: suspend (AttachmentUpload) -> T): T {
        val allocation = checkNotNull(retained.holdUpload(ready)) { "Attachment preparation unavailable" }
        try {
            // Only the concrete original-session calls below use this helper. They do not return
            // on cancellation before the actual callback/writer ends. Even construction/precheck
            // failures close this hold; the private draft keeps its own hold unless retired.
            val upload = AttachmentUpload(allocation.payload, caption, replyId)
            onTransfer(upload.transferFacts)
            return operation(upload)
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
            validatedDirectPrivacy(sessions.state.value.privacy)
            uploadDirectAttachment(peerId, upload, access.csrf, access.userId)
        }
    }

    private fun checkPolicy(upload: AttachmentUpload) {
        val current = sessions.state.value
        if (current.user?.guest != false) throw AttachmentUploadPolicyFailure(AttachmentPolicyObservation.Unavailable)
        when (val observation = observeAttachmentPolicy(current.attachmentPolicy, upload.sizeBytes, upload.mimeType)) {
            AttachmentPolicyObservation.Unadvertised, AttachmentPolicyObservation.WithinAdvertisedFacts -> Unit
            else -> throw AttachmentUploadPolicyFailure(observation)
        }
    }
}

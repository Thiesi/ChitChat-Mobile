package io.github.thiesi.chitchat.core.media

import io.github.thiesi.chitchat.core.session.SessionState
import kotlinx.coroutines.flow.StateFlow

/** Concrete retained transport seam for the serialized feature owners. The synchronous binding
 * exposes scalar facts only, never bytes/Call or authorization. Completion includes actual stop.
 */
internal interface AttachmentUploadOperations {
    suspend fun room(expected: SessionState, roomId: Long, retained: RetainedAttachmentPreparation,
        ready: AttachmentPreparation.Ready, caption: String, replyId: Long? = null,
        onTransfer: (StateFlow<UploadTransferFacts>) -> Unit = {}): RoomUploadReceipt
    suspend fun direct(expected: SessionState, peerId: Long, retained: RetainedAttachmentPreparation,
        ready: AttachmentPreparation.Ready, caption: String, replyId: Long? = null,
        onTransfer: (StateFlow<UploadTransferFacts>) -> Unit = {}): DirectUploadReceipt
}

/** Cached policy changed before POST; not a server refusal or permission to retry. */
internal class AttachmentUploadPolicyFailure(val observation: AttachmentPolicyObservation) :
    IllegalStateException("Upload policy unavailable")

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
        when (val policy = current.attachmentPolicy) {
            AttachmentPolicy.Unavailable, AttachmentPolicy.Invalid -> error("Uploads unavailable")
            AttachmentPolicy.Unknown -> Unit // No invented limit or MIME allowlist.
            is AttachmentPolicy.Known -> {
                check(policy.maxBytes > 0 && upload.sizeBytes <= policy.maxBytes) { "Upload exceeds policy" }
                val mime = upload.mimeType?.lowercase(java.util.Locale.ROOT)
                check(mime == null || mime == "application/octet-stream" || mime in policy.allowedMimeTypes) {
                    "Upload type outside policy"
                }
            }
        }
    }
}

package io.github.thiesi.chitchat.feature.conversation

import io.github.thiesi.chitchat.core.media.AttachmentPhotoPrivacy
import io.github.thiesi.chitchat.core.media.PhotoPrivacyFailure
import io.github.thiesi.chitchat.core.media.PreparationFailureKind
import io.github.thiesi.chitchat.core.session.SessionState

internal enum class RoomAttachmentPhase { PREPARING, READY, PRIVACY_CHOICE, FAILED }

/** Stable original selection/slot identity; no generated private-data string or saved state. */
internal class RoomAttachmentSelection(
    private val owner: RoomDraftAccount,
    val roomId: Long,
    val slot: Any,
    val sourceEntryId: String,
    val filename: String,
    val mimeType: String?,
) {
    fun matches(session: SessionState) = owner == session.roomDraftAccount()
}

/** Private draft facts only, never payload bytes, an upload request or Send-anyway consent.
 * Reading this from a saved draft is not active-pane adoption. Visible consumers/actions must
 * use a fresh actual-entry check, even when this completed in the original background slot.
 */
internal class RoomAttachmentDraft(
    val selection: RoomAttachmentSelection,
    val phase: RoomAttachmentPhase,
    val sizeBytes: Long? = null,
    val privacy: AttachmentPhotoPrivacy? = null,
    val privacyFailure: PhotoPrivacyFailure? = null,
    val failure: PreparationFailureKind? = null,
) {
    val filename: String get() = selection.filename
    val mimeType: String? get() = selection.mimeType
}

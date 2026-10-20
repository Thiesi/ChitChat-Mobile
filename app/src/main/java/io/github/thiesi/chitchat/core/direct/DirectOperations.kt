package io.github.thiesi.chitchat.core.direct

import io.github.thiesi.chitchat.core.conversation.*
import io.github.thiesi.chitchat.core.session.SessionState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable class DirectPrivacy(
    @SerialName("end_to_end_encrypted") val endToEndEncrypted: Boolean,
    @SerialName("admin_inspection_enabled") val inspectionEnabled: Boolean,
    @SerialName("admin_inspection_role") val inspectionRole: String,
    val retention: String,
    @SerialName("retention_days") val retentionDays: Int,
)
@Serializable class RevisionPrivacy(
    @SerialName("admin_review_enabled") val reviewEnabled: Boolean,
    @SerialName("admin_review_role") val reviewRole: String,
    @SerialName("reason_required") val reasonRequired: Boolean,
    @SerialName("audit_each_review") val auditEachReview: Boolean,
    @SerialName("participant_notification") val participantNotification: Boolean,
)
@Serializable class MessagePrivacy(
    @SerialName("direct_messages") val direct: DirectPrivacy,
    @SerialName("message_revisions") val revisions: RevisionPrivacy,
)
@Serializable class DirectRelationship(
    @SerialName("blocked_by_me") val blockedByMe: Boolean,
    @SerialName("messaging_available") val messagingAvailable: Boolean,
)
// DM wire mentions have no room broadcast field; quoted targets have no room type field.
@Serializable class DirectMention(@SerialName("user_id") val userId: Long, val username: String)
@Serializable class DirectReply(val kind: String, @SerialName("message_id") val messageId: Long,
    val available: Boolean, val message: DirectReplyTarget?)
@Serializable class DirectReplyTarget(val id: Long, val sender: MessagePerson, val body: String, val deleted: Boolean)

/** Canonical enriched client model, not a claimed server response schema or generated content string. */
class DirectMessage(val id: Long, val sender: MessagePerson, val recipient: MessagePerson,
    val body: String?, val readAt: String?, val createdAt: String, val outgoing: Boolean,
    val deleted: Boolean, val editedAt: String?, val canEdit: Boolean, val canDelete: Boolean,
    val replyTo: DirectReply?, val mentions: List<DirectMention>, val reactions: List<MessageReaction>,
    val attachment: MessageAttachment?)
class DirectPreview(val id: Long, val body: String?, val createdAt: String, val outgoing: Boolean,
    val deleted: Boolean, val editedAt: String?, val attachment: MessageAttachment?)
class DirectConversation(val peer: MessagePerson, val lastMessage: DirectPreview, val unreadCount: Int)
class DirectPage(val messages: List<DirectMessage>, val olderCursor: Long?, val mayHaveOlder: Boolean)
class DirectContext(val peer: MessagePerson, val relationship: DirectRelationship, val privacy: MessagePrivacy,
    val display: DisplayPreferences, val mutedEverywhere: RoomMute?)
class DirectInbox(val conversations: List<DirectConversation>, val privacy: MessagePrivacy, val display: DisplayPreferences)

/** One existing PHP session; no separate credentials, retry transport or conversation cache. */
interface DirectOperations {
    val session: StateFlow<SessionState>
    suspend fun inbox(): DirectInbox
    suspend fun context(peerId: Long): DirectContext
    suspend fun search(prefix: String, limit: Int = 20): List<MessagePerson>
    suspend fun history(peerId: Long, beforeId: Long? = null, limit: Int = 50): DirectPage
    suspend fun window(peerId: Long, lower: Long, upper: Long? = null): DirectWindow =
        readCanonicalDirectWindow(peerId, checkNotNull(session.value.user).id, lower, upper) { before, limit ->
            history(peerId, before, limit)
        }
    /** Confirmed POST ID only: a later enrichment GET failure must not turn a known send into unknown delivery. */
    suspend fun send(peerId: Long, body: String, replyId: Long? = null): Long
    /** Existing whole-peer acknowledgment, NOT a rendered through-ID cursor. Caller must refetch afterward. */
    suspend fun read(peerId: Long): Int
    suspend fun setBlocked(peerId: Long, blocked: Boolean): DirectRelationship
}

fun validateDirectBody(input: String): String? {
    val body = input.phpTrim()
    return when {
        body.isEmpty() -> "empty_message"
        body.codePointCount(0, body.length) > 4_000 -> "message_too_long"
        else -> null // Slash commands are plain DM text, unlike the room composer.
    }
}
fun validateDirectSearch(input: String): Boolean = input.phpTrim().matches(Regex("[A-Za-z0-9_.-]{2,32}"))

package io.github.thiesi.chitchat.core.network

import io.github.thiesi.chitchat.core.conversation.*
import io.github.thiesi.chitchat.core.direct.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable internal class DirectUsersReply(val users: List<MessagePerson>)
@Serializable internal class DirectConversationsReply(val conversations: List<WireDirectConversation>)
@Serializable internal class WireDirectConversation(val user: MessagePerson,
    @SerialName("last_message") val last: WireDirectPreview, @SerialName("unread_count") val unreadCount: Int)
@Serializable internal class WireDirectPreview(val id: Long, @SerialName("sender_id") val senderId: Long,
    val body: String, @SerialName("created_at") val createdAt: String, val outgoing: Boolean)
@Serializable internal class DirectHistoryReply(val messages: List<WireDirectMessage>)
@Serializable internal class DirectSendReply(val message: WireDirectMessage)
@Serializable internal class WireDirectMessage(val id: Long, val sender: MessagePerson, val recipient: MessagePerson,
    val body: String, @SerialName("read_at") val readAt: String?, @SerialName("created_at") val createdAt: String,
    val outgoing: Boolean, @SerialName("reply_to") val replyTo: DirectReply?,
    val mentions: List<DirectMention>, val reactions: List<MessageReaction>)
@Serializable internal class DirectStatusReply(val user: MessagePerson, val relationship: DirectRelationship)
@Serializable internal class DirectRelationshipReply(val relationship: DirectRelationship)
@Serializable internal class DirectPeerRequest(@SerialName("user_id") val userId: Long)
@Serializable internal class DirectSendRequest(@SerialName("recipient_user_id") val peerId: Long, val body: String,
    @SerialName("reply_to_message_id") val replyId: Long?)
@Serializable internal class DirectReadReply(val updated: Int)
@Serializable internal class DirectMutationsReply(val messages: List<DirectMutation>)
@Serializable internal class DirectMutation(val id: Long, val body: String?,
    @SerialName("edited_at") val editedAt: String?, @Serializable(with = StrictJsonBoolean::class) val deleted: Boolean,
    @SerialName("can_edit") @Serializable(with = StrictJsonBoolean::class) val canEdit: Boolean,
    @SerialName("can_delete") @Serializable(with = StrictJsonBoolean::class) val canDelete: Boolean,
    val mentions: List<DirectMention>, val reactions: List<MessageReaction>)
@Serializable internal class DirectAttachmentsReply(val attachments: List<DirectAttachment>)
@Serializable internal class DirectAttachment(val id: Long, @SerialName("message_id") val messageId: Long,
    val name: String, @SerialName("mime_type") val mimeType: String, @SerialName("size_bytes") val sizeBytes: Long,
    val sha256: String, val previewable: Boolean) {
    fun model() = MessageAttachment(id, name, mimeType, sizeBytes, sha256, previewable)
}

internal fun requireDirectPeer(peerId: Long, viewer: Long) {
    if (peerId <= 0) throw ApiFailure(FailureKind.HTTP, 400, "validation_error")
    if (peerId == viewer) throw ApiFailure(FailureKind.HTTP, 400, "direct_message_self_forbidden")
}
internal fun requireDirectPerson(person: MessagePerson) = incompatibleUnless(person.id > 0 && !person.username.isNullOrBlank())
internal fun requireDirectRelationship(value: DirectRelationship) = incompatibleUnless(!value.blockedByMe || !value.messagingAvailable)
internal fun requireDirectWire(message: WireDirectMessage, viewer: Long, peer: Long) {
    requireDirectPerson(message.sender); requireDirectPerson(message.recipient)
    incompatibleUnless(message.id > 0 && setOf(message.sender.id, message.recipient.id) == setOf(viewer, peer) &&
        message.outgoing == (message.sender.id == viewer) && serverInstant(message.createdAt) != null &&
        (message.readAt == null || serverInstant(message.readAt) != null))
    message.replyTo?.let { reply ->
        incompatibleUnless(reply.kind == "direct" && reply.messageId > 0 && reply.available == (reply.message != null))
        reply.message?.let { target -> requireDirectPerson(target.sender)
            incompatibleUnless(target.id == reply.messageId && target.sender.id in setOf(viewer, peer)) }
    }
    incompatibleUnless(message.mentions.all { it.userId == message.recipient.id })
    requireValidAttention(emptyList(), message.reactions)
}
internal fun requireDirectMutation(value: DirectMutation, owned: Boolean, recipient: Long) {
    incompatibleUnless(value.id > 0 && (value.deleted || value.body != null) &&
        (value.editedAt == null || serverInstant(value.editedAt) != null) &&
        (!value.canEdit || owned && !value.deleted) && (!value.canDelete || owned && !value.deleted) &&
        value.mentions.all { it.userId == recipient })
    requireValidAttention(emptyList(), value.reactions)
}

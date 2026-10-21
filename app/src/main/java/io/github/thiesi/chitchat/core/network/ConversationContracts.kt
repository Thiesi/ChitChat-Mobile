package io.github.thiesi.chitchat.core.network

import io.github.thiesi.chitchat.core.conversation.*
import io.github.thiesi.chitchat.core.mutations.AuthorMessageActions
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable internal class MessagesReply(val messages: List<RoomMessage>)
@Serializable internal class PingsReply(val pings: List<RoomPing>)
@Serializable internal class SendReply(val message: RoomMessage? = null, val ping: RoomPing? = null)
@Serializable internal class SendRequest(@SerialName("room_id") val roomId: Long, val body: String,
    @SerialName("reply_to_message_id") val replyId: Long? = null)
@Serializable internal class ReadRequest(@SerialName("room_id") val roomId: Long,
    @SerialName("message_id") val messageId: Long)
@Serializable internal class ReadReply(@SerialName("last_read_message_id") val messageId: Long)
@Serializable internal class MutationsReply(val messages: List<MessageMutation>)
@Serializable internal class MessageMutation(val id: Long, val body: String?, val type: String,
    @SerialName("edited_at") val editedAt: String?, @Serializable(with = StrictJsonBoolean::class) val deleted: Boolean,
    @SerialName("deletion_kind") val deletionKind: String?,
    val mentions: List<MessageMention>, val reactions: List<MessageReaction>,
    @SerialName("can_edit") @Serializable(with = StrictJsonBoolean::class) val canEdit: Boolean? = null,
    @SerialName("can_delete") @Serializable(with = StrictJsonBoolean::class) val canDelete: Boolean? = null)

internal fun incompatibleUnless(condition: Boolean) { if (!condition) throw ApiFailure(FailureKind.INCOMPATIBLE) }

internal fun requireValidMessage(value: RoomMessage, roomId: Long) {
    incompatibleUnless(value.id > 0 && value.roomId == roomId && (value.senderId == null || value.senderId > 0) &&
        value.type in setOf("text", "emote", "system", "attachment") && serverInstant(value.createdAt) != null &&
        (value.deleted || value.body != null))
    value.attachment?.let { incompatibleUnless(it.id > 0 && it.sizeBytes >= 0) }
    value.replyTo?.let { reply ->
        incompatibleUnless(reply.kind == "room" && reply.messageId > 0 && (reply.available == (reply.message != null)))
        reply.message?.let { incompatibleUnless(it.id == reply.messageId && (it.sender == null || it.sender.id > 0)) }
    }
    requireValidAttention(value.mentions, value.reactions)
}

internal fun requireValidAttention(mentions: List<MessageMention>, reactions: List<MessageReaction>) {
    incompatibleUnless(mentions.all { it.userId > 0 } && reactions.all { reaction ->
        reaction.emoji.isNotEmpty() && reaction.users.all { it.id > 0 }
    })
}

internal fun requireValidPing(value: RoomPing, roomId: Long, viewerId: Long) {
    incompatibleUnless(value.id > 0 && value.roomId == roomId && value.sender.id > 0 && value.target.id > 0 &&
        viewerId in setOf(value.sender.id, value.target.id) && serverInstant(value.createdAt) != null)
}

internal fun requireValidMute(value: RoomMute, roomId: Long?) {
    incompatibleUnless(value.id > 0 && value.scope in setOf("room", "everywhere") &&
        (if (value.scope == "everywhere") value.roomId == null else value.roomId != null && value.roomId > 0) &&
        (roomId == null && value.scope == "everywhere" || roomId != null && (value.roomId == null || value.roomId == roomId)) &&
        (value.expiresAt == null || serverInstant(value.expiresAt) != null))
}

internal fun applyMutation(message: RoomMessage, mutation: MessageMutation): RoomMessage {
    requireValidAttention(mutation.mentions, mutation.reactions)
    incompatibleUnless(mutation.type in setOf("text", "emote", "system", "attachment") &&
        (mutation.deleted || mutation.body != null) && mutation.deletionKind in setOf(null, "author", "moderator") &&
        (mutation.editedAt == null || serverInstant(mutation.editedAt) != null) &&
        (!mutation.deleted || mutation.canEdit != true && mutation.canDelete != true))
    return RoomMessage(message.id, message.roomId, message.senderId, message.username, mutation.type,
        if (mutation.deleted) null else mutation.body, mutation.deleted, message.createdAt,
        if (mutation.deleted) null else message.attachment, if (mutation.deleted) null else message.replyTo,
        if (mutation.deleted) emptyList() else mutation.mentions, mutation.reactions, mutation.editedAt, mutation.deletionKind,
        if (mutation.canEdit != null && mutation.canDelete != null) AuthorMessageActions(mutation.canEdit, mutation.canDelete) else null)
}

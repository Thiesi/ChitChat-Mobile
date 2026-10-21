package io.github.thiesi.chitchat.core.network

import io.github.thiesi.chitchat.core.conversation.RoomMessage
import io.github.thiesi.chitchat.core.direct.DirectMessage
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable internal class EditMessageRequest(@SerialName("message_id") val messageId: Long, val body: String)
@Serializable internal class DeleteMessageRequest(@SerialName("message_id") val messageId: Long)
@Serializable internal class RoomMutationReply(val message: MessageMutation)
@Serializable internal class DirectMutationReply(val message: DirectMutation)

internal fun requireOwnRoomMessage(message: RoomMessage, viewer: Long) {
    require(viewer > 0 && message.id > 0 && message.roomId > 0 && message.senderId == viewer &&
        !message.deleted && message.body != null && message.type in setOf("text", "emote", "attachment"))
}
internal fun requireOwnDirectMessage(message: DirectMessage, viewer: Long) {
    require(viewer > 0 && message.id > 0 && message.sender.id == viewer && message.outgoing &&
        message.recipient.id > 0 && message.recipient.id != viewer && !message.deleted && message.body != null)
}

/** Preserve canonical identity and original quote/file only while the returned metadata is active. */
internal fun applyDirectMutation(message: DirectMessage, mutation: DirectMutation): DirectMessage {
    incompatibleUnless(message.id == mutation.id)
    requireDirectMutation(mutation, message.outgoing, message.recipient.id)
    return DirectMessage(message.id, message.sender, message.recipient, if (mutation.deleted) null else mutation.body,
        message.readAt, message.createdAt, message.outgoing, mutation.deleted, mutation.editedAt,
        mutation.canEdit, mutation.canDelete, if (mutation.deleted) null else message.replyTo,
        if (mutation.deleted) emptyList() else mutation.mentions, mutation.reactions,
        if (mutation.deleted) null else message.attachment)
}

package io.github.thiesi.chitchat.core.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Decode only acknowledgment identity. Optional display/enrichment cannot hide a committed POST.
@Serializable internal class RoomUploadReply(val message: RoomUploadAck)
@Serializable internal class RoomUploadAck(val id: Long, @SerialName("room_id") val roomId: Long,
    @SerialName("sender_id") val senderId: Long, val type: String,
    @Serializable(with = StrictJsonBoolean::class) val deleted: Boolean,
    @SerialName("reply_to") val replyTo: UploadReplyPointer? = null)
@Serializable internal class DirectUploadReply(val message: DirectUploadAck)
@Serializable internal class DirectUploadAck(val id: Long, val sender: UploadPerson, val recipient: UploadPerson,
    @Serializable(with = StrictJsonBoolean::class) val outgoing: Boolean,
    @SerialName("reply_to") val replyTo: UploadReplyPointer? = null)
@Serializable internal class UploadPerson(val id: Long)
@Serializable internal class UploadReplyPointer(val kind: String, @SerialName("message_id") val messageId: Long)

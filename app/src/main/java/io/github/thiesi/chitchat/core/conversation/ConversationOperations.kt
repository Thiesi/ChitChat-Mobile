package io.github.thiesi.chitchat.core.conversation

import io.github.thiesi.chitchat.core.rooms.RoomInfo
import io.github.thiesi.chitchat.core.session.SessionState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.OffsetDateTime

// Private conversation content has no generated string representation or saved/disk state.
@Serializable class RoomMessage(
    val id: Long,
    @SerialName("room_id") val roomId: Long,
    @SerialName("sender_id") val senderId: Long?,
    val username: String?, val type: String, val body: String?, val deleted: Boolean,
    @SerialName("created_at") val createdAt: String,
    val attachment: MessageAttachment? = null,
    @SerialName("reply_to") val replyTo: MessageReply? = null,
    val mentions: List<MessageMention> = emptyList(), val reactions: List<MessageReaction> = emptyList(),
    @SerialName("edited_at") val editedAt: String? = null,
    @SerialName("deletion_kind") val deletionKind: String? = null,
)
@Serializable class MessageAttachment(val id: Long, val name: String,
    @SerialName("mime_type") val mimeType: String, @SerialName("size_bytes") val sizeBytes: Long,
    val sha256: String, val previewable: Boolean)
@Serializable class MessageReply(val kind: String, @SerialName("message_id") val messageId: Long,
    val available: Boolean, val message: ReplyMessage?)
@Serializable class ReplyMessage(val id: Long, val sender: MessagePerson?, val type: String,
    val body: String?, val deleted: Boolean)
@Serializable class MessagePerson(val id: Long, val username: String?)
@Serializable class MessageMention(@SerialName("user_id") val userId: Long, val username: String, val broadcast: Boolean)
@Serializable class MessageReaction(val emoji: String, val users: List<MessagePerson>,
    @SerialName("reacted_by_me") val reactedByMe: Boolean)
@Serializable class RoomPing(val id: Long, @SerialName("room_id") val roomId: Long,
    val sender: MessagePerson, val target: MessagePerson, val message: String,
    @SerialName("created_at") val createdAt: String)
@Serializable class RoomMute(val id: Long, val scope: String,
    @SerialName("room_id") val roomId: Long?, @SerialName("expires_at") val expiresAt: String?, val reason: String)
@Serializable class DisplayPreferences(@SerialName("date_locale") val dateLocale: String? = null,
    @SerialName("hour_cycle") val hourCycle: String? = null)
class ChatPolicy(val ignoredUserIds: Set<Long>, val mutedEverywhere: RoomMute?, val display: DisplayPreferences)
class ConversationContext(val room: RoomInfo, val policy: ChatPolicy) {
    val mute get() = policy.mutedEverywhere ?: room.muted
}
class MessagePage(val messages: List<RoomMessage>, val olderCursor: Long?, val mayHaveOlder: Boolean)
sealed interface SendResult {
    class Message(val message: RoomMessage) : SendResult
    class Ping(val ping: RoomPing) : SendResult
}

/** All methods reuse the existing session capability. History is oldest-to-newest, exclusive before-ID. */
interface ConversationOperations {
    val session: StateFlow<SessionState>
    suspend fun context(roomId: Long): ConversationContext
    suspend fun history(roomId: Long, beforeId: Long? = null, limit: Int = 50): MessagePage
    suspend fun window(roomId: Long, lowerInclusive: Long, upperInclusive: Long? = null): WindowSnapshot =
        readCanonicalRoomWindow(roomId, lowerInclusive, upperInclusive) { before, limit -> history(roomId, before, limit) }
    suspend fun pings(roomId: Long): List<RoomPing>
    suspend fun send(roomId: Long, body: String, replyId: Long? = null): SendResult
    suspend fun read(roomId: Long, messageId: Long): Long
}

internal fun String.phpTrim() = trim { it in " \t\n\r\u0000\u000B" }
internal fun isPrivatePingCommand(input: String) = input.phpTrim().let { it == "/ping" || it.startsWith("/ping ") }

/** PDO history timestamps use a space and may end in +00; pings use DATE_ATOM. */
fun serverInstant(value: String): Instant? = runCatching {
    OffsetDateTime.parse(value.replace(' ', 'T').replace(Regex("([+-]\\d{2})$"), "$1:00")).toInstant()
}.getOrNull()

/** Only the two server commands are interpreted here; other client commands belong to M5. */
fun validateMessageBody(input: String): String? {
    val body = input.phpTrim()
    if (body.isEmpty()) return "empty_message"
    if (isPrivatePingCommand(body)) {
        val arguments = body.substring(5).phpTrim()
        if (arguments.isEmpty()) return "invalid_ping_command"
        val message = arguments.split(Regex("\\s+"), limit = 2).getOrElse(1) { "" }.phpTrim()
        return if (message.codePointCount(0, message.length) > 500) "ping_too_long" else null
    }
    val text = if (body == "/me" || body.startsWith("/me ")) body.substring(3).phpTrim()
        else if (body.startsWith('/')) return "unknown_command" else body
    return when {
        text.isEmpty() -> "empty_message"
        text.codePointCount(0, text.length) > 4000 -> "message_too_long"
        else -> null
    }
}

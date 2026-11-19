package io.github.thiesi.chitchat.core.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import io.github.thiesi.chitchat.core.conversation.ChatPolicy
import io.github.thiesi.chitchat.core.conversation.ConversationContext
import io.github.thiesi.chitchat.core.conversation.ConversationOperations
import io.github.thiesi.chitchat.core.conversation.DisplayPreferences
import io.github.thiesi.chitchat.core.conversation.MessageAttachment
import io.github.thiesi.chitchat.core.conversation.MessagePage
import io.github.thiesi.chitchat.core.conversation.MessagePerson
import io.github.thiesi.chitchat.core.conversation.MessageReply
import io.github.thiesi.chitchat.core.conversation.RoomMessage
import io.github.thiesi.chitchat.core.conversation.RoomPing
import io.github.thiesi.chitchat.core.conversation.SendResult
import io.github.thiesi.chitchat.core.direct.DirectContext
import io.github.thiesi.chitchat.core.direct.DirectInbox
import io.github.thiesi.chitchat.core.direct.DirectMessage
import io.github.thiesi.chitchat.core.direct.DirectOperations
import io.github.thiesi.chitchat.core.direct.DirectPage
import io.github.thiesi.chitchat.core.direct.DirectPrivacy
import io.github.thiesi.chitchat.core.direct.DirectRelationship
import io.github.thiesi.chitchat.core.direct.MessagePrivacy
import io.github.thiesi.chitchat.core.direct.RevisionPrivacy
import io.github.thiesi.chitchat.core.media.AttachmentMemoryBudget
import io.github.thiesi.chitchat.core.media.AttachmentRef
import io.github.thiesi.chitchat.core.media.AttachmentSource
import io.github.thiesi.chitchat.core.media.AttachmentVariant
import io.github.thiesi.chitchat.core.media.DownloadResult
import io.github.thiesi.chitchat.core.media.NetworkConditions
import io.github.thiesi.chitchat.core.media.NetworkState
import io.github.thiesi.chitchat.core.network.SessionUser
import io.github.thiesi.chitchat.core.rooms.RoomInfo
import io.github.thiesi.chitchat.core.server.AddressResult
import io.github.thiesi.chitchat.core.server.ServerAddress
import io.github.thiesi.chitchat.core.session.SessionPhase
import io.github.thiesi.chitchat.core.session.SessionState
import java.io.ByteArrayOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Shared received-files fixtures (F3): no network, real PNG bytes, the real decoder. */
internal object ReceivedFiles {
    private val server = (ServerAddress.parse("chat.example.test") as AddressResult.Valid).address
    fun session() = MutableStateFlow(SessionState(server, 1, SessionPhase.AUTHENTICATED,
        user = SessionUser(7, "Alice", listOf("member"), 1, false)))

    /** A two-tone image (left warm, right cool), so orientation and contain-fit are visible in captures. */
    fun png(width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(0xFF336699.toInt())
            drawRect(0f, 0f, width / 2f, height.toFloat(), Paint().apply { color = 0xFFF0A050.toInt() })
        }
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            .also { bitmap.recycle() }
    }

    fun photo(id: Long, size: Long = 3_565_158, width: Int? = 4000, height: Int? = 3000, mime: String = "image/jpeg") =
        MessageAttachment(id, "photo.jpg", mime, size, "0".repeat(64), previewable = true, width = width, height = height)

    fun file(id: Long, name: String, mime: String = "application/pdf", size: Long = 2_516_582) =
        MessageAttachment(id, name, mime, size, "0".repeat(64), previewable = false)

    fun roomMessage(id: Long, body: String?, attachment: MessageAttachment? = null, reply: MessageReply? = null) =
        RoomMessage(id, 42, 8, "Bob", "text", body, false, "2026-10-20 12:00:00+00", attachment, reply)

    fun dm(id: Long, body: String?, attachment: MessageAttachment?, outgoing: Boolean = false) = DirectMessage(id,
        MessagePerson(if (outgoing) 7 else 8, if (outgoing) "Alice" else "Peer"), MessagePerson(if (outgoing) 8 else 7, if (outgoing) "Peer" else "Alice"),
        body, "2026-10-20T10:01:00Z", "2026-10-20T10:00:00Z", outgoing, false, null, outgoing, outgoing,
        null, emptyList(), emptyList(), attachment)
}

/** Answers in order per attachment ID; otherwise [bytes] for that ID (a small real PNG by default) on [budget]. */
internal class ReceivedSource(private val budget: AttachmentMemoryBudget = AttachmentMemoryBudget(64L * 1024 * 1024)) : AttachmentSource {
    val calls = CopyOnWriteArrayList<Long>()
    val replies = HashMap<Long, ArrayDeque<suspend () -> DownloadResult>>()
    var bytes: (Long) -> ByteArray = { ReceivedFiles.png(400, 300) }
    fun reply(id: Long, result: suspend () -> DownloadResult) { replies.getOrPut(id) { ArrayDeque() } += result }
    override suspend fun load(ref: AttachmentRef, variant: AttachmentVariant, expected: SessionState, ifNoneMatch: String?): DownloadResult {
        calls += ref.attachment.id
        replies[ref.attachment.id]?.removeFirstOrNull()?.let { return it() }
        val data = bytes(ref.attachment.id)
        return DownloadResult.Bytes(data, null, budget.reserve(data.size.toLong())!!)
    }
}

internal class FixedNetwork(online: Boolean = true, metered: Boolean = false) : NetworkConditions {
    override val state = MutableStateFlow(NetworkState(online, metered))
}

internal class ReceivedRooms(override val session: StateFlow<SessionState>) : ConversationOperations {
    @Volatile var messages = emptyList<RoomMessage>()
    @Volatile var ignored = emptySet<Long>()
    override suspend fun context(roomId: Long) = ConversationContext(RoomInfo(42, "lounge", "Lounge", "", "public", 0, 0, 7,
        "member", false, "none", 1, 2, null), ChatPolicy(ignored, null, DisplayPreferences()))
    override suspend fun history(roomId: Long, beforeId: Long?, limit: Int) = MessagePage(messages, messages.firstOrNull()?.id, false)
    override suspend fun pings(roomId: Long) = emptyList<RoomPing>()
    override suspend fun send(roomId: Long, body: String, replyId: Long?): SendResult = error("No sends in this fixture")
    override suspend fun read(roomId: Long, messageId: Long) = messageId
}

internal class ReceivedDirects(override val session: StateFlow<SessionState>) : DirectOperations {
    private val privacy = MessagePrivacy(DirectPrivacy(false, false, "admin", "for up to 30 days", 30),
        RevisionPrivacy(false, "super_admin", true, true, false))
    @Volatile var rows = emptyList<DirectMessage>()
    @Volatile var blocked = false
    override suspend fun context(peerId: Long) =
        DirectContext(MessagePerson(peerId, "Peer"), DirectRelationship(blocked, !blocked), privacy, DisplayPreferences(), null)
    override suspend fun inbox() = DirectInbox(emptyList(), privacy, DisplayPreferences())
    override suspend fun search(prefix: String, limit: Int) = listOf(MessagePerson(8, "Peer"))
    override suspend fun history(peerId: Long, beforeId: Long?, limit: Int) = DirectPage(rows, rows.firstOrNull()?.id, false)
    override suspend fun send(peerId: Long, body: String, replyId: Long?): Long = error("No sends in this fixture")
    override suspend fun read(peerId: Long) = 0
    override suspend fun setBlocked(peerId: Long, blocked: Boolean) = DirectRelationship(blocked, !blocked)
}

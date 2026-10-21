package io.github.thiesi.chitchat.feature.notifications

import io.github.thiesi.chitchat.core.conversation.*
import io.github.thiesi.chitchat.core.direct.*
import io.github.thiesi.chitchat.core.network.*
import io.github.thiesi.chitchat.core.notifications.*
import io.github.thiesi.chitchat.core.rooms.RoomInfo
import io.github.thiesi.chitchat.core.targets.*
import io.github.thiesi.chitchat.feature.auth.TestSessions
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Typed native-journey fixtures only. No live HTTP, push, credentials or production fallback. */
internal class NotificationRoutingFixtures(val sessions: TestSessions) {
    val policy = ChatPolicy(emptySet(), null, DisplayPreferences())
    val privacy = MessagePrivacy(DirectPrivacy(false, false, "admin", "permanently", 0),
        RevisionPrivacy(false, "super_admin", true, true, false))
    private val timestamp = "2026-10-21T10:00:00Z"
    val rooms = Rooms()
    val directs = Directs()
    val notices = Notices()
    val lookups = Lookups()

    inner class Rooms : ConversationOperations {
        override val session = sessions.state
        val reads = mutableListOf<Long>()
        val histories = mutableListOf<Triple<Long, Long?, Int>>()
        var sends = 0
        var sendFailure: ApiFailure? = null
        override suspend fun context(roomId: Long) = ConversationContext(RoomInfo(roomId, "room$roomId", "Room $roomId",
            "", "private", 0, 0, 7, "member", false, "none", 100, 0), checkNotNull(session.value.chatPolicy))
        private fun rows(room: Long) = (1L..100L).map {
            RoomMessage(it, room, 8, "Peer", "text", "Room $room message $it", false, timestamp)
        }
        override suspend fun history(roomId: Long, beforeId: Long?, limit: Int): MessagePage {
            histories += Triple(roomId, beforeId, limit)
            val rows = rows(roomId).filter { beforeId == null || it.id < beforeId }.takeLast(limit)
            return MessagePage(rows, rows.firstOrNull()?.id, rows.size == limit)
        }
        override suspend fun window(roomId: Long, lowerInclusive: Long, upperInclusive: Long?) = WindowSnapshot(
            rows(roomId).filter { it.id >= lowerInclusive && (upperInclusive == null || it.id <= upperInclusive) },
            true, lowerInclusive, lowerInclusive > 1)
        override suspend fun pings(roomId: Long) = (100L downTo 1L).map {
            RoomPing(it, roomId, MessagePerson(if (it == 80L) 8 else 9, "Peer"), MessagePerson(7, "Viewer"), "Private ping $it", timestamp)
        }
        override suspend fun send(roomId: Long, body: String, replyId: Long?): SendResult {
            sends++; sendFailure?.let { throw it }
            return SendResult.Message(RoomMessage(101, roomId, 7, "Viewer", "text", body, false, timestamp))
        }
        override suspend fun read(roomId: Long, messageId: Long): Long { reads += roomId; return messageId }
    }

    inner class Directs : DirectOperations {
        override val session = sessions.state
        val reads = mutableListOf<Long>()
        private val readPeers = mutableSetOf<Long>()
        var sends = 0
        var sendFailure: ApiFailure? = null
        override suspend fun context(peerId: Long) = DirectContext(MessagePerson(peerId, "Peer $peerId"),
            DirectRelationship(false, true), privacy, policy.display, null)
        private fun rows(peer: Long) = (1L..100L).map { DirectMessage(it, MessagePerson(peer, "Peer $peer"),
            MessagePerson(7, "Viewer"), "Direct $peer message $it", timestamp.takeIf { peer in readPeers }, timestamp, false, false,
            null, false, false, null, emptyList(), emptyList(), null) }
        override suspend fun history(peerId: Long, beforeId: Long?, limit: Int): DirectPage {
            val rows = rows(peerId).filter { beforeId == null || it.id < beforeId }.takeLast(limit)
            return DirectPage(rows, rows.firstOrNull()?.id, rows.size == limit)
        }
        override suspend fun window(peerId: Long, lower: Long, upper: Long?) = DirectWindow(
            rows(peerId).filter { it.id >= lower && (upper == null || it.id <= upper) }, true, lower, lower > 1)
        override suspend fun inbox() = DirectInbox(emptyList(), privacy, policy.display)
        override suspend fun search(prefix: String, limit: Int) = emptyList<MessagePerson>()
        override suspend fun send(peerId: Long, body: String, replyId: Long?): Long { sends++; sendFailure?.let { throw it }; return 101 }
        override suspend fun read(peerId: Long): Int { reads += peerId; return if (readPeers.add(peerId)) 100 else 0 }
        override suspend fun setBlocked(peerId: Long, blocked: Boolean) = DirectRelationship(blocked, !blocked)
    }

    inner class Notices : NotificationOperations {
        override val session = sessions.state
        private val readIds = mutableSetOf<Long>()
        var posts = 0
        var cancelled = 0
        var postGate: CompletableDeferred<Unit>? = null
        val rows = listOf(
            notice(60, "/?room_id=43&message_id=20"), notice(59, "/?room_id=42&message_id=2"), notice(58, "/?room_id=42"),
            notice(57, "/messages.php?user_id=9&message_id=20"), notice(56, "/messages.php?user_id=8&message_id=18"),
            notice(55, "/messages.php?user_id=8"), notice(54, "/?room_id=43&ping_id=80"),
            notice(53, "/?room_id=43&ping_id=999"), notice(52, "/account.php"), notice(51, "https://foreign.example.test/account.php"),
            notice(50, "/?register=1"), notice(49, "/messages.php?user_id=7"), notice(48, "/unknown.php"))
        private fun notice(id: Long, link: String) = AccountNotification(id, "mentioned", "Notice $id", "Private fixture notice $id",
            emptyList(), link, false, null, timestamp)
        override suspend fun page(expected: io.github.thiesi.chitchat.core.session.SessionState, beforeId: Long?, limit: Int): NotificationPage {
            check(expected.noticeIdentity() == session.value.noticeIdentity())
            val visible = if (expected.user?.id == 7L) rows else emptyList()
            val page = visible.filter { beforeId == null || it.id < beforeId }.take(limit).map {
                if (it.id in readIds) AccountNotification(it.id, it.kind, it.title, it.message, it.details, it.link, true, timestamp, it.createdAt) else it
            }
            return NotificationPage(page, visible.count { it.id !in readIds }, page.lastOrNull()?.id, page.size == limit)
        }
        override suspend fun markRead(expected: io.github.thiesi.chitchat.core.session.SessionState, ids: List<Long>): NotificationReadResult {
            posts++
            try { postGate?.await() } catch (failure: kotlinx.coroutines.CancellationException) { cancelled++; throw failure }
            readIds.addAll(ids); return NotificationReadResult(ids.size, rows.count { it.id !in readIds })
        }
        override suspend fun markAllRead(expected: io.github.thiesi.chitchat.core.session.SessionState) = markRead(expected, rows.map { it.id })
    }

    inner class Lookups : MessageTargetOperations {
        override val session = sessions.state
        val calls = java.util.concurrent.CopyOnWriteArrayList<TargetRequest>()
        val gates = mutableMapOf<MessageTarget, CompletableDeferred<Unit>>()
        var found = true
        var failure: ApiFailure? = null
        var ignoreCancellation = false
        override suspend fun lookup(request: TargetRequest): TargetSnapshot {
            calls += request
            failure?.let { throw it }
            val result = when (val target = request.target) {
                is MessageTarget.RoomMessage -> TargetSnapshot.RoomMessage(request, rooms.context(target.roomId),
                    if (found) rooms.history(target.roomId, beforeTarget(target.id)) else MessagePage(emptyList(), null, false), rooms.pings(target.roomId))
                is MessageTarget.RoomPing -> TargetSnapshot.RoomPing(request, rooms.context(target.roomId), rooms.pings(target.roomId))
                is MessageTarget.DirectMessage -> TargetSnapshot.DirectMessage(request, directs.context(target.peerId),
                    if (found) directs.history(target.peerId, beforeTarget(target.id)) else DirectPage(emptyList(), null, false))
            }
            // Deliberately permit stale snapshots after cancellation to exercise the real actor's fences.
            gates[request.target]?.let { if (ignoreCancellation) withContext(NonCancellable) { it.await() } else it.await() }
            return result
        }
    }
}

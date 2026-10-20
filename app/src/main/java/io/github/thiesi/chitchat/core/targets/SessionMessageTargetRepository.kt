package io.github.thiesi.chitchat.core.targets

import io.github.thiesi.chitchat.core.conversation.ConversationContext
import io.github.thiesi.chitchat.core.direct.DirectContext
import io.github.thiesi.chitchat.core.direct.validatedDirectPrivacy
import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.network.FailureKind
import io.github.thiesi.chitchat.core.network.requireDirectPeer
import io.github.thiesi.chitchat.core.session.SessionRepository
import kotlinx.coroutines.CancellationException

/** One original session across ordinary bootstrap/context/history/metadata, no read/presence/write. */
internal class SessionMessageTargetRepository(private val sessions: SessionRepository) : MessageTargetOperations {
    override val session = sessions.state
    override suspend fun lookup(request: TargetRequest): TargetSnapshot {
        if (!request.matches(session.value)) throw CancellationException("Target session retired")
        val viewer = checkNotNull(request.original.user).id
        if (!request.target.valid(viewer)) throw ApiFailure(FailureKind.HTTP, 400,
            if (request.target is MessageTarget.DirectMessage && request.target.peerId == viewer) "direct_message_self_forbidden" else "validation_error")
        val access = sessions.refreshedAccess(request.original)
        policy() // Unknown is not an empty ignore/mute policy; fail before fetching conversation content.
        val snapshot = sessions.authorized(access) {
            when (val target = request.target) {
                is MessageTarget.RoomMessage -> {
                    val room = rooms().firstOrNull { it.id == target.roomId } ?: room(target.roomId)
                    sessions.ensureCurrent(access)
                    if (room.id != target.roomId) throw ApiFailure(FailureKind.INCOMPATIBLE)
                    val page = roomHistory(target.roomId, beforeTarget(target.id), 50)
                    sessions.ensureCurrent(access)
                    val pings = roomPings(target.roomId, access.userId)
                    sessions.ensureCurrent(access)
                    TargetSnapshot.RoomMessage(request, ConversationContext(room, policy()), page, pings)
                }
                is MessageTarget.RoomPing -> {
                    val room = rooms().firstOrNull { it.id == target.roomId } ?: room(target.roomId)
                    sessions.ensureCurrent(access)
                    if (room.id != target.roomId) throw ApiFailure(FailureKind.INCOMPATIBLE)
                    val pings = roomPings(target.roomId, access.userId)
                    sessions.ensureCurrent(access)
                    TargetSnapshot.RoomPing(request, ConversationContext(room, policy()), pings)
                }
                is MessageTarget.DirectMessage -> {
                    requireDirectPeer(target.peerId, viewer)
                    validatedDirectPrivacy(session.value.privacy)
                    val (peer, relationship) = directStatus(target.peerId, access.userId)
                    sessions.ensureCurrent(access)
                    val page = directHistory(target.peerId, beforeTarget(target.id), 50, access.userId)
                    sessions.ensureCurrent(access)
                    val policy = policy()
                    TargetSnapshot.DirectMessage(request, DirectContext(peer, relationship,
                        validatedDirectPrivacy(session.value.privacy), policy.display, policy.mutedEverywhere), page)
                }
            }
        }
        sessions.ensureCurrent(access)
        // authorized() also awaits vault persistence. Never restore policy captured before that last await.
        return snapshot.withCurrentPolicy(session.value) ?: throw ApiFailure(FailureKind.INCOMPATIBLE)
    }
    private fun policy() = session.value.chatPolicy ?: throw ApiFailure(FailureKind.INCOMPATIBLE)
}

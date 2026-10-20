package io.github.thiesi.chitchat.core.targets

import io.github.thiesi.chitchat.core.conversation.*
import io.github.thiesi.chitchat.core.conversation.RoomPing as ConversationPing
import io.github.thiesi.chitchat.core.direct.DirectContext
import io.github.thiesi.chitchat.core.direct.DirectPage
import io.github.thiesi.chitchat.core.direct.validatedDirectPrivacy
import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.session.SessionPhase
import io.github.thiesi.chitchat.core.session.SessionState
import kotlinx.coroutines.flow.StateFlow

/** Exact targets shared by notifications, search and replies; never arbitrary URLs. */
sealed interface MessageTarget {
    val id: Long
    data class RoomMessage(val roomId: Long, override val id: Long) : MessageTarget
    data class RoomPing(val roomId: Long, override val id: Long) : MessageTarget
    data class DirectMessage(val peerId: Long, override val id: Long) : MessageTarget
}
internal fun MessageTarget.valid(viewer: Long?) = id > 0 && when (this) {
    is MessageTarget.RoomMessage -> roomId > 0
    is MessageTarget.RoomPing -> roomId > 0
    is MessageTarget.DirectMessage -> peerId > 0 && peerId != viewer
}
internal fun beforeTarget(id: Long): Long? {
    require(id > 0)
    return id.takeUnless { it == Long.MAX_VALUE }?.plus(1)
}

/** Original UI intent/reference; no generated account-name or private-content string. */
class TargetRequest internal constructor(internal val original: SessionState, val target: MessageTarget) {
    private val owner = original.targetOwner()
    fun matches(current: SessionState) = owner != null && owner == current.targetOwner()
}
internal data class TargetOwner(val server: String?, val generation: Long, val user: Long, val version: Int)
internal fun SessionState.targetOwner() = user?.takeIf { phase == SessionPhase.AUTHENTICATED && !it.guest }
    ?.let { TargetOwner(server?.value, generation, it.id, it.sessionVersion) }

/** GET evidence, not a chat viewport/read proof. Consumers must recheck scope/current policy. */
sealed class TargetSnapshot(val request: TargetRequest) {
    abstract val available: Boolean
    fun matches(current: SessionState) = request.matches(current)
    class RoomMessage internal constructor(request: TargetRequest, val context: ConversationContext,
        val page: MessagePage, val pings: List<ConversationPing>) : TargetSnapshot(request) {
        override val available = (request.target as? MessageTarget.RoomMessage)?.let { target ->
            context.room.id == target.roomId && page.messages.any { it.id == target.id && it.roomId == target.roomId }
        } == true
    }
    class RoomPing internal constructor(request: TargetRequest, val context: ConversationContext,
        val pings: List<ConversationPing>) : TargetSnapshot(request) {
        override val available = (request.target as? MessageTarget.RoomPing)?.let { target ->
            context.room.id == target.roomId && pings.any { it.id == target.id && it.roomId == target.roomId &&
                request.original.user?.id in setOf(it.sender.id, it.target.id) }
        } == true
    }
    class DirectMessage internal constructor(request: TargetRequest, val context: DirectContext,
        val page: DirectPage) : TargetSnapshot(request) {
        override val available = (request.target as? MessageTarget.DirectMessage)?.let { target ->
            val viewer = request.original.user?.id
            context.peer.id == target.peerId && page.messages.any { it.id == target.id &&
                setOf(it.sender.id, it.recipient.id) == setOf(viewer, target.peerId) && it.outgoing == (it.sender.id == viewer) }
        } == true
    }
}

/** Re-read policy after every final await AND before adoption; room ignores must never become DM blocks. */
internal fun TargetSnapshot.withCurrentPolicy(current: SessionState): TargetSnapshot? {
    if (!matches(current)) return null
    val policy = current.chatPolicy ?: return null
    return when (this) {
        is TargetSnapshot.RoomMessage -> TargetSnapshot.RoomMessage(request, ConversationContext(context.room, policy),
            page, pings.filterNot { it.sender.id in policy.ignoredUserIds })
        is TargetSnapshot.RoomPing -> TargetSnapshot.RoomPing(request, ConversationContext(context.room, policy),
            pings.filterNot { it.sender.id in policy.ignoredUserIds })
        is TargetSnapshot.DirectMessage -> {
            val disclosure = try { validatedDirectPrivacy(current.privacy) } catch (_: ApiFailure) { return null }
            TargetSnapshot.DirectMessage(request, DirectContext(context.peer, context.relationship, disclosure,
                policy.display, policy.mutedEverywhere), page)
        }
    }
}
interface MessageTargetOperations {
    val session: StateFlow<SessionState>
    suspend fun lookup(request: TargetRequest): TargetSnapshot
}

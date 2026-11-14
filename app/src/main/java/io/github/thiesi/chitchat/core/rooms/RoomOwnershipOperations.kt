package io.github.thiesi.chitchat.core.rooms

import kotlinx.coroutines.flow.StateFlow

/** A room member the snapshot names, with only what the transfer flow shows: no joins, connections or invitations. */
class RoomTransferMember(val id: Long, val username: String, val role: String)

/**
 * The transfer facts of `admin/rooms/snapshot.php` (v3.10.0, #302) for one room, held in memory only.
 * [candidates] are the members whose row has `can_transfer_ownership` true. The flags are hints: they mean "allowed now,
 * after step-up", may be stale, and for viewers who can't see bans they ignore bans. The transfer's response decides.
 */
class RoomTransferSnapshot(val roomId: Long, val owner: RoomTransferMember?, val candidates: List<RoomTransferMember>)

/**
 * Room ownership transfer (ChitChat#273, flags #302). Protocol only; the native entry follows separately.
 * [transfer] needs active privileged step-up: a `403 step_up_required` comes back as that ApiFailure for the caller's
 * `withStepUp`. Every other refusal is typed by its code, and nothing is ever resent.
 */
interface RoomOwnershipOperations {
    val session: StateFlow<io.github.thiesi.chitchat.core.session.SessionState>
    /** One GET. Allowed for the room's owner and global room managers; anyone else gets `403 forbidden`. */
    suspend fun transferSnapshot(roomId: Long): RoomTransferSnapshot
    /** One POST, never replayed. Returns the room as the actor now sees it. */
    suspend fun transferOwnership(roomId: Long, targetUserId: Long): RoomInfo
}

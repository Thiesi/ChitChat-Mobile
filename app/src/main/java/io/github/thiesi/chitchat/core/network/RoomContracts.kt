package io.github.thiesi.chitchat.core.network

import io.github.thiesi.chitchat.core.rooms.RoomInfo
import io.github.thiesi.chitchat.core.rooms.RoomTransferMember
import io.github.thiesi.chitchat.core.rooms.RoomTransferSnapshot
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable internal class RoomsReply(val rooms: List<RoomInfo>)
@Serializable internal class RoomReply(val room: RoomInfo)
@Serializable internal class RoomIdRequest(@SerialName("room_id") val roomId: Long)
@Serializable internal class RoomLeftReply(val status: String)

// admin/rooms/snapshot.php, decoded as a subset: invitations, joins and connection counts are never read or kept.
@Serializable internal class TransferSnapshotRoom(val id: Long)
@Serializable internal class TransferSnapshotMember(val id: Long, val username: String, val role: String,
    @SerialName("can_transfer_ownership") @Serializable(with = StrictJsonBoolean::class) val canTransferOwnership: Boolean? = null)
@Serializable internal class TransferSnapshotReply(val room: TransferSnapshotRoom, val members: List<TransferSnapshotMember>)
@Serializable internal class TransferOwnershipRequest(@SerialName("room_id") val roomId: Long,
    @SerialName("target_user_id") val targetUserId: Long)

internal fun TransferSnapshotReply.transferSnapshot(expectedRoom: Long): RoomTransferSnapshot {
    incompatibleUnless(room.id == expectedRoom && members.map { it.id }.distinct().size == members.size &&
        members.all { it.id > 0 && it.username.isNotBlank() && it.role in setOf("owner", "moderator", "member") })
    val owners = members.filter { it.role == "owner" }
    // At most one owner (the server enforces it), and the owner's own row is never offered.
    incompatibleUnless(owners.size <= 1 && owners.none { it.canTransferOwnership == true })
    fun TransferSnapshotMember.member() = RoomTransferMember(id, username, role)
    // A missing flag is "not offered", never guessed (presentation lead, P-217).
    return RoomTransferSnapshot(room.id, owners.singleOrNull()?.member(),
        members.filter { it.canTransferOwnership == true }.map { it.member() })
}

internal fun requireValidRoom(room: RoomInfo, expectedId: Long? = null) {
    // Displayed metadata is not a new creation request. Imported/existing strings need not
    // satisfy today's input limits; authorization and effective policy remain server-owned.
    if (room.id <= 0 || (expectedId != null && room.id != expectedId) || room.createdBy <= 0 ||
        room.visibility !in setOf("public", "unlisted", "private") ||
        room.guestAccess !in setOf("none", "read", "write") ||
        room.minimumAge < 0 || room.inactivityTimeoutSeconds < 0 ||
        room.memberRole !in setOf(null, "member", "moderator", "owner") || room.unreadCount < 0 ||
        (room.lastReadMessageId != null && room.lastReadMessageId < 0)) throw ApiFailure(FailureKind.INCOMPATIBLE)
    room.muted?.let { requireValidMute(it, room.id) }
}

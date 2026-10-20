package io.github.thiesi.chitchat.core.realtime

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One connection's server-confirmed lease, separate from persistent room membership. */
@Serializable
class PresenceStatus(
    @SerialName("room_id") val roomId: Long?,
    @SerialName("idle_seconds") val idleSeconds: Int,
    @SerialName("warning_seconds") val warningSeconds: Int?,
    val expired: Boolean,
    @SerialName("lease_seconds") val leaseSeconds: Int,
)

/** Server aggregation across this person's currently eligible connections in the room. */
@Serializable
class PresenceUser(
    val id: Long,
    val username: String,
    val guest: Boolean,
    @SerialName("idle_seconds") val idleSeconds: Int,
    val connections: Int,
)

package io.github.thiesi.chitchat.core.profile

import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.network.FailureKind
import io.github.thiesi.chitchat.core.session.SessionState
import kotlinx.coroutines.flow.StateFlow

/** Immutable target; optional room is server moderation context, never membership permission. */
class ProfileRequest(val userId: Long, val roomId: Long? = null)

/** Read-only server facts. A guest's memberSince is visit start, not account tenure. */
class BasicProfile(val id: Long, val username: String, val memberSince: String, val badge: String?,
    val guest: Boolean, val hasAvatar: Boolean, val avatarVersion: Long?, val canRemoveAvatar: Boolean)

/** No image URL/bytes or mutation capability. Removal hints/availability never authorize an action. */
class ProfileSnapshot(val profile: BasicProfile, val roomId: Long?, val avatarsAvailable: Boolean)

/** One explicit member GET through the original session; no cache, policy mutation or replay. */
interface ProfileOperations {
    val session: StateFlow<SessionState>
    suspend fun load(expected: SessionState, request: ProfileRequest): ProfileSnapshot
}

internal fun validateProfileTarget(userId: Long, roomId: Long?) {
    if (userId <= 0 || roomId != null && roomId <= 0)
        throw ApiFailure(FailureKind.HTTP, 400, "validation_error")
}

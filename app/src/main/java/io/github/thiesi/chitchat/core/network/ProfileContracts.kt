package io.github.thiesi.chitchat.core.network

import io.github.thiesi.chitchat.core.conversation.serverInstant
import io.github.thiesi.chitchat.core.profile.BasicProfile
import io.github.thiesi.chitchat.core.profile.ProfileSnapshot
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable internal class ProfileFactsReply(val id: Long, val username: String,
    @SerialName("member_since") val memberSince: String, val badge: String?,
    @Serializable(with = StrictJsonBoolean::class) val guest: Boolean,
    @SerialName("has_avatar") @Serializable(with = StrictJsonBoolean::class) val hasAvatar: Boolean,
    @SerialName("avatar_version") val avatarVersion: Long?,
    @SerialName("can_remove_avatar") @Serializable(with = StrictJsonBoolean::class) val canRemoveAvatar: Boolean)

@Serializable internal class ProfileReply(val profile: ProfileFactsReply,
    @SerialName("avatars_available") @Serializable(with = StrictJsonBoolean::class) val avatarsAvailable: Boolean)

internal fun ProfileReply.snapshot(userId: Long, roomId: Long?): ProfileSnapshot {
    incompatibleUnless(profile.id > 0 && profile.id == userId && profile.username.isNotBlank() &&
        serverInstant(profile.memberSince) != null && profile.hasAvatar == (profile.avatarVersion != null) &&
        (!profile.canRemoveAvatar || profile.hasAvatar))
    // version0 is a legitimate server fallback. Availability and removal hints are independent facts.
    return ProfileSnapshot(BasicProfile(profile.id, profile.username, profile.memberSince, profile.badge,
        profile.guest, profile.hasAvatar, profile.avatarVersion, profile.canRemoveAvatar), roomId, avatarsAvailable)
}

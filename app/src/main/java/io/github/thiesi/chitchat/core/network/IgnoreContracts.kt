package io.github.thiesi.chitchat.core.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal class IgnoreRequest(@SerialName("user_id") val userId: Long, val ignored: Boolean)

@Serializable
internal class IgnoreReply(@SerialName("ignored_user_ids") val ignoredUserIds: List<Long>)

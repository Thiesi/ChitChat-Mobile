package io.github.thiesi.chitchat.core.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal class TypingPreferenceRequest(@SerialName("share_typing") val shared: Boolean)

@Serializable
internal class TypingPreferenceReply(@SerialName("share_typing")
    @Serializable(with = StrictJsonBoolean::class) val shared: Boolean)

@Serializable
internal class RoomTypingRequest(@SerialName("room_id") val roomId: Long)

@Serializable
internal class DirectTypingRequest(@SerialName("recipient_user_id") val recipient: Long)

@Serializable
internal class TypingSignalReply(@Serializable(with = StrictJsonBoolean::class) val signalled: Boolean)

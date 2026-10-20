package io.github.thiesi.chitchat.feature.conversation

import io.github.thiesi.chitchat.core.session.SessionPhase
import io.github.thiesi.chitchat.core.session.SessionState

/** Unsent input, never server message storage or saved-instance state. No generated content string. */
class RoomDraft internal constructor(private val owner: RoomDraftAccount, val roomId: Long,
    val roomName: String?, val body: String, val uncertain: Boolean) {
    internal fun matches(session: SessionState) = owner == session.roomDraftAccount()
}

internal data class RoomDraftAccount(val server: String?, val generation: Long, val user: Long, val version: Int)
internal fun SessionState.roomDraftAccount() = user?.takeIf { phase == SessionPhase.AUTHENTICATED }
    ?.let { RoomDraftAccount(server?.value, generation, it.id, it.sessionVersion) }

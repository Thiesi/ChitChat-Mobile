package io.github.thiesi.chitchat.feature.direct

import io.github.thiesi.chitchat.core.session.SessionPhase
import io.github.thiesi.chitchat.core.session.SessionState

internal data class DirectIdentity(val server: String?, val generation: Long, val user: Long, val version: Int)
internal fun SessionState.directIdentity(): DirectIdentity? = user?.takeIf {
    phase == SessionPhase.AUTHENTICATED && !it.guest
}?.let { DirectIdentity(server?.value, generation, it.id, it.sessionVersion) }

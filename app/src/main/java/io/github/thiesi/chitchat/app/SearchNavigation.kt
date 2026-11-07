package io.github.thiesi.chitchat.app

import io.github.thiesi.chitchat.core.session.SessionPhase
import io.github.thiesi.chitchat.core.session.SessionState
import java.net.URLEncoder

internal const val SEARCH_ROUTE = "message-search?generation={generation}&viewer={viewer}&version={version}&server={server}"
internal fun searchRoute(session: SessionState): String? {
    val user = session.user ?: return null
    val server = session.server ?: return null
    if (session.phase != SessionPhase.AUTHENTICATED || user.guest) return null
    return "message-search?generation=${session.generation}&viewer=${user.id}&version=${user.sessionVersion}" +
        "&server=${URLEncoder.encode(server.value, "UTF-8")}"
}
internal fun matchesSearchScope(session: SessionState, generation: Long, viewer: Long, version: Int, server: String?): Boolean {
    val user = session.user ?: return false
    return searchRoute(session) != null && session.generation == generation && user.id == viewer &&
        user.sessionVersion == version && session.server?.value == server
}

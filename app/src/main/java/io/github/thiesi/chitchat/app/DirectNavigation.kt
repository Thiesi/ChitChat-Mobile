package io.github.thiesi.chitchat.app

import androidx.navigation.NavType
import androidx.navigation.navArgument
import io.github.thiesi.chitchat.core.session.SessionState
import io.github.thiesi.chitchat.feature.direct.directIdentity
import java.net.URLEncoder

private const val SCOPE = "generation={generation}&viewer={viewer}&version={version}&server={server}"
internal const val DIRECT_INBOX_ROUTE = "direct?$SCOPE"
internal const val DIRECT_THREAD_ROUTE = "direct/{peerId}?$SCOPE"
internal fun directArguments(thread: Boolean = false) = buildList {
    if (thread) add(navArgument("peerId") { type = NavType.LongType })
    add(navArgument("generation") { type = NavType.LongType; defaultValue = -1L })
    add(navArgument("viewer") { type = NavType.LongType; defaultValue = -1L })
    add(navArgument("version") { type = NavType.IntType; defaultValue = -1 })
    add(navArgument("server") { type = NavType.StringType; defaultValue = "" })
}
/** Only non-secret scope metadata enters navigation state, never drafts/content/cookies. */
internal fun directRoute(session: SessionState, peer: Long? = null): String? {
    val identity = session.directIdentity() ?: return null
    val server = identity.server ?: return null
    if (peer != null && (peer <= 0 || peer == identity.user)) return null
    return "direct" + (peer?.let { "/$it" } ?: "") + "?generation=${identity.generation}&viewer=${identity.user}" +
        "&version=${identity.version}&server=${URLEncoder.encode(server, "UTF-8")}"
}
internal fun matchesDirectScope(session: SessionState, generation: Long, viewer: Long, version: Int, server: String?): Boolean {
    val identity = session.directIdentity() ?: return false
    return identity.generation == generation && identity.user == viewer && identity.version == version && identity.server == server
}

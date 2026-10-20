package io.github.thiesi.chitchat.core.notifications

import io.github.thiesi.chitchat.core.server.ServerAddress
import java.net.URI
import okhttp3.HttpUrl.Companion.toHttpUrl

/** A typed navigation hint, not permission or proof the requested resource still exists. */
sealed interface NotificationDestination {
    class Room(val roomId: Long, val messageId: Long? = null, val pingId: Long? = null) : NotificationDestination
    class Direct(val peerId: Long, val messageId: Long? = null) : NotificationDestination
    data object Account : NotificationDestination
    data object Registration : NotificationDestination
}

/** Consume actual server links only. Unknown links never become arbitrary browser/API intents. */
fun notificationDestination(server: ServerAddress, link: String?): NotificationDestination? {
    if (link.isNullOrBlank() || link.any { it.code <= 32 || it.code == 127 || it == '\\' } || link.startsWith("//")) return null
    val raw = try { URI(link) } catch (_: Exception) { return null }
    if (raw.isOpaque || raw.rawFragment != null || raw.rawUserInfo != null ||
        (raw.isAbsolute && raw.rawAuthority.isNullOrBlank()) ||
        (raw.isAbsolute && !raw.scheme.equals("https", true)) || (!raw.isAbsolute && !link.startsWith('/'))) return null
    // Do not let URL normalization turn traversal/encoded page paths into recognized routes.
    val path = raw.rawPath.orEmpty().ifEmpty { "/" }
    if (path !in setOf("/", "/account.php", "/messages.php")) return null
    val origin = server.value.toHttpUrl()
    val url = origin.resolve(link) ?: return null
    if (url.scheme != origin.scheme || url.host != origin.host || url.port != origin.port ||
        url.username.isNotEmpty() || url.password.isNotEmpty()) return null
    val keys = url.queryParameterNames
    if (keys.any { url.queryParameterValues(it).size != 1 }) return null
    fun positive(name: String): Long? = url.queryParameter(name)?.takeIf { it.matches(Regex("[0-9]+")) }
        ?.toLongOrNull()?.takeIf { it > 0 }
    return when (path) {
        "/account.php" -> NotificationDestination.Account.takeIf { keys.isEmpty() }
        "/messages.php" -> {
            if (keys.any { it !in setOf("user_id", "peer_name", "message_id") } ||
                ("peer_name" in keys && url.queryParameter("peer_name").isNullOrEmpty())) return null
            val peer = positive("user_id") ?: return null
            val message = if ("message_id" in keys) positive("message_id") ?: return null else null
            NotificationDestination.Direct(peer, message)
        }
        else -> {
            if (keys == setOf("register") && url.queryParameter("register") == "1") return NotificationDestination.Registration
            if (keys.any { it !in setOf("room_id", "message_id", "ping_id") } ||
                "message_id" in keys && "ping_id" in keys) return null
            val room = positive("room_id") ?: return null
            val message = if ("message_id" in keys) positive("message_id") ?: return null else null
            val ping = if ("ping_id" in keys) positive("ping_id") ?: return null else null
            NotificationDestination.Room(room, message, ping)
        }
    }
}

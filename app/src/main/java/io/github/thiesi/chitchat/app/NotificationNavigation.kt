package io.github.thiesi.chitchat.app

import androidx.navigation.NavType
import androidx.navigation.navArgument
import io.github.thiesi.chitchat.core.session.SessionState
import io.github.thiesi.chitchat.feature.notifications.noticeIdentity
import java.net.URLEncoder

internal const val NOTIFICATIONS_ROUTE = "notifications?generation={generation}&viewer={viewer}&version={version}&server={server}"
internal const val NOTIFICATION_ROOM_ROUTE = "notification-room/{roomId}?generation={generation}&viewer={viewer}&version={version}&server={server}"
internal const val NOTIFICATION_ACCOUNT_ROUTE = "notification-account?generation={generation}&viewer={viewer}&version={version}&server={server}"
internal fun notificationArguments() = listOf(
    navArgument("generation") { type = NavType.LongType; defaultValue = -1L },
    navArgument("viewer") { type = NavType.LongType; defaultValue = -1L },
    navArgument("version") { type = NavType.IntType; defaultValue = -1 },
    navArgument("server") { type = NavType.StringType; defaultValue = "" },
)
/** Navigation retains only non-secret ownership metadata, never notice text or read choices. */
private fun ownerQuery(session: SessionState): String? = session.noticeIdentity()?.let {
    "generation=${it.generation}&viewer=${it.user}&version=${it.version}&server=${URLEncoder.encode(it.server, "UTF-8")}"
}
internal fun notificationRoute(session: SessionState): String? = ownerQuery(session)?.let { "notifications?$it" }
internal fun notificationRoomRoute(session: SessionState, roomId: Long): String? =
    ownerQuery(session)?.takeIf { roomId > 0 }?.let { "notification-room/$roomId?$it" }
internal fun notificationAccountRoute(session: SessionState): String? = ownerQuery(session)?.let { "notification-account?$it" }
internal fun matchesNotificationScope(session: SessionState, generation: Long, viewer: Long, version: Int, server: String?): Boolean {
    val owner = session.noticeIdentity() ?: return false
    return owner.generation == generation && owner.user == viewer && owner.version == version && owner.server == server
}

package io.github.thiesi.chitchat.core.network

import io.github.thiesi.chitchat.core.conversation.serverInstant
import io.github.thiesi.chitchat.core.notifications.AccountNotification
import io.github.thiesi.chitchat.core.notifications.NotificationPage
import io.github.thiesi.chitchat.core.notifications.NotificationReadResult
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable internal class NotificationListReply(val notifications: List<AccountNotification>,
    @SerialName("unread_count") val unreadCount: Int)
@Serializable internal class NotificationReadIds(val ids: List<Long>)
@Serializable internal class NotificationReadAll(val all: Boolean)
@Serializable internal class NotificationReadReply(val updated: Int, @SerialName("unread_count") val unreadCount: Int)

internal fun NotificationListReply.page(before: Long?, limit: Int): NotificationPage {
    incompatibleUnless(unreadCount >= 0 && notifications.size <= limit &&
        notifications.zipWithNext().all { (a, b) -> a.id > b.id })
    notifications.forEach { item ->
        incompatibleUnless(item.id > 0 && (before == null || item.id < before) && item.kind.isNotBlank() &&
            item.title.isNotBlank() && serverInstant(item.createdAt) != null &&
            item.read == (item.readAt != null) && (item.readAt == null || serverInstant(item.readAt) != null))
    }
    return NotificationPage(notifications, unreadCount, notifications.lastOrNull()?.id, notifications.size == limit)
}
internal fun NotificationReadReply.result(maxUpdated: Int? = null): NotificationReadResult {
    incompatibleUnless(updated >= 0 && unreadCount >= 0 && (maxUpdated == null || updated <= maxUpdated))
    return NotificationReadResult(updated, unreadCount)
}

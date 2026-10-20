package io.github.thiesi.chitchat.core.notifications

import io.github.thiesi.chitchat.core.network.StrictJsonBoolean
import io.github.thiesi.chitchat.core.session.SessionState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Server-rendered private account text, not HTML, a chat read cursor or a push payload. */
@Serializable
class AccountNotification(val id: Long, val kind: String, val title: String, val message: String,
    val details: List<String>, val link: String?, @Serializable(with = StrictJsonBoolean::class) val read: Boolean,
    @SerialName("read_at") val readAt: String?, @SerialName("created_at") val createdAt: String)

/** Cursor/page evidence is derived from the raw descending page, never a server snapshot. */
class NotificationPage(val notifications: List<AccountNotification>, val unreadCount: Int,
    val olderCursor: Long?, val mayHaveOlder: Boolean)
class NotificationReadResult(val updated: Int, val unreadCount: Int)

/** Every request keeps the original rendered account; no implicit POST retry or marking on GET. */
interface NotificationOperations {
    val session: StateFlow<SessionState>
    suspend fun page(expected: SessionState, beforeId: Long? = null, limit: Int = 25): NotificationPage
    suspend fun markRead(expected: SessionState, ids: List<Long>): NotificationReadResult
    suspend fun markAllRead(expected: SessionState): NotificationReadResult
}

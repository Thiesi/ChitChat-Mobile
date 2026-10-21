package io.github.thiesi.chitchat.core.notifications

import io.github.thiesi.chitchat.core.network.incompatibleUnless
import io.github.thiesi.chitchat.core.network.requireNotificationRows
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Client coverage only. The count is server-wide, not derived from these private rows. */
class NotificationWindow(val notifications: List<AccountNotification>, val coveredLowerBoundary: Boolean,
    val unreadCount: Int, val olderCursor: Long, val mayHaveOlder: Boolean)

/** Canonical bounded GET reconciliation; never merges cached rows or performs a read POST.
 * The caller must capture its original session and reject incomplete coverage before adoption.
 */
internal suspend fun readCanonicalNotificationWindow(lowerInclusive: Long, upperInclusive: Long?,
    readPage: suspend (beforeId: Long?, limit: Int) -> NotificationPage): NotificationWindow {
    require(lowerInclusive > 0 && (upperInclusive == null || upperInclusive >= lowerInclusive))
    var before = upperInclusive?.takeUnless { it == Long.MAX_VALUE }?.plus(1)
    val rows = ArrayList<AccountNotification>(600)
    var unread = 0
    repeat(6) {
        currentCoroutineContext().ensureActive()
        val query = before
        val page = readPage(query, 100)
        currentCoroutineContext().ensureActive()
        requireNotificationRows(page.notifications, page.unreadCount, query, 100)
        val cursor = page.notifications.lastOrNull()?.id
        incompatibleUnless(page.olderCursor == cursor && page.mayHaveOlder == (page.notifications.size == 100))
        rows += page.notifications.filter { it.id >= lowerInclusive && (upperInclusive == null || it.id <= upperInclusive) }
        unread = page.unreadCount
        if (cursor == null || cursor <= lowerInclusive || !page.mayHaveOlder) {
            // Rows below the captured lower bound were fetched, not adopted. Do not skip them on More.
            return NotificationWindow(rows, true, unread, lowerInclusive,
                cursor != null && (cursor < lowerInclusive || page.mayHaveOlder))
        }
        before = cursor
    }
    return NotificationWindow(rows, false, unread, lowerInclusive, true)
}

package io.github.thiesi.chitchat.core.conversation

import io.github.thiesi.chitchat.core.network.incompatibleUnless
import io.github.thiesi.chitchat.core.network.requireValidMessage
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Client-derived coverage, never a server snapshot/cursor guarantee or generated content string. */
class WindowSnapshot(
    val messages: List<RoomMessage>,
    val coveredLowerBoundary: Boolean,
    val olderCursor: Long?,
    val mayHaveOlder: Boolean,
)

/** Refresh a captured range, including newly hydrated attachment and off-window reply summaries.
 * The caller decides whether coverage permits adoption; this never stitches in cached rows.
 */
internal suspend fun readCanonicalRoomWindow(
    roomId: Long,
    lowerInclusive: Long,
    upperInclusive: Long?,
    readHistory: suspend (beforeId: Long?, limit: Int) -> MessagePage,
): WindowSnapshot {
    require(roomId > 0 && lowerInclusive > 0 && (upperInclusive == null || upperInclusive >= lowerInclusive))
    // MAX is already the greatest representable ID; no before-ID can exclude anything above it.
    var before = upperInclusive?.takeUnless { it == Long.MAX_VALUE }?.plus(1)
    val messages = ArrayList<RoomMessage>(600)
    repeat(6) {
        currentCoroutineContext().ensureActive()
        val queryCursor = before
        val page = readHistory(queryCursor, 100)
        currentCoroutineContext().ensureActive()
        val cursor = page.olderCursor
        incompatibleUnless(page.messages.size <= 100 && page.messages.zipWithNext().all { (a, b) -> a.id < b.id })
        incompatibleUnless(if (cursor == null) page.messages.isEmpty() && !page.mayHaveOlder
            else cursor > 0 && (queryCursor == null || cursor < queryCursor))
        incompatibleUnless(page.messages.size != 100 || (page.mayHaveOlder && cursor == page.messages.first().id))
        page.messages.forEach { message ->
            requireValidMessage(message, roomId)
            incompatibleUnless(cursor != null && message.id >= cursor && (queryCursor == null || message.id < queryCursor))
        }
        messages += page.messages.filter { it.id >= lowerInclusive && (upperInclusive == null || it.id <= upperInclusive) }
        val covered = cursor == null || cursor <= lowerInclusive || !page.mayHaveOlder
        if (covered) return WindowSnapshot(messages.sortedBy { it.id }, true, lowerInclusive,
            cursor != null && (cursor < lowerInclusive || page.mayHaveOlder))
        before = cursor
    }
    return WindowSnapshot(messages.sortedBy { it.id }, false, lowerInclusive, true)
}


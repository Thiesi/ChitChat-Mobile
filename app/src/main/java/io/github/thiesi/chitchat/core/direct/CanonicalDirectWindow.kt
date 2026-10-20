package io.github.thiesi.chitchat.core.direct

import io.github.thiesi.chitchat.core.network.incompatibleUnless
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Bounded client coverage, not a server snapshot or generated content string. */
class DirectWindow(val messages: List<DirectMessage>, val covered: Boolean,
    val olderCursor: Long?, val mayHaveOlder: Boolean)

internal suspend fun readCanonicalDirectWindow(peer: Long, viewer: Long, lower: Long, upper: Long?,
    history: suspend (Long?, Int) -> DirectPage): DirectWindow {
    require(peer > 0 && viewer > 0 && peer != viewer && lower > 0 && (upper == null || upper >= lower))
    var before = upper?.takeUnless { it == Long.MAX_VALUE }?.plus(1)
    val rows = ArrayList<DirectMessage>(600)
    repeat(6) {
        currentCoroutineContext().ensureActive()
        val cursor = before
        val page = history(cursor, 100)
        currentCoroutineContext().ensureActive()
        val older = page.olderCursor
        incompatibleUnless(page.messages.size <= 100 && page.messages.zipWithNext().all { (a, b) -> a.id < b.id })
        incompatibleUnless(if (older == null) page.messages.isEmpty() && !page.mayHaveOlder
            else older > 0 && (cursor == null || older < cursor))
        incompatibleUnless(page.messages.size != 100 || page.mayHaveOlder && older == page.messages.first().id)
        page.messages.forEach { row ->
            incompatibleUnless(older != null && row.id >= older && (cursor == null || row.id < cursor) &&
                setOf(row.sender.id, row.recipient.id) == setOf(viewer, peer) && row.outgoing == (row.sender.id == viewer))
        }
        rows += page.messages.filter { it.id >= lower && (upper == null || it.id <= upper) }
        if (older == null || older <= lower || !page.mayHaveOlder)
            return DirectWindow(rows.sortedBy { it.id }, true, lower, older != null && (older < lower || page.mayHaveOlder))
        before = older
    }
    return DirectWindow(rows.sortedBy { it.id }, false, lower, true)
}

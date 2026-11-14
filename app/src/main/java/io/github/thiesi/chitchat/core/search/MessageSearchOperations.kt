package io.github.thiesi.chitchat.core.search

import io.github.thiesi.chitchat.core.session.SessionState
import kotlinx.coroutines.flow.StateFlow

enum class MessageSearchScope(val wireValue: String) { ALL("all"), ROOMS("rooms"), DIRECT("direct") }

/**
 * Immutable private query, sent only in a POST body. The server normalizes/validates its syntax.
 * [roomId] (v3.10.0, #283) limits the search to one room with scope All or Rooms; protocol only, no native entry yet.
 */
class MessageSearchRequest(val query: String, val scope: MessageSearchScope = MessageSearchScope.ALL,
    val limit: Int = 25, val offset: Int = 0, val roomId: Long? = null)

/**
 * One server-marked match inside an excerpt: UTF-16 [start] and [length], relative to the excerpt as sent (#283).
 * A presentation hint only: a row whose ranges don't all validate keeps none of them and shows plain text.
 */
class ExcerptMatch(val start: Int, val length: Int) { val end get() = start + length }

class SearchParticipant(val id: Long, val username: String)
class SearchRoom(val id: Long, val key: String, val name: String)

/** Plain-text excerpts and target hints, never canonical messages, HTML or navigation authority. */
sealed interface MessageSearchHit {
    val messageId: Long
    val excerpt: String
    val sender: SearchParticipant?
    val createdAt: String
    val editedAt: String?
    /** Validated server matches for highlighting; empty when absent or when any range was invalid. */
    val excerptMatches: List<ExcerptMatch>

    class Room(override val messageId: Long, override val excerpt: String,
        override val sender: SearchParticipant?, val room: SearchRoom,
        override val createdAt: String, override val editedAt: String?,
        override val excerptMatches: List<ExcerptMatch> = emptyList()) : MessageSearchHit
    class Direct(override val messageId: Long, override val excerpt: String,
        override val sender: SearchParticipant, val peer: SearchParticipant, val outgoing: Boolean,
        override val createdAt: String, override val editedAt: String?,
        override val excerptMatches: List<ExcerptMatch> = emptyList()) : MessageSearchHit
}

/** Relevance order/offset evidence only: concurrent edits can move results between pages. */
class MessageSearchPage(val results: List<MessageSearchHit>, val hasMore: Boolean, val nextOffset: Int?) {
    // Preserve a server-reported continuation beyond the request ceiling rather than inventing exhaustion.
    val canLoadMore: Boolean get() = hasMore && nextOffset != null && nextOffset in 0..5000
}

/** One explicit request; no cache, automatic POST replay, chat read or message-send consent. */
interface MessageSearchOperations {
    val session: StateFlow<SessionState>
    suspend fun search(expected: SessionState, request: MessageSearchRequest): MessageSearchPage
}

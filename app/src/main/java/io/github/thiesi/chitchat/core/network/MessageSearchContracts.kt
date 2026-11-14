package io.github.thiesi.chitchat.core.network

import io.github.thiesi.chitchat.core.conversation.serverInstant
import io.github.thiesi.chitchat.core.search.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** `room_id` is omitted when null (defaults aren't encoded). */
@Serializable internal class MessageSearchBody(val query: String, val scope: String, val limit: Int, val offset: Int,
    @SerialName("room_id") val roomId: Long? = null)
@Serializable internal class SearchPersonReply(val id: Long, val username: String)
@Serializable internal class SearchRoomReply(val id: Long, val key: String, val name: String)
@Serializable internal class SearchHitReply(val kind: String, @SerialName("message_id") val messageId: Long,
    val excerpt: String, val sender: SearchPersonReply?, val room: SearchRoomReply?, val peer: SearchPersonReply?,
    @Serializable(with = StrictJsonBoolean::class) val outgoing: Boolean?,
    @SerialName("created_at") val createdAt: String, @SerialName("edited_at") val editedAt: String?,
    // Kept as raw JSON so a malformed hint can never fail the response; see excerptMatches().
    @SerialName("excerpt_matches") val excerptMatches: JsonElement? = null)
@Serializable internal class MessageSearchReply(val results: List<SearchHitReply>,
    @SerialName("has_more") @Serializable(with = StrictJsonBoolean::class) val hasMore: Boolean,
    @SerialName("next_offset") val nextOffset: Int?)

internal fun MessageSearchReply.page(request: MessageSearchRequest, viewer: Long): MessageSearchPage {
    incompatibleUnless(viewer > 0 && results.size <= request.limit &&
        results.map { it.kind to it.messageId }.distinct().size == results.size)
    incompatibleUnless(if (hasMore) results.size == request.limit && nextOffset == request.offset + results.size
        else nextOffset == null)
    val hits = results.map { row ->
        incompatibleUnless(row.messageId > 0 && row.excerpt.codePointCount(0, row.excerpt.length) <= 320 &&
            serverInstant(row.createdAt) != null && (row.editedAt == null || serverInstant(row.editedAt) != null))
        // A room-filtered search returns only that room's messages (#283).
        incompatibleUnless(request.roomId == null || (row.kind == "room" && row.room?.id == request.roomId))
        val sender = row.sender?.participant()
        val matches = excerptMatches(row.excerpt, row.excerptMatches)
        when (row.kind) {
            "room" -> {
                incompatibleUnless(request.scope != MessageSearchScope.DIRECT && row.room != null &&
                    row.peer == null && row.outgoing == null)
                val room = checkNotNull(row.room)
                incompatibleUnless(room.id > 0 && room.key.isNotBlank() && room.name.isNotBlank())
                MessageSearchHit.Room(row.messageId, row.excerpt, sender,
                    SearchRoom(room.id, room.key, room.name), row.createdAt, row.editedAt, matches)
            }
            "direct" -> {
                incompatibleUnless(request.scope != MessageSearchScope.ROOMS && row.room == null &&
                    row.peer != null && row.outgoing != null && sender != null)
                val peer = checkNotNull(row.peer).participant()
                val outgoing = checkNotNull(row.outgoing)
                incompatibleUnless(peer.id != viewer && sender?.id == if (outgoing) viewer else peer.id)
                MessageSearchHit.Direct(row.messageId, row.excerpt, checkNotNull(sender), peer,
                    outgoing, row.createdAt, row.editedAt, matches)
            }
            else -> throw ApiFailure(FailureKind.INCOMPATIBLE)
        }
    }
    return MessageSearchPage(hits, hasMore, nextOffset)
}

/**
 * Server match ranges (v3.10.0, #283), all or nothing per row: every range must be a pair of JSON integers,
 * length at least 1, inside the excerpt in UTF-16 units, in order, not overlapping and not splitting a surrogate
 * pair. Anything else drops this row's ranges silently, on purpose: they are a presentation hint, and a bad hint
 * must never erase the result (as for other optional hints). Ranges are never repaired or clipped.
 */
internal fun excerptMatches(excerpt: String, value: JsonElement?): List<ExcerptMatch> {
    val items = value as? JsonArray ?: return emptyList()
    if (items.size > excerpt.length) return emptyList()
    fun splitsPair(index: Int) = index in 1 until excerpt.length && excerpt[index].isLowSurrogate() && excerpt[index - 1].isHighSurrogate()
    var previousEnd = 0
    val matches = ArrayList<ExcerptMatch>(items.size)
    for (item in items) {
        val fields = item as? JsonObject ?: return emptyList()
        val start = (fields["start"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull ?: return emptyList()
        val length = (fields["length"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull ?: return emptyList()
        if (start < previousEnd || length < 1 || start > excerpt.length - length) return emptyList()
        val match = ExcerptMatch(start, length)
        if (splitsPair(match.start) || splitsPair(match.end)) return emptyList()
        matches += match; previousEnd = match.end
    }
    return matches
}

private fun SearchPersonReply.participant(): SearchParticipant {
    incompatibleUnless(id > 0 && username.isNotBlank())
    return SearchParticipant(id, username)
}

package io.github.thiesi.chitchat.core.network

import io.github.thiesi.chitchat.core.conversation.serverInstant
import io.github.thiesi.chitchat.core.search.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable internal class MessageSearchBody(val query: String, val scope: String, val limit: Int, val offset: Int)
@Serializable internal class SearchPersonReply(val id: Long, val username: String)
@Serializable internal class SearchRoomReply(val id: Long, val key: String, val name: String)
@Serializable internal class SearchHitReply(val kind: String, @SerialName("message_id") val messageId: Long,
    val excerpt: String, val sender: SearchPersonReply?, val room: SearchRoomReply?, val peer: SearchPersonReply?,
    @Serializable(with = StrictJsonBoolean::class) val outgoing: Boolean?,
    @SerialName("created_at") val createdAt: String, @SerialName("edited_at") val editedAt: String?)
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
        val sender = row.sender?.participant()
        when (row.kind) {
            "room" -> {
                incompatibleUnless(request.scope != MessageSearchScope.DIRECT && row.room != null &&
                    row.peer == null && row.outgoing == null)
                val room = checkNotNull(row.room)
                incompatibleUnless(room.id > 0 && room.key.isNotBlank() && room.name.isNotBlank())
                MessageSearchHit.Room(row.messageId, row.excerpt, sender,
                    SearchRoom(room.id, room.key, room.name), row.createdAt, row.editedAt)
            }
            "direct" -> {
                incompatibleUnless(request.scope != MessageSearchScope.ROOMS && row.room == null &&
                    row.peer != null && row.outgoing != null && sender != null)
                val peer = checkNotNull(row.peer).participant()
                val outgoing = checkNotNull(row.outgoing)
                incompatibleUnless(peer.id != viewer && sender?.id == if (outgoing) viewer else peer.id)
                MessageSearchHit.Direct(row.messageId, row.excerpt, checkNotNull(sender), peer,
                    outgoing, row.createdAt, row.editedAt)
            }
            else -> throw ApiFailure(FailureKind.INCOMPATIBLE)
        }
    }
    return MessageSearchPage(hits, hasMore, nextOffset)
}

private fun SearchPersonReply.participant(): SearchParticipant {
    incompatibleUnless(id > 0 && username.isNotBlank())
    return SearchParticipant(id, username)
}

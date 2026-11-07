package io.github.thiesi.chitchat.core.ui

import io.github.thiesi.chitchat.core.conversation.MessageMention
import io.github.thiesi.chitchat.core.direct.DirectMention
import io.github.thiesi.chitchat.core.formatting.FormattedMessage
import io.github.thiesi.chitchat.core.formatting.MessageBlock
import io.github.thiesi.chitchat.core.formatting.MessageInline
import java.util.Locale

internal val messageMentionToken = Regex("@([A-Za-z0-9][A-Za-z0-9_.-]{2,31})")
internal const val MESSAGE_MENTION_ANNOTATION = "message_mention"

/** Server-provided authorization snapshots for presentation, never user targets or send consent. */
internal class MessageMentions private constructor(
    private val room: List<MessageMention>?, private val direct: List<DirectMention>?,
) {
    fun resolve(message: FormattedMessage): MentionHighlights {
        if (message.literalFallback || room.isNullOrEmpty() && direct.isNullOrEmpty()) return MentionHighlights.None
        val wanted = mutableSetOf<String>()
        fun visit(nodes: List<MessageInline>) {
            nodes.forEach { node -> when (node) {
                is MessageInline.Text -> messageMentionToken.findAll(node.text).forEach {
                    wanted += it.groupValues[1].lowercase(Locale.ROOT)
                }
                is MessageInline.Styled -> visit(node.children)
                else -> Unit // Code and links never acquire mention decorations.
            } }
        }
        message.blocks.forEach { block -> when (block) {
            is MessageBlock.Paragraph -> visit(block.children)
            is MessageBlock.Quote -> visit(block.children)
            is MessageBlock.CodeBlock -> Unit
        } }
        if (wanted.isEmpty()) return MentionHighlights.None
        // Retain only names actually present in bounded parsed Text nodes, not a room roster.
        val remaining = wanted.toMutableSet()
        val names = mutableSetOf<String>()
        val broadcast = ("room" in wanted || "here" in wanted) && room.orEmpty().any { it.broadcast }
        if (broadcast) { remaining.remove("room"); remaining.remove("here") }
        fun consider(username: String) {
            if (username.length in 3..32) {
                val name = username.lowercase(Locale.ROOT)
                if (name in wanted) { names += name; remaining.remove(name) }
            }
        }
        for (mention in room.orEmpty()) {
            consider(mention.username)
            if (remaining.isEmpty()) break
        }
        for (mention in direct.orEmpty()) {
            consider(mention.username) // A DM hint cannot invent a room broadcast.
            if (remaining.isEmpty()) break
        }
        return if (names.isEmpty() && !broadcast) MentionHighlights.None else MentionHighlights(names.toSet(), broadcast)
    }
    override fun toString() = "MessageMentions"
    companion object {
        val None = MessageMentions(null, null)
        fun room(values: List<MessageMention>) = MessageMentions(values, null)
        fun direct(values: List<DirectMention>) = MessageMentions(null, values)
    }
}

internal class MentionHighlights internal constructor(private val names: Set<String>, private val broadcast: Boolean) {
    enum class Kind { PERSONAL, BROADCAST }
    fun kind(username: String): Kind? {
        val name = username.lowercase(Locale.ROOT)
        return when {
            broadcast && (name == "room" || name == "here") -> Kind.BROADCAST
            name in names -> Kind.PERSONAL
            else -> null
        }
    }
    override fun toString() = "MentionHighlights"
    companion object { val None = MentionHighlights(emptySet(), false) }
}

package io.github.thiesi.chitchat.core.formatting

/** Ephemeral presentation data only; never an authorization, navigation or persistence model. */
internal sealed class MessageInline {
    class Text(val text: String) : MessageInline()
    class Code(val text: String) : MessageInline()
    class Link(val text: String, val target: String) : MessageInline()
    class Styled(val emphasis: Emphasis, val children: List<MessageInline>) : MessageInline()
    enum class Emphasis { STRONG, EMPHASIS }
    final override fun toString() = "MessageInline"
}

internal sealed class MessageBlock {
    class Paragraph(val children: List<MessageInline>) : MessageBlock()
    class Quote(val children: List<MessageInline>) : MessageBlock()
    class CodeBlock(val text: String) : MessageBlock()
    final override fun toString() = "MessageBlock"
}

internal class FormattedMessage(val blocks: List<MessageBlock>, val literalFallback: Boolean = false) {
    override fun toString() = "FormattedMessage"
}

internal class FormattedInline(val nodes: List<MessageInline>, val literalFallback: Boolean = false) {
    override fun toString() = "FormattedInline"
}

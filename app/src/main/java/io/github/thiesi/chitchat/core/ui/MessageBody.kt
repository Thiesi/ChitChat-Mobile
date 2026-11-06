package io.github.thiesi.chitchat.core.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.github.thiesi.chitchat.R
import io.github.thiesi.chitchat.core.formatting.MessageBlock
import io.github.thiesi.chitchat.core.formatting.MessageFormatting
import io.github.thiesi.chitchat.core.formatting.MessageInline
import io.github.thiesi.chitchat.core.formatting.FormattedMessage

/** Body presentation only. The enclosing48dp row opens canonical message actions, including links.
 * No inline browser launches, fetching, inferred mention authority, saved state or global cache.
 */
@Composable
internal fun MessageBody(text: String, modifier: Modifier = Modifier, emotePrefix: String? = null,
    mentions: MessageMentions = MessageMentions.None) {
    val parsed = remember(text, emotePrefix) {
        if (emotePrefix != null) {
            val inline = MessageFormatting.parseInline(text)
            FormattedMessage(listOf(MessageBlock.Paragraph(inline.nodes)), inline.literalFallback)
        } else MessageFormatting.parseMessage(text)
    }
    val highlights = remember(parsed, mentions) { mentions.resolve(parsed) }
    val palette = MaterialTheme.colorScheme
    val personal = SpanStyle(color = palette.onTertiaryContainer, background = palette.tertiaryContainer)
    val broadcast = SpanStyle(color = palette.onPrimaryContainer, background = palette.primaryContainer, fontWeight = FontWeight.Bold)
    fun inline(nodes: List<MessageInline>, prefix: String = "") = messageInlineText(nodes, palette.primary,
        palette.onSecondaryContainer, palette.secondaryContainer, prefix, highlights, personal, broadcast)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        parsed.blocks.forEach { block ->
            when (block) {
                is MessageBlock.Paragraph -> Text(inline(block.children, emotePrefix.orEmpty()))
                is MessageBlock.Quote -> {
                    val label = stringResource(R.string.message_formatted_quote)
                    Surface(shape = MaterialTheme.shapes.small, color = palette.surfaceContainer,
                        border = BorderStroke(1.dp, palette.outlineVariant),
                        modifier = Modifier.semantics { stateDescription = label }) {
                        Text(inline(block.children), Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                    }
                }
                is MessageBlock.CodeBlock -> {
                    val label = stringResource(R.string.message_formatted_code)
                    Surface(shape = MaterialTheme.shapes.small, color = palette.secondaryContainer,
                        contentColor = palette.onSecondaryContainer,
                        modifier = Modifier.semantics { stateDescription = label }) {
                        Text(block.text, Modifier.padding(8.dp), fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }
}

internal fun messageInlineText(nodes: List<MessageInline>, linkColor: Color, codeColor: Color,
    codeBackground: Color, prefix: String = "", highlights: MentionHighlights = MentionHighlights.None,
    personalStyle: SpanStyle = SpanStyle(), broadcastStyle: SpanStyle = SpanStyle()): AnnotatedString = buildAnnotatedString {
    append(prefix) // Emote author/prefix stays literal, separate from the body grammar.
    fun appendText(value: String) {
        if (highlights === MentionHighlights.None) append(value) else {
            var cursor = 0
            messageMentionToken.findAll(value).forEach { token ->
                append(value.substring(cursor, token.range.first))
                val kind = highlights.kind(token.groupValues[1])
                if (kind == null) append(token.value) else {
                    pushStyle(if (kind == MentionHighlights.Kind.BROADCAST) broadcastStyle else personalStyle)
                    pushStringAnnotation(MESSAGE_MENTION_ANNOTATION, if (kind == MentionHighlights.Kind.BROADCAST) "broadcast" else "personal")
                    append(token.value)
                    pop(); pop()
                }
                cursor = token.range.last + 1
            }
            append(value.substring(cursor))
        }
    }
    fun appendNodes(values: List<MessageInline>) {
        values.forEach { node -> when (node) {
            is MessageInline.Text -> appendText(node.text)
            is MessageInline.Code -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace,
                color = codeColor, background = codeBackground)) { append(node.text) }
            is MessageInline.Link -> withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) { append(node.text) }
            is MessageInline.Styled -> withStyle(if (node.emphasis == MessageInline.Emphasis.STRONG)
                SpanStyle(fontWeight = FontWeight.Bold) else SpanStyle(fontStyle = FontStyle.Italic)) { appendNodes(node.children) }
        } }
    }
    appendNodes(nodes)
}

/** Only AST links become explicit choices: code, literal fallbacks and rejected URLs stay inert. */
internal fun messageLinks(text: String, inlineOnly: Boolean = false): List<MessageInline.Link> {
    val result = mutableListOf<MessageInline.Link>()
    fun visit(nodes: List<MessageInline>) {
        nodes.forEach { node -> when (node) {
            is MessageInline.Link -> result += node
            is MessageInline.Styled -> visit(node.children)
            else -> Unit
        } }
    }
    if (inlineOnly) visit(MessageFormatting.parseInline(text).nodes)
    else MessageFormatting.parseMessage(text).blocks.forEach { block -> when (block) {
        is MessageBlock.Paragraph -> visit(block.children)
        is MessageBlock.Quote -> visit(block.children)
        is MessageBlock.CodeBlock -> Unit
    } }
    return result.distinctBy { it.target }
}

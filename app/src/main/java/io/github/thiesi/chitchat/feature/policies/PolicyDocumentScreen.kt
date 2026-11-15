package io.github.thiesi.chitchat.feature.policies

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.thiesi.chitchat.R
import io.github.thiesi.chitchat.core.policies.DocumentBlock
import io.github.thiesi.chitchat.core.policies.DocumentInline
import io.github.thiesi.chitchat.core.policies.PolicyDocumentName
import io.github.thiesi.chitchat.core.theme.ChitChatTheme
import io.github.thiesi.chitchat.core.ui.ExternalLinkChoice
import io.github.thiesi.chitchat.core.ui.InlineError
import io.github.thiesi.chitchat.core.ui.PrimaryButton
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * The document screen (play-readiness.md §1, P-176): a hosted document's Markdown through the bounded formatter (never
 * server HTML, no WebView); an external one as its host with Open in browser for `https` only; NotPublished as a
 * plain line. Every link inside goes through the explicit external choice. Memory only while open.
 */
@Composable
fun PolicyDocumentScreen(model: PolicyDocumentViewModel, name: PolicyDocumentName, installation: String) {
    val ui by model.state.collectAsStateWithLifecycle()
    var choice by rememberSaveable { mutableStateOf<String?>(null) }
    Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().testTag("policy_document"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(if (name == PolicyDocumentName.RULES) R.string.policy_rules_title else R.string.policy_privacy_title, installation),
            Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall)
        when (val status = ui?.takeIf { it.name == name }?.status ?: PolicyDocumentStatus.Loading) {
            PolicyDocumentStatus.Loading -> Text(stringResource(R.string.policy_loading), Modifier.testTag("policy_loading"))
            is PolicyDocumentStatus.Failed -> {
                InlineError(stringResource(R.string.policy_failed))
                PrimaryButton(onClick = model::retry, modifier = Modifier.fillMaxWidth().testTag("policy_retry")) { Text(stringResource(R.string.retry)) }
            }
            PolicyDocumentStatus.NotPublished ->
                Text(stringResource(R.string.policy_not_published, installation), Modifier.testTag("policy_not_published"))
            is PolicyDocumentStatus.External -> {
                val https = status.url.startsWith("https:", ignoreCase = true)
                Text(stringResource(R.string.policy_external, installation))
                Text(runCatching { status.url.toUri().host }.getOrNull().orEmpty(), Modifier.testTag("policy_external_host"),
                    fontWeight = FontWeight.SemiBold)
                if (https) PrimaryButton(onClick = { choice = status.url }, modifier = Modifier.fillMaxWidth().testTag("policy_open_external")) {
                    Text(stringResource(R.string.a11y_feedback_open_web))
                }
                else Text(stringResource(R.string.policy_external_insecure), Modifier.testTag("policy_external_insecure"))
            }
            is PolicyDocumentStatus.Hosted -> {
                caption(name, status.document.version, status.document.updatedAt)?.let {
                    Text(it, color = ChitChatTheme.colors.textMuted, modifier = Modifier.testTag("policy_caption"))
                }
                Column(Modifier.testTag("policy_body"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // Consecutive list items form one list with 8dp between items (play-readiness.md §1).
                    groupLists(status.formatted.blocks).forEachIndexed { index, group ->
                        // Headings get 8dp more above them than other blocks, except at the very top (J, PR #267).
                        if (group.size == 1 && group[0] is DocumentBlock.Heading && index > 0)
                            androidx.compose.foundation.layout.Box(Modifier.padding(top = 8.dp)) { DocumentBlockView(group[0]) { choice = it } }
                        else if (group.size == 1 && group[0] !is DocumentBlock.ListItem) DocumentBlockView(group[0]) { choice = it }
                        else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            group.forEach { item -> DocumentBlockView(item) { choice = it } }
                        }
                    }
                }
            }
        }
    }
    choice?.let { url ->
        ExternalLinkChoice(url, stringResource(R.string.policy_link_title), "policy_link") { choice = null }
    }
}

/** Runs of list items become one group; every other block stands alone. */
private fun groupLists(blocks: List<DocumentBlock>): List<List<DocumentBlock>> {
    val groups = mutableListOf<MutableList<DocumentBlock>>()
    blocks.forEach { block ->
        val last = groups.lastOrNull()
        if (block is DocumentBlock.ListItem && last != null && last.last() is DocumentBlock.ListItem) last += block
        else groups += mutableListOf(block)
    }
    return groups
}

@Composable
private fun caption(name: PolicyDocumentName, version: Int?, updatedAt: String?): String? {
    val date = updatedAt?.let { runCatching { OffsetDateTime.parse(it).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)) }.getOrNull() }
        ?: return null
    return if (name == PolicyDocumentName.RULES && version != null) stringResource(R.string.policy_version_updated, version, date)
        else stringResource(R.string.policy_updated, date)
}

@Composable
private fun DocumentBlockView(block: DocumentBlock, onLink: (String) -> Unit) {
    val linkStyle = TextLinkStyles(SpanStyle(color = ChitChatTheme.colors.steward, textDecoration = TextDecoration.Underline))
    when (block) {
        // Level 2 is the Lantern Title (Bricolage 700); level 3 and below system sans SemiBold, sentence case (J1, PR #267).
        is DocumentBlock.Heading -> Text(annotated(block.content, linkStyle, onLink), Modifier.semantics { heading() },
            style = if (block.level <= 2) MaterialTheme.typography.titleLarge
                else MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold))
        is DocumentBlock.Paragraph -> Text(annotated(block.content, linkStyle, onLink), style = MaterialTheme.typography.bodyLarge)
        is DocumentBlock.Plain -> Text(block.text, style = MaterialTheme.typography.bodyLarge)
        is DocumentBlock.ListItem -> Row(Modifier.padding(start = (block.indent * 16).dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (block.ordered) "${block.number ?: 1}." else "•", style = MaterialTheme.typography.bodyLarge)
            Text(annotated(block.content, linkStyle, onLink), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/** Inline nodes as one annotated string; a link is a clickable annotation that only asks for the explicit choice. */
private fun annotated(nodes: List<DocumentInline>, linkStyle: TextLinkStyles, onLink: (String) -> Unit): AnnotatedString = buildAnnotatedString {
    fun append(list: List<DocumentInline>) {
        list.forEach { node ->
            when (node) {
                is DocumentInline.Text -> append(node.text)
                is DocumentInline.Code -> append(node.text)
                is DocumentInline.Strong -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(node.content) }
                is DocumentInline.Emphasis -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(node.content) }
                is DocumentInline.Link -> withLink(LinkAnnotation.Clickable(node.target, linkStyle) { onLink(node.target) }) { append(node.content) }
            }
        }
    }
    append(nodes)
}

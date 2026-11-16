package io.github.thiesi.chitchat.app

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.thiesi.chitchat.R
import io.github.thiesi.chitchat.core.policies.PolicyDocumentName
import io.github.thiesi.chitchat.core.session.SessionState
import io.github.thiesi.chitchat.core.theme.ChitChatTheme
import io.github.thiesi.chitchat.core.ui.InlineError
import io.github.thiesi.chitchat.core.ui.PrimaryButton
import io.github.thiesi.chitchat.feature.policies.PolicyDocumentViewModel
import io.github.thiesi.chitchat.feature.policies.RulesUpdateViewModel
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * "{installation} updated its rules" (play-readiness.md §1, mockup `RulesUpdated`, O3b3a): a full-screen dialog over the
 * shell, which stays composed and STARTED, so nothing departs or stops underneath (an in-flight upload or POST keeps
 * going) and the dialog window's focus suppresses read and typing proof. System Back is Not now.
 */
@Composable
internal fun RulesUpdatedDialog(session: SessionState, server: String?, model: RulesUpdateViewModel,
    documents: PolicyDocumentViewModel?) {
    val ui by model.state.collectAsStateWithLifecycle()
    var document by rememberSaveable { mutableStateOf(false) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    val installation = installationName(session, server)
    val title = stringResource(R.string.rules_updated_title, installation)
    val rules = session.public?.policies?.rules
    LaunchedEffect(session.generation, rules?.version, session.user?.id) { model.loadSummary(session) }
    Dialog(onDismissRequest = { model.notNow(session) },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        Surface(Modifier.fillMaxSize().testTag("rules_updated").semantics { paneTitle = title },
            color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Column(Modifier.widthIn(max = 600.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(title, Modifier.testTag("rules_updated_title").semantics { heading() }, style = MaterialTheme.typography.headlineSmall)
                    updatedOn(rules?.updatedAt)?.let { Text(it, color = ChitChatTheme.colors.textMuted, modifier = Modifier.testTag("rules_updated_caption")) }
                    val summary = ui.summary?.takeIf { ui.summaryFor?.let { it.account == session.user?.id && it.generation == session.generation } == true }
                    if (summary != null) Surface(color = ChitChatTheme.colors.raised, shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth().testTag("rules_updated_summary_card")) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.rules_updated_what_changed), Modifier.semantics { heading() },
                                style = MaterialTheme.typography.labelLarge)
                            // Plain text, never formatted; semantics keep the whole summary in both states (PL, O3b3a).
                            Text(summary, Modifier.testTag("rules_updated_summary").clearAndSetSemantics { contentDescription = summary },
                                maxLines = if (expanded) Int.MAX_VALUE else 6, overflow = TextOverflow.Ellipsis)
                            if (summary.lines().size > 6 || summary.length > 360) TextButton(onClick = { expanded = !expanded },
                                Modifier.heightIn(min = 48.dp).testTag("rules_updated_show_all")) {
                                Text(stringResource(if (expanded) R.string.rules_updated_show_less else R.string.rules_updated_show_all))
                            }
                        }
                    }
                    if (documents != null) Surface(color = ChitChatTheme.colors.raised, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
                        DocumentRow(stringResource(R.string.guidelines_read_rules, installation), null, "rules_updated_read", enabled = true) { document = true }
                    }
                    if (ui.outdated && ui.scope?.account == session.user?.id) InlineError(stringResource(R.string.guidelines_rules_outdated, installation))
                    ui.failure?.takeIf { ui.scope?.account == session.user?.id }?.let { InlineError(guidelinesFailure(it)) }
                    Text(stringResource(R.string.rules_updated_consent, installation), Modifier.testTag("rules_updated_consent"),
                        color = ChitChatTheme.colors.textMuted)
                    PrimaryButton(onClick = { model.agree(session) }, enabled = !ui.saving && !session.busy,
                        modifier = Modifier.fillMaxWidth().testTag("rules_updated_agree")) {
                        Text(stringResource(if (ui.saving) R.string.guidelines_saving else R.string.guidelines_agree))
                    }
                    TextButton(onClick = { model.notNow(session) }, enabled = !ui.saving,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("rules_updated_not_now")) {
                        Text(stringResource(R.string.rules_updated_not_now))
                    }
                }
            }
        }
    }
    if (document && documents != null) PolicyDocumentDialog(documents, PolicyDocumentName.RULES, session, server) { document = false }
}

/**
 * The level-1 strip in room and DM conversations while the rules need accepting (PL, O3b3a): directly under the status
 * slot, no background, the `info` glyph and a muted caption merged for TalkBack, Review as its own 48dp action, and a
 * 1dp `line` bottom edge. At large text Review wraps below the sentence instead of squeezing it. Persistent state, so no
 * live region.
 */
@Composable
internal fun RulesBanner(session: SessionState, server: String?, onReview: () -> Unit) {
    val colors = ChitChatTheme.colors
    val sentence = stringResource(R.string.rules_banner, installationName(session, server))
    FlowRow(Modifier.fillMaxWidth().testTag("rules_banner").drawBehind {
        drawLine(colors.line, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1.dp.toPx())
    }.padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.Center,
        itemVerticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.padding(vertical = 8.dp).padding(end = 8.dp).semantics(mergeDescendants = true) {}.testTag("rules_banner_text"),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(painterResource(R.drawable.ic_info), contentDescription = null, Modifier.size(16.dp), tint = colors.textMuted)
            Text(sentence, style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
        }
        TextButton(onClick = onReview, Modifier.heightIn(min = 48.dp).testTag("rules_banner_review")) {
            Text(stringResource(R.string.rules_banner_review))
        }
    }
}

private fun installationName(session: SessionState, server: String?): String = session.installationName
    ?: breakableHost(server?.let { runCatching { java.net.URI(it).host }.getOrNull() }.orEmpty())

@Composable
private fun updatedOn(updatedAt: String?): String? = updatedAt
    ?.let { runCatching { OffsetDateTime.parse(it).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)) }.getOrNull() }
    ?.let { stringResource(R.string.policy_updated, it) }

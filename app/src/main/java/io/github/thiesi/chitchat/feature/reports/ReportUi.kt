package io.github.thiesi.chitchat.feature.reports

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import io.github.thiesi.chitchat.R
import io.github.thiesi.chitchat.core.conversation.MessageAttachment
import io.github.thiesi.chitchat.core.media.FileNames
import io.github.thiesi.chitchat.core.reports.ReportCategory
import io.github.thiesi.chitchat.core.reports.ReportDetails
import io.github.thiesi.chitchat.core.reports.ReportRefusal
import io.github.thiesi.chitchat.core.reports.ReportTarget
import io.github.thiesi.chitchat.core.status.Wait
import io.github.thiesi.chitchat.core.theme.ChitChatTheme
import io.github.thiesi.chitchat.core.theme.LanternOutlinedButton
import io.github.thiesi.chitchat.core.ui.DialogScrollBody
import io.github.thiesi.chitchat.core.ui.InlineError
import io.github.thiesi.chitchat.core.ui.WarningNotice
import io.github.thiesi.chitchat.core.theme.lanternDialogEdge
import io.github.thiesi.chitchat.core.ui.middleElided
import io.github.thiesi.chitchat.feature.status.waitText
import kotlinx.coroutines.delay

/** The app-scoped report state (MainActivity), as F5's LocalFileTransfers. Null in fixtures that don't report. */
internal val LocalReports = staticCompositionLocalOf<ReportViewModel?> { null }

/**
 * Provides [reports] and turns a genuine background into its rule (R1): a report in flight becomes a kept Unknown and an
 * open form is dropped. A configuration change is neither, so the form and its fields survive it.
 */
@Composable
internal fun ReportsHost(reports: ReportViewModel, content: @Composable () -> Unit) {
    val activity = LocalActivity.current
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { if (activity?.isChangingConfigurations != true) reports.backgrounded() }
    CompositionLocalProvider(LocalReports provides reports, content = content)
}

/** What the form shows about the message: the sender over a one-line excerpt (the mockup's card, PL). */
internal sealed interface ReportExcerpt {
    data class Text(val text: String) : ReportExcerpt
    data object Photo : ReportExcerpt
    data object Gif : ReportExcerpt
    data class File(val name: String) : ReportExcerpt
}

/** The text, or for an attachment-only message "Photo", "GIF" or its file name (PL). */
internal fun reportExcerpt(body: String?, attachment: MessageAttachment?): ReportExcerpt? = when {
    !body.isNullOrBlank() -> ReportExcerpt.Text(body)
    attachment == null -> null
    attachment.mimeType.equals("image/gif", ignoreCase = true) -> ReportExcerpt.Gif
    attachment.previewable && !FileNames.isApkLike(attachment) -> ReportExcerpt.Photo
    else -> ReportExcerpt.File(attachment.name)
}

/** The facts a conversation supplies for its own report's form. Read when shown; nothing is copied into the state. */
internal class ReportSubject(val sender: String, val excerpt: ReportExcerpt?, val hasAttachment: Boolean)

/** The follow-up sheet's action when it applies (PL): Ignore in a room, Block in a DM. Null shows no sheet. */
internal class ReportFollowUp(val name: String, val direct: Boolean, val act: () -> Unit)

/**
 * A conversation's own report: the form dialog while it's presented, and the follow-up sheet once, in place (PL). Only
 * the report whose origin is [origin] shows here. Leaving the conversation (not a configuration change) consumes its shown
 * outcome or takes the dialog away; the report itself keeps going (R1).
 */
/** Where the report's copy points: a room (its name, or null while it isn't known yet) or a direct message. */
internal sealed interface ReportPlace {
    data class Room(val name: String?) : ReportPlace
    data object Direct : ReportPlace
}

@Composable
internal fun ReportHost(origin: ReportOrigin, place: ReportPlace, subject: (ReportTarget) -> ReportSubject?,
    followUp: (ReportTarget) -> ReportFollowUp?) {
    val reports = LocalReports.current ?: return
    val activity = LocalActivity.current
    DisposableEffect(reports, origin) {
        onDispose { if (activity?.isChangingConfigurations != true) reports.leaveConversation(origin) }
    }
    val state by reports.state.collectAsState()
    val current = state?.takeIf { it.origin == origin } ?: return
    if (current.presented) ReportDialog(reports, current, place, subject(current.target))
    if (current.sheet) {
        val action = followUp(current.target)
        // An action that doesn't apply (already ignored or blocked, unknown policy) shows no sheet at all (PL).
        if (action == null) LaunchedEffect(Unit) { reports.dismissSheet() } else FollowUpSheet(action) { reports.dismissSheet() }
    }
}

/** Whether this conversation's report has a dialog or sheet up: a modal for read proof and typing (composerDialog). */
@Composable
internal fun reportModalOpen(origin: ReportOrigin): Boolean {
    val reports = LocalReports.current ?: return false
    val state by reports.state.collectAsState()
    return state?.takeIf { it.origin == origin }?.let { it.presented || it.sheet } == true
}

/**
 * The report's lines in a conversation's notice stack (PL): the outcome (level 1), "Sending your report…" after leaving
 * mid-send, "Not sure your report went through." with Review after a background (level 2), and the BUSY line.
 */
@Composable
internal fun ReportNotices(origin: ReportOrigin, place: ReportPlace, modifier: Modifier = Modifier) {
    val reports = LocalReports.current ?: return
    val state by reports.state.collectAsState()
    val busy by reports.busyNotice.collectAsState()
    val current = state?.takeIf { it.origin == origin }
    Column(modifier) {
        if (busy == origin) NoticeLine(stringResource(R.string.report_busy), "report_busy")
        if (current == null) return@Column
        when (val phase = current.phase) {
            ReportPhase.Sending, ReportPhase.Checking -> if (!current.presented) NoticeLine(stringResource(R.string.report_sending_line), "report_sending_line")
            is ReportPhase.Done -> NoticeLine(outcomeLine(phase.outcome, place), "report_outcome")
            is ReportPhase.Unknown -> if (!current.presented) WarningNotice(stringResource(R.string.report_unknown), "",
                Modifier.testTag("report_unknown_notice")) {
                TextButton(onClick = reports::review, modifier = Modifier.heightIn(min = 48.dp).testTag("report_review")) {
                    Text(stringResource(R.string.report_review))
                }
            }
            is ReportPhase.Editing -> Unit
        }
    }
}

@Composable
private fun outcomeLine(outcome: ReportOutcome, place: ReportPlace) = when (outcome) {
    // A room whose name isn't loaded yet gets the neutral room wording, never an empty name (review).
    ReportOutcome.Sent -> when (place) {
        is ReportPlace.Room -> place.name?.takeIf { it.isNotBlank() }?.let { stringResource(R.string.report_sent_room, it) }
            ?: stringResource(R.string.report_sent_room_unnamed)
        ReportPlace.Direct -> stringResource(R.string.report_sent_dm)
    }
    ReportOutcome.AlreadyReported -> stringResource(R.string.report_already)
    is ReportOutcome.Refused -> stringResource(if (outcome.reason == ReportRefusal.NOT_REPORTABLE) R.string.report_not_reportable else R.string.report_not_allowed)
}

/** A level-1 line, announced politely once. */
@Composable
private fun NoticeLine(text: String, tag: String) = Text(text, color = ChitChatTheme.colors.textMuted, style = MaterialTheme.typography.bodyMedium,
    modifier = Modifier.padding(vertical = 4.dp).semantics { liveRegion = LiveRegionMode.Polite }.testTag(tag))

private val categoryText = mapOf(
    ReportCategory.SPAM to (R.string.report_spam to R.string.report_spam_help),
    ReportCategory.HARASSMENT to (R.string.report_harassment to R.string.report_harassment_help),
    ReportCategory.HATE to (R.string.report_hate to R.string.report_hate_help),
    ReportCategory.THREATS to (R.string.report_threats to R.string.report_threats_help),
    ReportCategory.SEXUAL_CONTENT to (R.string.report_sexual_content to R.string.report_sexual_content_help),
    ReportCategory.PRIVACY to (R.string.report_privacy to R.string.report_privacy_help),
    ReportCategory.IMPERSONATION to (R.string.report_impersonation to R.string.report_impersonation_help),
    ReportCategory.OTHER to (R.string.report_other to R.string.report_other_help),
)

/**
 * The report form (reporting.md): a form dialog with the StepUpDialog geometry, a scrolling body above the keyboard with
 * the buttons kept below it. While sending it can't be dismissed (R1 refuses close()); Unknown replaces the editable form
 * with a read-only summary, Send again and Close (PL).
 */
@Composable
private fun ReportDialog(reports: ReportViewModel, state: ReportState, place: ReportPlace, subject: ReportSubject?) {
    val phase = state.phase
    val sending = phase == ReportPhase.Sending || phase == ReportPhase.Checking
    val unknown = phase is ReportPhase.Unknown
    val notice = (phase as? ReportPhase.Editing)?.notice ?: (phase as? ReportPhase.Unknown)?.notice
    // The report budget's wait: a composition-bound timer that only re-enables Send, never sends (TL).
    val waitSeconds = (notice as? ReportNotice.RateLimited)?.retryAfterSeconds
    val until = remember(notice) { waitSeconds?.let { System.currentTimeMillis() + it * 1000 } }
    var now by remember(notice) { mutableLongStateOf(System.currentTimeMillis()) }
    if (until != null) LaunchedEffect(until) { while (now < until) { delay(minOf(1_000L, until - now)); now = System.currentTimeMillis() } }
    val waiting = until != null && now < until
    val title = stringResource(R.string.report_title)
    AlertDialog(onDismissRequest = { if (!sending) reports.close() },
        modifier = Modifier.lanternDialogEdge().semantics { paneTitle = title }.testTag("report_dialog"),
        title = { Text(title, modifier = Modifier.semantics { heading() }) },
        text = {
            DialogScrollBody(Modifier.imePadding().testTag("report_body"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                subject?.let { ContextCard(it) }
                if (unknown) Summary(state) else Form(reports, state, sending)
                notice?.let { NoticeText(it, waitSeconds, waiting) }
                Text(when (place) {
                    is ReportPlace.Room -> place.name?.takeIf { it.isNotBlank() }?.let { stringResource(R.string.report_who_room, it) }
                        ?: stringResource(R.string.report_who_room_unnamed)
                    ReportPlace.Direct -> stringResource(R.string.report_who_dm)
                },
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("report_who"))
                if (subject?.hasAttachment == true) Text(stringResource(R.string.report_who_attachment),
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("report_who_attachment"))
                if (unknown) WarningNotice(stringResource(R.string.report_unknown), "", Modifier.testTag("report_unknown"))
            }
        },
        confirmButton = {
            when {
                unknown -> TextButton(onClick = reports::sendAgain, enabled = !waiting, modifier = Modifier.testTag("report_send_again")) {
                    Text(stringResource(R.string.report_send_again))
                }
                sending -> {
                    val spoken = stringResource(R.string.report_sending_spoken)
                    TextButton(onClick = {}, enabled = false, modifier = Modifier.testTag("report_send")
                        .clearAndSetSemantics { contentDescription = spoken; testTag = "report_send" }) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Text(stringResource(R.string.report_send))
                        }
                    }
                }
                else -> TextButton(onClick = reports::send, enabled = state.canSend && !waiting, modifier = Modifier.testTag("report_send")) {
                    Text(stringResource(R.string.report_send))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = reports::close, enabled = !sending, modifier = Modifier.testTag("report_cancel")) {
                Text(stringResource(if (unknown) R.string.report_close else R.string.cancel))
            }
        })
}

@Composable
private fun ContextCard(subject: ReportSubject) = Column(Modifier.fillMaxWidth().testTag("report_context")) {
    Text(subject.sender, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    when (val excerpt = subject.excerpt) {
        is ReportExcerpt.Text -> Text(excerpt.text, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.testTag("report_excerpt"))
        ReportExcerpt.Photo -> Text(stringResource(R.string.report_photo), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("report_excerpt"))
        ReportExcerpt.Gif -> Text(stringResource(R.string.report_gif), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("report_excerpt"))
        // The received-files name rules: bidi controls stripped, isolated, middle-elided so the extension stays (PL).
        is ReportExcerpt.File -> {
            val name = FileNames.withoutBidiControls(excerpt.name)
            val style = MaterialTheme.typography.bodyMedium
            val measurer = androidx.compose.ui.text.rememberTextMeasurer()
            androidx.compose.foundation.layout.BoxWithConstraints {
                val width = constraints.maxWidth
                val shown = remember(name, width, style) {
                    middleElided(name) { !measurer.measure(it, style, maxLines = 1,
                        constraints = androidx.compose.ui.unit.Constraints(maxWidth = width)).hasVisualOverflow }
                }
                Text(androidx.core.text.BidiFormatter.getInstance().unicodeWrap(shown), style = style, maxLines = 1,
                    modifier = Modifier.testTag("report_excerpt"))
            }
        }
        null -> Unit
    }
}

@Composable
private fun Form(reports: ReportViewModel, state: ReportState, sending: Boolean) {
    Text(stringResource(R.string.report_whats_wrong), style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
    Column(Modifier.selectableGroup()) {
        for (category in state.categories) {
            val (label, help) = categoryText[category] ?: continue
            Row(Modifier.fillMaxWidth().heightIn(min = 52.dp)
                .selectable(selected = state.category == category, enabled = !sending, role = Role.RadioButton) { reports.choose(category) }
                .testTag("report_category:${category.wire}"), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = state.category == category, onClick = null, enabled = !sending)
                Column(Modifier.padding(start = 12.dp)) {
                    Text(stringResource(label), style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(help), style = MaterialTheme.typography.bodySmall, color = ChitChatTheme.colors.textMuted)
                }
            }
        }
    }
    OutlinedTextField(state.details, reports::edit, enabled = !sending, minLines = 3,
        label = { Text(stringResource(R.string.report_details_label)) },
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("report_details"))
    Counter(state.details)
}

/**
 * "0 / 1000", or "Too long: 1012 / 1000" in error (words and colour, PL). Brought into view when it changes state with
 * the keyboard up (TL). TalkBack hears it only at the crossing: a live node whose text changes only then.
 */
@Composable
private fun Counter(details: String) {
    val count = ReportDetails.length(details)
    val tooLong = count > ReportDetails.MAX
    val requester = remember { BringIntoViewRequester() }
    LaunchedEffect(tooLong) { if (tooLong) requester.bringIntoView() }
    val visible = if (tooLong) stringResource(R.string.report_counter_too_long, count) else stringResource(R.string.report_counter, count)
    Text(visible, style = MaterialTheme.typography.bodySmall,
        color = if (tooLong) MaterialTheme.colorScheme.error else ChitChatTheme.colors.textMuted,
        modifier = Modifier.fillMaxWidth().bringIntoViewRequester(requester).testTag("report_counter"))
    val crossing = remember(tooLong) { if (tooLong) visible else "" }
    if (tooLong) Text("", modifier = Modifier.semantics {
        contentDescription = crossing; liveRegion = LiveRegionMode.Polite; testTag = "report_counter_crossing"
    })
}

@Composable
private fun Summary(state: ReportState) {
    val label = state.category?.let { categoryText[it]?.first }
    if (label != null) Text(stringResource(R.string.report_reason, stringResource(label)), style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.testTag("report_reason"))
    if (state.details.isNotBlank()) Text(state.details, style = MaterialTheme.typography.bodyMedium, color = ChitChatTheme.colors.textMuted,
        modifier = Modifier.testTag("report_summary_details"))
}

@Composable
private fun WaitLine(text: String) {
    val requester = remember { BringIntoViewRequester() }
    LaunchedEffect(text) { requester.bringIntoView() }
    Text(text, color = ChitChatTheme.colors.textMuted, style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.bringIntoViewRequester(requester).semantics { liveRegion = LiveRegionMode.Polite }.testTag("report_wait"))
}

@Composable
private fun NoticeText(notice: ReportNotice, waitSeconds: Long?, waiting: Boolean) {
    when (notice) {
        // Status ladder row 7: a wait is level 1, a muted line, never the person's error (PL). A known wait's line goes
        // when it ends, as Send re-enables; "Try again later." only when the delay was unknown from the start (PL).
        is ReportNotice.RateLimited -> when {
            waitSeconds != null && waiting -> WaitLine(stringResource(R.string.report_rate_limited, waitText(Wait.of(waitSeconds * 1000))))
            waitSeconds == null -> WaitLine(stringResource(R.string.report_rate_limited_unknown))
            else -> Unit
        }
        ReportNotice.NotSent, ReportNotice.SnapshotUnavailable, ReportNotice.PictureGone, ReportNotice.DidNotArrive ->
            InlineError(stringResource(R.string.report_not_sent))
    }
}

/** The follow-up sheet (PL): a heading and pane title, the lead, the outlined action and Done. Back or scrim is Done. */
@Composable
private fun FollowUpSheet(action: ReportFollowUp, onDone: () -> Unit) {
    val title = if (action.direct) stringResource(R.string.report_sheet_dm_title, action.name) else stringResource(R.string.report_sheet_room_title, action.name)
    AlertDialog(onDismissRequest = onDone, modifier = Modifier.lanternDialogEdge().semantics { paneTitle = title }.testTag("report_sheet"),
        title = { Text(title, modifier = Modifier.semantics { heading() }) },
        text = { DialogScrollBody {
            Text(if (action.direct) stringResource(R.string.report_sheet_dm_lead, action.name) else stringResource(R.string.report_sheet_room_lead, action.name),
                style = MaterialTheme.typography.bodyMedium)
        } },
        confirmButton = {
            LanternOutlinedButton(onClick = { onDone(); action.act() }, modifier = Modifier.heightIn(min = 48.dp).testTag("report_sheet_action")) {
                Text(if (action.direct) stringResource(R.string.report_sheet_block, action.name) else stringResource(R.string.report_sheet_ignore, action.name))
            }
        },
        dismissButton = {
            TextButton(onClick = onDone, modifier = Modifier.heightIn(min = 48.dp).testTag("report_sheet_done")) { Text(stringResource(R.string.report_sheet_done)) }
        })
}

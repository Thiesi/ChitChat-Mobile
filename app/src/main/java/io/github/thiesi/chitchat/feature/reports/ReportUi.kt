package io.github.thiesi.chitchat.feature.reports

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import io.github.thiesi.chitchat.core.ui.FormDialog
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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

/** The follow-up's inputs: what was reported, and for a person (R3) the name frozen from their card. */
internal class ReportFollowUpTarget(val target: ReportTarget, val name: String?)

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
    followUp: (ReportFollowUpTarget) -> ReportFollowUp?) {
    val reports = LocalReports.current ?: return
    val activity = LocalActivity.current
    DisposableEffect(reports, origin) {
        onDispose { if (activity?.isChangingConfigurations != true) reports.leaveConversation(origin) }
    }
    val state by reports.state.collectAsState()
    val current = state?.takeIf { it.origin == origin } ?: return
    // A person report's context is the name frozen from their card (R3), not something the conversation looks up.
    if (current.presented) ReportDialog(reports, current, place, if (current.target is ReportTarget.Person) null else subject(current.target))
    if (current.sheet) {
        val action = followUp(ReportFollowUpTarget(current.target, current.name))
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
            is ReportPhase.Done -> NoticeLine(outcomeLine(phase.outcome, current, place), "report_outcome")
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

/**
 * Where a report's copy points. A person report (R3) uses its own frozen room context: none is global (the DM wording),
 * and a context other than this conversation's room gets the neutral room wording, never another room's name.
 */
private fun reportPlace(state: ReportState, place: ReportPlace): ReportPlace {
    val person = state.target as? ReportTarget.Person ?: return place
    val room = person.roomId ?: return ReportPlace.Direct
    return if ((state.origin as? ReportOrigin.Room)?.roomId == room && place is ReportPlace.Room) place else ReportPlace.Room(null)
}

@Composable
private fun outcomeLine(outcome: ReportOutcome, state: ReportState, place: ReportPlace): String {
    val name = state.name?.takeIf { state.target is ReportTarget.Person }
    return when (outcome) {
        // A room whose name isn't loaded yet gets the neutral room wording, never an empty name (review).
        ReportOutcome.Sent -> when (val shown = reportPlace(state, place)) {
            is ReportPlace.Room -> shown.name?.takeIf { it.isNotBlank() }?.let { stringResource(R.string.report_sent_room, it) }
                ?: stringResource(R.string.report_sent_room_unnamed)
            ReportPlace.Direct -> stringResource(R.string.report_sent_dm)
        }
        ReportOutcome.AlreadyReported -> name?.let { stringResource(R.string.report_person_already, it) } ?: stringResource(R.string.report_already)
        // A person: "You can't report {name} here." (also guest_not_allowed, PL ruling 3), or the account is gone.
        is ReportOutcome.Refused -> when {
            name != null && outcome.reason == ReportRefusal.NOT_FOUND -> stringResource(R.string.report_person_not_found)
            name != null -> stringResource(R.string.report_person_not_allowed, name)
            else -> stringResource(if (outcome.reason == ReportRefusal.NOT_REPORTABLE) R.string.report_not_reportable else R.string.report_not_allowed)
        }
    }
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
    ReportCategory.OFFENSIVE_USERNAME to (R.string.report_offensive_username to R.string.report_offensive_username_help),
    ReportCategory.OFFENSIVE_PROFILE_PICTURE to (R.string.report_offensive_picture to R.string.report_offensive_picture_help),
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
    val person = state.target as? ReportTarget.Person
    val name = state.name.orEmpty()
    val title = if (person != null) stringResource(R.string.report_person_title, name) else stringResource(R.string.report_title)
    // The form-dialog scaffold (#328): its own window above the keyboard, one scrolling column when a short window leaves
    // the body under 120dp, so Send and Cancel never sit under the IME or squeeze the form away.
    // PL: an outside tap does nothing in every phase, so a stray tap never loses the chosen reason, the typed details or
    // the Unknown summary (whose Close also dismisses its notice). Cancel, Close and Back still close it; while sending,
    // close is refused.
    // A shown notice holds the view until the person's next interaction (PL, TL): FormDialog's hook, scroll only.
    // DIAGNOSTIC (never merged), #378 failure 1 (a): when the composed hold changes.
    val diagHeld = state.noticeHeld && notice != null
    androidx.compose.runtime.SideEffect {
        if (io.github.thiesi.chitchat.core.ui.KeepProbe.lastHeldUi != diagHeld) {
            io.github.thiesi.chitchat.core.ui.KeepProbe.lastHeldUi = diagHeld
            io.github.thiesi.chitchat.core.ui.KeepProbe.log("HOLDUI held=$diagHeld noticeHeld=${state.noticeHeld} notice=${notice != null}")
        }
    }
    FormDialog(onDismissRequest = { if (!sending) reports.close() }, dismissOnOutsideTap = false,
        holdFocusedInView = state.noticeHeld && notice != null,
        modifier = Modifier.lanternDialogEdge().semantics { paneTitle = title }.testTag("report_dialog"),
        title = { Text(title, modifier = Modifier.semantics { heading() }) },
        body = {
            Column(Modifier.fillMaxWidth().testTag("report_body"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (person != null) PersonContext(name, person.userId) else subject?.let { ContextCard(it) }
                if (unknown) Summary(state) else Form(reports, state, sending)
                notice?.let { NoticeText(it, waitSeconds, waiting, name, state.noticeSeq, state.noticeHeld) }
                Text(if (person != null) personWho(reportPlace(state, place), name, state.pictureAvailable) else when (place) {
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
                // A person's Unknown offers Check, one profile GET, never a POST (P-167); a message's offers Send again.
                unknown && person != null -> TextButton(onClick = reports::check, modifier = Modifier.testTag("report_check")) {
                    Text(stringResource(R.string.report_check))
                }
                unknown -> TextButton(onClick = reports::sendAgain, enabled = !waiting, modifier = Modifier.testTag("report_send_again")) {
                    Text(stringResource(R.string.report_send_again))
                }
                phase == ReportPhase.Checking -> {
                    val spoken = stringResource(R.string.report_checking_spoken)
                    TextButton(onClick = {}, enabled = false, modifier = Modifier.testTag("report_check")
                        .clearAndSetSemantics { contentDescription = spoken; testTag = "report_check" }) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Text(stringResource(R.string.report_check))
                        }
                    }
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

/**
 * R3: who is being reported, the person's pebble and username (reporting.md). The pebble is decoration only: the Lantern
 * pebble, the person's identity hue with the initial in ink in both themes (PL: a `raised` fill vanished on the dialog's
 * own `raised` container and left a stray letter). The name is server text, so it wraps and is never ellipsized (PL).
 */
@Composable
private fun PersonContext(name: String, userId: Long) = Row(Modifier.fillMaxWidth().testTag("report_context"),
    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
    androidx.compose.foundation.layout.Box(Modifier.size(32.dp).background(io.github.thiesi.chitchat.core.theme.identityHue(userId), CircleShape)
        .clearAndSetSemantics { }, contentAlignment = Alignment.Center) {
        Text(io.github.thiesi.chitchat.feature.profile.profileInitial(name), style = MaterialTheme.typography.labelLarge,
            color = io.github.thiesi.chitchat.core.theme.Lantern.ink)
    }
    Text(name, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
        modifier = Modifier.weight(1f).testTag("report_person"))
}

/** Who sees a person report (reporting.md): the room's owners and moderators too with a room context, else global. */
@Composable
private fun personWho(place: ReportPlace, name: String, picture: Boolean): String = when (place) {
    is ReportPlace.Room -> place.name?.takeIf { it.isNotBlank() }?.let { room ->
        stringResource(if (picture) R.string.report_person_who_room else R.string.report_person_who_room_no_picture, room, name)
    } ?: stringResource(if (picture) R.string.report_person_who_room_unnamed else R.string.report_person_who_room_unnamed_no_picture, name)
    ReportPlace.Direct -> stringResource(if (picture) R.string.report_person_who_global else R.string.report_person_who_global_no_picture, name)
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
    // PL (J on run 44864313479): the question is a visible label above the field, styled like "What's wrong?", not a
    // floating label, which wrapped onto the border at 200% and overlapped the helper above. TalkBack reads it once, as
    // its own text right before the field: Compose 1.11 drops a text field's contentDescription and exposes no hint or
    // labeledBy, so naming the field itself left it unnamed (TL, PL; measured on the real node).
    Text(stringResource(R.string.report_details_label), style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.testTag("report_details_label"))
    // The field's own value, seeded from the VM per form and pushed to it on every change: a value read back through
    // the StateFlow lags a frame and reset the caret or dropped input (TL). Memory only; a reopen or recreation reseeds.
    var field by remember(state.target) { mutableStateOf(TextFieldValue(state.details, TextRange(state.details.length))) }
    // The person's next interaction lets a held notice go (TL, PL): (b) a focus gain on the field, and (a) the keyboard
    // going from hidden to shown while the field is focused, read from the dialog window's own insets. Focus or a keyboard
    // that is merely kept (an instant reply) is neither. Observed only: nothing is consumed or intercepted.
    var hadFocus by remember { mutableStateOf(false) }
    val imeShown = WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current) > 0
    var wasShown by remember { mutableStateOf(imeShown) }
    val fieldInView = remember { BringIntoViewRequester() }
    LaunchedEffect(imeShown) {
        val shownNow = imeShown && !wasShown
        wasShown = imeShown
        // The form's own keep-the-field-in-view effect may have run while the hold was on, so the field is brought back
        // here, once, as a scroll only.
        if (shownNow && hadFocus && reports.keyboardShown()) { androidx.compose.runtime.withFrameNanos { }; fieldInView.bringIntoView() }
    }
    // #369 (TL, PL): the MessageInput pattern, a BasicTextField with M3's outlined decoration, exactly what
    // OutlinedTextField composes with these parameters (same colours, text style, cursor, minimum size, container and
    // keyboard defaults), so the field looks and behaves as before. What it adds is onTextLayout: the field publishes its
    // caret's line to the form dialog from its own selection and layout, so the dialog can keep that line in view after
    // any change, including text an accessibility service sets through semantics (which doesn't bring the caret into
    // view by itself).
    val enabled = !sending
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors()
    val fieldFocused by interaction.collectIsFocusedAsState()
    val style = androidx.compose.material3.LocalTextStyle.current
    val textColor = style.color.takeOrElse {
        when { !enabled -> colors.disabledTextColor; fieldFocused -> colors.focusedTextColor; else -> colors.unfocusedTextColor }
    }
    val caret = io.github.thiesi.chitchat.core.ui.LocalFormDialogCaret.current
    var layout by remember { mutableStateOf<androidx.compose.ui.text.TextLayoutResult?>(null) }
    var textArea by remember { mutableStateOf<androidx.compose.ui.layout.LayoutCoordinates?>(null) }
    LaunchedEffect(layout, field.selection, textArea) {
        val result = layout ?: return@LaunchedEffect
        val area = textArea ?: return@LaunchedEffect
        val end = field.selection.end.coerceIn(0, result.layoutInput.text.length)
        // A2: whether the caret is on the last line, by line index (an empty field is on its last line).
        caret?.publish(area, result.getCursorRect(end), lastLine = result.getLineForOffset(end) == result.lineCount - 1)
    }
    CompositionLocalProvider(androidx.compose.foundation.text.selection.LocalTextSelectionColors provides colors.textSelectionColors) {
        androidx.compose.foundation.text.BasicTextField(field, { field = it; reports.edit(it.text) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).bringIntoViewRequester(fieldInView).onFocusChanged { focus ->
                if (focus.isFocused && !hadFocus) reports.fieldFocused()
                hadFocus = focus.isFocused
            }.testTag("report_details").defaultMinSize(minWidth = androidx.compose.material3.OutlinedTextFieldDefaults.MinWidth,
                minHeight = androidx.compose.material3.OutlinedTextFieldDefaults.MinHeight),
            enabled = enabled, textStyle = style.merge(androidx.compose.ui.text.TextStyle(color = textColor)),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(colors.cursorColor), interactionSource = interaction,
            minLines = 3, onTextLayout = { layout = it },
            decorationBox = { inner ->
                androidx.compose.material3.OutlinedTextFieldDefaults.DecorationBox(value = field.text,
                    innerTextField = {
                        // Transparent to layout (the child keeps its constraints); only reports where the text area is.
                        Box(Modifier.onGloballyPositioned { textArea = it }, propagateMinConstraints = true) { inner() }
                    },
                    enabled = enabled, singleLine = false, visualTransformation = androidx.compose.ui.text.input.VisualTransformation.None,
                    interactionSource = interaction, colors = colors,
                    container = {
                        androidx.compose.material3.OutlinedTextFieldDefaults.Container(enabled = enabled, isError = false,
                            interactionSource = interaction, colors = colors, shape = androidx.compose.material3.OutlinedTextFieldDefaults.shape)
                    })
            })
    }
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
    // A2 (PL): the counter is the field's trailing line, kept in view with the caret's line while the caret is on the
    // last line; it unpublishes when it leaves composition.
    val caret = io.github.thiesi.chitchat.core.ui.LocalFormDialogCaret.current
    DisposableEffect(caret) { onDispose { caret?.publishTrailing(null) } }
    Text(visible, style = MaterialTheme.typography.bodySmall,
        color = if (tooLong) MaterialTheme.colorScheme.error else ChitChatTheme.colors.textMuted,
        modifier = Modifier.fillMaxWidth().bringIntoViewRequester(requester).onGloballyPositioned { caret?.publishTrailing(it) }
            .testTag("report_counter"))
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
private fun WaitLine(text: String, modifier: Modifier = Modifier) {
    val requester = remember { BringIntoViewRequester() }
    LaunchedEffect(text) { requester.bringIntoView() }
    Text(text, color = ChitChatTheme.colors.textMuted, style = MaterialTheme.typography.bodyMedium,
        modifier = modifier.bringIntoViewRequester(requester).semantics { liveRegion = LiveRegionMode.Polite }.testTag("report_wait"))
}

@Composable
private fun NoticeText(notice: ReportNotice, waitSeconds: Long?, waiting: Boolean, name: String, occurrence: Long, held: Boolean) {
    when (notice) {
        // Status ladder row 7: a wait is level 1, a muted line, never the person's error (PL). A known wait's line goes
        // when it ends, as Send re-enables; "Try again later." only when the delay was unknown from the start (PL).
        is ReportNotice.RateLimited -> when {
            waitSeconds != null && waiting -> InViewNotice(occurrence, held) { modifier ->
                WaitLine(stringResource(R.string.report_rate_limited, waitText(Wait.of(waitSeconds * 1000))), modifier) }
            waitSeconds == null -> InViewNotice(occurrence, held) { modifier ->
                WaitLine(stringResource(R.string.report_rate_limited_unknown), modifier) }
            else -> Unit
        }
        ReportNotice.NotSent -> InViewNotice(occurrence, held) { modifier ->
            Box(modifier) { InlineError(stringResource(R.string.report_not_sent)) } }
        // Person outcomes (reporting.md): a level-2 notice that nothing was sent; the picture gone, with its category
        // removed; and the Check that found nothing, after which Send report is an explicit choice again.
        ReportNotice.SnapshotUnavailable -> InViewNotice(occurrence, held) { modifier ->
            WarningNotice(stringResource(R.string.report_person_snapshot_lead), stringResource(R.string.report_person_snapshot_body, name),
                modifier.testTag("report_notice")) }
        ReportNotice.PictureGone -> InViewNotice(occurrence, held) { modifier ->
            Box(modifier) { InlineError(stringResource(R.string.report_person_picture_gone, name)) } }
        ReportNotice.DidNotArrive -> InViewNotice(occurrence, held) { modifier ->
            WarningNotice(stringResource(R.string.report_person_did_not_arrive), "", modifier.testTag("report_notice")) }
    }
}

/**
 * An in-form line that appears after Send is brought into view when it appears (PL and TL, 2026-11-22; measured at 200%
 * portrait: the WarningNotice lines ended out of view below the field). While it's [held] (until the person's next
 * interaction) it's brought back into view across a layout change too: the keyboard closing, a mode switch. A scroll
 * only: no focus or caret change and no extra announcement. One taller than the body shows from its top edge: the whole
 * line first, then its top edge, which is a no-op for a line that fits.
 */
@Composable
private fun InViewNotice(occurrence: Long, held: Boolean, content: @Composable (Modifier) -> Unit) {
    val requester = remember { BringIntoViewRequester() }
    val holding by androidx.compose.runtime.rememberUpdatedState(held)
    val layout = io.github.thiesi.chitchat.core.ui.LocalFormDialogLayout.current
    suspend fun bring() {
        requester.bringIntoView()
        requester.bringIntoView(androidx.compose.ui.geometry.Rect(0f, 0f, 1f, 1f))
    }
    // Keyed on the occurrence, not the notice: a second identical refusal is brought into view again (TL).
    LaunchedEffect(occurrence) { bring() }
    // Ordering (TL): a keyboard going from hidden to shown while the details field is focused ends the hold, rule (a),
    // so that layout change belongs to the field: the form brings the field into view once and this doesn't re-bring the
    // notice for it, so the two scrolls never race. Only the details field can bring the keyboard up in this form. While
    // held, every other layout change (the keyboard hiding, a mode switch) brings the notice back.
    val previous = remember { java.util.concurrent.atomic.AtomicReference(layout) }
    LaunchedEffect(layout) {
        val before = previous.getAndSet(layout)
        val keyboardShowing = before?.ime == false && layout?.ime == true
        if (holding && !keyboardShowing) { androidx.compose.runtime.withFrameNanos { }; bring() }
    }
    content(Modifier.bringIntoViewRequester(requester))
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

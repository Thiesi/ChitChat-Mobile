package io.github.thiesi.chitchat.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import io.github.thiesi.chitchat.R
import io.github.thiesi.chitchat.core.media.*
import io.github.thiesi.chitchat.core.theme.ChitChatTheme
import io.github.thiesi.chitchat.core.theme.Lantern
import io.github.thiesi.chitchat.core.theme.lanternDialogEdge

/** Presentation only. Features own authorization, original selections, jobs and every action. */
internal enum class AttachAvailability { AVAILABLE, SERVER_OFF, SETTINGS_UNREADABLE }

/** Test seam for instrumentation: replaces the system pickers with a synthetic one-shot read.
 * Production leaves it null; the system Photo Picker and document picker are then used.
 */
internal fun interface AttachmentPickerOverride {
    fun pick(source: AttachmentPickSource, filters: AttachmentPickFilters, deliver: (PickedAttachmentRead?) -> Unit)
}
internal val LocalAttachmentPickerOverride = compositionLocalOf<AttachmentPickerOverride?> { null }

@Composable
internal fun AttachButton(availability: AttachAvailability, enabled: Boolean, onClick: () -> Unit,
    modifier: Modifier = Modifier) {
    val label = stringResource(when (availability) {
        AttachAvailability.AVAILABLE -> R.string.attach_label
        AttachAvailability.SERVER_OFF -> R.string.attach_label_server_off
        AttachAvailability.SETTINGS_UNREADABLE -> R.string.attach_label_unreadable
    })
    IconButton(onClick = onClick, enabled = enabled, modifier = modifier.size(Lantern.target).semantics { contentDescription = label }) {
        Icon(painterResource(R.drawable.ic_attach), contentDescription = null, Modifier.size(24.dp),
            tint = if (enabled) ChitChatTheme.colors.textMuted else ChitChatTheme.colors.textMuted.copy(alpha = 0.38f))
    }
}

/** The level-1 explanation shown instead of the choice sheet when uploads are off. */
@Composable
internal fun AttachUnavailableNotice(availability: AttachAvailability, installation: String, persisting: Boolean,
    canCheckAgain: Boolean, onCheckAgain: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier,
    maxHeight: androidx.compose.ui.unit.Dp = androidx.compose.ui.unit.Dp.Infinity) {
    val colors = ChitChatTheme.colors
    // A level-1 line (info, not a refusal). Stacked at every scale so the text keeps the full width;
    // its actions sit below and stay visible while the capped text scrolls (A-04).
    Column(modifier.fillMaxWidth().testTag("attach_unavailable").padding(start = 16.dp, end = 4.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(painterResource(R.drawable.ic_info), contentDescription = null,
                Modifier.padding(top = 2.dp, end = 6.dp).size(statusGlyphSize()), tint = colors.textMuted)
            CappedText(maxHeight, colors.ground, "attach_unavailable_text", Modifier.weight(1f).padding(end = 12.dp),
                Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) {
                if (availability == AttachAvailability.SERVER_OFF)
                    Text(stringResource(R.string.attach_server_off), style = MaterialTheme.typography.bodySmall, color = colors.text)
                else {
                    Text(stringResource(R.string.attach_unreadable_title, installation), style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold, color = colors.text)
                    Text(stringResource(R.string.attach_unreadable_body), style = MaterialTheme.typography.bodySmall, color = colors.text)
                    if (persisting) Text(stringResource(R.string.attach_unreadable_persisting, installation),
                        style = MaterialTheme.typography.bodySmall, color = colors.text)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            if (availability == AttachAvailability.SETTINGS_UNREADABLE) TextButton(onClick = onCheckAgain, enabled = canCheckAgain,
                modifier = Modifier.heightIn(min = Lantern.target).testTag("attach_check_again")) {
                Text(stringResource(R.string.attach_check_again))
            }
            val close = stringResource(R.string.attach_notice_close)
            IconButton(onClick = onDismiss, modifier = Modifier.semantics { contentDescription = close }) { CloseIcon() }
        }
    }
}

/** Status glyphs follow the text size (16sp) but never exceed 24dp, so they stay attached to it. */
@Composable
private fun statusGlyphSize() = with(androidx.compose.ui.platform.LocalDensity.current) { 16.sp.toDp() }.coerceAtMost(24.dp)

/** Text capped above the composer so it never pushes the field and Send off screen (A-04). When it
 * overflows, its last visible line fades into [background] and More opens the whole text in a dialog,
 * so consent or limit copy is never only partly readable. Actions stay with the caller, outside.
 * Unbounded (for example inside Details) the text simply grows.
 */
@Composable
private fun CappedText(maxHeight: androidx.compose.ui.unit.Dp, background: Color, tag: String, modifier: Modifier = Modifier,
    contentModifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val bounded = maxHeight != androidx.compose.ui.unit.Dp.Infinity
    val scroll = rememberScrollState()
    // Overflow is the text's own natural height against the cap, measured without the space reserved
    // for More; otherwise that reserve would itself look like overflow (a feedback loop) and More
    // would appear when nothing is cut off.
    var textHeight by remember { mutableIntStateOf(0) }
    val capPx = with(androidx.compose.ui.platform.LocalDensity.current) { if (bounded) maxHeight.roundToPx() else Int.MAX_VALUE }
    val overflow = bounded && textHeight > capPx
    val atEnd by remember { derivedStateOf { scroll.value >= scroll.maxValue } }
    var full by remember { mutableStateOf(false) }
    // More sits over the capped area's bottom end on a solid backing (no extra row, so the composer
    // keeps its room); the scrolled text reserves space beneath it so the last line can clear it.
    Box(modifier) {
        Column(contentModifier.then(if (bounded) Modifier.heightIn(max = maxHeight).verticalScroll(scroll) else Modifier).testTag(tag)) {
            Column(Modifier.onSizeChanged { textHeight = it.height }, content = content)
            if (overflow) Spacer(Modifier.height(Lantern.target))
        }
        if (overflow && !atEnd) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(20.dp)
            .background(Brush.verticalGradient(listOf(Color.Transparent, background))))
        if (overflow) Row(Modifier.align(Alignment.BottomEnd), verticalAlignment = Alignment.CenterVertically) {
            // A short leading fade so a long line meets the backing softly instead of being cut mid-letter.
            Box(Modifier.size(16.dp, Lantern.target).background(Brush.horizontalGradient(listOf(Color.Transparent, background))))
            TextButton(onClick = { full = true }, modifier = Modifier.background(background).heightIn(min = Lantern.target)
                .testTag("${tag}_more")) { Text(stringResource(R.string.attach_more)) }
        }
    }
    if (full) AlertDialog(onDismissRequest = { full = false }, modifier = Modifier.testTag("${tag}_dialog").lanternDialogEdge(),
        text = { Column(Modifier.verticalScroll(rememberScrollState()), content = content) },
        confirmButton = { TextButton(onClick = { full = false }) { Text(stringResource(R.string.rooms_close)) } })
}

/** Two rows: Photo (system Photo Picker) and File (system document picker). Nothing is read,
 * attached or sent from here; the caller launches exactly one picker for one file.
 */
@Composable
internal fun AttachChoiceSheet(filters: AttachmentPickFilters, rateLimit: UploadRateLimit?,
    onPhoto: () -> Unit, onFile: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, modifier = Modifier.testTag("attach_sheet").lanternDialogEdge(),
        title = { Text(stringResource(R.string.attach_label)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            if (filters.photo != null) TextButton(onClick = onPhoto,
                modifier = Modifier.fillMaxWidth().heightIn(min = Lantern.target).testTag("attach_photo")) {
                Text(stringResource(R.string.attach_photo), Modifier.fillMaxWidth())
            }
            TextButton(onClick = onFile, modifier = Modifier.fillMaxWidth().heightIn(min = Lantern.target).testTag("attach_file")) {
                Text(stringResource(R.string.attach_file), Modifier.fillMaxWidth())
            }
            rateLimit?.takeIf { it.maximumAttempts > 0 && it.windowSeconds > 0 }?.let { limit ->
                val count = limit.maximumAttempts.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                val uploads = pluralStringResource(R.plurals.attach_rate_uploads, count, count)
                val window = when (val value = uploadRateWindow(limit.windowSeconds)) {
                    UploadRateWindow.Hour -> stringResource(R.string.attach_rate_per_hour)
                    is UploadRateWindow.Minutes -> value.minutes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                        .let { pluralStringResource(R.plurals.attach_rate_minutes, it, it) }
                    is UploadRateWindow.Seconds -> value.seconds.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                        .let { pluralStringResource(R.plurals.attach_rate_seconds, it, it) }
                }
                Text(stringResource(R.string.attach_rate_line, uploads, window), style = MaterialTheme.typography.bodySmall,
                    color = ChitChatTheme.colors.textMuted, modifier = Modifier.padding(top = 8.dp).testTag("attach_rate"))
            }
        } },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}

@Composable
internal fun ReplaceAttachmentDialog(onReplace: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, modifier = Modifier.testTag("attach_replace_dialog").lanternDialogEdge(),
        title = { Text(stringResource(R.string.attach_replace_title)) },
        confirmButton = { TextButton(onClick = onReplace, modifier = Modifier.testTag("attach_replace_confirm")) {
            Text(stringResource(R.string.attach_replace)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}

internal enum class AttachmentCardPhase { PREPARING, READY, PRIVACY_CHOICE, FAILED, UPLOADING, FINISHING, CANCELLING, UNKNOWN, CONFIRMED }

/** A definite refusal or not-sent outcome of the last explicit Send. The file stays attached. */
internal sealed interface AttachmentSendFailure {
    class TooLarge(val maxBytes: Long?) : AttachmentSendFailure
    data object TypeRefused : AttachmentSendFailure
    data object Empty : AttachmentSendFailure
    class RateLimited(val retryAfterSeconds: Long?) : AttachmentSendFailure
    data object Membership : AttachmentSendFailure
    data object Unavailable : AttachmentSendFailure
    data object NotSent : AttachmentSendFailure
    data object PolicyChanged : AttachmentSendFailure
    data object Rejected : AttachmentSendFailure
}

/** Neutral display facts mapped by each feature from its own private draft. No bytes. */
internal class AttachmentCardFacts(
    val filename: String,
    val mimeType: String?,
    val sizeBytes: Long?,
    val phase: AttachmentCardPhase,
    val privacy: AttachmentPhotoPrivacy? = null,
    val privacyFailure: PhotoPrivacyFailure? = null,
    val failure: PreparationFailureKind? = null,
    val observation: AttachmentPolicyObservation? = null,
    /** Buffered file bytes: presentation only, never proof of delivery or non-delivery. */
    val sentBytes: Long? = null,
    val sendFailure: AttachmentSendFailure? = null,
    val captionTooLong: Boolean = false,
) {
    override fun toString() = "AttachmentCardFacts"
    /** Something the person needs to act on before this file could be sent. */
    val needsAttention: Boolean get() = phase == AttachmentCardPhase.PRIVACY_CHOICE || phase == AttachmentCardPhase.FAILED ||
        observation is AttachmentPolicyObservation.TooLarge || observation is AttachmentPolicyObservation.TypeNotAllowed ||
        observation == AttachmentPolicyObservation.Invalid || observation == AttachmentPolicyObservation.UploadsDisabled ||
        observation == AttachmentPolicyObservation.Unavailable || captionTooLong
}

@Composable
private fun waitText(seconds: Long): String = when {
    seconds < 60 -> pluralStringResource(R.plurals.attach_wait_seconds, seconds.toInt(), seconds.toInt())
    seconds < 120 -> stringResource(R.string.attach_wait_minute)
    seconds < 3_600 -> ((seconds + 59) / 60).toInt().let { pluralStringResource(R.plurals.attach_wait_minutes, it, it) }
    else -> ((seconds + 3_599) / 3_600).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        .let { pluralStringResource(R.plurals.attach_wait_hours, it, it) }
}

@Composable
private fun sendFailureText(failure: AttachmentSendFailure, locale: java.util.Locale): String = when (failure) {
    is AttachmentSendFailure.TooLarge -> failure.maxBytes?.takeIf { it > 0 }
        ?.let { stringResource(R.string.attach_refused_too_large_limit, sizeText(attachmentSizeText(it, locale))) }
        ?: stringResource(R.string.attach_refused_too_large)
    AttachmentSendFailure.TypeRefused -> stringResource(R.string.attach_refused_type)
    AttachmentSendFailure.Empty -> stringResource(R.string.attach_refused_empty)
    is AttachmentSendFailure.RateLimited -> failure.retryAfterSeconds?.takeIf { it > 0 }
        ?.let { stringResource(R.string.attach_refused_rate_wait, waitText(it)) }
        ?: stringResource(R.string.attach_refused_rate)
    AttachmentSendFailure.Membership -> stringResource(R.string.conversation_membership)
    AttachmentSendFailure.Unavailable -> stringResource(R.string.attach_refused_dm_unavailable)
    AttachmentSendFailure.NotSent -> stringResource(R.string.attach_not_sent)
    AttachmentSendFailure.PolicyChanged -> stringResource(R.string.attach_policy_changed)
    AttachmentSendFailure.Rejected -> stringResource(R.string.attach_refused_other)
}

private enum class LineTone { MUTED, WARNING, UNSURE, BLOCKING }

@Composable
internal fun CloseIcon() = Icon(painterResource(R.drawable.ic_close), contentDescription = null, Modifier.size(24.dp))

/** Non-muted lines carry a decorative leading glyph, so state is never color-only (A-05). */
@Composable
private fun StatusLine(text: String, tone: LineTone) {
    val colors = ChitChatTheme.colors
    Row(verticalAlignment = Alignment.Top) {
        if (tone != LineTone.MUTED) Icon(painterResource(if (tone == LineTone.BLOCKING) R.drawable.ic_error else R.drawable.ic_warning),
            contentDescription = null, Modifier.padding(top = 2.dp, end = 6.dp).size(statusGlyphSize()),
            tint = if (tone == LineTone.BLOCKING) colors.error else colors.unsureLine)
        Text(text, style = MaterialTheme.typography.bodySmall, color = when (tone) {
            LineTone.MUTED -> colors.textMuted
            LineTone.WARNING, LineTone.UNSURE -> colors.text
            LineTone.BLOCKING -> colors.error
        }, fontWeight = if (tone == LineTone.MUTED) null else FontWeight.SemiBold)
    }
}

@Composable
private fun sizeText(value: AttachmentSizeText) = when (value.unit) {
    AttachmentSizeUnit.BYTES -> stringResource(R.string.attach_size_bytes, value.number)
    AttachmentSizeUnit.KB -> stringResource(R.string.attach_size_kb, value.number)
    AttachmentSizeUnit.MB -> stringResource(R.string.attach_size_mb, value.number)
    AttachmentSizeUnit.GB -> stringResource(R.string.attach_size_gb, value.number)
}

@Composable
private fun kindText(mimeType: String?, filename: String): String? = when (val kind = attachmentKindText(mimeType, filename)) {
    is AttachmentKindText.Images -> stringResource(R.string.attach_kind_images, kind.name)
    is AttachmentKindText.Videos -> stringResource(R.string.attach_kind_videos, kind.name)
    is AttachmentKindText.Audio -> stringResource(R.string.attach_kind_audio, kind.name)
    is AttachmentKindText.Files -> stringResource(R.string.attach_kind_files, kind.extension)
    null -> null
}

/** The pending card above the composer field. Remove is offered only when the caller allows it;
 * Send anyway only binds an explicit unchanged-photo choice, it never uploads by itself.
 */
@Composable
internal fun AttachmentCard(facts: AttachmentCardFacts, enabled: Boolean, onRemove: (() -> Unit)?,
    onSendAnyway: (() -> Unit)?, modifier: Modifier = Modifier, onCancel: (() -> Unit)? = null,
    unknownActions: (@Composable () -> Unit)? = null, maxHeight: androidx.compose.ui.unit.Dp = androidx.compose.ui.unit.Dp.Infinity) {
    val colors = ChitChatTheme.colors
    val locale = LocalConfiguration.current.locales[0]
    val lines = mutableListOf<Pair<String, LineTone>>()
    when (facts.phase) {
        AttachmentCardPhase.PREPARING -> lines += stringResource(R.string.attach_preparing) to LineTone.MUTED
        AttachmentCardPhase.READY -> when (facts.privacy) {
            AttachmentPhotoPrivacy.LOCATION_REMOVED -> lines += stringResource(R.string.attach_location_removed) to LineTone.MUTED
            AttachmentPhotoPrivacy.UNCHANGED_AFTER_CHOICE -> lines += stringResource(R.string.attach_location_kept) to LineTone.WARNING
            else -> Unit
        }
        AttachmentCardPhase.PRIVACY_CHOICE -> lines += stringResource(
            if (facts.privacyFailure == PhotoPrivacyFailure.CLEANING_FAILED) R.string.attach_privacy_failed
            else R.string.attach_privacy_unsupported) to LineTone.WARNING
        AttachmentCardPhase.FAILED -> lines += stringResource(
            if (facts.failure == PreparationFailureKind.CLIENT_BUDGET) R.string.attach_too_large_for_phone
            else R.string.attach_unreadable_file) to LineTone.BLOCKING
        AttachmentCardPhase.UPLOADING -> {
            val total = facts.sizeBytes
            val sent = facts.sentBytes?.coerceIn(0, total ?: Long.MAX_VALUE)
            lines += (if (total != null && sent != null) {
                val sentText = attachmentSizeText(sent, locale); val totalText = attachmentSizeText(total, locale)
                if (sentText.unit == totalText.unit) stringResource(R.string.attach_sending_progress, sentText.number, sizeText(totalText))
                else stringResource(R.string.attach_sending_progress, sizeText(sentText), sizeText(totalText))
            } else stringResource(R.string.attach_sending)) to LineTone.MUTED
        }
        AttachmentCardPhase.FINISHING -> lines += stringResource(R.string.attach_finishing) to LineTone.MUTED
        AttachmentCardPhase.CANCELLING -> lines += stringResource(R.string.attach_cancelling) to LineTone.MUTED
        AttachmentCardPhase.UNKNOWN -> lines += stringResource(R.string.attach_unknown) to LineTone.UNSURE
        AttachmentCardPhase.CONFIRMED -> lines += stringResource(R.string.attach_sent) to LineTone.MUTED
    }
    facts.sendFailure?.takeIf { facts.phase == AttachmentCardPhase.READY }?.let { lines += sendFailureText(it, locale) to LineTone.BLOCKING }
    if (facts.captionTooLong) lines += stringResource(R.string.attach_caption_too_long) to LineTone.BLOCKING
    when (val observed = facts.observation) {
        is AttachmentPolicyObservation.TooLarge -> {
            val (size, limit) = attachmentSizeComparison(observed.sizeBytes, observed.maxBytes, locale)
            lines += stringResource(R.string.attach_too_large, sizeText(size), sizeText(limit)) to LineTone.BLOCKING
        }
        is AttachmentPolicyObservation.TypeNotAllowed -> lines += (kindText(observed.mimeType, facts.filename)
            ?.let { stringResource(R.string.attach_type_refused, it) }
            ?: stringResource(R.string.attach_type_refused_generic)) to LineTone.BLOCKING
        AttachmentPolicyObservation.UploadsDisabled, AttachmentPolicyObservation.Unavailable ->
            lines += stringResource(R.string.attach_server_off) to LineTone.BLOCKING
        AttachmentPolicyObservation.Invalid -> lines += stringResource(R.string.attach_unreadable_short) to LineTone.BLOCKING
        else -> Unit
    }
    val type = attachmentExtension(facts.filename)
    val size = facts.sizeBytes?.let { sizeText(attachmentSizeText(it, locale)) }
    val detail = listOfNotNull(type, size).joinToString(" · ")
    // In the composer only the status text scrolls, within [maxHeight], so long lines at large font
    // scales never push the field and Send off screen (A-04). The file row with Remove and every action
    // row stay outside the scroll and always visible. Unbounded (inside Details) the text simply grows.
    val bounded = maxHeight != androidx.compose.ui.unit.Dp.Infinity
    Surface(modifier.fillMaxWidth().testTag("attachment_card"), color = colors.raised, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).background(colors.surface, MaterialTheme.shapes.small), contentAlignment = Alignment.Center) {
                    val total = facts.sizeBytes?.takeIf { it > 0 }
                    val sent = facts.sentBytes
                    if (facts.phase == AttachmentCardPhase.UPLOADING && total != null && sent != null)
                        CircularProgressIndicator(progress = { (sent.toFloat() / total).coerceIn(0f, 1f) },
                            Modifier.size(24.dp).testTag("attachment_card_progress"), strokeWidth = 2.dp)
                    else if (facts.phase in setOf(AttachmentCardPhase.PREPARING, AttachmentCardPhase.UPLOADING,
                            AttachmentCardPhase.FINISHING, AttachmentCardPhase.CANCELLING))
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else Icon(painterResource(if (facts.mimeType?.startsWith("image/", ignoreCase = true) == true ||
                        facts.privacy == AttachmentPhotoPrivacy.LOCATION_REMOVED || facts.privacyFailure != null)
                        R.drawable.ic_image else R.drawable.ic_document), contentDescription = null, Modifier.size(24.dp),
                        tint = colors.textMuted)
                }
                Column(Modifier.weight(1f).padding(horizontal = 12.dp).semantics(mergeDescendants = true) {}) {
                    Text(facts.filename, style = MaterialTheme.typography.bodyMedium, color = colors.text,
                        maxLines = 1, overflow = TextOverflow.MiddleEllipsis, modifier = Modifier.testTag("attachment_card_name"))
                    if (detail.isNotEmpty()) Text(detail, style = MaterialTheme.typography.bodySmall, color = colors.textMuted, maxLines = 1)
                }
                if (onRemove != null) {
                    val remove = stringResource(R.string.attach_remove_label)
                    IconButton(onClick = onRemove, enabled = enabled, modifier = Modifier.testTag("attachment_card_remove")
                        .semantics { contentDescription = remove }) { CloseIcon() }
                }
            }
            if (lines.isNotEmpty()) CappedText(maxHeight, colors.raised, "attachment_card_status",
                Modifier.padding(end = 8.dp, bottom = 4.dp),
                Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) {
                lines.forEach { (text, tone) -> StatusLine(text, tone) }
            }
            if (facts.phase == AttachmentCardPhase.UPLOADING && onCancel != null)
                Row(Modifier.fillMaxWidth().padding(end = 4.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onCancel, enabled = enabled, modifier = Modifier.heightIn(min = Lantern.target)
                        .testTag("attachment_card_cancel")) { Text(stringResource(R.string.attach_cancel_send)) }
                }
            if (facts.phase == AttachmentCardPhase.UNKNOWN) unknownActions?.invoke()
            if (facts.phase == AttachmentCardPhase.PRIVACY_CHOICE && (onSendAnyway != null || onRemove != null))
                Row(Modifier.fillMaxWidth().padding(end = 4.dp), horizontalArrangement = Arrangement.End) {
                    onRemove?.let { TextButton(onClick = it, enabled = enabled, modifier = Modifier.heightIn(min = Lantern.target)
                        .testTag("attachment_card_privacy_remove")) { Text(stringResource(R.string.attach_remove)) } }
                    onSendAnyway?.let { TextButton(onClick = it, enabled = enabled, modifier = Modifier.heightIn(min = Lantern.target)
                        .testTag("attachment_card_send_anyway")) { Text(stringResource(R.string.attach_send_anyway)) } }
                }
        }
    }
}

internal enum class AttachmentReviewPhase { CHECKING, EVIDENCE, DUPLICATE_RISK, FAILED }
internal class AttachmentEvidenceRow(val key: Any, val filename: String, val time: String)

/** One explicit history check's outcome. Confirming evidence or accepting the risk never posts:
 * a match is the person's own judgment, and "Send again" only unlocks a later, separate Send.
 */
@Composable
internal fun AttachmentReviewDialog(phase: AttachmentReviewPhase, evidence: List<AttachmentEvidenceRow>, error: String?,
    canAct: Boolean, onConfirm: (Any) -> Unit, onReject: () -> Unit, onAccept: () -> Unit, onCheckAgain: () -> Unit,
    onClose: () -> Unit) {
    AlertDialog(onDismissRequest = onClose, modifier = Modifier.testTag("attach_review_dialog").lanternDialogEdge(),
        title = { Text(stringResource(when (phase) {
            AttachmentReviewPhase.CHECKING -> R.string.attach_review_checking_title
            AttachmentReviewPhase.EVIDENCE -> R.string.attach_review_found_title
            AttachmentReviewPhase.DUPLICATE_RISK -> R.string.attach_review_send_again_title
            AttachmentReviewPhase.FAILED -> R.string.attach_review_failed_title
        })) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (phase) {
                AttachmentReviewPhase.CHECKING -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.attach_review_checking), Modifier.padding(start = 12.dp))
                }
                AttachmentReviewPhase.EVIDENCE -> evidence.forEach { row ->
                    Text(stringResource(R.string.attach_review_found, row.filename, row.time))
                    TextButton(onClick = { onConfirm(row.key) }, enabled = canAct, modifier = Modifier.fillMaxWidth()
                        .heightIn(min = Lantern.target).testTag("attach_review_confirm")) {
                        Text(stringResource(R.string.attach_review_confirm), Modifier.fillMaxWidth())
                    }
                }
                AttachmentReviewPhase.DUPLICATE_RISK -> Text(stringResource(R.string.attach_review_send_again))
                AttachmentReviewPhase.FAILED -> {
                    Text(stringResource(R.string.attach_review_failed))
                    error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = ChitChatTheme.colors.textMuted) }
                }
            }
        } },
        confirmButton = { when (phase) {
            AttachmentReviewPhase.EVIDENCE -> TextButton(onClick = onReject, enabled = canAct,
                modifier = Modifier.testTag("attach_review_reject")) { Text(stringResource(R.string.attach_review_reject)) }
            AttachmentReviewPhase.DUPLICATE_RISK -> TextButton(onClick = onAccept, enabled = canAct,
                modifier = Modifier.testTag("attach_review_accept")) { Text(stringResource(R.string.attach_review_accept)) }
            AttachmentReviewPhase.FAILED -> TextButton(onClick = onCheckAgain, enabled = canAct,
                modifier = Modifier.testTag("attach_review_retry")) { Text(stringResource(R.string.attach_check_history)) }
            AttachmentReviewPhase.CHECKING -> Unit
        } },
        dismissButton = { TextButton(onClick = onClose, modifier = Modifier.testTag("attach_review_close")) {
            Text(stringResource(if (phase == AttachmentReviewPhase.DUPLICATE_RISK || phase == AttachmentReviewPhase.EVIDENCE)
                R.string.attach_review_keep else R.string.cancel))
        } })
}

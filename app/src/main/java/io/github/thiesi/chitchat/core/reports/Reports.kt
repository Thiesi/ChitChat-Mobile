package io.github.thiesi.chitchat.core.reports

import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.network.FailureKind
import io.github.thiesi.chitchat.core.session.SessionState
import kotlinx.coroutines.flow.StateFlow

/*
 * Participant reporting (reporting.md, P-160–P-167; ChitChat v3.10.0 docs/api/moderation-reports.md). Two CSRF POSTs:
 * `reports/message.php` and `reports/user.php`. The server keeps one report per account per case, including after the
 * case closes, so an explicit Send again after an ambiguous result can never duplicate one: a repeat answers
 * already-reported. Nothing is ever resent automatically.
 */

/** The fixed categories. The two person-only ones are refused for a message. */
enum class ReportCategory(val wire: String, val personOnly: Boolean = false) {
    SPAM("spam"), HARASSMENT("harassment"), HATE("hate"), THREATS("threats"), SEXUAL_CONTENT("sexual_content"),
    PRIVACY("privacy"), IMPERSONATION("impersonation"), OTHER("other"),
    OFFENSIVE_USERNAME("offensive_username", personOnly = true),
    OFFENSIVE_PROFILE_PICTURE("offensive_profile_picture", personOnly = true);

    companion object {
        val messageCategories = entries.filter { !it.personOnly }
        /** Person-only first (the picture one only with a current picture), then the message categories (PL). */
        fun personCategories(hasPicture: Boolean) =
            listOfNotNull(OFFENSIVE_USERNAME, OFFENSIVE_PROFILE_PICTURE.takeIf { hasPicture }) + messageCategories
    }
}

/** What is reported. A person's [roomId] is the profile request's own context, frozen with it (null: a global report). */
sealed interface ReportTarget {
    data class RoomMessage(val messageId: Long) : ReportTarget
    data class DirectMessage(val messageId: Long) : ReportTarget
    data class Person(val userId: Long, val roomId: Long?) : ReportTarget
}

/** Why a report was definitely refused; the form closes with the PL's line and nothing is retried. */
enum class ReportRefusal {
    /** `message_not_found` or `user_not_found`. */
    NOT_FOUND,
    /** 403 `report_recipient_required`, `report_not_allowed` or `guest_not_allowed`. */
    NOT_ALLOWED,
    /** 409 `message_not_reportable`: deleted, system, own or empty. */
    NOT_REPORTABLE,
}

/** One report POST's outcome. Only [Unknown] may have committed without saying so. */
sealed interface ReportResult {
    /** 201. [caseId] is informational only; a 201 whose body doesn't parse is still sent. */
    data class Sent(val caseId: Long?) : ReportResult
    /** `message_already_reported` or `already_reported`: the report exists, so this reads as success (PL). */
    data object AlreadyReported : ReportResult
    data class Refused(val reason: ReportRefusal) : ReportResult
    /** The report budget's own 429 (`rate_limited`), kept local (TL ruling, R1): the server's positive wait, or null. */
    data class RateLimited(val retryAfterSeconds: Long?) : ReportResult
    /** 400 on the picture category: the account no longer has a picture. Nothing was sent. */
    data object PictureGone : ReportResult
    /** Any other 400 `validation_error`. Nothing was sent. */
    data object Invalid : ReportResult
    /** 409 `report_snapshot_unavailable`: the server kept no copy and wrote nothing. */
    data object SnapshotUnavailable : ReportResult
    /** Lost connection, timeout, 5xx or an unreadable success: it may have arrived. Only an explicit choice follows. */
    data class Unknown(val failure: ApiFailure) : ReportResult
    /** Refused before or without committing (session ended, CSRF, an unexpected 4xx). Nothing was sent. */
    data class Failed(val failure: ApiFailure) : ReportResult
}

/** A person's report facts from their profile: strict Booleans, else unknown (null), which hides the entry. */
data class ReportFacts(val canReport: Boolean, val reportedByMe: Boolean)

/** The report operations (SessionReportRepository). */
interface ReportOperations {
    val session: StateFlow<SessionState>
    /** One POST for the rendered [expected] session; never resent. [details] goes as typed (the server trims it). */
    suspend fun send(expected: SessionState, target: ReportTarget, category: ReportCategory, details: String?): ReportResult
    /** One profile GET with [target]'s own room context: the person Check after an Unknown. Throws on failure. */
    suspend fun personFacts(expected: SessionState, target: ReportTarget.Person): ReportFacts?
}

/** Maps a report POST's failure to its outcome (moderation-reports.md's error lists). */
internal fun reportResult(failure: ApiFailure, category: ReportCategory): ReportResult = when {
    failure.kind in setOf(FailureKind.NETWORK, FailureKind.TIMEOUT) || (failure.status ?: 0) >= 500 -> ReportResult.Unknown(failure)
    // An unreadable or unexpected reply to a POST that may have committed.
    failure.kind == FailureKind.INCOMPATIBLE -> ReportResult.Unknown(failure)
    failure.status == 409 && (failure.code == "message_already_reported" || failure.code == "already_reported") ->
        ReportResult.AlreadyReported
    failure.status == 409 && failure.code == "message_not_reportable" -> ReportResult.Refused(ReportRefusal.NOT_REPORTABLE)
    failure.status == 409 && failure.code == "report_snapshot_unavailable" -> ReportResult.SnapshotUnavailable
    failure.status == 404 && (failure.code == "message_not_found" || failure.code == "user_not_found") ->
        ReportResult.Refused(ReportRefusal.NOT_FOUND)
    failure.status == 403 && failure.code in setOf("report_recipient_required", "report_not_allowed", "guest_not_allowed") ->
        ReportResult.Refused(ReportRefusal.NOT_ALLOWED)
    failure.status == 400 && failure.code == "validation_error" ->
        if (category == ReportCategory.OFFENSIVE_PROFILE_PICTURE) ReportResult.PictureGone else ReportResult.Invalid
    // Any 429 is a definite refusal: the report budget's own (kept local, #323's RateBudget), its local gate, the
    // session's wait already running, or a proxy's. Only the budget decides whether the session-wide wait applies.
    failure.status == 429 -> ReportResult.RateLimited(failure.retryAfterSeconds?.takeIf { it > 0 })
    else -> ReportResult.Failed(failure)
}

/** The details field's rule, counted exactly as the server counts it (PL, TL). */
object ReportDetails {
    const val MAX = 1_000

    /** PHP `trim`: ASCII space, tab, newline, carriage return, NUL and vertical tab only, never Unicode whitespace. */
    fun phpTrim(text: String) = text.trim { it == ' ' || it == '\t' || it == '\n' || it == '\r' || it == '\u0000' || it == '\u000B' }

    /** Code points after PHP trim, the server's `mb_strlen`: an emoji counts once. */
    fun length(text: String): Int = phpTrim(text).let { it.codePointCount(0, it.length) }

    /** Over the limit: Send is disabled locally; nothing is ever truncated (PL). The server stays the authority. */
    fun tooLong(text: String) = length(text) > MAX

    /** What goes on the wire: the text as typed, or nothing when it's empty after the server's own trim. */
    fun wire(text: String): String? = text.takeIf { phpTrim(it).isNotEmpty() }
}

/**
 * Whether Report… shows for a message (P-160; the server's requireReportable). Never your own, deleted or system
 * messages. A room message is never refused for emptiness; a DM only when it has neither text nor an attachment, and
 * only the recipient may report it. An attachment-only message is reportable (TL).
 */
fun canReportMessage(direct: Boolean, own: Boolean, deleted: Boolean, system: Boolean, hasBody: Boolean, hasAttachment: Boolean): Boolean =
    !own && !deleted && !system && (!direct || hasBody || hasAttachment)

/** Report {name}… on a profile card (P-165): only with strict `can_report: true` and `reported_by_me: false`, never your own. */
fun offersPersonReport(facts: ReportFacts?, ownCard: Boolean) = !ownCard && facts?.canReport == true && !facts.reportedByMe

/** "You've reported {name}." replaces the entry when the server says so (P-165); missing facts show neither. */
fun showsReportedCaption(facts: ReportFacts?, ownCard: Boolean) = !ownCard && facts?.reportedByMe == true

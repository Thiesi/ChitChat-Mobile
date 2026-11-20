package io.github.thiesi.chitchat.feature.reports

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.reports.ReportCategory
import io.github.thiesi.chitchat.core.reports.ReportDetails
import io.github.thiesi.chitchat.core.reports.ReportOperations
import io.github.thiesi.chitchat.core.reports.ReportRefusal
import io.github.thiesi.chitchat.core.reports.ReportResult
import io.github.thiesi.chitchat.core.reports.ReportTarget
import io.github.thiesi.chitchat.core.session.SessionPhase
import io.github.thiesi.chitchat.core.session.SessionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** A line kept with the form (or with the Unknown summary). Nothing it describes was sent by the attempt it follows. */
sealed interface ReportNotice {
    /** The report budget's wait (the server's positive seconds), or unknown. Display only: it never sends. */
    data class RateLimited(val retryAfterSeconds: Long?) : ReportNotice
    /** The account no longer has a picture: that category is removed and the choice cleared (PL). */
    data object PictureGone : ReportNotice
    /** "Something went wrong. Nothing was sent." (a 400, or refused before sending). */
    data object NotSent : ReportNotice
    /** "Nothing was sent." The server couldn't keep a copy of the picture just now. */
    data object SnapshotUnavailable : ReportNotice
    /** Person Check: the report didn't arrive. Send report is an explicit choice again. */
    data object DidNotArrive : ReportNotice
}

/** How a report ended; the form closes and the UI shows the level-1 line where the report started (PL). */
sealed interface ReportOutcome {
    /** 201, a person Check that found the report, or a Send again answered already-reported: "Thanks. Your report…" */
    data object Sent : ReportOutcome
    /** A first attempt answered already-reported: "You've already reported…" (level-1 success, PL). */
    data object AlreadyReported : ReportOutcome
    data class Refused(val reason: ReportRefusal) : ReportOutcome
}

sealed interface ReportPhase {
    /** Choosing a category and typing details. */
    data class Editing(val notice: ReportNotice? = null) : ReportPhase
    data object Sending : ReportPhase
    /** It may have arrived: the read-only summary with Send again (message) or Check (person). Never automatic. */
    data class Unknown(val notice: ReportNotice? = null) : ReportPhase
    /** Person Check: one profile GET with the frozen room context. */
    data object Checking : ReportPhase
    /** Finished. The category and details are already gone. */
    data class Done(val outcome: ReportOutcome) : ReportPhase
}

/**
 * One report's state. [category] and [details] live only here, in memory, and are frozen (read-only) while sending,
 * in the Unknown summary and while checking. They never reach a bundle, a log or [toString] (TL, P-164).
 */
class ReportState internal constructor(
    val target: ReportTarget,
    val category: ReportCategory?,
    val details: String,
    val phase: ReportPhase,
    /** Whether the picture category is offered (a person with a current picture). */
    val pictureAvailable: Boolean,
    /** This attempt is a Send again after Unknown: its already-reported answer means the first one arrived (PL). */
    val afterUnknown: Boolean,
) {
    val categories: List<ReportCategory>
        get() = if (target is ReportTarget.Person) ReportCategory.personCategories(pictureAvailable) else ReportCategory.messageCategories
    val editable get() = phase is ReportPhase.Editing
    val canSend get() = editable && category != null && !ReportDetails.tooLong(details)

    internal fun copy(category: ReportCategory? = this.category, details: String = this.details, phase: ReportPhase = this.phase,
        pictureAvailable: Boolean = this.pictureAvailable, afterUnknown: Boolean = this.afterUnknown) =
        ReportState(target, category, details, phase, pictureAvailable, afterUnknown)

    override fun toString() = "ReportState(${target::class.simpleName}, phase=$phase)"
}

/**
 * Reporting a message or a person (reporting.md, P-160–P-167; TL conditions of 2026-11-20). App-scoped, memory only.
 * One report at a time. Each POST is one explicit request: Send again (message) and a send after Check (person) are new
 * choices, never automatic, never on foreground or reconnect. It survives configuration change; [close] (Cancel, Close,
 * genuine background, navigation away) and any session change drop everything, cancelling an attempt in flight.
 */
class ReportViewModel(private val operations: ReportOperations) : ViewModel() {
    private val mutable = MutableStateFlow<ReportState?>(null)
    val state = mutable.asStateFlow()
    private var identity = operations.session.value.identity()
    private var attempt = 0L
    private var job: Job? = null

    private data class Identity(val server: String, val userId: Long, val generation: Long)
    private fun SessionState.identity(): Identity? =
        user?.takeIf { phase == SessionPhase.AUTHENTICATED }?.let { u -> server?.let { Identity(it.value, u.id, generation) } }

    init {
        viewModelScope.launch {
            operations.session.collect { session ->
                val next = session.identity()
                if (next != identity) { close(); identity = next }
            }
        }
    }

    /** Report… or Report {name}…: a new form. [pictureAvailable] is the card's current-picture fact (person only). */
    fun open(target: ReportTarget, pictureAvailable: Boolean = false) {
        if (operations.session.value.identity() == null || operations.session.value.identity() != identity) return
        if (mutable.value?.phase.let { it != null && it !is ReportPhase.Done }) return
        job?.cancel(); attempt++
        mutable.value = ReportState(target, null, "", ReportPhase.Editing(), pictureAvailable && target is ReportTarget.Person, false)
    }

    fun choose(category: ReportCategory) {
        val current = mutable.value ?: return
        if (!current.editable || category !in current.categories) return
        mutable.value = current.copy(category = category)
    }

    fun edit(details: String) {
        val current = mutable.value ?: return
        if (current.editable) mutable.value = current.copy(details = details)
    }

    /** Send report: the first attempt, or a new one after Did not arrive or a refusal that kept the form. */
    fun send() {
        val current = mutable.value ?: return
        if (!current.canSend) return
        launch(current.copy(phase = ReportPhase.Sending, afterUnknown = false))
    }

    /** Send again after a message Unknown: the same frozen category and details (TL). Only on a tap. */
    fun sendAgain() {
        val current = mutable.value ?: return
        if (current.phase !is ReportPhase.Unknown || current.target is ReportTarget.Person || current.category == null) return
        launch(current.copy(phase = ReportPhase.Sending, afterUnknown = true))
    }

    /** Check after a person Unknown: one profile GET with the frozen room context; never a POST. */
    fun check() {
        val current = mutable.value ?: return
        val target = current.target as? ReportTarget.Person ?: return
        if (current.phase !is ReportPhase.Unknown) return
        val session = operations.session.value
        if (session.identity() != identity) return
        val mine = ++attempt
        mutable.value = current.copy(phase = ReportPhase.Checking)
        job = viewModelScope.launch {
            val facts = try { operations.personFacts(session, target) } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: ApiFailure) { null }
            if (mine != attempt) return@launch
            val now = mutable.value ?: return@launch
            mutable.value = when (facts?.reportedByMe) {
                true -> done(ReportOutcome.Sent)
                // It didn't arrive: the form is editable again and Send report is an explicit choice.
                false -> now.copy(phase = ReportPhase.Editing(ReportNotice.DidNotArrive), afterUnknown = false)
                // The read failed or said nothing: still Unknown, with Check still offered.
                null -> now.copy(phase = ReportPhase.Unknown())
            }
        }
    }

    /** Cancel, Close, Done, genuine background, navigation away or sign-out: everything is dropped. */
    fun close() {
        job?.cancel(); job = null; attempt++
        mutable.value = null
    }

    private fun done(outcome: ReportOutcome) = mutable.value!!.let {
        ReportState(it.target, null, "", ReportPhase.Done(outcome), false, false)
    }

    private fun launch(sending: ReportState) {
        val session = operations.session.value
        if (session.identity() == null || session.identity() != identity) return
        val category = sending.category ?: return
        val details = ReportDetails.wire(sending.details)
        val mine = ++attempt
        mutable.value = sending
        job = viewModelScope.launch {
            val result = operations.send(session, sending.target, category, details)
            if (mine != attempt || mutable.value == null) return@launch
            mutable.value = next(sending, result)
        }
    }

    private fun next(sent: ReportState, result: ReportResult): ReportState = when (result) {
        is ReportResult.Sent -> done(ReportOutcome.Sent)
        // A Send again answered already-reported: the first attempt arrived, so it reads as sent (PL).
        ReportResult.AlreadyReported -> done(if (sent.afterUnknown) ReportOutcome.Sent else ReportOutcome.AlreadyReported)
        is ReportResult.Refused -> done(ReportOutcome.Refused(result.reason))
        is ReportResult.Unknown -> sent.copy(phase = ReportPhase.Unknown())
        is ReportResult.RateLimited -> {
            val notice = ReportNotice.RateLimited(result.retryAfterSeconds)
            // After an Unknown the earlier attempt may still have arrived: the summary stays, with the wait.
            if (sent.afterUnknown) sent.copy(phase = ReportPhase.Unknown(notice)) else sent.copy(phase = ReportPhase.Editing(notice))
        }
        ReportResult.PictureGone -> sent.copy(category = null, pictureAvailable = false, phase = ReportPhase.Editing(ReportNotice.PictureGone))
        ReportResult.SnapshotUnavailable -> sent.copy(phase = ReportPhase.Editing(ReportNotice.SnapshotUnavailable))
        ReportResult.Invalid, is ReportResult.Failed ->
            if (sent.afterUnknown) sent.copy(phase = ReportPhase.Unknown(ReportNotice.NotSent))
            else sent.copy(phase = ReportPhase.Editing(ReportNotice.NotSent))
    }
}

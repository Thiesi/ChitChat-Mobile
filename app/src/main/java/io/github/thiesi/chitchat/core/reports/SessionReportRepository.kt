package io.github.thiesi.chitchat.core.reports

import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.session.SessionRepository
import io.github.thiesi.chitchat.core.session.SessionState

/**
 * Reports through the original rendered session (reporting.md). Each POST is one request, never resent; its refusals
 * stay typed. A report budget's `429 rate_limited` stays local to the form (TL ruling): it doesn't lock the session.
 */
internal class SessionReportRepository(private val sessions: SessionRepository) : ReportOperations {
    override val session = sessions.state

    override suspend fun send(expected: SessionState, target: ReportTarget, category: ReportCategory, details: String?): ReportResult {
        if (category.personOnly && target !is ReportTarget.Person) return ReportResult.Invalid
        // Refused before sending: the session's own 429 wait shows as the wait; anything else is Failed. Nothing was sent.
        val access = try { sessions.openAccess(expected) } catch (failure: ApiFailure) {
            return if (failure.status == 429) ReportResult.RateLimited(failure.retryAfterSeconds?.takeIf { it > 0 }) else ReportResult.Failed(failure)
        }
        return try {
            val caseId = sessions.authorized(access, localFailure = ApiFailure::isReportBudget) {
                when (target) {
                    is ReportTarget.RoomMessage -> reportMessage(false, target.messageId, category.wire, details, access.csrf)
                    is ReportTarget.DirectMessage -> reportMessage(true, target.messageId, category.wire, details, access.csrf)
                    is ReportTarget.Person -> reportUser(target.userId, target.roomId, category.wire, details, access.csrf)
                }
            }
            ReportResult.Sent(caseId)
        } catch (failure: ApiFailure) { reportResult(failure, category) }
    }

    override suspend fun personFacts(expected: SessionState, target: ReportTarget.Person): ReportFacts? {
        val access = sessions.openAccess(expected)
        return sessions.authorized(access) { profile(target.userId, target.roomId) }.report
    }
}

package io.github.thiesi.chitchat.core.reports

import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.network.FailureKind
import io.github.thiesi.chitchat.core.network.reportFacts
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reporting R1: the pure rules (reporting.md, P-160–P-167; TL conditions of 2026-11-20). */
class ReportsTest {
    private fun http(status: Int, code: String?, wait: Long? = null) = ApiFailure(FailureKind.HTTP, status, code, retryAfterSeconds = wait)

    @Test fun categoriesMatchTheServerAndPersonOnlyOnesComeFirst() {
        assertEquals(listOf("spam", "harassment", "hate", "threats", "sexual_content", "privacy", "impersonation", "other"),
            ReportCategory.messageCategories.map { it.wire })
        assertEquals(listOf("offensive_username", "offensive_profile_picture") + ReportCategory.messageCategories.map { it.wire },
            ReportCategory.personCategories(hasPicture = true).map { it.wire })
        assertEquals("The picture category only with a current picture", listOf("offensive_username") + ReportCategory.messageCategories.map { it.wire },
            ReportCategory.personCategories(hasPicture = false).map { it.wire })
    }

    @Test fun everyRefusalMapsToItsOutcome() {
        val c = ReportCategory.SPAM
        assertEquals(ReportResult.AlreadyReported, reportResult(http(409, "message_already_reported"), c))
        assertEquals(ReportResult.AlreadyReported, reportResult(http(409, "already_reported"), c))
        assertEquals(ReportResult.Refused(ReportRefusal.NOT_REPORTABLE), reportResult(http(409, "message_not_reportable"), c))
        assertEquals(ReportResult.SnapshotUnavailable, reportResult(http(409, "report_snapshot_unavailable"), c))
        assertEquals(ReportResult.Refused(ReportRefusal.NOT_FOUND), reportResult(http(404, "message_not_found"), c))
        assertEquals(ReportResult.Refused(ReportRefusal.NOT_FOUND), reportResult(http(404, "user_not_found"), c))
        for (code in listOf("report_recipient_required", "report_not_allowed", "guest_not_allowed"))
            assertEquals(code, ReportResult.Refused(ReportRefusal.NOT_ALLOWED), reportResult(http(403, code), c))
        assertEquals(ReportResult.Invalid, reportResult(http(400, "validation_error"), c))
        assertEquals("A 400 on the picture category means the picture is gone",
            ReportResult.PictureGone, reportResult(http(400, "validation_error"), ReportCategory.OFFENSIVE_PROFILE_PICTURE))
    }

    @Test fun every429IsTheWaitAndOnlyTheReportBudgetsIsLocal() {
        assertEquals(ReportResult.RateLimited(42), reportResult(http(429, "rate_limited", 42), ReportCategory.HATE))
        assertEquals("A non-positive wait is unknown", ReportResult.RateLimited(null), reportResult(http(429, "rate_limited", 0), ReportCategory.HATE))
        assertEquals("A proxy's 429 is still a refusal", ReportResult.RateLimited(null), reportResult(http(429, null), ReportCategory.HATE))
        assertTrue(http(429, "rate_limited").isReportBudget())
        assertFalse("A non-JSON 429 takes the session-wide path", http(429, null).isReportBudget())
        assertFalse(http(429, "other_code").isReportBudget())
        assertFalse(http(400, "rate_limited").isReportBudget())
    }

    @Test fun anythingThatMayHaveCommittedIsUnknownAndADefiniteRefusalIsFailed() {
        val c = ReportCategory.OTHER
        assertTrue(reportResult(ApiFailure(FailureKind.NETWORK), c) is ReportResult.Unknown)
        assertTrue(reportResult(ApiFailure(FailureKind.TIMEOUT), c) is ReportResult.Unknown)
        assertTrue(reportResult(http(502, null), c) is ReportResult.Unknown)
        assertTrue("An unreadable reply to a POST", reportResult(ApiFailure(FailureKind.INCOMPATIBLE), c) is ReportResult.Unknown)
        assertTrue(reportResult(http(401, "authentication_required"), c) is ReportResult.Failed)
        assertTrue(reportResult(http(403, "csrf_failed"), c) is ReportResult.Failed)
        assertTrue(reportResult(http(403, "forbidden"), c) is ReportResult.Failed)
    }

    @Test fun theCounterCountsLikeTheServer() {
        assertEquals("PHP trim strips ASCII whitespace only", "x", ReportDetails.phpTrim(" \t\n\r\u0000\u000Bx \t"))
        assertEquals("Never Unicode whitespace", " x ", ReportDetails.phpTrim("  x  "))
        assertEquals("An emoji counts once", 1, ReportDetails.length("😀"))
        assertEquals(3, ReportDetails.length("  a😀b  "))
        assertFalse(ReportDetails.tooLong("a".repeat(1_000)))
        assertTrue(ReportDetails.tooLong("a".repeat(1_001)))
        assertFalse("Surrounding spaces don't count", ReportDetails.tooLong(" ".repeat(50) + "a".repeat(1_000) + "\n"))
        assertFalse("1,000 emoji are 1,000 characters", ReportDetails.tooLong("😀".repeat(1_000)))
    }

    @Test fun detailsGoAsTypedOrNotAtAll() {
        assertEquals("As typed, untrimmed", "  why \n", ReportDetails.wire("  why \n"))
        assertNull(ReportDetails.wire(""))
        assertNull("Empty after the server's own trim", ReportDetails.wire(" \t\n"))
        assertEquals("Unicode whitespace isn't trimmed by the server", " ", ReportDetails.wire(" "))
    }

    @Test fun reportOnlyWhereTheServerAllows() {
        fun room(own: Boolean = false, deleted: Boolean = false, system: Boolean = false, body: Boolean = true, attachment: Boolean = false) =
            canReportMessage(false, own, deleted, system, body, attachment)
        fun dm(outgoing: Boolean = false, deleted: Boolean = false, body: Boolean = true, attachment: Boolean = false) =
            canReportMessage(true, outgoing, deleted, false, body, attachment)
        assertTrue(room())
        assertFalse(room(own = true)); assertFalse(room(deleted = true)); assertFalse(room(system = true))
        assertTrue("A room message is never refused for emptiness", room(body = false))
        assertTrue("An attachment-only room message", room(body = false, attachment = true))
        assertTrue(dm())
        assertFalse("Only the recipient reports a DM", dm(outgoing = true))
        assertFalse(dm(deleted = true))
        assertTrue("An attachment-only DM is reportable (TL)", dm(body = false, attachment = true))
        assertFalse("A DM with neither text nor attachment", dm(body = false, attachment = false))
    }

    @Test fun thePersonEntryNeedsStrictFacts() {
        assertTrue(offersPersonReport(ReportFacts(canReport = true, reportedByMe = false), ownCard = false))
        assertFalse(offersPersonReport(ReportFacts(canReport = true, reportedByMe = true), ownCard = false))
        assertFalse(offersPersonReport(ReportFacts(canReport = false, reportedByMe = false), ownCard = false))
        assertFalse("Missing facts hide it", offersPersonReport(null, ownCard = false))
        assertFalse("Never your own card", offersPersonReport(ReportFacts(true, false), ownCard = true))
        assertTrue(showsReportedCaption(ReportFacts(canReport = true, reportedByMe = true), ownCard = false))
        assertFalse(showsReportedCaption(null, ownCard = false))
        assertFalse(showsReportedCaption(ReportFacts(true, true), ownCard = true))
    }

    @Test fun profileFactsDecodeStrictly() {
        assertEquals(ReportFacts(true, false), reportFacts(JsonPrimitive(true), JsonPrimitive(false)))
        assertNull("Missing is unknown", reportFacts(null, JsonPrimitive(false)))
        assertNull(reportFacts(JsonPrimitive(true), null))
        assertNull("A quoted Boolean is unknown", reportFacts(JsonPrimitive("true"), JsonPrimitive(false)))
        assertNull("Null is unknown", reportFacts(JsonNull, JsonPrimitive(false)))
        assertNull("A number is unknown", reportFacts(JsonPrimitive(1), JsonPrimitive(false)))
    }
}

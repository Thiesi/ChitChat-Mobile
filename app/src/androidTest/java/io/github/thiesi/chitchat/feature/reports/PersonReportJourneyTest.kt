package io.github.thiesi.chitchat.feature.reports

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.thiesi.chitchat.R
import io.github.thiesi.chitchat.core.account.IgnoreOperations
import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.network.FailureKind
import io.github.thiesi.chitchat.core.profile.BasicProfile
import io.github.thiesi.chitchat.core.profile.ProfileSnapshot
import io.github.thiesi.chitchat.core.reports.ReportCategory
import io.github.thiesi.chitchat.core.reports.ReportFacts
import io.github.thiesi.chitchat.core.reports.ReportOperations
import io.github.thiesi.chitchat.core.reports.ReportRefusal
import io.github.thiesi.chitchat.core.reports.ReportResult
import io.github.thiesi.chitchat.core.reports.ReportTarget
import io.github.thiesi.chitchat.core.session.SessionState
import io.github.thiesi.chitchat.core.ui.ReceivedDirects
import io.github.thiesi.chitchat.core.ui.ReceivedFiles
import io.github.thiesi.chitchat.core.ui.ReceivedFiles.dm
import io.github.thiesi.chitchat.core.ui.ReceivedFiles.roomMessage
import io.github.thiesi.chitchat.core.ui.ReceivedRooms
import io.github.thiesi.chitchat.feature.account.IgnorePersonViewModel
import io.github.thiesi.chitchat.feature.conversation.ConversationScreen
import io.github.thiesi.chitchat.feature.conversation.ConversationViewModel
import io.github.thiesi.chitchat.feature.direct.DirectThreadScreen
import io.github.thiesi.chitchat.feature.direct.DirectThreadViewModel
import io.github.thiesi.chitchat.feature.profile.ProfileDialog
import io.github.thiesi.chitchat.feature.profile.ProfileState
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Reporting R3: a person report in the real room and DM screens it belongs to (reporting.md "Report a person"; TL
 * conditions of 2026-11-22). The card's tap is [ReportViewModel.requestPerson] with the card's frozen facts, as the root
 * passes them; ProfileJourneyTest covers the tap on the real card.
 */
class PersonReportJourneyTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val session = ReceivedFiles.session()
    private val rooms = ReceivedRooms(session).apply { messages = listOf(roomMessage(3, "hello")) }
    private val room = ConversationViewModel(rooms)
    private val directs = ReceivedDirects(session).apply { rows = listOf(dm(3, "hi", null)) }
    private val direct = DirectThreadViewModel(directs)
    private val fake = Reports(session)
    private val reports = ReportViewModel(fake)
    private val ignoreCalls = CopyOnWriteArrayList<Long>()
    private val ignores = IgnorePersonViewModel(object : IgnoreOperations {
        override val session = this@PersonReportJourneyTest.session
        override suspend fun change(expected: SessionState, userId: Long, ignored: Boolean): Set<Long> { ignoreCalls += userId; return setOf(userId) }
    })

    @After fun tearDown() { compose.runOnIdle { room.cancel(); direct.background(); reports.close() } }

    private fun text(id: Int, vararg args: Any) = context.getString(id, *args)
    private fun present(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    private fun policy() { session.value = session.value.copy(chatPolicy = io.github.thiesi.chitchat.core.conversation.ChatPolicy(
        emptySet(), null, io.github.thiesi.chitchat.core.conversation.DisplayPreferences())) }

    private fun show(dm: Boolean = false) {
        compose.setContent { MaterialTheme { CompositionLocalProvider(LocalReports provides reports) {
            Box(Modifier.size(320.dp, 640.dp)) {
                if (dm) DirectThreadScreen(direct, 8, {}) else ConversationScreen(room, 42, {}, ignores = ignores)
            }
        } } }
        if (dm) compose.waitUntil(5_000) { direct.state.value.loaded && !direct.state.value.busy }
        else compose.waitUntil(5_000) { room.state.value.loaded && !room.state.value.busy && room.state.value.context != null }
    }
    /** What Report {name}… on Bob's card does, with the card's frozen facts. */
    private fun reportBob(roomId: Long?, origin: ReportOrigin, picture: Boolean = true) {
        compose.runOnIdle { assertEquals(ReportOpen.OPENED,
            reports.requestPerson(ReportTarget.Person(8, roomId), origin, picture, "Bob", reports.settled)) }
        compose.waitUntil(5_000) { present("report_dialog") }
    }
    private fun choose(category: ReportCategory) = compose.onNodeWithTag("report_category:${category.wire}").performScrollTo().performClick()
    private fun send() { compose.onNodeWithTag("report_send").performScrollTo().performClick() }

    @Test fun aRoomCardsReportUsesTheRoomCopyAndOffersIgnoreForThatPerson() {
        policy(); show(); reportBob(42, ReportOrigin.Room(42))
        compose.onNode(hasText(text(R.string.report_person_title, "Bob")) and hasAnyAncestor(hasTestTag("report_dialog"))).assertExists()
        compose.onNodeWithTag("report_person").assertTextEquals("Bob")
        compose.onNodeWithTag("report_who").assertTextEquals(text(R.string.report_person_who_room, "Lounge", "Bob"))
        assertTrue("The picture category, with a current picture", present("report_category:offensive_profile_picture"))
        assertTrue("No sender excerpt for a person", !present("report_excerpt"))
        choose(ReportCategory.OFFENSIVE_USERNAME); send()
        compose.waitUntil(5_000) { present("report_sheet") }
        assertEquals("The card's own room context", ReportTarget.Person(8, 42), fake.sends.single().first)
        compose.onNodeWithTag("report_outcome").assertTextEquals(text(R.string.report_sent_room, "Lounge"))
        compose.onNodeWithTag("report_sheet_action").assertTextEquals(text(R.string.report_sheet_ignore, "Bob")).performClick()
        compose.waitUntil(5_000) { present("room_person_dialog") }
        assertTrue("Nothing applied before the dialog's own confirm", ignoreCalls.isEmpty())
    }

    @Test fun withoutARoomContextOrAPictureTheCopyIsGlobalAndNameOnly() {
        // TL condition 3: a card whose profile came without a room context reports globally, never with this room's.
        policy(); show(); reportBob(null, ReportOrigin.Room(42), picture = false)
        compose.onNodeWithTag("report_who").assertTextEquals(text(R.string.report_person_who_global_no_picture, "Bob"))
        assertTrue("No picture, no picture category", !present("report_category:offensive_profile_picture"))
        choose(ReportCategory.SPAM); send()
        compose.waitUntil(5_000) { present("report_outcome") }
        compose.onNodeWithTag("report_outcome").assertTextEquals(text(R.string.report_sent_dm))
        assertEquals(ReportTarget.Person(8, null), fake.sends.single().first)
    }

    @Test fun aDmCardsReportIsGlobalAndOffersBlock() {
        show(dm = true); reportBob(null, ReportOrigin.Direct(8))
        compose.onNodeWithTag("report_who").assertTextEquals(text(R.string.report_person_who_global, "Bob"))
        choose(ReportCategory.HARASSMENT); send()
        compose.waitUntil(5_000) { present("report_sheet") }
        compose.onNodeWithTag("report_sheet_action").performClick()
        compose.waitUntil(5_000) { present("dm_confirm_block") }
        assertTrue("The sheet closes", !present("report_sheet"))
    }

    @Test fun anUnknownOffersCheckOnlyAndCheckNeverPosts() {
        fake.results += ReportResult.Unknown(ApiFailure(FailureKind.TIMEOUT))
        policy(); show(); reportBob(42, ReportOrigin.Room(42))
        choose(ReportCategory.OFFENSIVE_USERNAME); send()
        compose.waitUntil(5_000) { present("report_check") }
        assertTrue("Never Send again for a person", !present("report_send_again"))
        fake.facts = ReportFacts(canReport = false, reportedByMe = true)
        compose.onNodeWithTag("report_check").performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(5_000) { present("report_outcome") }
        assertEquals("One POST; Check is a GET with the same room", 1, fake.sends.size)
        assertEquals(listOf(ReportTarget.Person(8, 42)), fake.checks.toList())
    }

    @Test fun aSnapshotRefusalKeepsTheFormAndARefusalClosesWithTheNamedLine() {
        fake.results += ReportResult.SnapshotUnavailable
        fake.results += ReportResult.Refused(ReportRefusal.NOT_ALLOWED)
        policy(); show(); reportBob(42, ReportOrigin.Room(42))
        choose(ReportCategory.OFFENSIVE_PROFILE_PICTURE); send()
        compose.waitUntil(5_000) { present("report_notice") }
        assertTrue("Nothing was sent: the form stays", present("report_dialog"))
        send()
        compose.waitUntil(5_000) { present("report_outcome") }
        compose.onNodeWithTag("report_outcome").assertTextEquals(text(R.string.report_person_not_allowed, "Bob"))
        assertTrue("No follow-up after a refusal", !present("report_sheet"))
        assertEquals(2, fake.sends.size)
    }

    @Test fun thePictureGoneRefusalRemovesThatCategory() {
        fake.results += ReportResult.PictureGone
        policy(); show(); reportBob(42, ReportOrigin.Room(42))
        choose(ReportCategory.OFFENSIVE_PROFILE_PICTURE); send()
        compose.waitUntil(5_000) { !present("report_category:offensive_profile_picture") }
        compose.onNode(hasText(text(R.string.report_person_picture_gone, "Bob"))).assertExists()
        compose.onNodeWithTag("report_who").assertTextEquals(text(R.string.report_person_who_room_no_picture, "Lounge", "Bob"))
    }

    /** P-165 on the card: the entry with can_report and not reported, the caption once reported, neither otherwise. */
    @Test fun theCardShowsTheEntryOrTheCaptionFromItsOwnFacts() {
        val snapshot = ProfileSnapshot(BasicProfile(8, "Bob", "2026-01-02T12:00:00Z", null, false, false, null, false), 42, true,
            ReportFacts(canReport = true, reportedByMe = false))
        var mode by androidx.compose.runtime.mutableIntStateOf(0)
        var taps = 0
        compose.setContent { MaterialTheme {
            ProfileDialog(ProfileState(snapshot = snapshot), true, {}, {},
                onReport = if (mode == 0) ({ taps++ }) else null, reported = mode == 1)
        } }
        compose.onNodeWithTag("profile_report").assertTextEquals(text(R.string.report_person_action, "Bob")).performClick()
        compose.runOnIdle { assertEquals(1, taps) }
        assertTrue(!present("profile_reported"))
        compose.runOnIdle { mode = 1 }
        compose.onNodeWithTag("profile_reported").assertTextEquals(text(R.string.report_person_reported, "Bob"))
        assertTrue("No second report", !present("profile_report"))
        compose.runOnIdle { mode = 2 }
        compose.waitForIdle()
        assertTrue("Missing facts or no origin: neither", !present("profile_report") && !present("profile_reported"))
    }

    private class Reports(override val session: kotlinx.coroutines.flow.StateFlow<SessionState>) : ReportOperations {
        val sends = CopyOnWriteArrayList<Triple<ReportTarget, ReportCategory, String?>>()
        val results = ArrayDeque<ReportResult>()
        val checks = CopyOnWriteArrayList<ReportTarget.Person>()
        @Volatile var facts: ReportFacts? = null
        override suspend fun send(expected: SessionState, target: ReportTarget, category: ReportCategory, details: String?): ReportResult {
            sends += Triple(target, category, details)
            return synchronized(results) { results.removeFirstOrNull() } ?: ReportResult.Sent(1)
        }
        override suspend fun personFacts(expected: SessionState, target: ReportTarget.Person): ReportFacts? { checks += target; return facts }
    }
}

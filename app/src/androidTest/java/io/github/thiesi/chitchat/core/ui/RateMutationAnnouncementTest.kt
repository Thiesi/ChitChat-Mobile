package io.github.thiesi.chitchat.core.ui

import android.view.View
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.platform.app.InstrumentationRegistry
import io.github.thiesi.chitchat.R
import io.github.thiesi.chitchat.core.network.SessionUser
import io.github.thiesi.chitchat.core.session.RateBudget
import io.github.thiesi.chitchat.core.session.RateLimitNotice
import io.github.thiesi.chitchat.core.session.RateWait
import io.github.thiesi.chitchat.core.session.SessionPhase
import io.github.thiesi.chitchat.core.session.SessionState
import io.github.thiesi.chitchat.core.theme.ChitChatTheme
import io.github.thiesi.chitchat.testing.LiveAnnouncements
import io.github.thiesi.chitchat.testing.LiveFlushSentinel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * #323b2 (TL, the #342 contract): the dialog wait line's announcements, counted from real accessibility events in the
 * dialog's OWN window (a Dialog is a separate window: the dialog's AndroidComposeView is captured through LocalView and its
 * event gate opened; the sentinel sits in the same window). Every count is between two delivered sentinels.
 * - A 429 arriving while the picker is open: one announcement; each refused tap during the wait: one more.
 * - Clearing (an unknown wait closed, or a known wait's deadline): none.
 * - Empty: no text, no description, so no swipe stop.
 */
class RateMutationAnnouncementTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val base = 1_000_000L

    private var notice by mutableStateOf<RateLimitNotice?>(null)
    private var deadline by mutableStateOf<Long?>(null)
    private var repeats by mutableIntStateOf(0)
    private val sentinel = mutableIntStateOf(0)
    @Volatile private var dialogView: View? = null
    @Volatile private var sentinelView: View? = null

    private fun session(deadline: Long?) = SessionState(generation = 3, phase = SessionPhase.AUTHENTICATED,
        user = SessionUser(7, "Viewer", listOf("member"), 1, false),
        rateWaits = deadline?.let { mapOf(RateBudget.ROOM_MESSAGE_MUTATION to RateWait(it, 3, 7)) } ?: emptyMap())

    private fun picker() {
        compose.setContent {
            CompositionLocalProvider(LocalMonotonicClock provides { base + compose.mainClock.currentTime }) {
                ChitChatTheme(dark = false) {
                    val current = session(deadline)
                    MessageReactionPicker(Any(), 101, emptyList(), deleted = false, viewer = 7, busy = false, checked = false,
                        changed = false, unknownMessageId = null, dialogTag = "reactions_dialog", choicesTag = "reaction_choices",
                        canCheck = false, canReview = false, error = null, canChoose = { _, _ -> true }, onChoose = { _, _ -> },
                        onCheck = {}, onReview = {}, onDismiss = {}, rateLine = { slot ->
                            val view = LocalView.current
                            SideEffect { dialogView = view }
                            Column {
                                RateWaitDialogLine(current, mutationNotice(current, notice, RateBudget.ROOM_MESSAGE_MUTATION),
                                    R.string.rate_wait_mutations, R.string.rate_wait_mutations_unknown, "reaction_rate_line",
                                    parity = repeats % 2 == 1, drawnPadding = slot.padding)
                                // TL: the sentinel and the carrier share one AndroidComposeView (asserted in attachDialog).
                                val sentinelHost = LocalView.current
                                SideEffect { sentinelView = sentinelHost }
                                LiveFlushSentinel(sentinel.intValue)
                            }
                        })
                }
            }
        }
        compose.waitForIdle()
    }

    private fun announcements(text: () -> String) = LiveAnnouncements { node -> node.contentDescription?.toString()?.trimEnd() == text() }

    /** The dialog's own window: its AndroidComposeView, found from inside the dialog's content (not the activity's decor). */
    private fun LiveAnnouncements.attachDialog() {
        val view = checkNotNull(dialogView) { "The dialog's view wasn't captured" }
        check(view.rootView === sentinelView?.rootView) { "The sentinel and the carrier aren't in the same window" }
        compose.runOnUiThread { attach(view.rootView) }
    }

    private fun line() = compose.onNodeWithTag("reaction_rate_line", useUnmergedTree = true).fetchSemanticsNode()
    private fun description() = line().config.getOrNull(SemanticsProperties.ContentDescription)?.singleOrNull()

    /**
     * TL (the #348 rule): close a bracket only after a positive condition on the carrier. After Compose idle, the line's
     * semantics show [expected] (null: no description), so the change is applied before mark() and a following action
     * can't coalesce with it.
     */
    private fun awaitDescription(expected: String?) = compose.waitUntil(5_000) { description() == expected }

    @Test fun anUnknownWaitWhileOpenAnnouncesOnceAndPerRefusalAndClosingIsSilent() {
        val unknown = context.getString(R.string.rate_wait_mutations_unknown)
        announcements { unknown }.use { heard ->
            picker()
            heard.attachDialog()
            // Empty: no text and no description, so no swipe stop; the carrier is there (polite live region).
            val empty = line()
            assertNull(empty.config.getOrNull(SemanticsProperties.ContentDescription))
            assertTrue(empty.config.getOrNull(SemanticsProperties.Text).isNullOrEmpty())
            assertEquals(LiveRegionMode.Polite, empty.config.getOrNull(SemanticsProperties.LiveRegion))
            val start = heard.mark(compose, sentinel)
            val idBefore = line().id
            compose.runOnIdle { notice = RateLimitNotice(RateBudget.ROOM_MESSAGE_MUTATION, false) } // A 429 while open.
            awaitDescription(unknown)
            val idAfter = line().id
            val appeared = heard.mark(compose, sentinel)
            compose.runOnIdle { repeats++ } // A refused tap during the wait.
            awaitDescription("$unknown ")
            val tapped = heard.mark(compose, sentinel)
            compose.runOnIdle { notice = null } // Closing ends an unknown wait's line.
            awaitDescription(null)
            val cleared = heard.mark(compose, sentinel)
            heard.verify()
            val all = heard.spoken()
            assertEquals("None before: $all", 0, start)
            val ids = "carrier node $idBefore -> $idAfter"
            android.util.Log.i("CCMRateMutation", "unknown while open: $ids")
            assertEquals("The same carrier node ($ids)", idBefore, idAfter)
            assertEquals("Once when the 429 arrives while open ($ids): $all", 1, appeared - start)
            assertEquals("Once per refused tap: $all", 1, tapped - appeared)
            assertEquals("Clearing is silent: $all", 0, cleared - tapped)
        }
    }

    /**
     * TL ruling (a), pending the PL: opening with a known wait (another edit, delete or reaction started it) makes no live
     * announcement (the dialog's window and nodes are all new; no arming timer). The line is in the dialog's real node
     * tree, visible to the user, with its full description, and its traversal position is recorded for the PL. A refused
     * tap during that wait still announces once (the parity), so nobody acts without hearing it.
     */
    @Test fun openingWithAKnownWaitIsReadNotAnnouncedAndARefusedTapAnnouncesOnce() {
        val full = context.getString(R.string.rate_wait_mutations, context.resources.getQuantityString(R.plurals.wait_about_minutes, 5, 5))
        announcements { full }.use { heard ->
            deadline = base + compose.mainClock.currentTime + 5 * 60_000 // Known before the picker opens; notice stays null.
            picker()
            heard.attachDialog()
            val start = heard.mark(compose, sentinel)
            compose.waitForIdle()
            val settled = heard.mark(compose, sentinel)
            // The real node (as TalkBack sees it) in the dialog's window: before every action, above the emoji rows (PL).
            val record = io.github.thiesi.chitchat.testing.assertLineBeforeEveryAction(full, "reaction picker opened with a known wait")
            writeTraversal(record)
            compose.runOnIdle { repeats++ } // A refused reaction tap during that wait.
            awaitDescription("$full ")
            val tapped = heard.mark(compose, sentinel)
            heard.verify()
            assertEquals("No live announcement on opening: ${heard.spoken()}", 0, settled - start)
            assertEquals("A refused tap during the wait announces once: ${heard.spoken()}", 1, tapped - settled)
        }
    }

    private fun writeTraversal(line: String) {
        val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let { java.io.File(it) }
            ?: checkNotNull(context.getExternalFilesDir(null))
        val folder = java.io.File(output, "ccm-rate-mutation-presentation")
        check(folder.isDirectory || folder.mkdirs())
        java.io.File(folder, "rate-mutation-traversal.txt").writeText(line + "\n")
    }

    /**
     * TL: the appearance bracket uses a five-minute wait, so the line's deadline one-shot (a delay on the test's auto-advancing
     * clock) can't clear it before the delegate's diff sends the appearance (a 2 s wait did, run 45426410650: no event at
     * all). The clearing bracket then advances the clock explicitly past the deadline. No real-time sleeps.
     */
    @Test fun aKnownWaitsDeadlineClearsTheLineSilently() {
        val known = context.getString(R.string.rate_wait_mutations, context.resources.getQuantityString(R.plurals.wait_about_minutes, 5, 5))
        announcements { known }.use { heard ->
            picker() // Opened with no wait: the 429 with a known wait arrives while open.
            heard.attachDialog()
            val start = heard.mark(compose, sentinel)
            val idBefore = line().id
            compose.runOnIdle {
                deadline = base + compose.mainClock.currentTime + 5 * 60_000
                notice = RateLimitNotice(RateBudget.ROOM_MESSAGE_MUTATION, true)
            }
            awaitDescription(known)
            val idAfter = line().id
            val appeared = heard.mark(compose, sentinel)
            val afterMark = description() // Still the known line at the bracket's close? (null: cleared early.)
            compose.mainClock.advanceTimeBy(5 * 60_000 + 100L) // Explicitly past the deadline: the clearing bracket.
            compose.waitForIdle()
            awaitDescription(null)
            val cleared = heard.mark(compose, sentinel)
            heard.verify()
            assertNull("Emptied at the deadline", line().config.getOrNull(SemanticsProperties.ContentDescription))
            val ids = "carrier node $idBefore -> $idAfter, description at the bracket's close: $afterMark"
            android.util.Log.i("CCMRateMutation", "known while open: $ids")
            assertEquals("The same carrier node ($ids)", idBefore, idAfter)
            assertEquals("Once on appearance while open ($ids): ${heard.spoken()}", 1, appeared - start)
            assertEquals("The deadline's clearing is silent: ${heard.spoken()}", 0, cleared - appeared)
        }
    }
}

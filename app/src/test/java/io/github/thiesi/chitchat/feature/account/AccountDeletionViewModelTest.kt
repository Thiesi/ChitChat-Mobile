package io.github.thiesi.chitchat.feature.account

import androidx.compose.ui.text.font.FontStyle
import io.github.thiesi.chitchat.core.account.AccountAccess
import io.github.thiesi.chitchat.core.account.AccountOperations
import io.github.thiesi.chitchat.core.account.ClosureResult
import io.github.thiesi.chitchat.core.account.ClosureState
import io.github.thiesi.chitchat.core.account.VerificationMethods
import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.network.FailureKind
import io.github.thiesi.chitchat.core.network.SessionUser
import io.github.thiesi.chitchat.core.session.SessionPhase
import io.github.thiesi.chitchat.core.session.SessionState
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Delete account from the explanation screen (D2): one POST from the dialog only, never resent; Check is a session read. */
@OptIn(ExperimentalCoroutinesApi::class)
class AccountDeletionViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val fake = Accounts()
    private lateinit var model: AccountDeletionViewModel
    private val origin = "https://chat.example.test"
    @Before fun setUp() { Dispatchers.setMain(dispatcher); model = AccountDeletionViewModel(fake) }
    @After fun tearDown() { model.leave(); Dispatchers.resetMain() }

    @Test fun deleteSendsOnePostAndAConfirmedResultLeavesNoRefusal() = runTest(dispatcher) {
        advanceUntilIdle()
        model.delete(); advanceUntilIdle()
        assertEquals(1, fake.posts)
        assertNull("Closed lives in the session state, not as a refusal", model.state.value.refusal)
        assertEquals(DeletionStage.IDLE, model.state.value.stage)
        model.delete(); advanceUntilIdle()
        assertEquals("Each delete is one explicit POST", 2, fake.posts)
    }

    @Test fun stepUpAppearsOnlyOn403AndCancelSendsNothingMore() = runTest(dispatcher) {
        fake.stepUp = true; advanceUntilIdle()
        model.delete(); advanceUntilIdle()
        assertEquals(DeletionStage.VERIFYING, model.state.value.stage)
        assertEquals(1, fake.posts)
        model.cancelVerification(); advanceUntilIdle()
        assertEquals(DeletionStage.IDLE, model.state.value.stage)
        assertEquals("The 403 only, never resent", 1, fake.posts)
    }

    @Test fun verifyingRetriesOnce() = runTest(dispatcher) {
        fake.stepUp = true; advanceUntilIdle()
        model.delete(); advanceUntilIdle()
        model.verify("fixture-password", recovery = false); advanceUntilIdle()
        assertTrue(fake.verified)
        assertEquals("The 403 and one retry", 2, fake.posts)
        assertNull(model.state.value.refusal)
    }

    @Test fun definiteRefusalsShowOnTheScreenAndUnknownDoesNot() = runTest(dispatcher) {
        advanceUntilIdle()
        for (refusal in listOf(ClosureResult.LastSuperAdmin, ClosureResult.NotActive, ClosureResult.RateLimited(30))) {
            fake.result = refusal
            model.delete(); advanceUntilIdle()
            assertEquals(refusal, model.state.value.refusal)
        }
        fake.result = ClosureResult.Unknown
        model.delete(); advanceUntilIdle()
        assertNull("Unknown is the session's notice, not a refusal line", model.state.value.refusal)
    }

    @Test fun startingAgainAfterWasntDeletedClearsThatLineFirst() = runTest(dispatcher) {
        fake.session.value = fake.session.value.copy(closure = ClosureState.NotClosed(origin, 7)); advanceUntilIdle()
        model.delete(); advanceUntilIdle()
        assertEquals(1, fake.dismissals)
        assertEquals(1, fake.posts)
    }

    @Test fun noPostWhileAnOutcomeIsPending() = runTest(dispatcher) {
        fake.session.value = fake.session.value.copy(closure = ClosureState.Unknown(origin, 7)); advanceUntilIdle()
        model.delete(); advanceUntilIdle()
        assertEquals("Unknown needs Check, never a second POST", 0, fake.posts)
    }

    @Test fun leavingDuringThePostCancelsIt() = runTest(dispatcher) {
        advanceUntilIdle()
        fake.hold = CompletableDeferred()
        model.delete(); advanceUntilIdle()
        model.leave(); advanceUntilIdle()
        assertTrue(fake.cancelled)
        assertEquals(DeletionStage.IDLE, model.state.value.stage)
        assertEquals(1, fake.posts)
    }

    @Test fun theServersForcedLogoutDuringThePostNeitherCancelsItNorStrandsARefusal() = runTest(dispatcher) {
        // Review (#300): the server ends the session before it answers; D1 lands the result on its marker.
        advanceUntilIdle()
        fake.hold = CompletableDeferred()
        model.delete(); advanceUntilIdle()
        assertEquals(1, fake.posts)
        fake.session.value = fake.session.value.copy(phase = SessionPhase.INVALIDATED, user = null,
            closure = ClosureState.Waiting(origin, 7)); advanceUntilIdle()
        model.leave(); advanceUntilIdle() // The signed-out takeover disposes the explanation screen.
        assertFalse("A sent POST settles on its own", fake.cancelled)
        fake.result = ClosureResult.LastSuperAdmin
        fake.hold!!.complete(Unit); advanceUntilIdle()
        assertFalse(fake.cancelled)
        assertEquals("Never resent", 1, fake.posts)
        assertNull("The ended outcome shows it, not a stray line for a later session", model.state.value.refusal)
    }

    @Test fun aConfirmedDeletionAfterTheForcedLogoutStillCompletes() = runTest(dispatcher) {
        advanceUntilIdle()
        fake.hold = CompletableDeferred()
        model.delete(); advanceUntilIdle()
        fake.session.value = fake.session.value.copy(phase = SessionPhase.INVALIDATED, user = null,
            closure = ClosureState.Waiting(origin, 7)); advanceUntilIdle()
        fake.hold!!.complete(Unit); advanceUntilIdle()
        assertFalse(fake.cancelled)
        assertEquals("The POST returned Closed to D1", 1, fake.completed)
    }

    @Test fun leavingClearsWasntDeleted() = runTest(dispatcher) {
        fake.session.value = fake.session.value.copy(closure = ClosureState.NotClosed(origin, 7)); advanceUntilIdle()
        model.leave(); advanceUntilIdle()
        assertEquals(1, fake.dismissals)
    }

    @Test fun checkIsOneReadAndAFailedCheckShowsTheCaptionUntilTheNextTap() = runTest(dispatcher) {
        fake.session.value = fake.session.value.copy(closure = ClosureState.Unknown(origin, 7)); advanceUntilIdle()
        fake.checkHold = CompletableDeferred()
        model.check(); advanceUntilIdle()
        assertEquals(ClosureState.Unknown(origin, 7), model.state.value.checking)
        model.check(); advanceUntilIdle()
        assertEquals("One read at a time", 1, fake.checks)
        fake.checkHold!!.complete(Unit); advanceUntilIdle()
        assertNull(model.state.value.checking)
        assertTrue("Still Unknown: the check didn't get through", model.state.value.checkFailed)
        fake.checkHold = null
        fake.checkResolves = ClosureState.LikelyClosed(origin, 7)
        model.check()
        assertFalse("The next tap clears the caption", model.state.value.checkFailed)
        advanceUntilIdle()
        assertFalse(model.state.value.checkFailed)
        assertEquals(2, fake.checks)
        assertEquals("Never a POST", 0, fake.posts)
    }

    @Test fun checkWithoutAnUnknownDoesNothing() = runTest(dispatcher) {
        advanceUntilIdle()
        model.check(); advanceUntilIdle()
        assertEquals(0, fake.checks)
    }

    @Test fun doneClearsTheOutcome() = runTest(dispatcher) {
        advanceUntilIdle()
        model.dismiss(); advanceUntilIdle()
        assertEquals(1, fake.dismissals)
    }

    @Test fun theDeadlineIsTheServersInstantInTheDeviceZoneOnOneLine() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.UK)
            val shown = closureDeadline("2026-12-02T10:00:00+00:00", ZoneId.of("Europe/Berlin"))!!
            val spaced = shown.replace(' ', ' ')
            assertTrue("Medium date and short time in the device zone: $spaced", spaced.contains("2 Dec 2026") && spaced.contains("11:00"))
            assertFalse("Kept on one line: no breaking spaces", shown.contains(' '))
            assertNull("Never a guessed date", closureDeadline("not a date"))
            val about = approximateDeadline(Instant.parse("2026-11-18T10:00:00Z"), ZoneId.of("UTC"))
            assertTrue("Fourteen days on, date only: $about", about.replace(' ', ' ').contains("2 Dec 2026") && !about.contains(":"))
            assertFalse(about.contains(' '))
        } finally { Locale.setDefault(previous) }
    }

    @Test fun theRestoreLabelIsItalicAndMatchesTheButton() {
        val text = withLabel("To restore it, sign in with Restore a closed account.", "Restore a closed account")
        val span = text.spanStyles.single()
        assertEquals(FontStyle.Italic, span.item.fontStyle)
        assertEquals("Restore a closed account", text.text.substring(span.start, span.end))
        assertTrue("A missing label leaves the text plain", withLabel("Plain", "Absent").spanStyles.isEmpty())
    }

    private class Accounts : AccountOperations {
        override val session = MutableStateFlow(SessionState(generation = 1, phase = SessionPhase.AUTHENTICATED,
            user = SessionUser(7, "Alice", listOf("member"), 1, false)))
        var posts = 0; var checks = 0; var dismissals = 0; var completed = 0
        var hold: CompletableDeferred<Unit>? = null
        var checkHold: CompletableDeferred<Unit>? = null
        var checkResolves: ClosureState? = null
        var cancelled = false
        var stepUp = false
        var verified = false
        var result: ClosureResult = ClosureResult.Closed("2026-12-02T10:00:00+00:00")
        override suspend fun checkClosure() {
            checks++
            checkHold?.await()
            checkResolves?.let { session.value = session.value.copy(closure = it) }
        }
        override suspend fun dismissClosure() { dismissals++; session.value = session.value.copy(closure = null) }
        override suspend fun open(): AccountAccess = object : AccountAccess {
            override fun ensureCurrent() = Unit
            override suspend fun export() = error("No export here")
            override suspend fun verificationMethods() = VerificationMethods(false, true, 8)
            override suspend fun verifyPassword(password: String) { verified = true }
            override suspend fun verifyRecovery(code: String) = error("No recovery code here")
            override suspend fun createRecoveryKey() = error("No recovery key here")
            override suspend fun closeAccount(): ClosureResult {
                posts++
                hold?.let { try { it.await() } catch (cancel: kotlinx.coroutines.CancellationException) { cancelled = true; throw cancel } }
                if (stepUp && !verified) throw ApiFailure(FailureKind.HTTP, 403, "step_up_required")
                completed++
                return result
            }
        }
    }
}

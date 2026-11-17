package io.github.thiesi.chitchat.feature.account

import io.github.thiesi.chitchat.core.account.AccountAccess
import io.github.thiesi.chitchat.core.account.AccountOperations
import io.github.thiesi.chitchat.core.account.CreatedRecoveryKey
import io.github.thiesi.chitchat.core.account.RecoveryKey
import io.github.thiesi.chitchat.core.account.RecoveryKeyStatus
import io.github.thiesi.chitchat.core.account.UnknownResolution
import io.github.thiesi.chitchat.core.account.VerificationMethods
import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.network.FailureKind
import io.github.thiesi.chitchat.core.network.SessionUser
import io.github.thiesi.chitchat.core.session.SessionPhase
import io.github.thiesi.chitchat.core.session.SessionState
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

/** Generate or Replace from Privacy and security (O4a2b): one POST, never resent; background follows M2b2. */
@OptIn(ExperimentalCoroutinesApi::class)
class RecoveryKeyViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val fake = Accounts()
    private lateinit var model: RecoveryKeyViewModel
    private val before = "2026-11-01T10:00:00+00:00"
    private val after = "2026-11-17T10:00:00+00:00"
    @Before fun setUp() { Dispatchers.setMain(dispatcher); model = RecoveryKeyViewModel(fake) }
    @After fun tearDown() { model.leave(); Dispatchers.resetMain() }

    @Test fun replaceShowsTheNewKeyOnceAndISavedItDropsIt() = runTest(dispatcher) {
        advanceUntilIdle()
        model.request(); advanceUntilIdle()
        val shown = checkNotNull(model.state.value.shown)
        assertTrue("A key existed, so this is Replace", shown.replaced)
        assertEquals(after, shown.createdAt)
        assertFalse("The key never reaches a string", model.state.value.toString().contains("A7K2"))
        model.request(); advanceUntilIdle()
        assertEquals("No second request while a key is shown", 1, fake.posts)
        model.leave()
        assertNotNull("Background keeps the shown key (P-184)", model.state.value.shown)
        model.saved()
        assertNull(model.state.value.shown)
    }

    @Test fun createIsNotReplace() = runTest(dispatcher) {
        fake.session.value = fake.session.value.copy(recoveryKey = RecoveryKeyStatus.Known(false, null)); advanceUntilIdle()
        model.request(); advanceUntilIdle()
        assertFalse(checkNotNull(model.state.value.shown).replaced)
    }

    @Test fun backgroundDuringThePostCancelsItThenChecksCreatedAtOnceWithoutResending() = runTest(dispatcher) {
        advanceUntilIdle()
        fake.hold = CompletableDeferred()
        model.request(); advanceUntilIdle()
        assertEquals(1, fake.posts)
        model.leave(); advanceUntilIdle()
        assertTrue("The POST was cancelled", fake.cancelled)
        assertEquals(RecoveryKeyStage.IDLE, model.state.value.stage)
        assertEquals("Nothing is read while in the background", 0, fake.rereads)
        // The server had committed it: the one check on return finds a new date.
        fake.reread = RecoveryKeyStatus.Known(true, after)
        model.foreground(); advanceUntilIdle()
        assertEquals(1, fake.rereads)
        assertEquals(RecoveryKeyOutcome.Unknown(UnknownResolution.CREATED_ELSEWHERE), model.state.value.outcome)
        assertEquals("Never resent", 1, fake.posts)
        model.foreground(); advanceUntilIdle()
        assertEquals("One check only", 1, fake.rereads)
        // Create again is an explicit new request.
        fake.hold = null
        model.request(); advanceUntilIdle()
        assertEquals(2, fake.posts)
        assertNotNull(model.state.value.shown)
    }

    @Test fun aCheckThatCannotReadTheStatusIsUnresolved() = runTest(dispatcher) {
        advanceUntilIdle()
        fake.hold = CompletableDeferred()
        model.request(); advanceUntilIdle()
        model.leave(); advanceUntilIdle()
        fake.reread = null
        model.foreground(); advanceUntilIdle()
        assertEquals(RecoveryKeyOutcome.Unknown(UnknownResolution.UNRESOLVED), model.state.value.outcome)
        assertEquals(1, fake.posts)
    }

    @Test fun leavingDuringStepUpIsNotUncertain() = runTest(dispatcher) {
        fake.stepUp = true; advanceUntilIdle()
        model.request(); advanceUntilIdle()
        assertEquals(RecoveryKeyStage.VERIFYING, model.state.value.stage)
        model.leave(); advanceUntilIdle()
        model.foreground(); advanceUntilIdle()
        assertEquals("The 403 committed nothing: no check", 0, fake.rereads)
        assertNull(model.state.value.outcome)
    }

    @Test fun stepUpRetriesOnce() = runTest(dispatcher) {
        fake.stepUp = true; advanceUntilIdle()
        model.request(); advanceUntilIdle()
        model.verify("fixture password", false); advanceUntilIdle()
        assertEquals(2, fake.posts)
        assertNotNull(model.state.value.shown)
    }

    @Test fun anAmbiguousResponseIsCheckedRightAwayAndNotResent() = runTest(dispatcher) {
        advanceUntilIdle()
        fake.failure = ApiFailure(FailureKind.TIMEOUT)
        fake.reread = RecoveryKeyStatus.Known(true, before)
        model.request(); advanceUntilIdle()
        assertEquals(RecoveryKeyOutcome.Unknown(UnknownResolution.UNCHANGED), model.state.value.outcome)
        assertEquals(1, fake.posts); assertEquals(1, fake.rereads)
    }

    @Test fun definiteRefusalsAreTyped() = runTest(dispatcher) {
        advanceUntilIdle()
        fake.failure = ApiFailure(FailureKind.HTTP, 409, "password_account_required")
        model.request(); advanceUntilIdle()
        assertEquals(RecoveryKeyOutcome.PasswordAccountRequired, model.state.value.outcome)
        fake.failure = ApiFailure(FailureKind.HTTP, 429, "rate_limited", retryAfterSeconds = 120)
        model.request(); advanceUntilIdle()
        assertEquals(RecoveryKeyOutcome.RateLimited(120), model.state.value.outcome)
        assertEquals(0, fake.rereads)
    }

    @Test fun anIdentityChangeDropsTheShownKeyAndAPendingCheck() = runTest(dispatcher) {
        advanceUntilIdle()
        model.request(); advanceUntilIdle()
        assertNotNull(model.state.value.shown)
        fake.session.value = fake.session.value.copy(generation = 2); advanceUntilIdle()
        assertNull("Sign-out or another session drops it", model.state.value.shown)
        fake.hold = CompletableDeferred()
        model.request(); advanceUntilIdle()
        model.leave(); advanceUntilIdle()
        fake.session.value = fake.session.value.copy(generation = 3); advanceUntilIdle()
        model.foreground(); advanceUntilIdle()
        assertEquals("A pending check belongs to its session", 0, fake.rereads)
    }

    private class Accounts : AccountOperations {
        override val session = MutableStateFlow(SessionState(generation = 1, phase = SessionPhase.AUTHENTICATED,
            user = SessionUser(7, "Alice", listOf("member"), 1, false),
            recoveryKey = RecoveryKeyStatus.Known(true, "2026-11-01T10:00:00+00:00")))
        var posts = 0; var rereads = 0
        var hold: CompletableDeferred<Unit>? = null
        var cancelled = false
        var stepUp = false
        var verified = false
        var failure: ApiFailure? = null
        var reread: RecoveryKeyStatus? = null
        override suspend fun open(): AccountAccess = object : AccountAccess {
            override fun ensureCurrent() = Unit
            override suspend fun export() = error("No export here")
            override suspend fun verificationMethods() = VerificationMethods(false, true, 8)
            override suspend fun verifyPassword(password: String) { verified = true }
            override suspend fun verifyRecovery(code: String) = error("No recovery code here")
            override suspend fun createRecoveryKey(): CreatedRecoveryKey {
                posts++
                hold?.let { try { it.await() } catch (cancel: kotlinx.coroutines.CancellationException) { cancelled = true; throw cancel } }
                if (stepUp && !verified) throw ApiFailure(FailureKind.HTTP, 403, "step_up_required")
                failure?.let { failure = null; throw it }
                return CreatedRecoveryKey(RecoveryKey("A7K2-B9QX-C4MZ-D8RT-E2WN-F6HP"), "2026-11-17T10:00:00+00:00")
            }
            override suspend fun recoveryKeyAfterRefresh(): RecoveryKeyStatus? { rereads++; return reread }
        }
    }
}

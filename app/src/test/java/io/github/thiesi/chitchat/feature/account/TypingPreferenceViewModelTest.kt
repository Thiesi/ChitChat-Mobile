package io.github.thiesi.chitchat.feature.account

import io.github.thiesi.chitchat.core.account.TypingPreferenceOperations
import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.network.FailureKind
import io.github.thiesi.chitchat.core.network.SessionUser
import io.github.thiesi.chitchat.core.session.SessionPhase
import io.github.thiesi.chitchat.core.session.SessionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TypingPreferenceViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val fake = Preferences()
    private lateinit var model: TypingPreferenceViewModel
    @Before fun setUp() { Dispatchers.setMain(dispatcher); model = TypingPreferenceViewModel(fake) { 1000L } }
    @After fun tearDown() { model.cancel(); Dispatchers.resetMain() }

    @Test fun unknownDoesNotWriteOrInventConsentAndExplicitDisableUsesCapturedIdentity() = runTest(dispatcher) {
        runCurrent(); assertEquals(0, fake.calls)
        model.change(false); runCurrent()
        assertEquals(1, fake.calls); assertEquals(false, fake.session.value.shareTyping)
        assertEquals(7L, fake.expected?.user?.id); assertFalse(model.state.value.busy)
    }
    @Test fun confirmedSameValueDoesNotPost() = runTest(dispatcher) {
        fake.session.value = fake.session.value.copy(shareTyping = false)
        model.change(false); runCurrent(); assertEquals(0, fake.calls)
        model.change(true); runCurrent(); assertEquals(true, fake.session.value.shareTyping)
    }
    @Test fun guestBusyExpiredStorageAndRateLimitNeverPost() = runTest(dispatcher) {
        val original = fake.session.value
        for (session in listOf(original.copy(phase = SessionPhase.GUEST), original.copy(busy = true),
            original.copy(refreshRequired = true), original.copy(storageWarning = true), original.copy(retryAtMillis = 2000))) {
            fake.session.value = session; model.change(true); runCurrent()
        }
        assertEquals(0, fake.calls)
    }
    @Test fun inFlightChangeCannotDoubleSubmitAndBackgroundNeverReplays() = runTest(dispatcher) {
        fake.hold = true
        model.change(false); runCurrent(); model.change(true); runCurrent()
        assertEquals(1, fake.calls); assertTrue(model.state.value.busy)
        model.cancel(); runCurrent()
        assertFalse(model.state.value.busy); assertTrue(model.state.value.unconfirmed)
        model.change(true); runCurrent(); assertEquals(1, fake.calls)
        fake.session.value = fake.session.value.copy(shareTyping = false)
        runCurrent(); assertFalse(model.state.value.unconfirmed)
    }
    @Test fun failedChangeRequiresReconciliationAndDoesNotClaimTheRequestedValue() = runTest(dispatcher) {
        fake.failure = ApiFailure(FailureKind.NETWORK)
        model.change(false); runCurrent()
        assertNull(fake.session.value.shareTyping); assertTrue(model.state.value.unconfirmed)
        assertEquals(FailureKind.NETWORK, model.state.value.error?.kind)
        model.change(false); runCurrent(); assertEquals(1, fake.calls)
        fake.session.value = fake.session.value.copy(shareTyping = true)
        runCurrent(); assertFalse(model.state.value.unconfirmed); assertNull(model.state.value.error)
    }
    @Test fun switchingIdentityCancelsAndClearsPriorFailure() = runTest(dispatcher) {
        fake.hold = true; model.change(false); runCurrent()
        fake.session.value = fake.session.value.copy(generation = 2,
            user = SessionUser(8, "Second", listOf("member"), 1, false))
        runCurrent()
        assertEquals(TypingPreferenceState(), model.state.value)
        fake.hold = false; model.change(true); runCurrent()
        assertEquals(2, fake.calls); assertEquals(8L, fake.expected?.user?.id)
    }

    private class Preferences : TypingPreferenceOperations {
        override val session = MutableStateFlow(SessionState(generation = 1, phase = SessionPhase.AUTHENTICATED,
            user = SessionUser(7, "Viewer", listOf("member"), 1, false)))
        var calls = 0
        var expected: SessionState? = null
        var hold = false
        var failure: ApiFailure? = null
        override suspend fun change(expected: SessionState, shared: Boolean) {
            calls++; this.expected = expected
            if (hold) awaitCancellation()
            failure?.let { throw it }
            session.value = session.value.copy(shareTyping = shared)
        }
    }
}

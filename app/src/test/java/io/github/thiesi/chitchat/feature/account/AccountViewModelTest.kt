package io.github.thiesi.chitchat.feature.account

import io.github.thiesi.chitchat.core.account.AccountAccess
import io.github.thiesi.chitchat.core.account.AccountOperations
import io.github.thiesi.chitchat.core.account.PersonalExport
import io.github.thiesi.chitchat.core.account.VerificationMethods
import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.network.FailureKind
import io.github.thiesi.chitchat.core.network.SessionUser
import io.github.thiesi.chitchat.core.session.SessionPhase
import io.github.thiesi.chitchat.core.session.SessionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AccountViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val fake = Accounts()
    private lateinit var model: AccountViewModel
    private var writes = 0
    private val destination = ExportDestination { bytes, current -> current(); assertEquals("private fixture", bytes.decodeToString()); writes++ }
    @Before fun setUp() { Dispatchers.setMain(dispatcher); model = AccountViewModel(fake) }
    @After fun tearDown() { model.cancel(); Dispatchers.resetMain() }

    private fun start(destination: ExportDestination = this.destination) {
        assertTrue(model.chooseDestination())
        model.destinationSelected(destination, 1)
    }

    @Test fun cancelledOrUnsolicitedPickerResultNeverContactsServer() = runTest(dispatcher) {
        model.destinationSelected(destination, 1)
        assertTrue(model.chooseDestination())
        model.destinationSelected(null, 1)
        advanceUntilIdle()
        assertEquals(0, fake.opens)
        assertEquals(0, writes)
        assertEquals(ExportStage.CANCELLED, model.state.value.stage)
    }

    @Test fun passwordVerificationRetriesOnceAndWipesTransientExportBytes() = runTest(dispatcher) {
        start(); advanceUntilIdle()
        assertEquals(ExportStage.VERIFYING, model.state.value.stage)
        model.verify("", false); advanceUntilIdle()
        assertEquals(0, fake.passwords)
        model.verify("  exact fixture password  ", false); advanceUntilIdle()
        assertEquals("  exact fixture password  ", fake.lastPassword)
        assertEquals(2, fake.exports)
        assertEquals(1, writes)
        assertEquals(ExportStage.SAVED, model.state.value.stage)
        assertTrue(fake.bytes.all { it == 0.toByte() })
    }

    @Test fun wrongPasswordAllowsCorrectionWithoutResubmittingProtectedAction() = runTest(dispatcher) {
        fake.passwordFailure = ApiFailure(FailureKind.HTTP, 403, "step_up_invalid_credentials")
        start(); advanceUntilIdle()
        model.verify("wrong fixture", false); advanceUntilIdle()
        assertEquals(ExportStage.VERIFYING, model.state.value.stage)
        assertFalse(model.state.value.verificationBusy)
        assertEquals(1, fake.exports)
        fake.passwordFailure = null
        model.verify("correct fixture", false); advanceUntilIdle()
        assertEquals(2, fake.passwords)
        assertEquals(2, fake.exports)
        assertEquals(1, writes)
    }

    @Test fun secondStepUpRejectionDoesNotLoopOrWriteAFile() = runTest(dispatcher) {
        fake.rejectEvenAfterVerification = true
        start(); advanceUntilIdle()
        model.verify("fixture", false); advanceUntilIdle()
        assertEquals(ExportStage.FAILED, model.state.value.stage)
        assertEquals(2, fake.exports)
        assertEquals(1, fake.passwords)
        assertEquals(0, writes)
    }

    @Test fun metadataNeverOffersPasswordForMfaOrPasswordlessAccounts() = runTest(dispatcher) {
        fake.methods = VerificationMethods(false, false, 0)
        start(); advanceUntilIdle()
        model.verify("fixture", false); advanceUntilIdle()
        assertEquals(0, fake.passwords)
        assertEquals(ExportStage.VERIFYING, model.state.value.stage)
        model.cancel()
        fake.methods = VerificationMethods(true, true, 8)
        start(); advanceUntilIdle()
        model.verify("fixture", false); advanceUntilIdle()
        assertEquals(0, fake.passwords)
    }

    @Test fun policyUpgradeRefreshesMethodsAndRequiresStrongerVerification() = runTest(dispatcher) {
        start(); advanceUntilIdle()
        fake.methods = VerificationMethods(true, true, 8)
        fake.passwordFailure = ApiFailure(FailureKind.HTTP, 403, "passkey_step_up_required")
        model.verify("fixture", false); advanceUntilIdle()
        assertTrue(model.state.value.methods!!.mfaEnabled)
        assertEquals(1, fake.exports)
        model.verify("UNUSED-FIXTURE-CODE", true); advanceUntilIdle()
        assertEquals(ExportStage.SAVED, model.state.value.stage)
        assertEquals(7, model.state.value.remainingCodes)
        assertEquals(1, fake.recoveries)
        assertEquals(2, fake.exports)
    }

    @Test fun ambiguousRecoveryIsNotReplayedAndWarnsThatCodeMayBeSpent() = runTest(dispatcher) {
        fake.methods = VerificationMethods(true, true, 8)
        fake.recoveryFailure = ApiFailure(FailureKind.TIMEOUT)
        start(); advanceUntilIdle()
        model.verify("UNUSED-FIXTURE-CODE", true); advanceUntilIdle()
        assertEquals(ExportStage.FAILED, model.state.value.stage)
        assertTrue(model.state.value.recoveryMayBeSpent)
        assertEquals(1, fake.exports)
        assertEquals(1, fake.recoveries)
        assertEquals(0, writes)
    }

    @Test fun backgroundCancelsWaitingVerificationAndNeverRetriesIt() = runTest(dispatcher) {
        start(); advanceUntilIdle()
        model.background(); advanceUntilIdle()
        model.verify("fixture", false); advanceUntilIdle()
        assertEquals(ExportStage.CANCELLED, model.state.value.stage)
        assertNull(model.state.value.methods)
        assertEquals(0, fake.passwords)
        assertEquals(1, fake.exports)
    }

    @Test fun pickerMayBackgroundAppButDoesNotCarryAcrossAccountChanges() = runTest(dispatcher) {
        assertTrue(model.chooseDestination())
        model.background()
        assertEquals(ExportStage.CHOOSING, model.state.value.stage)
        fake.session.value = fake.session.value.copy(generation = 2, phase = SessionPhase.SIGNED_OUT, user = null)
        advanceUntilIdle()
        model.destinationSelected(destination, 1); advanceUntilIdle()
        assertEquals(0, fake.opens)
        assertEquals(ExportStage.IDLE, model.state.value.stage)
    }

    @Test fun backgroundDuringRecoveryWarnsThatTheInFlightCodeMayBeSpent() = runTest(dispatcher) {
        fake.methods = VerificationMethods(true, true, 8)
        fake.waitForRecovery = true
        start(); advanceUntilIdle()
        model.verify("UNUSED-FIXTURE-CODE", true); runCurrent()
        model.background(); advanceUntilIdle()
        assertEquals(ExportStage.CANCELLED, model.state.value.stage)
        assertTrue(model.state.value.recoveryMayBeSpent)
        assertEquals(1, fake.recoveries)
        assertEquals(1, fake.exports)
    }

    @Test fun providerWriteFailureWipesBytesAndNeverClaimsSuccess() = runTest(dispatcher) {
        fake.verified = true
        start(ExportDestination { _, _ -> throw ApiFailure(FailureKind.STORAGE) }); advanceUntilIdle()
        assertEquals(ExportStage.FAILED, model.state.value.stage)
        assertEquals(FailureKind.STORAGE, model.state.value.error!!.kind)
        assertTrue(fake.bytes.all { it == 0.toByte() })
        assertEquals(1, fake.exports)
    }

    @Test fun oldPickerCannotExportANewAccountsDataEvenIfAnotherPickerIsPending() = runTest(dispatcher) {
        assertTrue(model.chooseDestination())
        fake.session.value = fake.session.value.copy(generation = 2)
        advanceUntilIdle()
        assertTrue(model.chooseDestination())
        model.destinationSelected(destination, 1); advanceUntilIdle()
        assertEquals(0, fake.opens)
        assertEquals(ExportStage.CHOOSING, model.state.value.stage)
    }

    @Test fun definiteRecoveryRejectionDoesNotClaimTheCodeMayBeSpent() = runTest(dispatcher) {
        fake.methods = VerificationMethods(true, true, 8)
        fake.recoveryFailure = ApiFailure(FailureKind.HTTP, 429, "rate_limited")
        start(); advanceUntilIdle()
        model.verify("UNUSED-FIXTURE-CODE", true); advanceUntilIdle()
        assertEquals(ExportStage.FAILED, model.state.value.stage)
        assertFalse(model.state.value.recoveryMayBeSpent)
        assertEquals(1, fake.recoveries)
    }

    @Test fun cancellationDuringWriteWipesBytesAndDoesNotShowSaved() = runTest(dispatcher) {
        fake.verified = true
        start(ExportDestination { _, current -> current(); awaitCancellation() }); runCurrent()
        model.background(); advanceUntilIdle()
        assertEquals(ExportStage.CANCELLED, model.state.value.stage)
        assertTrue(fake.bytes.all { it == 0.toByte() })
    }

    @Test fun destinationReturnWaitsForForegroundRevalidation() = runTest(dispatcher) {
        assertTrue(model.chooseDestination())
        fake.session.value = fake.session.value.copy(busy = true)
        model.destinationSelected(destination, 1); runCurrent()
        assertEquals(0, fake.opens)
        fake.session.value = fake.session.value.copy(busy = false)
        advanceUntilIdle()
        assertEquals(1, fake.opens)
        assertEquals(ExportStage.VERIFYING, model.state.value.stage)
    }

    private class Accounts : AccountOperations {
        override val session = MutableStateFlow(SessionState(generation = 1, phase = SessionPhase.AUTHENTICATED,
            user = SessionUser(7, "Alice", listOf("member"), 1, false)))
        var opens = 0; var exports = 0; var passwords = 0; var recoveries = 0
        var verified = false
        var rejectEvenAfterVerification = false
        var methods = VerificationMethods(false, true, 8)
        var passwordFailure: ApiFailure? = null
        var recoveryFailure: ApiFailure? = null
        var waitForRecovery = false
        var lastPassword: String? = null
        val bytes = "private fixture".encodeToByteArray()
        override suspend fun open(): AccountAccess {
            opens++
            val generation = session.value.generation
            return object : AccountAccess {
                override fun ensureCurrent() {
                    if (session.value.generation != generation || session.value.phase != SessionPhase.AUTHENTICATED)
                        throw CancellationException("Fixture session changed")
                }
                override suspend fun export(): PersonalExport {
                    ensureCurrent(); exports++
                    if (!verified || rejectEvenAfterVerification) throw ApiFailure(FailureKind.HTTP, 403, "step_up_required")
                    return PersonalExport(bytes)
                }
                override suspend fun verificationMethods() = methods
                override suspend fun verifyPassword(password: String) {
                    passwords++; lastPassword = password
                    passwordFailure?.let { throw it }
                    verified = true
                }
                override suspend fun verifyRecovery(code: String): Int {
                    recoveries++
                    if (waitForRecovery) awaitCancellation()
                    recoveryFailure?.let { throw it }; verified = true
                    return methods.recoveryCodesRemaining - 1
                }
            }
        }
    }
}

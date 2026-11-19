package io.github.thiesi.chitchat.feature.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.thiesi.chitchat.core.account.AccountAccess
import io.github.thiesi.chitchat.core.account.AccountOperations
import io.github.thiesi.chitchat.core.account.ClosureResult
import io.github.thiesi.chitchat.core.account.ClosureState
import io.github.thiesi.chitchat.core.account.VerificationMethods
import io.github.thiesi.chitchat.core.account.requestClosure
import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.session.SessionPhase
import io.github.thiesi.chitchat.core.session.SessionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class DeletionStage { IDLE, RUNNING, VERIFYING }

data class AccountDeletionUiState(
    val stage: DeletionStage = DeletionStage.IDLE,
    val methods: VerificationMethods? = null,
    val verificationBusy: Boolean = false,
    /** A step-up error shown inside the dialog. */
    val error: ApiFailure? = null,
    /** A definite refusal on a live session, shown on the explanation screen. Closed and Unknown live in `SessionState.closure`. */
    val refusal: ClosureResult? = null,
    /** The Unknown being checked: Check's session read can replace the session state while it runs (D1, TL C2). */
    val checking: ClosureState.Unknown? = null,
    /** The last Check didn't get through (PL): a caption under the notice, cleared by the next tap. */
    val checkFailed: Boolean = false,
)

/**
 * Delete account (play-readiness.md §2, D2). App-scoped, memory only. One POST through `withStepUp` (D1's
 * `requestClosure`), sent only by the decision dialog's Delete account. It cancels on genuine background and navigation,
 * not on configuration change (M2b2); D1's repository turns a cancel after sending into Unknown and lands Closed, Unknown
 * and the ended-session cases in `SessionState.closure`, even when the session ends first. Check is one session read and
 * Done clears the outcome; nothing is ever resent.
 */
class AccountDeletionViewModel(private val operations: AccountOperations) : ViewModel() {
    private val mutable = MutableStateFlow(AccountDeletionUiState())
    val state = mutable.asStateFlow()
    private var identity = operations.session.value.identity()
    private var sequence = 0L
    private var operation: Job? = null
    private var verification: Job? = null
    private var check: Job? = null
    private var gate: CompletableDeferred<Unit>? = null
    private var access: AccountAccess? = null
    /** The close POST is on the wire: the server's forced_logout may end the session before it answers (D1, TL C1). */
    private var sending = false
    /** The session identity that sent it; the screen may only cancel it while that session is still the live one. */
    private var sentBy: Identity? = null

    init {
        viewModelScope.launch {
            operations.session.collect { session ->
                val next = session.identity()
                if (next != identity) {
                    // A sent deletion settles on its own: the server's forced_logout ends the session before the 200, and
                    // D1 lands the result on its marker (review, #300). Only an unsent attempt (or its step-up) retires.
                    if (sending) retireVerification() else retire()
                    identity = next
                    // A check in flight survives the session change it causes; everything else is per identity.
                    mutable.value = AccountDeletionUiState(checking = mutable.value.checking, checkFailed = mutable.value.checkFailed)
                }
            }
        }
    }

    /** The decision dialog's Delete account: the only sender. Never automatic, never resent. */
    fun delete() {
        val session = operations.session.value
        val closure = session.closure
        if (session.identity() == null || session.identity() != identity || session.busy || session.refreshRequired ||
            session.updateRequired || (closure != null && closure !is ClosureState.NotClosed) ||
            mutable.value.stage != DeletionStage.IDLE || mutable.value.checking != null) return
        val attempt = ++sequence
        val owner = identity
        mutable.value = AccountDeletionUiState(stage = DeletionStage.RUNNING)
        operation = viewModelScope.launch {
            // A refusal belongs on the explanation screen of the session that asked; once that session has ended, D1's
            // ended-session outcome shows it instead.
            fun stillOwner() = sequence == attempt && operations.session.value.identity() == owner
            try {
                // Starting again after "wasn't deleted" (PL): that line leaves first.
                if (closure is ClosureState.NotClosed) operations.dismissClosure()
                val current = operations.open()
                val result = requestClosure({
                    sending = true; sentBy = owner
                    try { current.closeAccount() } finally { sending = false; sentBy = null }
                }, { awaitVerification(current, attempt) })
                if (stillOwner()) mutable.value = AccountDeletionUiState(refusal = result.takeIf {
                    it != ClosureResult.Unknown && it !is ClosureResult.Closed })
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: ApiFailure) {
                if (stillOwner()) mutable.value = AccountDeletionUiState(refusal = ClosureResult.Failed(failure))
            } finally {
                if (sequence == attempt) { gate?.cancel(); gate = null; access = null }
            }
        }
    }

    private suspend fun awaitVerification(current: AccountAccess, attempt: Long) {
        val methods = current.verificationMethods()
        current.ensureCurrent()
        if (sequence != attempt) throw CancellationException("Action cancelled")
        val waiting = CompletableDeferred<Unit>()
        gate = waiting
        access = current
        mutable.value = mutable.value.copy(stage = DeletionStage.VERIFYING, methods = methods, error = null)
        waiting.await()
        current.ensureCurrent()
    }

    /** The step-up dialog's Verify: the same rules as the export and Replace key. */
    fun verify(secret: String, recovery: Boolean) {
        val current = access ?: return
        val waiting = gate ?: return
        val methods = mutable.value.methods ?: return
        if (mutable.value.stage != DeletionStage.VERIFYING || mutable.value.verificationBusy || secret.isEmpty() ||
            (recovery && (!methods.mfaEnabled || methods.recoveryCodesRemaining == 0)) ||
            (!recovery && (methods.mfaEnabled || !methods.hasPassword))) return
        val attempt = sequence
        mutable.value = mutable.value.copy(verificationBusy = true, error = null)
        verification = viewModelScope.launch {
            try {
                if (recovery) current.verifyRecovery(secret) else current.verifyPassword(secret)
                current.ensureCurrent()
                if (sequence == attempt) {
                    mutable.value = mutable.value.copy(stage = DeletionStage.RUNNING, methods = null, verificationBusy = false)
                    waiting.complete(Unit)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: ApiFailure) {
                if (sequence != attempt) return@launch
                when (failure.code) {
                    "step_up_invalid_credentials", "invalid_recovery_code" ->
                        mutable.value = mutable.value.copy(error = failure, verificationBusy = false)
                    "passkey_step_up_required" -> try {
                        val fresh = current.verificationMethods().copy(mfaEnabled = true)
                        if (sequence == attempt) mutable.value = mutable.value.copy(methods = fresh, error = null, verificationBusy = false)
                    } catch (next: ApiFailure) { waiting.completeExceptionally(next) }
                    else -> waiting.completeExceptionally(failure)
                }
            }
        }
    }

    /** The step-up dialog's Cancel: the POST answered 403, so nothing was committed. */
    fun cancelVerification() { if (mutable.value.stage == DeletionStage.VERIFYING) leave() }

    /**
     * Check, after an Unknown deletion (live or ended): one session read through D1, never a resend. A Check that leaves the
     * outcome Unknown didn't get through; the next tap clears that caption.
     */
    fun check() {
        val unknown = operations.session.value.closure as? ClosureState.Unknown ?: return
        if (check?.isActive == true || mutable.value.stage != DeletionStage.IDLE) return
        mutable.value = mutable.value.copy(checking = unknown, checkFailed = false)
        check = viewModelScope.launch {
            try { operations.checkClosure() }
            finally {
                val still = operations.session.value.closure.let { it is ClosureState.Unknown && it.origin == unknown.origin && it.userId == unknown.userId }
                mutable.value = mutable.value.copy(checking = null, checkFailed = still)
            }
        }
    }

    /** Done on an outcome screen: the outcome leaves memory (TL C4). */
    fun dismiss() {
        mutable.value = mutable.value.copy(checkFailed = false, refusal = null)
        viewModelScope.launch { operations.dismissClosure() }
    }

    /**
     * Genuine background or navigation away from the explanation screen (not configuration change): an in-flight request
     * of the still-live session is cancelled (D1 then marks it Unknown), and "wasn't deleted" leaves with the screen. When
     * the session has already ended (the server's forced_logout, whose takeover also disposes the screen), a sent request
     * settles on its own (review, #300). An Unknown stays until Check resolves it.
     */
    fun leave() {
        val active = mutable.value.stage != DeletionStage.IDLE
        if (sending && operations.session.value.identity() != sentBy) retireVerification() else retire()
        mutable.value = mutable.value.copy(refusal = null).let {
            if (active) it.copy(stage = DeletionStage.IDLE, methods = null, verificationBusy = false, error = null) else it
        }
        if (operations.session.value.closure is ClosureState.NotClosed) viewModelScope.launch { operations.dismissClosure() }
    }

    private fun retire() {
        sequence++
        operation?.cancel(); verification?.cancel(); gate?.cancel()
        gate = null; access = null
    }

    /** Ends a pending step-up only; a sent POST keeps running. */
    private fun retireVerification() {
        verification?.cancel(); gate?.cancel()
        gate = null; access = null
    }

    private data class Identity(val generation: Long, val userId: Long, val version: Int)
    private fun SessionState.identity(): Identity? = user?.takeIf { phase == SessionPhase.AUTHENTICATED }
        ?.let { Identity(generation, it.id, it.sessionVersion) }

    companion object {
        fun factory(operations: AccountOperations): ViewModelProvider.Factory = viewModelFactory {
            initializer { AccountDeletionViewModel(operations) }
        }
    }
}

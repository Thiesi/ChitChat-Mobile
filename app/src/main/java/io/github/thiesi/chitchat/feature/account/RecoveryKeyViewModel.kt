package io.github.thiesi.chitchat.feature.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.thiesi.chitchat.core.account.AccountAccess
import io.github.thiesi.chitchat.core.account.AccountOperations
import io.github.thiesi.chitchat.core.account.RecoveryKey
import io.github.thiesi.chitchat.core.account.RecoveryKeyResult
import io.github.thiesi.chitchat.core.account.RecoveryKeyStatus
import io.github.thiesi.chitchat.core.account.UnknownResolution
import io.github.thiesi.chitchat.core.account.VerificationMethods
import io.github.thiesi.chitchat.core.account.requestRecoveryKey
import io.github.thiesi.chitchat.core.account.unknownResolution
import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.session.SessionPhase
import io.github.thiesi.chitchat.core.session.SessionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class RecoveryKeyStage { IDLE, RUNNING, VERIFYING, CHECKING }

/** What the Recovery key screen says after a request that didn't show a key (recovery-key.md's result table). */
sealed interface RecoveryKeyOutcome {
    data object PasswordAccountRequired : RecoveryKeyOutcome
    data class RateLimited(val retryAfterSeconds: Long?) : RecoveryKeyOutcome
    data class Unknown(val resolution: UnknownResolution) : RecoveryKeyOutcome
    data class Failed(val failure: ApiFailure) : RecoveryKeyOutcome
}

/** A key from Generate or Replace, shown once. [RecoveryKey] keeps its value out of every string. */
data class ShownRecoveryKey(val key: RecoveryKey, val createdAt: String, val replaced: Boolean)

data class RecoveryKeyUiState(
    val stage: RecoveryKeyStage = RecoveryKeyStage.IDLE,
    val methods: VerificationMethods? = null,
    val verificationBusy: Boolean = false,
    /** A step-up error shown inside the dialog. */
    val error: ApiFailure? = null,
    val outcome: RecoveryKeyOutcome? = null,
    val shown: ShownRecoveryKey? = null,
)

/**
 * Generate or Replace from Privacy and security (recovery-key.md, P-188; O4a2b). App-scoped, memory only. One POST
 * through `withStepUp`; it cancels on genuine background, navigation and logout, not on configuration change (M2b2).
 * A cancel after the POST was sent is the Unknown path: the next time the screen starts, one fenced refresh compares
 * `created_at`. Nothing is ever resent; Create again is an explicit new request. The shown key survives configuration
 * change and background, and is dropped on I've saved it or any identity change.
 */
class RecoveryKeyViewModel(private val operations: AccountOperations) : ViewModel() {
    private val mutable = MutableStateFlow(RecoveryKeyUiState())
    val state = mutable.asStateFlow()
    private var identity = operations.session.value.identity()
    private var sequence = 0L
    private var operation: Job? = null
    private var verification: Job? = null
    private var gate: CompletableDeferred<Unit>? = null
    private var access: AccountAccess? = null
    private var sending = false
    private var sentBefore: String? = null
    /** A POST that was cancelled after sending: its `created_at` before the request, awaiting the check. */
    private var pendingCheck: PendingCheck? = null
    private class PendingCheck(val before: String?)

    init {
        viewModelScope.launch {
            operations.session.collect { session ->
                val next = session.identity()
                if (next != identity) {
                    retire()
                    pendingCheck = null
                    identity = next
                    mutable.value = RecoveryKeyUiState()
                }
            }
        }
    }

    /** Replace key (after its dialog) or Create recovery key; also Create again. Never called automatically. */
    fun request() {
        val session = operations.session.value
        if (session.identity() == null || session.identity() != identity || session.busy || session.refreshRequired ||
            session.updateRequired || mutable.value.stage != RecoveryKeyStage.IDLE || mutable.value.shown != null) return
        val known = session.recoveryKey as? RecoveryKeyStatus.Known ?: return
        val before = known.createdAt
        val attempt = ++sequence
        pendingCheck = null
        mutable.value = RecoveryKeyUiState(stage = RecoveryKeyStage.RUNNING)
        operation = viewModelScope.launch {
            try {
                val current = operations.open()
                val result = requestRecoveryKey(create = {
                    sending = true; sentBefore = before
                    try { current.createRecoveryKey() } finally { sending = false }
                }, verify = { awaitVerification(current, attempt) }, before = before, reread = {
                    if (sequence == attempt) mutable.value = mutable.value.copy(stage = RecoveryKeyStage.CHECKING)
                    current.recoveryKeyAfterRefresh()
                })
                if (sequence == attempt) mutable.value = when (result) {
                    is RecoveryKeyResult.Created -> RecoveryKeyUiState(shown = ShownRecoveryKey(result.created.key,
                        result.created.createdAt, replaced = known.exists))
                    RecoveryKeyResult.PasswordAccountRequired -> RecoveryKeyUiState(outcome = RecoveryKeyOutcome.PasswordAccountRequired)
                    is RecoveryKeyResult.RateLimited -> RecoveryKeyUiState(outcome = RecoveryKeyOutcome.RateLimited(result.retryAfterSeconds))
                    is RecoveryKeyResult.Unknown -> RecoveryKeyUiState(outcome = RecoveryKeyOutcome.Unknown(result.resolution))
                    is RecoveryKeyResult.Failed -> RecoveryKeyUiState(outcome = RecoveryKeyOutcome.Failed(result.failure))
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: ApiFailure) {
                // Opening access or the step-up itself failed: nothing was committed by this request.
                if (sequence == attempt) mutable.value = RecoveryKeyUiState(outcome = RecoveryKeyOutcome.Failed(failure))
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
        mutable.value = mutable.value.copy(stage = RecoveryKeyStage.VERIFYING, methods = methods, error = null)
        waiting.await()
        current.ensureCurrent()
    }

    /** The step-up dialog's Verify: the same rules as the export (AccountViewModel). */
    fun verify(secret: String, recovery: Boolean) {
        val current = access ?: return
        val waiting = gate ?: return
        val methods = mutable.value.methods ?: return
        if (mutable.value.stage != RecoveryKeyStage.VERIFYING || mutable.value.verificationBusy || secret.isEmpty() ||
            (recovery && (!methods.mfaEnabled || methods.recoveryCodesRemaining == 0)) ||
            (!recovery && (methods.mfaEnabled || !methods.hasPassword))) return
        val attempt = sequence
        mutable.value = mutable.value.copy(verificationBusy = true, error = null)
        verification = viewModelScope.launch {
            try {
                if (recovery) current.verifyRecovery(secret) else current.verifyPassword(secret)
                current.ensureCurrent()
                if (sequence == attempt) {
                    mutable.value = mutable.value.copy(stage = RecoveryKeyStage.RUNNING, methods = null, verificationBusy = false)
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

    /** The step-up dialog's Cancel: the POST answered 403 and was never committed, so nothing is uncertain. */
    fun cancelVerification() { if (mutable.value.stage == RecoveryKeyStage.VERIFYING) leave() }

    /**
     * Genuine background or navigation away from the Recovery key screen (not configuration change). An in-flight POST
     * is cancelled; if it had been sent, or its `created_at` check was running (after an ambiguous response, or the
     * check from [foreground] itself), the next [foreground] runs the one check instead of resending.
     */
    fun leave() {
        val uncertain = sending || mutable.value.stage == RecoveryKeyStage.CHECKING
        val before = sentBefore
        val active = mutable.value.stage != RecoveryKeyStage.IDLE
        retire()
        if (uncertain) pendingCheck = PendingCheck(before)
        if (active) mutable.value = mutable.value.copy(stage = RecoveryKeyStage.IDLE, methods = null, verificationBusy = false, error = null)
    }

    /** The Recovery key screen started: a cancelled, sent POST gets its one `created_at` check now. Never a resend. */
    fun foreground() {
        val check = pendingCheck ?: return
        if (mutable.value.stage != RecoveryKeyStage.IDLE || operations.session.value.identity() != identity) return
        pendingCheck = null
        sentBefore = check.before
        val attempt = ++sequence
        mutable.value = RecoveryKeyUiState(stage = RecoveryKeyStage.CHECKING)
        operation = viewModelScope.launch {
            val now = try { operations.open().recoveryKeyAfterRefresh() } catch (_: ApiFailure) { null }
            if (sequence == attempt) mutable.value = RecoveryKeyUiState(
                outcome = RecoveryKeyOutcome.Unknown(unknownResolution(now, check.before)))
        }
    }

    /** I've saved it: the key leaves memory; nothing records whether it was saved. */
    fun saved() { mutable.value = mutable.value.copy(shown = null) }

    /** Go there or Not now on the "didn't reach this phone" notice. */
    fun dismissNotice(expected: SessionState) { viewModelScope.launch { operations.dismissRecoveryKeyNotice(expected) } }

    fun dismissOutcome() { if (mutable.value.stage == RecoveryKeyStage.IDLE) mutable.value = mutable.value.copy(outcome = null) }

    private fun retire() {
        sequence++
        operation?.cancel(); verification?.cancel(); gate?.cancel()
        gate = null; access = null; sending = false
    }

    private data class Identity(val generation: Long, val userId: Long, val version: Int)
    private fun SessionState.identity(): Identity? = user?.takeIf { phase == SessionPhase.AUTHENTICATED }
        ?.let { Identity(generation, it.id, it.sessionVersion) }

    companion object {
        fun factory(operations: AccountOperations): ViewModelProvider.Factory = viewModelFactory {
            initializer { RecoveryKeyViewModel(operations) }
        }
    }
}

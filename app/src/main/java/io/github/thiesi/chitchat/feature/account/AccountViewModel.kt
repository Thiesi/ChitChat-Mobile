package io.github.thiesi.chitchat.feature.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.thiesi.chitchat.core.account.AccountAccess
import io.github.thiesi.chitchat.core.account.AccountOperations
import io.github.thiesi.chitchat.core.account.VerificationMethods
import io.github.thiesi.chitchat.core.account.withStepUp
import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.network.FailureKind
import io.github.thiesi.chitchat.core.session.SessionPhase
import io.github.thiesi.chitchat.core.session.SessionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

enum class ExportStage { IDLE, CHOOSING, RUNNING, VERIFYING, SAVED, CANCELLED, FAILED }

data class AccountUiState(
    val stage: ExportStage = ExportStage.IDLE,
    val methods: VerificationMethods? = null,
    val verificationBusy: Boolean = false,
    val error: ApiFailure? = null,
    val remainingCodes: Int? = null,
    val recoveryMayBeSpent: Boolean = false,
)

fun interface ExportDestination {
    suspend fun write(bytes: ByteArray, ensureCurrent: () -> Unit)
}

class AccountViewModel(private val operations: AccountOperations) : ViewModel() {
    private val mutable = MutableStateFlow(AccountUiState())
    val state = mutable.asStateFlow()
    private var identity = operations.session.value.identity()
    private var sequence = 0L
    private var operation: Job? = null
    private var verification: Job? = null
    private var gate: CompletableDeferred<Unit>? = null
    private var access: AccountAccess? = null
    private var recoveryInFlight = false

    init {
        viewModelScope.launch {
            operations.session.collect { session ->
                val next = session.identity()
                if (next != identity) {
                    cancel()
                    identity = next
                    mutable.value = AccountUiState()
                }
            }
        }
    }

    fun chooseDestination(): Boolean {
        val session = operations.session.value
        if (session.identity() == null || session.busy || session.refreshRequired || session.storageWarning ||
            session.retryAtMillis > System.currentTimeMillis() || mutable.value.stage in
            setOf(ExportStage.CHOOSING, ExportStage.RUNNING, ExportStage.VERIFYING)) return false
        identity = session.identity()
        mutable.value = AccountUiState(stage = ExportStage.CHOOSING)
        return true
    }

    /** Called only for this ViewModel's outstanding system picker; process-death results are discarded. */
    fun destinationSelected(destination: ExportDestination?, expectedGeneration: Long) {
        if (mutable.value.stage != ExportStage.CHOOSING || identity?.generation != expectedGeneration ||
            operations.session.value.generation != expectedGeneration) return
        if (destination == null) { cancel(); return }
        val expected = identity
        val attempt = ++sequence
        mutable.value = AccountUiState(stage = ExportStage.RUNNING)
        operation = viewModelScope.launch {
            try {
                // Returning from SAF can race foreground session validation; never bypass it.
                withTimeout(30_000) { operations.session.first { !it.busy || it.identity() != expected } }
                if (operations.session.value.identity() != expected) throw CancellationException("Session changed")
                val current = operations.open()
                val export = withStepUp({ current.export() }, { awaitVerification(current, attempt) })
                try {
                    current.ensureCurrent()
                    destination.write(export.bytes, current::ensureCurrent)
                    current.ensureCurrent()
                    if (sequence == attempt) mutable.value = mutable.value.copy(stage = ExportStage.SAVED, error = null)
                } finally { export.bytes.fill(0) }
            } catch (cancelled: CancellationException) {
                if (sequence == attempt) mutable.value = mutable.value.copy(stage = ExportStage.CANCELLED, methods = null)
                throw cancelled
            } catch (failure: ApiFailure) {
                if (sequence == attempt) mutable.value = mutable.value.copy(stage = ExportStage.FAILED, error = failure, methods = null)
            } finally {
                if (sequence == attempt) {
                    gate?.cancel(); gate = null; access = null
                    mutable.value = mutable.value.copy(verificationBusy = false)
                }
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
        mutable.value = mutable.value.copy(stage = ExportStage.VERIFYING, methods = methods, error = null)
        waiting.await()
        current.ensureCurrent()
    }

    fun verify(secret: String, recovery: Boolean) {
        val current = access ?: return
        val waiting = gate ?: return
        val methods = mutable.value.methods ?: return
        if (mutable.value.stage != ExportStage.VERIFYING || mutable.value.verificationBusy || secret.isEmpty() ||
            (recovery && (!methods.mfaEnabled || methods.recoveryCodesRemaining == 0)) ||
            (!recovery && (methods.mfaEnabled || !methods.hasPassword))) return
        val attempt = sequence
        recoveryInFlight = recovery
        mutable.value = mutable.value.copy(verificationBusy = true, error = null)
        verification = viewModelScope.launch {
            try {
                val remaining = if (recovery) current.verifyRecovery(secret) else { current.verifyPassword(secret); null }
                current.ensureCurrent()
                if (sequence == attempt) {
                    mutable.value = mutable.value.copy(stage = ExportStage.RUNNING, methods = null,
                        verificationBusy = false, remainingCodes = remaining)
                    waiting.complete(Unit)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: ApiFailure) {
                if (sequence == attempt) {
                    if (failure.code == "step_up_invalid_credentials" || failure.code == "invalid_recovery_code") {
                        mutable.value = mutable.value.copy(error = failure, verificationBusy = false)
                    } else if (failure.code == "passkey_step_up_required") {
                        try {
                            val fresh = current.verificationMethods().copy(mfaEnabled = true)
                            if (sequence == attempt) mutable.value = mutable.value.copy(methods = fresh, error = null, verificationBusy = false)
                        } catch (next: ApiFailure) { waiting.completeExceptionally(next) }
                    } else {
                        val ambiguous = recovery && (failure.kind in setOf(FailureKind.NETWORK, FailureKind.TIMEOUT,
                            FailureKind.INCOMPATIBLE, FailureKind.REDIRECT) || (failure.status ?: 0) >= 500)
                        mutable.value = mutable.value.copy(recoveryMayBeSpent = ambiguous)
                        waiting.completeExceptionally(failure)
                    }
                }
            } finally { if (sequence == attempt) recoveryInFlight = false }
        }
    }

    fun background() { if (mutable.value.stage != ExportStage.CHOOSING) cancel() }

    fun cancel() {
        val active = mutable.value.stage in setOf(ExportStage.CHOOSING, ExportStage.RUNNING, ExportStage.VERIFYING)
        val mayBeSpent = recoveryInFlight && mutable.value.verificationBusy
        sequence++
        operation?.cancel(); verification?.cancel(); gate?.cancel()
        gate = null; access = null
        recoveryInFlight = false
        if (active) mutable.value = AccountUiState(stage = ExportStage.CANCELLED,
            remainingCodes = mutable.value.remainingCodes, recoveryMayBeSpent = mayBeSpent)
    }

    private data class Identity(val generation: Long, val userId: Long, val version: Int)
    private fun SessionState.identity(): Identity? = user?.takeIf { phase == SessionPhase.AUTHENTICATED }
        ?.let { Identity(generation, it.id, it.sessionVersion) }

    companion object {
        fun factory(operations: AccountOperations): ViewModelProvider.Factory = viewModelFactory {
            initializer { AccountViewModel(operations) }
        }
    }
}

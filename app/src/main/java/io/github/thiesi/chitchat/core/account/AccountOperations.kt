package io.github.thiesi.chitchat.core.account

import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.session.SessionState
import kotlinx.coroutines.flow.StateFlow

data class VerificationMethods(val mfaEnabled: Boolean, val hasPassword: Boolean, val recoveryCodesRemaining: Int)

/** Message contents never enter UI state, saved state, logs or an app-owned disk file. */
class PersonalExport(val bytes: ByteArray)

interface AccountAccess {
    fun ensureCurrent()
    suspend fun export(): PersonalExport
    suspend fun verificationMethods(): VerificationMethods
    suspend fun verifyPassword(password: String)
    suspend fun verifyRecovery(code: String): Int
}

interface AccountOperations {
    val session: StateFlow<SessionState>
    suspend fun open(): AccountAccess
}

/** Only a definite server step-up rejection permits one retry, never an ambiguous write. */
internal suspend fun <T> withStepUp(operation: suspend () -> T, verify: suspend () -> Unit): T {
    try { return operation() }
    catch (failure: ApiFailure) {
        if (failure.status != 403 || failure.code != "step_up_required") throw failure
    }
    verify()
    return operation()
}

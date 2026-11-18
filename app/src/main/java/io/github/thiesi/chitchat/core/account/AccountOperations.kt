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
    /** One `account/recovery-key.php` POST; the confirmed date reaches the session state, the key only the caller. */
    suspend fun createRecoveryKey(): CreatedRecoveryKey
    /**
     * The Unknown re-read: one ordinary, generation-fenced session refresh, then the adopted status. Null when the refresh
     * was skipped (busy) or failed, which means unresolved; never a raw GET whose CSRF the session doesn't adopt.
     */
    suspend fun recoveryKeyAfterRefresh(): RecoveryKeyStatus? = null
    /**
     * Delete account: one `account/close.php` POST, never resent (D1). Returns the typed outcome; only a live session's
     * `403 step_up_required` is thrown, for `withStepUp`.
     */
    suspend fun closeAccount(): ClosureResult = error("Not supported by this fixture")
}

interface AccountOperations {
    val session: StateFlow<SessionState>
    suspend fun open(): AccountAccess
    /** Go there or Not now on "didn't reach this phone": the notice ends for this runtime (PL O4a2b). */
    suspend fun dismissRecoveryKeyNotice(expected: SessionState) {}
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

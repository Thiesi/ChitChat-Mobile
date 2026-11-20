package io.github.thiesi.chitchat.core.session

import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.network.AuthReply
import io.github.thiesi.chitchat.core.network.FailureKind
import io.github.thiesi.chitchat.core.network.Lockdown
import io.github.thiesi.chitchat.core.network.SessionHttp
import io.github.thiesi.chitchat.core.network.SessionReply
import io.github.thiesi.chitchat.core.network.SessionUser
import io.github.thiesi.chitchat.core.network.SignInProvider
import io.github.thiesi.chitchat.core.server.ServerAddress
import io.github.thiesi.chitchat.core.conversation.ChatPolicy
import io.github.thiesi.chitchat.core.conversation.DisplayPreferences
import io.github.thiesi.chitchat.core.direct.MessagePrivacy
import io.github.thiesi.chitchat.core.media.AttachmentPolicy
import java.io.IOException
import java.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class SessionPhase { NO_SERVER, CONNECTING, ANONYMOUS, PENDING_MFA, AUTHENTICATED, GUEST, INVALIDATED, SIGNED_OUT }
enum class AuthFlow { SIGN_IN, REGISTER, RESTORE, RESET }
enum class RegistrationPreparation { IDLE, PREPARING, READY, FAILED }

/** Self-service reset support, from the documented discovery (one challenge GET per step-4a visit, O4b). */
enum class ResetSupport { UNKNOWN, CHECKING, SUPPORTED, UNSUPPORTED }

/** How a reset attempt ended without signing in; refusals with a code stay in `SessionState.error` (O4b). */
enum class ResetOutcome {
    /** The POST may have committed (connection lost after sending): the password may have changed. Never resent. */
    UNCERTAIN,
    /** `cancel.php` acknowledged: nothing changed. */
    CANCELLED,
    /** Cancel failed, was lost or found no pending reset: whether the password changed is unknown. */
    CANCEL_UNCERTAIN,
    /** The pending reset expired or became invalid: start again with the recovery key. */
    EXPIRED,
}

/** A reset challenge or proof is used within four minutes; the server expires it after five (password-recovery.md). */
private const val RESET_CHALLENGE_REUSE_MS = 4 * 60 * 1000L

/** A pending reset waiting for MFA: not signed in (password-recovery.md). [methods] as the server listed them. */
data class PendingReset(val methods: List<String>, val expiresAt: String?)

/** The reset flow's state inside the ANONYMOUS phase (TL: no new phase; a pending reset is not signed in). */
data class ResetState(
    val support: ResetSupport = ResetSupport.UNKNOWN,
    val preparation: RegistrationPreparation = RegistrationPreparation.IDLE,
    val pending: PendingReset? = null,
    val outcome: ResetOutcome? = null,
)

data class PublicSession(
    val registrationEnabled: Boolean,
    val providers: List<SignInProvider>,
    val lockdown: Lockdown,
    /** P-221: a validated https or mailto address, or null when the server has none (presence gating). */
    val accessibilityFeedback: String? = null,
    /** Installation policies (policies.md, P-176): null when the server sent none (unknown); entries null when None. */
    val policies: io.github.thiesi.chitchat.core.policies.PolicyDiscovery? = null,
)

data class SessionState(
    val server: ServerAddress? = null,
    val generation: Long = 0,
    val phase: SessionPhase = SessionPhase.NO_SERVER,
    val user: SessionUser? = null,
    val public: PublicSession? = null,
    val busy: Boolean = false,
    val error: ApiFailure? = null,
    val refreshRequired: Boolean = false,
    val retryAtMillis: Long = 0,
    val storageWarning: Boolean = false,
    val logoutUnconfirmed: Boolean = false,
    val recoveryCodesRemaining: Int? = null,
    val authFlow: AuthFlow = AuthFlow.SIGN_IN,
    val registration: RegistrationPreparation = RegistrationPreparation.IDLE,
    val accountRestored: Boolean = false,
    val registrationMayHaveSucceeded: Boolean = false,
    val chatPolicy: ChatPolicy? = null,
    val privacy: MessagePrivacy? = null,
    val shareTyping: Boolean? = null,
    val attachmentPolicy: AttachmentPolicy = AttachmentPolicy.Unknown,
    /** Bootstrap Web Push discovery; like the attachment policy, never persisted and cleared on revalidation. */
    val webPush: io.github.thiesi.chitchat.core.push.WebPushDiscovery = io.github.thiesi.chitchat.core.push.WebPushDiscovery.Unknown,
    /** `application.name` from the last bootstrap, for copy only; null means use the host. */
    val installationName: String? = null,
    /** The last bootstrap's server is definitely older than the minimum (server-update.md); nothing new starts. */
    val updateRequired: Boolean = false,
    /** `user.rules` (policies.md, P-171): null when unknown (no user, an older server or a malformed status). */
    val rules: io.github.thiesi.chitchat.core.policies.RulesStatus? = null,
    /**
     * A write of this session was refused with `403 rules_acceptance_required` (O3b3). A definite refusal: nothing was
     * committed and nothing resends. Cleared by the next bootstrap (the server's status wins) or a confirmed accept.
     */
    val rulesRefused: Boolean = false,
    /** Counts this session's rules refusals (never reset by a bootstrap), so a new refusal reopens a dismissed dialog. */
    val rulesRefusal: Long = 0,
    /** `user.recovery_key` (password-recovery.md): Unknown when the field is missing, so every recovery-key surface hides. */
    val recoveryKey: io.github.thiesi.chitchat.core.account.RecoveryKeyStatus = io.github.thiesi.chitchat.core.account.RecoveryKeyStatus.Unknown,
    /**
     * The key `register.php` returned, held in memory only until the key display confirms it (P-184). A new session
     * state (sign-out, another account) drops it. Its `toString` is redacted.
     */
    val registrationRecoveryKey: io.github.thiesi.chitchat.core.account.RecoveryKey? = null,
    /** Where [registrationRecoveryKey] came from: registration or a completed password reset (O4b). */
    val recoveryKeyOrigin: io.github.thiesi.chitchat.core.account.KeyOrigin = io.github.thiesi.chitchat.core.account.KeyOrigin.REGISTRATION,
    /** The password-reset flow while signed out (O4b). */
    val reset: ResetState = ResetState(),
    /**
     * An in-app account deletion's outcome (D1, TL C1/C4), fenced to its origin and account. Memory only; it survives the
     * session's end that a deletion causes, and Done clears it.
     */
    val closure: io.github.thiesi.chitchat.core.account.ClosureState? = null,
    /**
     * A key never reached the phone, and where it came from (TL, O4a and O4b). REGISTRATION: the registration reply carried
     * none, or registration was ambiguous and the person then signed in. RESET: a completed reset carried none, or a reset
     * was Unknown and the next sign-in to that username found the key created at or after that attempt's server time.
     * With `recoveryKey` exists = true, the app shows "didn't reach this phone".
     */
    val recoveryKeyUndelivered: io.github.thiesi.chitchat.core.account.KeyOrigin? = null,
    /** `application.version` as reported, for the update state's details line and note; null is unknown. */
    val reportedVersion: String? = null,
    /**
     * Counts successful bootstraps (fresh session and policy) in this generation; a new generation starts at 0.
     * Read only by Send when back online, which needs a revalidation strictly newer than its reconnect.
     */
    val bootstrapSequence: Long = 0,
) {
    val canChangeServer get() = phase !in setOf(SessionPhase.AUTHENTICATED, SessionPhase.PENDING_MFA, SessionPhase.GUEST) && !storageWarning && !busy
}

interface SessionController {
    val state: StateFlow<SessionState>
    suspend fun connect(server: ServerAddress?)
    suspend fun signIn(username: String, password: String, restore: Boolean = false)
    suspend fun completeRecovery(code: String)
    suspend fun refresh()
    suspend fun signOut()
    suspend fun selectAuthFlow(flow: AuthFlow)
    suspend fun prepareRegistration()
    suspend fun register(username: String, password: String, birthDate: String?)
    /** The key display confirmed "I've saved it": the registration key leaves memory (recovery-key.md, P-184). */
    suspend fun dropRegistrationRecoveryKey(expected: SessionState) {}
    /** Step 4a opened: one challenge GET, the documented discovery of self-service reset (O4b). Never on recomposition. */
    suspend fun discoverPasswordReset() {}
    /** A fresh challenge and proof for the next reset attempt; every attempt needs its own (O4b). */
    suspend fun prepareReset() {}
    /** One reset POST with the prepared proof; never resent (O4b). */
    suspend fun resetPassword(username: String, recoveryKey: String, newPassword: String) {}
    /** The pending reset's sign-in recovery code (O4b). */
    suspend fun completeResetRecovery(code: String) {}
    /** Cancel reset: `cancel.php`; only an acknowledged cancel means nothing changed (O4b). */
    suspend fun cancelReset() {}
    /** Check after an Unknown deletion: one session read, never a resend (D1, TL C2). */
    suspend fun checkClosure() {}
    /** Done on a deletion outcome: it leaves memory (D1, TL C4). */
    suspend fun dismissClosure() {}
}

/** Local transitions and vault writes are serialized; network calls never hold the lock. */
/**
 * Local push teardown, run inside the session lock. [retire] runs when an account's session ends:
 * cancel its notifications, wipe the push record and keys, and ask the distributor to unregister
 * without waiting. It returns the server registration to revoke when that belongs to
 * [origin]/[userId]. [sessionChanged] runs whenever a session is published or saved; a record
 * created under any other login session (identified by its sign-in time) is retired.
 */
interface PushTeardown {
    fun retire(origin: String?, userId: Long?): Long?
    fun sessionChanged(origin: String, userId: Long?, signedInAt: Long?)
    companion object {
        val NONE = object : PushTeardown {
            override fun retire(origin: String?, userId: Long?): Long? = null
            override fun sessionChanged(origin: String, userId: Long?, signedInAt: Long?) {}
        }
    }
}

class SessionRepository internal constructor(
    private val vault: SessionVault,
    private val clock: Clock = Clock.systemUTC(),
    private val push: PushTeardown = PushTeardown.NONE,
    private val http: (ServerAddress) -> SessionHttp = { SessionHttp(it) },
) : SessionController, io.github.thiesi.chitchat.core.policies.PolicyReads, io.github.thiesi.chitchat.core.policies.RulesOperations {
    private val lock = Mutex()
    private var closureMarker: ClosureMarker? = null
    private var closureClientToClose: SessionHttp? = null
    private var closureAttempts = 0L
    private val mutable = MutableStateFlow(SessionState())
    override val state = mutable.asStateFlow()

    /**
     * One public `policies/show.php` read for the rendered session's server and generation; never across a change. A
     * missing server or a change during the read is a typed EXPIRED failure (the screen offers Try again), never a bare
     * cancellation that would leave it loading (review, PR #267).
     */
    override suspend fun policyDocument(name: io.github.thiesi.chitchat.core.policies.PolicyDocumentName,
        expected: SessionState): io.github.thiesi.chitchat.core.policies.PolicyDocument {
        val api = lock.withLock {
            val current = mutable.value
            val active = runtime ?: throw ApiFailure(FailureKind.EXPIRED)
            if (active.server != expected.server || current.generation != expected.generation) throw ApiFailure(FailureKind.EXPIRED)
            if (current.updateRequired) throw ApiFailure(FailureKind.INCOMPATIBLE) // Nothing new starts (server-update.md).
            active.api
        }
        val document = api.policyDocument(name)
        if (mutable.value.generation != expected.generation || runtime?.api !== api) throw ApiFailure(FailureKind.EXPIRED)
        return document
    }
    private var runtime: Runtime? = null
    private var initialized = false
    private var selected: ServerAddress? = null
    private var generation = 0L

    override suspend fun connect(server: ServerAddress?) {
        val active = lock.withLock {
            val changing = initialized && server != selected
            val retryAt = if (changing) 0 else mutable.value.retryAtMillis
            initialized = true
            selected = server
            runtime?.api?.close()
            runtime = null
            generation++
            val priorPhase = mutable.value.phase
            val priorFailure = mutable.value.error
            mutable.value = SessionState(server, generation,
                if (server == null) SessionPhase.NO_SERVER else SessionPhase.CONNECTING, retryAtMillis = retryAt)
            try {
                if (changing || server == null || priorPhase == SessionPhase.SIGNED_OUT ||
                    (priorPhase == SessionPhase.INVALIDATED && priorFailure?.kind !in
                        setOf(FailureKind.NETWORK, FailureKind.TIMEOUT))) vault.clear()
                if (server == null) return@withLock null
                val next = Runtime(server, http(server))
                runtime = next
                watchRulesRefusals(next)
                val saved = vault.read()
                if (saved != null) {
                    if (!saved.usableFor(server, clock.millis()) || !next.api.restoreCookies(saved.cookies)) {
                        vault.clear()
                    } else {
                        next.signedInAt = saved.signedInAt
                        next.lastValidatedAt = saved.lastValidatedAt
                        next.expectedUserId = saved.userId
                    }
                }
                next
            } catch (_: IOException) {
                runtime?.api?.close()
                runtime = null
                mutable.value = mutable.value.copy(phase = SessionPhase.INVALIDATED, error = ApiFailure(FailureKind.STORAGE))
                null
            }
        } ?: return
        bootstrap(active)
    }

    override suspend fun refresh() = refreshFor(null)

    private suspend fun refreshFor(expected: SessionAccess?) {
        val active = lock.withLock {
            expected?.let(::ensureCurrent) // Check inside the runtime-selection lock, not before waiting for it.
            val active = runtime ?: return@withLock null
            if (mutable.value.busy || active.bootstrapActive || active.policyChanging || mutable.value.phase == SessionPhase.CONNECTING ||
                mutable.value.phase == SessionPhase.PENDING_MFA) return@withLock null
            if (active.expectedUserId != null && !active.withinLifetime(clock.millis())) {
                invalidate(active, ApiFailure(FailureKind.EXPIRED))
                return@withLock null
            }
            mutable.value = mutable.value.copy(busy = true, error = null, attachmentPolicy = AttachmentPolicy.Unknown, webPush = io.github.thiesi.chitchat.core.push.WebPushDiscovery.Unknown)
            active
        } ?: return
        bootstrap(active)
    }

    override suspend fun signIn(username: String, password: String, restore: Boolean) {
        val active = begin(SessionPhase.ANONYMOUS) ?: return
        try {
            lock.withLock {
                if (runtime === active) {
                    active.preparation++
                    active.proof = null
                    // A sign-in attempt ends any cancel outcome shown on step 4a (PL, O4b2).
                    mutable.value = mutable.value.copy(authFlow = if (restore) AuthFlow.RESTORE else AuthFlow.SIGN_IN,
                        registration = RegistrationPreparation.IDLE, reset = mutable.value.reset.copy(outcome = null))
                    active.signInMayFollowUncertainRegistration =
                        active.uncertainRegistration?.equals(username.trim(), ignoreCase = true) == true
                    active.signInMayFollowUncertainReset =
                        active.uncertainReset?.takeIf { it.username.equals(username.trim(), ignoreCase = true) }
                }
            }
            val result = active.api.password(username.trim(), password, active.csrf!!, restore)
            authenticated(active, result)
        } catch (failure: ApiFailure) { failed(active, failure) }
    }

    override suspend fun completeRecovery(code: String) {
        val active = begin(SessionPhase.PENDING_MFA) ?: return
        try {
            val reply = active.api.recovery(code.trim(), active.csrf!!)
            if (mutable.value.authFlow == AuthFlow.RESTORE && !reply.restored) throw ApiFailure(FailureKind.INCOMPATIBLE)
            authenticated(active, reply)
        }
        catch (failure: ApiFailure) { failed(active, failure) }
    }

    override suspend fun selectAuthFlow(flow: AuthFlow) = lock.withLock {
        val current = mutable.value
        val active = runtime ?: return@withLock
        if (current.phase != SessionPhase.ANONYMOUS || current.busy || current.reset.pending != null ||
            (flow == AuthFlow.REGISTER && (current.public?.registrationEnabled != true || current.public.lockdown.enabled)) ||
            (flow == AuthFlow.RESET && current.reset.support != ResetSupport.SUPPORTED)) return@withLock
        active.preparation++
        active.proof = null
        active.resetProof = null
        mutable.value = current.copy(authFlow = flow, registration = RegistrationPreparation.IDLE,
            error = null, registrationMayHaveSucceeded = false,
            reset = current.reset.copy(preparation = RegistrationPreparation.IDLE, outcome = null))
    }

    override suspend fun prepareRegistration() {
        val pending = lock.withLock {
            val active = runtime ?: return@withLock null
            val current = mutable.value
            if (current.phase != SessionPhase.ANONYMOUS || current.authFlow != AuthFlow.REGISTER || current.busy ||
                current.refreshRequired || current.public?.registrationEnabled != true ||
                clock.millis() < current.retryAtMillis) return@withLock null
            active.proof = null
            val attempt = ++active.preparation
            active.proofIssuedAt = clock.millis()
            mutable.value = current.copy(registration = RegistrationPreparation.PREPARING, error = null)
            active to attempt
        } ?: return
        try {
            val proof = solveRegistration(pending.first.api.registrationChallenge())
            lock.withLock {
                if (runtime === pending.first && pending.first.preparation == pending.second) {
                    pending.first.proof = proof
                    mutable.value = mutable.value.copy(registration = RegistrationPreparation.READY)
                }
            }
        } catch (failure: ApiFailure) {
            lock.withLock {
                if (runtime === pending.first && pending.first.preparation == pending.second) {
                    failLocked(pending.first, failure)
                    mutable.value = mutable.value.copy(registration = RegistrationPreparation.FAILED)
                }
            }
        } finally {
            withContext(NonCancellable) {
                lock.withLock {
                    if (runtime === pending.first && pending.first.preparation == pending.second &&
                        mutable.value.registration == RegistrationPreparation.PREPARING) {
                        mutable.value = mutable.value.copy(registration = RegistrationPreparation.IDLE)
                    }
                }
            }
        }
    }

    override suspend fun discoverPasswordReset() {
        val active = lock.withLock {
            val active = runtime ?: return@withLock null
            val current = mutable.value
            if (current.phase != SessionPhase.ANONYMOUS || current.busy || current.reset.support == ResetSupport.CHECKING ||
                current.reset.pending != null) return@withLock null
            mutable.value = current.copy(reset = current.reset.copy(support = ResetSupport.CHECKING))
            active
        } ?: return
        var support = ResetSupport.UNSUPPORTED
        try {
            val challenge = active.api.passwordResetChallenge()
            // The fresh challenge serves the first attempt while it's young; any later attempt fetches its own.
            lock.withLock { if (runtime === active) { active.resetChallenge = challenge; active.resetChallengeAt = clock.millis() } }
            support = ResetSupport.SUPPORTED
        } catch (_: ApiFailure) {
            // A 404 (older server) or any failure keeps the static line; not a session error.
        } finally {
            withContext(NonCancellable) {
                lock.withLock {
                    if (runtime === active && mutable.value.reset.support == ResetSupport.CHECKING)
                        mutable.value = mutable.value.copy(reset = mutable.value.reset.copy(support = support))
                }
            }
        }
    }

    override suspend fun prepareReset() {
        val pending = lock.withLock {
            val active = runtime ?: return@withLock null
            val current = mutable.value
            if (current.phase != SessionPhase.ANONYMOUS || current.authFlow != AuthFlow.RESET || current.busy ||
                current.refreshRequired || current.reset.pending != null || current.reset.support != ResetSupport.SUPPORTED ||
                clock.millis() < current.retryAtMillis) return@withLock null
            active.resetProof = null
            val attempt = ++active.preparation
            // The discovery challenge once, while young (the server expires it after five minutes); never twice.
            val fresh = active.resetChallenge?.takeIf { clock.millis() - active.resetChallengeAt in 0 until RESET_CHALLENGE_REUSE_MS }
            active.resetChallenge = null
            // A stale-challenge notice stays through the automatic renewal until the next tap (review, #294).
            mutable.value = current.copy(error = current.error?.takeIf { it.code == "reset_challenge_expired" },
                reset = current.reset.copy(preparation = RegistrationPreparation.PREPARING))
            Triple(active, attempt, fresh)
        } ?: return
        val (active, attempt, fresh) = pending
        try {
            val challenge = fresh ?: active.api.passwordResetChallenge()
            val issuedAt = if (fresh != null) active.resetChallengeAt else clock.millis()
            val proof = solveRegistration(challenge.challenge)
            lock.withLock {
                if (runtime === active && active.preparation == attempt) {
                    active.resetProof = proof
                    active.resetProofDate = challenge.serverDate
                    active.resetProofAt = issuedAt
                    mutable.value = mutable.value.copy(reset = mutable.value.reset.copy(preparation = RegistrationPreparation.READY))
                }
            }
        } catch (failure: ApiFailure) {
            lock.withLock {
                if (runtime === active && active.preparation == attempt) {
                    failLocked(active, failure)
                    mutable.value = mutable.value.copy(reset = mutable.value.reset.copy(preparation = RegistrationPreparation.FAILED))
                }
            }
        } finally {
            withContext(NonCancellable) {
                lock.withLock {
                    if (runtime === active && active.preparation == attempt &&
                        mutable.value.reset.preparation == RegistrationPreparation.PREPARING)
                        mutable.value = mutable.value.copy(reset = mutable.value.reset.copy(preparation = RegistrationPreparation.IDLE))
                }
            }
        }
    }

    override suspend fun resetPassword(username: String, recoveryKey: String, newPassword: String) {
        val pending = lock.withLock {
            val active = runtime ?: return@withLock null
            val current = mutable.value
            if (current.phase != SessionPhase.ANONYMOUS || current.authFlow != AuthFlow.RESET || current.busy ||
                current.refreshRequired || current.reset.preparation != RegistrationPreparation.READY ||
                current.reset.pending != null || clock.millis() < current.retryAtMillis) return@withLock null
            val proof = active.resetProof ?: return@withLock null
            active.resetProof = null // Consumed even when refused (password-recovery.md); the next attempt prepares anew.
            if (clock.millis() - active.resetProofAt !in 0 until RESET_CHALLENGE_REUSE_MS) {
                mutable.value = current.copy(reset = current.reset.copy(preparation = RegistrationPreparation.IDLE),
                    error = ApiFailure(FailureKind.HTTP, 400, "reset_challenge_expired"))
                return@withLock null
            }
            mutable.value = current.copy(busy = true, error = null,
                reset = current.reset.copy(preparation = RegistrationPreparation.IDLE, outcome = null))
            Triple(active, proof, UncertainReset(username.trim(), active.resetProofDate))
        } ?: return
        val (active, proof, attempt) = pending
        try {
            when (val reply = active.api.resetPassword(username.trim(), recoveryKey.trim(), newPassword,
                proof.nonce!!, proof.solution!!, active.csrf!!)) {
                is SessionHttp.ResetReply.Complete -> authenticated(active, reply.auth, reset = true)
                is SessionHttp.ResetReply.PendingMfa -> lock.withLock {
                    if (runtime === active) {
                        active.csrf = reply.csrf
                        active.pendingReset = attempt
                        mutable.value = mutable.value.copy(busy = false,
                            reset = mutable.value.reset.copy(pending = PendingReset(reply.methods, reply.expiresAt)))
                    }
                }
            }
        } catch (failure: ApiFailure) { resetFailed(active, failure, attempt, keepPending = false) }
    }

    override suspend fun completeResetRecovery(code: String) {
        val pending = lock.withLock {
            val active = runtime ?: return@withLock null
            val current = mutable.value
            if (current.phase != SessionPhase.ANONYMOUS || current.busy || current.reset.pending == null ||
                "recovery_code" !in current.reset.pending.methods || active.csrf == null || current.refreshRequired ||
                clock.millis() < current.retryAtMillis) return@withLock null
            mutable.value = current.copy(busy = true, error = null)
            active to active.pendingReset
        } ?: return
        val (active, attempt) = pending
        try { authenticated(active, active.api.resetMfaRecovery(code.trim(), active.csrf!!), reset = true) }
        catch (failure: ApiFailure) {
            if (failure.status == 409 && failure.code == "reset_not_pending") lock.withLock {
                // Nothing was retried, so no completion committed: the pending reset expired or became invalid.
                if (runtime === active) {
                    active.pendingReset = null
                    mutable.value = mutable.value.copy(busy = false, error = null,
                        reset = mutable.value.reset.copy(pending = null, outcome = ResetOutcome.EXPIRED))
                }
            } else resetFailed(active, failure, attempt, keepPending = true)
        }
    }

    override suspend fun cancelReset() {
        val pending = lock.withLock {
            val active = runtime ?: return@withLock null
            val current = mutable.value
            if (current.phase != SessionPhase.ANONYMOUS || current.busy || current.reset.pending == null || active.csrf == null)
                return@withLock null
            mutable.value = current.copy(busy = true, error = null)
            active to active.pendingReset
        } ?: return
        val (active, attempt) = pending
        val outcome = try {
            val csrf = active.api.cancelReset(active.csrf!!)
            lock.withLock { if (runtime === active) active.csrf = csrf }
            ResetOutcome.CANCELLED
        } catch (_: ApiFailure) { ResetOutcome.CANCEL_UNCERTAIN }
        lock.withLock {
            if (runtime !== active) return@withLock
            // An uncertain cancel may follow a completion that committed: the conditional marker decides later.
            if (outcome == ResetOutcome.CANCEL_UNCERTAIN) attempt?.let { active.uncertainReset = it }
            active.pendingReset = null
            if (outcome == ResetOutcome.CANCEL_UNCERTAIN) active.csrf = null
            mutable.value = mutable.value.copy(busy = false, authFlow = AuthFlow.SIGN_IN,
                refreshRequired = mutable.value.refreshRequired || outcome == ResetOutcome.CANCEL_UNCERTAIN,
                reset = mutable.value.reset.copy(pending = null, preparation = RegistrationPreparation.IDLE, outcome = outcome))
        }
    }

    /**
     * A reset POST that didn't sign in. Ambiguous means it may have committed: the Unknown outcome, never a resend. The
     * contract's refusals are definite and set no marker: 400s, 429 and `503 maintenance_lockdown` (password-recovery.md).
     * Any other 5xx, a lost connection, a timeout, a malformed reply or a redirect is ambiguous.
     */
    private suspend fun resetFailed(active: Runtime, failure: ApiFailure, attempt: UncertainReset?, keepPending: Boolean) = lock.withLock {
        if (runtime !== active) return@withLock
        val lockdown = failure.status == 503 && failure.code == "maintenance_lockdown"
        val uncertain = failure.kind in setOf(FailureKind.NETWORK, FailureKind.TIMEOUT, FailureKind.INCOMPATIBLE,
            FailureKind.REDIRECT) || ((failure.status ?: 0) >= 500 && !lockdown)
        if (uncertain) attempt?.let { active.uncertainReset = it }
        failLocked(active, failure)
        if (runtime !== active) return@withLock
        if (uncertain) active.pendingReset = null
        mutable.value = mutable.value.copy(reset = mutable.value.reset.copy(
            pending = if (keepPending && !uncertain) mutable.value.reset.pending else null,
            outcome = if (uncertain) ResetOutcome.UNCERTAIN else null))
    }

    override suspend fun register(username: String, password: String, birthDate: String?) {
        val pending = lock.withLock {
            val active = runtime ?: return@withLock null
            val current = mutable.value
            if (current.phase != SessionPhase.ANONYMOUS || current.authFlow != AuthFlow.REGISTER || current.busy ||
                current.refreshRequired || current.registration != RegistrationPreparation.READY ||
                current.public?.registrationEnabled != true || clock.millis() < current.retryAtMillis) return@withLock null
            val proof = active.proof ?: return@withLock null
            active.proof = null // Single-use even after validation errors or ambiguous network failure.
            if (clock.millis() < active.proofIssuedAt || clock.millis() - active.proofIssuedAt >= 1_800_000) {
                mutable.value = current.copy(registration = RegistrationPreparation.FAILED,
                    error = ApiFailure(FailureKind.HTTP, 400, "registration_challenge_expired"))
                return@withLock null
            }
            mutable.value = current.copy(busy = true, error = null, registration = RegistrationPreparation.IDLE)
            // The rules version of the session the form rendered with (policies.md): sent only when rules are published.
            Triple(active, proof, current.public?.policies?.rules?.version)
        } ?: return
        var rulesOutdated: ApiFailure? = null
        try {
            authenticated(pending.first, pending.first.api.register(username.trim(), password, birthDate,
                pending.second.nonce, pending.second.solution, pending.first.csrf!!, acceptedRulesVersion = pending.third),
                registration = true)
        } catch (failure: ApiFailure) {
            if (failure.status == 409 && failure.code == "rules_version_outdated") rulesOutdated = failure
            lock.withLock {
                if (runtime === pending.first) {
                    val uncertain = failure.kind in setOf(FailureKind.NETWORK, FailureKind.TIMEOUT,
                        FailureKind.INCOMPATIBLE, FailureKind.REDIRECT) || (failure.status ?: 0) >= 500
                    if (uncertain) pending.first.uncertainRegistration = username.trim()
                    failLocked(pending.first, failure)
                    mutable.value = mutable.value.copy(registrationMayHaveSucceeded = uncertain,
                        registration = RegistrationPreparation.FAILED,
                        public = if (failure.code == "registration_disabled") mutable.value.public?.copy(registrationEnabled = false)
                            else mutable.value.public)
                }
            }
        }
        // The account wasn't created; re-read the session so the form shows the new rules version. Never resubmitted.
        // The notice stays after that re-read (the bootstrap clears errors), unless something newer replaced it.
        rulesOutdated?.let { outdated ->
            refresh()
            lock.withLock {
                if (runtime === pending.first && mutable.value.phase == SessionPhase.ANONYMOUS && mutable.value.error == null)
                    mutable.value = mutable.value.copy(error = outdated)
            }
        }
    }

    /**
     * One `policies/accept.php` POST for the rules [version] that was shown (policies.md, P-171), for the rendered
     * [expected] session. The confirmed status is published only into that same session. Never resent: an ambiguous
     * result is Unknown (settle with a session GET), and 409 is Outdated (re-read, then a new choice).
     */
    override suspend fun acceptRules(version: Int, expected: SessionState): io.github.thiesi.chitchat.core.policies.RulesAcceptResult {
        val access = try { openAccess(expected) } catch (failure: ApiFailure) {
            return io.github.thiesi.chitchat.core.policies.RulesAcceptResult.Failed(failure)
        }
        return try {
            val status = access.api.acceptRules(version, access.csrf)
            lock.withLock {
                ensureCurrent(access)
                mutable.value = mutable.value.copy(rules = status, rulesRefused = false)
            }
            io.github.thiesi.chitchat.core.policies.RulesAcceptResult.Accepted(status)
        } catch (failure: ApiFailure) {
            when {
                failure.status == 409 && failure.code == "rules_version_outdated" -> io.github.thiesi.chitchat.core.policies.RulesAcceptResult.Outdated
                failure.kind in setOf(FailureKind.NETWORK, FailureKind.TIMEOUT) || (failure.status ?: 0) >= 500 ->
                    io.github.thiesi.chitchat.core.policies.RulesAcceptResult.Unknown(failure)
                else -> io.github.thiesi.chitchat.core.policies.RulesAcceptResult.Failed(failure)
            }
        }
    }

    private suspend fun begin(phase: SessionPhase): Runtime? = lock.withLock {
        val active = runtime ?: return@withLock null
        val current = mutable.value
        if (current.phase != phase || current.busy || active.csrf == null || current.refreshRequired ||
            clock.millis() < current.retryAtMillis) return@withLock null
        mutable.value = current.copy(busy = true, error = null)
        active
    }

    private suspend fun bootstrap(active: Runtime) {
        try {
            val current = lock.withLock {
                if (runtime !== active) false else { active.bootstrapActive = true; true }
            }
            if (!current) return
            val reply = active.api.bootstrap()
            lock.withLock {
                if (runtime !== active) return@withLock
                val previousId = active.expectedUserId ?: mutable.value.user?.id
                if (previousId != null && reply.user?.id != previousId) {
                    invalidate(active, ApiFailure(FailureKind.EXPIRED))
                    return@withLock
                }
                active.csrf = reply.csrfToken
                mutable.value = mutable.value.copy(
                    phase = when { reply.user == null -> SessionPhase.ANONYMOUS; reply.user.guest -> SessionPhase.GUEST; else -> SessionPhase.AUTHENTICATED },
                    user = reply.user,
                    public = reply.publicSession(), busy = true, error = null, refreshRequired = false,
                    chatPolicy = if (reply.user == null || reply.ignoredUserIds == null) null else
                        ChatPolicy(reply.ignoredUserIds.toSet(), reply.mutedEverywhere, reply.preferences ?: DisplayPreferences()),
                    registrationMayHaveSucceeded = if (reply.user != null) false else mutable.value.registrationMayHaveSucceeded,
                    privacy = reply.privacy.takeIf { reply.user != null },
                    shareTyping = reply.shareTyping.takeIf { reply.user != null },
                    attachmentPolicy = if (reply.user != null && !reply.user.guest) reply.attachmentPolicy
                        else AttachmentPolicy.Unavailable,
                    webPush = if (reply.user != null && !reply.user.guest) reply.webPush else io.github.thiesi.chitchat.core.push.WebPushDiscovery.Unavailable,
                    installationName = io.github.thiesi.chitchat.core.network.installationName(reply.application),
                    updateRequired = reply.discovery?.let { needsServerUpdate(it, member = reply.user != null && !reply.user.guest) } ?: false,
                    rules = reply.rules.takeIf { reply.user != null },
                    recoveryKey = if (reply.user != null) reply.recoveryKey else io.github.thiesi.chitchat.core.account.RecoveryKeyStatus.Unknown,
                    rulesRefused = false,
                    reportedVersion = displayedServerVersion(reply.discovery?.version),
                    bootstrapSequence = mutable.value.bootstrapSequence + 1,
                )
                active.bootstrapActive = false
                persist(active, reply.user)
            }
        } catch (failure: ApiFailure) { failed(active, failure) }
        catch (cancelled: CancellationException) {
            withContext(NonCancellable) { lock.withLock {
                if (runtime === active) {
                    val current = mutable.value
                    // Headers/cookies may already have arrived. Rebootstrap before using CSRF,
                    // but never leave navigation cancellation holding the whole session busy.
                    active.csrf = null
                    active.bootstrapActive = false
                    mutable.value = current.copy(busy = false, refreshRequired = true,
                        shareTyping = null, attachmentPolicy = AttachmentPolicy.Unknown, webPush = io.github.thiesi.chitchat.core.push.WebPushDiscovery.Unknown,
                        phase = if (current.phase == SessionPhase.CONNECTING) SessionPhase.INVALIDATED else current.phase,
                        error = if (current.phase == SessionPhase.CONNECTING) current.error ?: ApiFailure(FailureKind.NETWORK) else current.error)
                }
            } }
            throw cancelled
        }
    }

    private suspend fun authenticated(active: Runtime, reply: AuthReply, registration: Boolean = false, reset: Boolean = false) = lock.withLock {
        if (runtime !== active) return@withLock
        active.csrf = reply.csrfToken
        if (reply.mfaRequired) {
            mutable.value = mutable.value.copy(phase = SessionPhase.PENDING_MFA, user = null, busy = true,
                error = null, recoveryCodesRemaining = null, chatPolicy = null, privacy = null, shareTyping = null,
                attachmentPolicy = AttachmentPolicy.Unknown, webPush = io.github.thiesi.chitchat.core.push.WebPushDiscovery.Unknown)
            persist(active, null)
        } else {
            active.signedInAt = clock.millis()
            active.expectedUserId = reply.user!!.id
            mutable.value = mutable.value.copy(phase = SessionPhase.AUTHENTICATED, user = reply.user,
                busy = true, error = null, recoveryCodesRemaining = reply.recoveryCodesRemaining,
                chatPolicy = null, privacy = null, shareTyping = null, attachmentPolicy = AttachmentPolicy.Unknown, webPush = io.github.thiesi.chitchat.core.push.WebPushDiscovery.Unknown,
                accountRestored = reply.restored, registrationMayHaveSucceeded = false, rules = reply.rules,
                recoveryKey = reply.recoveryKeyStatus,
                registrationRecoveryKey = reply.registrationRecoveryKey.takeIf { registration || reset },
                recoveryKeyOrigin = if (reset) io.github.thiesi.chitchat.core.account.KeyOrigin.RESET else io.github.thiesi.chitchat.core.account.KeyOrigin.REGISTRATION,
                reset = ResetState(),
                // The key display is the only way to see it: a registration or reset without one, or a sign-in after an
                // ambiguous registration, may have left a key that never reached this phone. After an Unknown reset, only
                // a key created at or after that attempt's server time counts (TL, O4b); an earlier one means the reset
                // never committed, and unknown times show nothing.
                recoveryKeyUndelivered = when {
                    registration -> io.github.thiesi.chitchat.core.account.KeyOrigin.REGISTRATION.takeIf { reply.registrationRecoveryKey == null }
                    reset -> io.github.thiesi.chitchat.core.account.KeyOrigin.RESET.takeIf { reply.registrationRecoveryKey == null }
                    active.signInMayFollowUncertainRegistration -> io.github.thiesi.chitchat.core.account.KeyOrigin.REGISTRATION
                    active.signInMayFollowUncertainReset?.let {
                        io.github.thiesi.chitchat.core.account.resetKeyCreatedSince(reply.recoveryKeyStatus, it.serverDate) } == true -> io.github.thiesi.chitchat.core.account.KeyOrigin.RESET
                    else -> null
                })
            // Runtime memory only: cleared by this (or any) completed authentication, by sign-out (the runtime is
            // replaced) and by process death. Never in session state, saved state, the vault or a log.
            active.uncertainRegistration = null
            active.signInMayFollowUncertainRegistration = false
            active.uncertainReset = null
            active.signInMayFollowUncertainReset = null
            active.pendingReset = null
            persist(active, reply.user)
        }
    }

    private suspend fun persist(active: Runtime, user: SessionUser?, finishOperation: Boolean = true) {
        // Called under lock: a late save cannot run after logout/server change.
        val person = user?.takeIf { !it.guest }
        if (person != null && active.signedInAt == null) active.signedInAt = clock.millis()
        try { withContext(Dispatchers.IO) { push.sessionChanged(active.server.value, person?.id, person?.let { active.signedInAt }) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* Push state never decides the session. */ }
        try {
            if (user == null || user.guest) vault.clear() else {
                val now = clock.millis()
                if (active.signedInAt == null) active.signedInAt = now
                active.lastValidatedAt = now
                active.expectedUserId = user.id
                val cookies = active.api.savedCookies()
                if (cookies.isEmpty()) throw IOException("No restorable session cookie")
                vault.save(SavedSession(active.server.value, user.id, cookies, active.signedInAt!!, now))
            }
            mutable.value = mutable.value.copy(storageWarning = false, busy = if (finishOperation) false else mutable.value.busy)
        } catch (_: IOException) {
            try { vault.clear() } catch (_: IOException) { /* Warning stays visible. */ }
            mutable.value = mutable.value.copy(storageWarning = true, busy = if (finishOperation) false else mutable.value.busy)
        }
    }

    /** A capability for one reauthorized runtime, not a second cookie/session manager. */
    internal suspend fun openAccess(expected: SessionState = mutable.value): SessionAccess = lock.withLock {
        val current = mutable.value
        // The default is captured before waiting for the vault/state lock. Never upgrade an
        // operation queued for one identity into a replacement account/session capability.
        if (current.server != expected.server || current.generation != expected.generation ||
            current.phase != expected.phase || current.user?.id != expected.user?.id ||
            current.user?.sessionVersion != expected.user?.sessionVersion)
            throw CancellationException("Session changed before access")
        val active = runtime ?: throw ApiFailure(FailureKind.EXPIRED)
        if (current.phase != SessionPhase.AUTHENTICATED || current.user == null || current.busy || active.bootstrapActive || active.policyChanging ||
            current.refreshRequired || active.csrf == null) throw ApiFailure(FailureKind.EXPIRED)
        // P-182: against a server that needs an update nothing NEW starts. Work that already holds access finishes under
        // its own rules (a POST is never cancelled); Check again is the ordinary refresh bootstrap.
        if (current.updateRequired) throw ApiFailure(FailureKind.INCOMPATIBLE)
        if (!active.withinLifetime(clock.millis())) {
            invalidate(active, ApiFailure(FailureKind.EXPIRED))
            throw ApiFailure(FailureKind.EXPIRED)
        }
        if (current.storageWarning) throw ApiFailure(FailureKind.STORAGE)
        if (clock.millis() < current.retryAtMillis) throw ApiFailure(FailureKind.HTTP, 429,
            retryAfterSeconds = (current.retryAtMillis - clock.millis() + 999) / 1000)
        SessionAccess(current.generation, current.user.id, current.user.sessionVersion, active.api, active.csrf!!)
    }

    /** Fresh policy/CSRF through ordinary bootstrap, still bound to the caller's original identity. */
    internal suspend fun refreshedAccess(expected: SessionState = mutable.value): SessionAccess {
        val original = openAccess(expected)
        refreshFor(original)
        val ready = withTimeoutOrNull(30_000) { state.first {
            !it.busy || it.generation != original.generation || it.user?.id != original.userId ||
                it.user.sessionVersion != original.sessionVersion
        } } ?: throw ApiFailure(FailureKind.TIMEOUT)
        ensureCurrent(original)
        return openAccess(ready)
    }

    internal fun ensureCurrent(access: SessionAccess) {
        val current = mutable.value
        if (current.generation != access.generation || current.phase != SessionPhase.AUTHENTICATED ||
            current.user?.id != access.userId || current.user.sessionVersion != access.sessionVersion ||
            current.refreshRequired || current.storageWarning) throw CancellationException("Session is no longer current")
    }

    /** One explicit preference POST, serialized against bootstrap without holding a network lock. */
    internal suspend fun setTypingPreference(shared: Boolean, expected: SessionState = mutable.value): Boolean {
        val access = openAccess(expected)
        val active = lock.withLock {
            ensureCurrent(access)
            val active = runtime ?: throw CancellationException("Session retired")
            if (mutable.value.busy || active.bootstrapActive || active.policyChanging)
                throw ApiFailure(FailureKind.EXPIRED)
            active.policyChanging = true
            mutable.value = mutable.value.copy(busy = true, shareTyping = null)
            active
        }
        var confirmed: Boolean? = null
        var interrupted = false
        try {
            val result = authorized(access) { setTypingPreference(shared, access.csrf) }
            confirmed = result
            return result
        } catch (cancelled: CancellationException) {
            interrupted = true
            throw cancelled
        } finally {
            withContext(NonCancellable) { lock.withLock {
                if (runtime === active) {
                    active.policyChanging = false
                    if (interrupted) active.csrf = null
                    mutable.value = mutable.value.copy(busy = false, shareTyping = confirmed,
                        refreshRequired = mutable.value.refreshRequired || interrupted)
                }
            } }
        }
    }

    /** Apply the returned complete list, not a local toggle; never race bootstrap or replay a POST. */
    internal suspend fun setIgnored(userId: Long, ignored: Boolean, expected: SessionState): Set<Long> {
        val access = openAccess(expected)
        if (userId <= 0 || userId == access.userId) throw ApiFailure(FailureKind.HTTP, 400, "validation_error")
        val active = lock.withLock {
            ensureCurrent(access)
            val active = runtime ?: throw CancellationException("Session retired")
            if (mutable.value.busy || active.bootstrapActive || active.policyChanging || mutable.value.chatPolicy == null)
                throw ApiFailure(FailureKind.EXPIRED)
            active.policyChanging = true
            mutable.value = mutable.value.copy(busy = true)
            active
        }
        var confirmed: Set<Long>? = null
        var interrupted = false
        try {
            val result = authorized(access) { setIgnored(userId, ignored, access.csrf) }
            confirmed = result
            return result
        } catch (cancelled: CancellationException) {
            interrupted = true
            throw cancelled
        } finally {
            withContext(NonCancellable) { lock.withLock {
                if (runtime === active) {
                    active.policyChanging = false
                    if (interrupted) active.csrf = null
                    val policy = mutable.value.chatPolicy
                    mutable.value = mutable.value.copy(busy = false,
                        chatPolicy = if (confirmed != null && policy != null)
                            ChatPolicy(confirmed, policy.mutedEverywhere, policy.display) else policy,
                        refreshRequired = mutable.value.refreshRequired || confirmed == null)
                }
            } }
        }
    }

    /** The key display confirmed "I've saved it" (or closed): the registration key is dropped from memory. */
    override suspend fun dropRegistrationRecoveryKey(expected: SessionState) = lock.withLock {
        val current = mutable.value
        if (current.generation == expected.generation && current.user?.id == expected.user?.id)
            mutable.value = current.copy(registrationRecoveryKey = null)
    }

    /** "Didn't reach this phone" was acted on or no longer applies. */
    internal suspend fun clearRecoveryKeyUndelivered(expected: SessionState) = lock.withLock {
        val current = mutable.value
        if (current.generation == expected.generation && current.user?.id == expected.user?.id)
            mutable.value = current.copy(recoveryKeyUndelivered = null)
    }

    /** See [io.github.thiesi.chitchat.core.account.AccountAccess.recoveryKeyAfterRefresh]. */
    internal suspend fun recoveryKeyAfterRefresh(access: SessionAccess): io.github.thiesi.chitchat.core.account.RecoveryKeyStatus? {
        val before = mutable.value.bootstrapSequence
        refreshFor(access)
        val now = mutable.value
        return now.recoveryKey.takeIf { now.generation == access.generation && now.user?.id == access.userId &&
            now.bootstrapSequence > before && now.error == null && !now.refreshRequired }
    }

    /** A confirmed Generate or Replace: the status shows the new key's date (the key itself never enters state). */
    internal suspend fun recoveryKeyCreated(access: SessionAccess, createdAt: String) = lock.withLock {
        ensureCurrent(access)
        mutable.value = mutable.value.copy(recoveryKey = io.github.thiesi.chitchat.core.account.RecoveryKeyStatus.Known(true, createdAt),
            recoveryKeyUndelivered = null)
    }

    internal suspend fun recoveryConsumed(access: SessionAccess, remaining: Int) = lock.withLock {
        ensureCurrent(access)
        mutable.value = mutable.value.copy(recoveryCodesRemaining = remaining)
    }

    internal suspend fun invalidateFromRealtime(access: SessionAccess) = lock.withLock {
        ensureCurrent(access)
        runtime?.let { invalidate(it, ApiFailure(FailureKind.EXPIRED)) }
    }

    internal suspend fun <T> authorized(access: SessionAccess, operation: suspend SessionHttp.() -> T): T {
        lock.withLock {
            ensureCurrent(access)
            val active = runtime ?: throw CancellationException("Session retired")
            if (!active.withinLifetime(clock.millis())) {
                invalidate(active, ApiFailure(FailureKind.EXPIRED))
                throw ApiFailure(FailureKind.EXPIRED)
            }
            if (active.csrf != access.csrf) throw CancellationException("Session token changed")
            if (clock.millis() < mutable.value.retryAtMillis) throw ApiFailure(FailureKind.HTTP, 429,
                retryAfterSeconds = (mutable.value.retryAtMillis - clock.millis() + 999) / 1000)
        }
        try {
            val result = access.api.operation()
            lock.withLock {
                ensureCurrent(access)
                val active = runtime ?: throw CancellationException("Session retired")
                persist(active, mutable.value.user, finishOperation = false)
            }
            return result
        } catch (failure: ApiFailure) {
            lock.withLock {
                if (mutable.value.generation != access.generation) throw CancellationException("Session retired")
                else {
                    val active = runtime
                    // Resource validation/permission errors belong to that screen, not global logout.
                    if (active != null && (failure.status == 401 || failure.status == 429 || failure.status == 503 ||
                            failure.code == "csrf_failed" || failure.kind in setOf(FailureKind.NETWORK,
                            FailureKind.TIMEOUT, FailureKind.REDIRECT))) failLocked(active, failure)
                }
            }
            throw failure
        }
    }

    private suspend fun failed(active: Runtime, failure: ApiFailure) = lock.withLock {
        if (runtime === active) {
            active.bootstrapActive = false
            failLocked(active, failure)
            if (runtime === active) mutable.value = mutable.value.copy(shareTyping = null)
        }
    }

    private suspend fun failLocked(active: Runtime, failure: ApiFailure) {
        if (runtime !== active) return
        val current = mutable.value
        if ((failure.status == 401 && current.phase != SessionPhase.ANONYMOUS) ||
            failure.kind == FailureKind.INCOMPATIBLE || failure.kind == FailureKind.REDIRECT) {
            invalidate(active, failure)
            return
        }
        val needsRefresh = failure.code == "csrf_failed" || failure.kind in setOf(FailureKind.NETWORK, FailureKind.TIMEOUT) ||
            ((current.authFlow == AuthFlow.REGISTER || current.authFlow == AuthFlow.RESET) && current.busy && (failure.status ?: 0) >= 500)
        if (needsRefresh) active.csrf = null
        mutable.value = current.copy(
            phase = if (current.phase == SessionPhase.CONNECTING) SessionPhase.INVALIDATED else current.phase,
            busy = false, error = failure, refreshRequired = needsRefresh,
            retryAtMillis = if (failure.status == 429 || failure.status == 503)
                clock.millis() + (failure.retryAfterSeconds ?: 30) * 1000 else current.retryAtMillis,
        )
    }

    /** Also used for a server 200 whose user became null: expiry is not necessarily an HTTP 401. */
    private suspend fun invalidate(active: Runtime, failure: ApiFailure?, closure: ClosureStateOverride? = null) {
        if (runtime !== active) return
        val userId = mutable.value.user?.id ?: active.expectedUserId
        // A deletion in flight for this origin and account: its own forced_logout (or any ended session) waits for the
        // POST's result instead of showing plain sign-in. An Unknown deletion stays Unknown (an admin can force a logout
        // too), and every other outcome is fenced to its own origin and account (TL C1).
        val carried = closure?.state ?: closureMarker?.takeIf { it.origin == active.server.value && it.userId == userId }
            ?.let { io.github.thiesi.chitchat.core.account.ClosureState.Waiting(it.origin, it.userId) }
            ?: mutable.value.closure?.takeIf { it.origin == active.server.value && it.userId == userId }
        // Closing the client would cancel the deletion POST itself (it shares this runtime's client). While one is in
        // flight for this origin and account, everything else on the client is retired now (jar, every other call, no
        // new requests) and only the final shutdown waits for that POST to settle (TL C1, #296).
        if (closureMarker?.let { it.origin == active.server.value && it.userId == userId } == true) {
            active.api.retireExcept()
            closureClientToClose = active.api
        } else active.api.close()
        runtime = null
        generation++
        var storageWarning = false
        try { vault.clear() } catch (_: IOException) { storageWarning = true }
        retirePush(active.server.value, userId) // Local only: the session is gone.
        mutable.value = SessionState(active.server, generation, SessionPhase.INVALIDATED,
            error = failure, storageWarning = storageWarning, closure = carried)
    }

    /** A deletion outcome to set during teardown, replacing whatever would otherwise be carried. */
    private class ClosureStateOverride(val state: io.github.thiesi.chitchat.core.account.ClosureState)

    /**
     * Delete account (D1; account.md "Account closure"): one POST, never resent, through `withStepUp` (a live session's
     * `403 step_up_required` is rethrown for it). The server fires `forced_logout` before it destroys the session and
     * before this reply arrives, so the result is applied against an app-scoped marker (origin, account, attempt), never
     * against the generation, which may already have retired (TL C1). After a confirmed closure the ended-session teardown
     * runs with no network: the session is already gone, and the server deleted this account's push subscriptions.
     */
    internal suspend fun closeAccount(access: SessionAccess): io.github.thiesi.chitchat.core.account.ClosureResult {
        val marker = lock.withLock {
            ensureCurrent(access)
            val active = runtime ?: throw CancellationException("Session retired")
            if (active.csrf != access.csrf) throw CancellationException("Session token changed")
            if (clock.millis() < mutable.value.retryAtMillis) return io.github.thiesi.chitchat.core.account.ClosureResult.RateLimited(
                (mutable.value.retryAtMillis - clock.millis() + 999) / 1000)
            ClosureMarker(active.server.value, access.userId, ++closureAttempts).also { closureMarker = it }
        }
        var settled = false
        try {
            val result = try {
                closed(access, marker, access.api.closeAccount(access.csrf))
            } catch (failure: ApiFailure) {
                // A reply arrived: settled, even when a live session's step-up refusal is rethrown for withStepUp
                // (the server refused before committing, so it must never read as Unknown; review on #296).
                try { closureFailed(access, marker, failure) } finally { settled = true }
            }
            settled = true
            return result
        } finally {
            withContext(NonCancellable) {
                lock.withLock {
                    // Cancelled after sending (genuine background or navigation, M2b2): it may have committed.
                    if (!settled) markClosureUnknown(access, marker)
                    if (closureMarker === marker) closureMarker = null
                    // A runtime retired meanwhile closes its client now that the POST has settled.
                    if (closureClientToClose === access.api) { access.api.close(); closureClientToClose = null }
                }
            }
        }
    }

    private suspend fun closed(access: SessionAccess, marker: ClosureMarker, finalizesAt: String) = lock.withLock {
        val scheduled = io.github.thiesi.chitchat.core.account.ClosureState.Scheduled(marker.origin, marker.userId, finalizesAt)
        val active = runtime
        if (active != null && mutable.value.generation == access.generation) invalidate(active, null, ClosureStateOverride(scheduled))
        else if (mutable.value.closure.matches(marker)) mutable.value = mutable.value.copy(closure = scheduled)
        io.github.thiesi.chitchat.core.account.ClosureResult.Closed(finalizesAt)
    }

    private suspend fun closureFailed(access: SessionAccess, marker: ClosureMarker, failure: ApiFailure): io.github.thiesi.chitchat.core.account.ClosureResult {
        val result = io.github.thiesi.chitchat.core.account.closureResult(failure)
        val stepUp = failure.status == 403 && failure.code == "step_up_required"
        lock.withLock {
            val active = runtime
            val live = active != null && mutable.value.generation == access.generation
            // The same session-wide effects as any authorized request (an ended session, a wait, a stale token).
            if (live && (failure.status == 401 || failure.status == 429 || failure.status == 503 || failure.code == "csrf_failed" ||
                    failure.kind in setOf(FailureKind.NETWORK, FailureKind.TIMEOUT, FailureKind.REDIRECT, FailureKind.INCOMPATIBLE)))
                failLocked(active!!, failure)
            val current = mutable.value
            when {
                // The session ended meanwhile: the result lands on the waiting marker.
                current.closure is io.github.thiesi.chitchat.core.account.ClosureState.Waiting && current.closure.matches(marker) ->
                    mutable.value = current.copy(closure = if (result == io.github.thiesi.chitchat.core.account.ClosureResult.Unknown)
                        io.github.thiesi.chitchat.core.account.ClosureState.Unknown(marker.origin, marker.userId)
                        else io.github.thiesi.chitchat.core.account.ClosureState.EndedWithRefusal(marker.origin, marker.userId, result))
                runtime != null && mutable.value.generation == access.generation && result == io.github.thiesi.chitchat.core.account.ClosureResult.Unknown ->
                    mutable.value = current.copy(closure = io.github.thiesi.chitchat.core.account.ClosureState.Unknown(marker.origin, marker.userId))
            }
        }
        if (stepUp && runtime != null && mutable.value.generation == access.generation) throw failure
        return result
    }

    /** Under the lock: a cancelled, sent deletion is Unknown, wherever the session now is (same origin and account only). */
    private fun markClosureUnknown(access: SessionAccess, marker: ClosureMarker) {
        val current = mutable.value
        val live = runtime != null && current.generation == access.generation
        if (live || (current.closure is io.github.thiesi.chitchat.core.account.ClosureState.Waiting && current.closure.matches(marker)))
            mutable.value = current.copy(closure = io.github.thiesi.chitchat.core.account.ClosureState.Unknown(marker.origin, marker.userId))
    }

    private fun io.github.thiesi.chitchat.core.account.ClosureState?.matches(marker: ClosureMarker) = this != null && origin == marker.origin && userId == marker.userId

    override suspend fun checkClosure() {
        val unknown = mutable.value.closure as? io.github.thiesi.chitchat.core.account.ClosureState.Unknown ?: return
        val live = lock.withLock { runtime != null && mutable.value.phase == SessionPhase.AUTHENTICATED }
        if (live) {
            // The ordinary session read. Still signed in: nothing changed. An anonymous reply or a 401 ends the session
            // (the teardown runs there) and keeps the Unknown, which becomes LikelyClosed below; a failed read stays Unknown.
            val before = mutable.value.bootstrapSequence
            refreshFor(null)
            lock.withLock {
                val now = mutable.value
                when {
                    now.phase == SessionPhase.AUTHENTICATED && now.user?.id == unknown.userId && now.bootstrapSequence > before &&
                        now.error == null -> mutable.value = now.copy(closure = io.github.thiesi.chitchat.core.account.ClosureState.NotClosed(unknown.origin, unknown.userId))
                    now.phase == SessionPhase.INVALIDATED && now.closure == unknown ->
                        mutable.value = now.copy(closure = io.github.thiesi.chitchat.core.account.ClosureState.LikelyClosed(unknown.origin, unknown.userId))
                }
            }
        } else {
            // The session already ended: an anonymous session read for this server is what Check can still do. connect()
            // replaces the session state (closure included), so the outcome is put back unconditionally, even when the
            // Check is cancelled or connect fails mid-way (TL, #300): an Unknown deletion never silently disappears.
            var read = false
            try { connect(selected); read = true }
            finally {
                withContext(NonCancellable) {
                    lock.withLock {
                        val now = mutable.value
                        // Only into this server's state, never another account's, and never over an outcome set meanwhile.
                        if (now.closure == null && now.server?.value == unknown.origin && (now.user == null || now.user.id == unknown.userId))
                            mutable.value = now.copy(closure = if (read && now.phase == SessionPhase.ANONYMOUS)
                                io.github.thiesi.chitchat.core.account.ClosureState.LikelyClosed(unknown.origin, unknown.userId) else unknown)
                    }
                }
            }
        }
    }

    override suspend fun dismissClosure() = lock.withLock { mutable.value = mutable.value.copy(closure = null) }

    override suspend fun signOut() {
        val pending = lock.withLock {
            val active = runtime
            val alreadyConfirmed = active == null && mutable.value.phase == SessionPhase.SIGNED_OUT &&
                !mutable.value.logoutUnconfirmed
            val csrf = active?.csrf
            // Local push cleanup happens now, offline or not; the revoke is prepared from the still-valid
            // runtime before its credentials are retired below.
            val revokeId = retirePush(active?.server?.value ?: selected?.value, mutable.value.user?.id ?: active?.expectedUserId)
            val revoke = if (active != null && csrf != null && revokeId != null) active.api.revokePushRequest(revokeId, csrf) else null
            val request = csrf?.let { active.api.logoutRequest(it) }
            active?.api?.close()
            runtime = null
            generation++
            var storageWarning = false
            try { vault.clear() } catch (_: IOException) { storageWarning = true }
            mutable.value = SessionState(selected, generation, SessionPhase.SIGNED_OUT,
                storageWarning = storageWarning, logoutUnconfirmed = !alreadyConfirmed, busy = request != null)
            if (active != null && request != null) PendingLogout(active, request, revoke, generation) else null
        } ?: return
        var confirmed = false
        try {
            coroutineScope {
                // Concurrent with logout, so the bounded logout wait is not extended. Logout also removes this
                // session's registrations on the server; the explicit revoke covers a failed logout, so a
                // logout failure is caught inside the scope and never cancels the revoke.
                pending.revoke?.let { revoke -> launch {
                    try { pending.runtime.api.sendRevokePush(revoke) } catch (_: ApiFailure) { /* Best effort. */ }
                } }
                confirmed = try { pending.runtime.api.sendLogout(pending.logout); true }
                    catch (_: ApiFailure) { false } // Local sign-out is immediate even without connectivity.
            }
        }
        catch (cancelled: CancellationException) { throw cancelled }
        finally {
            withContext(NonCancellable) {
                lock.withLock {
                    if (generation == pending.generation) mutable.value = mutable.value.copy(logoutUnconfirmed = !confirmed, busy = false)
                }
            }
        }
    }

    private class PendingLogout(val runtime: Runtime, val logout: okhttp3.Request, val revoke: okhttp3.Request?, val generation: Long)

    /**
     * The locally stored account (origin and user ID), read from the vault with no network and before any bootstrap; null
     * when none is stored. F5's start-up sweep uses it to delete other accounts' share copies (TL, 2026-11-20).
     */
    internal suspend fun storedAccount(): Pair<String, Long>? = lock.withLock {
        try { vault.read() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
    }?.let { it.origin to it.userId }

    /** Under the lock. Push cleanup never fails a sign-out; storage problems leave keys unreadable at worst. */
    private suspend fun retirePush(origin: String?, userId: Long?): Long? =
        try { withContext(Dispatchers.IO) { push.retire(origin, userId) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }

    private fun SessionReply.publicSession() = PublicSession(registrationEnabled, providers,
        lockdown.copy(message = lockdown.message?.take(500)),
        io.github.thiesi.chitchat.core.network.accessibilityFeedbackUrl(accessibilityFeedback),
        io.github.thiesi.chitchat.core.policies.policyDiscovery(policies))

    /** The account scope a request starts in; a refusal counts only if that exact scope is still the current one. */
    private data class RefusalScope(val generation: Long, val userId: Long)

    /** Refusal signals arrive on transport threads; they're applied under the lock, fenced to runtime, account and generation. */
    private val signals = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)

    private fun watchRulesRefusals(owner: Runtime) {
        owner.api.requestScope = {
            mutable.value.let { state -> state.user?.takeIf { state.phase == SessionPhase.AUTHENTICATED }?.let { RefusalScope(state.generation, it.id) } }
        }
        owner.api.onRulesRefused = { started ->
            if (started is RefusalScope) signals.launch {
                lock.withLock {
                    val state = mutable.value
                    if (runtime === owner && state.phase == SessionPhase.AUTHENTICATED && state.generation == started.generation &&
                        state.user?.id == started.userId) mutable.value = state.copy(rulesRefused = true, rulesRefusal = state.rulesRefusal + 1)
                }
            }
        }
    }

    /** A deletion in flight: origin, account and attempt only, never a session secret (TL C1). */
    private class ClosureMarker(val origin: String, val userId: Long, val attempt: Long)

    /** An Unknown reset attempt: the username and the server's `Date` from its challenge GET. No string form. */
    private class UncertainReset(val username: String, val serverDate: String?)

    private class Runtime(val server: ServerAddress, val api: SessionHttp) {
        var csrf: String? = null
        /**
         * The username of a registration whose result was ambiguous (O4a). Unlike `registrationMayHaveSucceeded`, it
         * survives switching to the sign-in form; a later sign-in to that same username consumes it and marks the key
         * undelivered. Memory only, per runtime.
         */
        var uncertainRegistration: String? = null
        /** A sign-in to [uncertainRegistration]'s username is in progress (it may finish through MFA). */
        var signInMayFollowUncertainRegistration = false
        /** An Unknown reset (O4b): its username and server time. Memory only; cleared by any completed authentication. */
        var uncertainReset: UncertainReset? = null
        /** A sign-in to [uncertainReset]'s username is in progress. */
        var signInMayFollowUncertainReset: UncertainReset? = null
        /** The attempt behind a pending reset, so an uncertain MFA or cancel can mark it. */
        var pendingReset: UncertainReset? = null
        var resetChallenge: SessionHttp.ResetChallenge? = null
        var resetChallengeAt = 0L
        var resetProof: RegistrationProof? = null
        var resetProofDate: String? = null
        var resetProofAt = 0L
        var signedInAt: Long? = null
        var lastValidatedAt: Long? = null
        var expectedUserId: Long? = null
        var bootstrapActive = false
        var policyChanging = false
        var preparation = 0L
        var proof: RegistrationProof? = null
        var proofIssuedAt = 0L
        fun withinLifetime(now: Long): Boolean {
            val start = signedInAt ?: return false
            val last = lastValidatedAt ?: return false
            return now >= last && last >= start && now - start < 30L * 24 * 60 * 60 * 1000 &&
                now - last < 7L * 24 * 60 * 60 * 1000
        }
    }
}

// No generated string representation for the captured CSRF/runtime capability.
internal class SessionAccess(val generation: Long, val userId: Long, val sessionVersion: Int,
    val api: SessionHttp, val csrf: String)

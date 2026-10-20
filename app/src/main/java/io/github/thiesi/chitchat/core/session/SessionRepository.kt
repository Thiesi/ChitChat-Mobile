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
import java.io.IOException
import java.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class SessionPhase { NO_SERVER, CONNECTING, ANONYMOUS, PENDING_MFA, AUTHENTICATED, GUEST, INVALIDATED, SIGNED_OUT }
enum class AuthFlow { SIGN_IN, REGISTER, RESTORE }
enum class RegistrationPreparation { IDLE, PREPARING, READY, FAILED }

data class PublicSession(
    val registrationEnabled: Boolean,
    val providers: List<SignInProvider>,
    val lockdown: Lockdown,
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
}

/** Local transitions and vault writes are serialized; network calls never hold the lock. */
class SessionRepository internal constructor(
    private val vault: SessionVault,
    private val clock: Clock = Clock.systemUTC(),
    private val http: (ServerAddress) -> SessionHttp = { SessionHttp(it) },
) : SessionController {
    private val lock = Mutex()
    private val mutable = MutableStateFlow(SessionState())
    override val state = mutable.asStateFlow()
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
            mutable.value = mutable.value.copy(busy = true, error = null)
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
                    mutable.value = mutable.value.copy(authFlow = if (restore) AuthFlow.RESTORE else AuthFlow.SIGN_IN,
                        registration = RegistrationPreparation.IDLE)
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
        if (current.phase != SessionPhase.ANONYMOUS || current.busy ||
            (flow == AuthFlow.REGISTER && (current.public?.registrationEnabled != true || current.public.lockdown.enabled))) return@withLock
        active.preparation++
        active.proof = null
        mutable.value = current.copy(authFlow = flow, registration = RegistrationPreparation.IDLE,
            error = null, registrationMayHaveSucceeded = false)
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
            active to proof
        } ?: return
        try {
            authenticated(pending.first, pending.first.api.register(username.trim(), password, birthDate,
                pending.second.nonce, pending.second.solution, pending.first.csrf!!))
        } catch (failure: ApiFailure) {
            lock.withLock {
                if (runtime === pending.first) {
                    val uncertain = failure.kind in setOf(FailureKind.NETWORK, FailureKind.TIMEOUT,
                        FailureKind.INCOMPATIBLE, FailureKind.REDIRECT) || (failure.status ?: 0) >= 500
                    failLocked(pending.first, failure)
                    mutable.value = mutable.value.copy(registrationMayHaveSucceeded = uncertain,
                        registration = RegistrationPreparation.FAILED,
                        public = if (failure.code == "registration_disabled") mutable.value.public?.copy(registrationEnabled = false)
                            else mutable.value.public)
                }
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
                        shareTyping = null,
                        phase = if (current.phase == SessionPhase.CONNECTING) SessionPhase.INVALIDATED else current.phase,
                        error = if (current.phase == SessionPhase.CONNECTING) current.error ?: ApiFailure(FailureKind.NETWORK) else current.error)
                }
            } }
            throw cancelled
        }
    }

    private suspend fun authenticated(active: Runtime, reply: AuthReply) = lock.withLock {
        if (runtime !== active) return@withLock
        active.csrf = reply.csrfToken
        if (reply.mfaRequired) {
            mutable.value = mutable.value.copy(phase = SessionPhase.PENDING_MFA, user = null, busy = true,
                error = null, recoveryCodesRemaining = null, chatPolicy = null, privacy = null, shareTyping = null)
            persist(active, null)
        } else {
            active.signedInAt = clock.millis()
            active.expectedUserId = reply.user!!.id
            mutable.value = mutable.value.copy(phase = SessionPhase.AUTHENTICATED, user = reply.user,
                busy = true, error = null, recoveryCodesRemaining = reply.recoveryCodesRemaining,
                chatPolicy = null, privacy = null, shareTyping = null,
                accountRestored = reply.restored, registrationMayHaveSucceeded = false)
            persist(active, reply.user)
        }
    }

    private suspend fun persist(active: Runtime, user: SessionUser?, finishOperation: Boolean = true) {
        // Called under lock: a late save cannot run after logout/server change.
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
            (current.authFlow == AuthFlow.REGISTER && current.busy && (failure.status ?: 0) >= 500)
        if (needsRefresh) active.csrf = null
        mutable.value = current.copy(
            phase = if (current.phase == SessionPhase.CONNECTING) SessionPhase.INVALIDATED else current.phase,
            busy = false, error = failure, refreshRequired = needsRefresh,
            retryAtMillis = if (failure.status == 429 || failure.status == 503)
                clock.millis() + (failure.retryAfterSeconds ?: 30) * 1000 else current.retryAtMillis,
        )
    }

    /** Also used for a server 200 whose user became null: expiry is not necessarily an HTTP 401. */
    private suspend fun invalidate(active: Runtime, failure: ApiFailure) {
        if (runtime !== active) return
        active.api.close()
        runtime = null
        generation++
        var storageWarning = false
        try { vault.clear() } catch (_: IOException) { storageWarning = true }
        mutable.value = SessionState(active.server, generation, SessionPhase.INVALIDATED,
            error = failure, storageWarning = storageWarning)
    }

    override suspend fun signOut() {
        val pending = lock.withLock {
            val active = runtime
            val alreadyConfirmed = active == null && mutable.value.phase == SessionPhase.SIGNED_OUT &&
                !mutable.value.logoutUnconfirmed
            val request = active?.csrf?.let { active.api.logoutRequest(it) }
            active?.api?.close()
            runtime = null
            generation++
            var storageWarning = false
            try { vault.clear() } catch (_: IOException) { storageWarning = true }
            mutable.value = SessionState(selected, generation, SessionPhase.SIGNED_OUT,
                storageWarning = storageWarning, logoutUnconfirmed = !alreadyConfirmed, busy = request != null)
            if (active != null && request != null) Triple(active, request, generation) else null
        } ?: return
        var confirmed = false
        try { pending.first.api.sendLogout(pending.second); confirmed = true }
        catch (_: ApiFailure) { /* Local sign-out is immediate even without connectivity. */ }
        catch (cancelled: CancellationException) { throw cancelled }
        finally {
            withContext(NonCancellable) {
                lock.withLock {
                    if (generation == pending.third) mutable.value = mutable.value.copy(logoutUnconfirmed = !confirmed, busy = false)
                }
            }
        }
    }

    private fun SessionReply.publicSession() = PublicSession(registrationEnabled, providers,
        lockdown.copy(message = lockdown.message?.take(500)))

    private class Runtime(val server: ServerAddress, val api: SessionHttp) {
        var csrf: String? = null
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

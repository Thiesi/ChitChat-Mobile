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
import java.io.IOException
import java.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class SessionPhase { NO_SERVER, CONNECTING, ANONYMOUS, PENDING_MFA, AUTHENTICATED, GUEST, INVALIDATED, SIGNED_OUT }

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
) {
    val canChangeServer get() = phase !in setOf(SessionPhase.AUTHENTICATED, SessionPhase.PENDING_MFA, SessionPhase.GUEST) && !storageWarning
}

interface SessionController {
    val state: StateFlow<SessionState>
    suspend fun connect(server: ServerAddress?)
    suspend fun signIn(username: String, password: String, restore: Boolean = false)
    suspend fun completeRecovery(code: String)
    suspend fun refresh()
    suspend fun signOut()
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

    override suspend fun refresh() {
        val active = lock.withLock {
            val active = runtime ?: return@withLock null
            if (mutable.value.busy || mutable.value.phase == SessionPhase.CONNECTING ||
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
            val result = active.api.password(username.trim(), password, active.csrf!!, restore)
            authenticated(active, result)
        } catch (failure: ApiFailure) { failed(active, failure) }
    }

    override suspend fun completeRecovery(code: String) {
        val active = begin(SessionPhase.PENDING_MFA) ?: return
        try { authenticated(active, active.api.recovery(code.trim(), active.csrf!!)) }
        catch (failure: ApiFailure) { failed(active, failure) }
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
                )
                persist(active, reply.user)
            }
        } catch (failure: ApiFailure) { failed(active, failure) }
    }

    private suspend fun authenticated(active: Runtime, reply: AuthReply) = lock.withLock {
        if (runtime !== active) return@withLock
        active.csrf = reply.csrfToken
        if (reply.mfaRequired) {
            mutable.value = mutable.value.copy(phase = SessionPhase.PENDING_MFA, user = null, busy = true,
                error = null, recoveryCodesRemaining = null)
            persist(active, null)
        } else {
            active.signedInAt = clock.millis()
            active.expectedUserId = reply.user!!.id
            mutable.value = mutable.value.copy(phase = SessionPhase.AUTHENTICATED, user = reply.user,
                busy = true, error = null, recoveryCodesRemaining = reply.recoveryCodesRemaining)
            persist(active, reply.user)
        }
    }

    private suspend fun persist(active: Runtime, user: SessionUser?) {
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
            mutable.value = mutable.value.copy(storageWarning = false, busy = false)
        } catch (_: IOException) {
            try { vault.clear() } catch (_: IOException) { /* Warning stays visible. */ }
            mutable.value = mutable.value.copy(storageWarning = true, busy = false)
        }
    }

    private suspend fun failed(active: Runtime, failure: ApiFailure) = lock.withLock {
        if (runtime !== active) return@withLock
        val current = mutable.value
        if ((failure.status == 401 && current.phase != SessionPhase.ANONYMOUS) ||
            failure.kind == FailureKind.INCOMPATIBLE || failure.kind == FailureKind.REDIRECT) {
            invalidate(active, failure)
            return@withLock
        }
        val needsRefresh = failure.code == "csrf_failed" || failure.kind in setOf(FailureKind.NETWORK, FailureKind.TIMEOUT)
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
        fun withinLifetime(now: Long): Boolean {
            val start = signedInAt ?: return false
            val last = lastValidatedAt ?: return false
            return now >= last && last >= start && now - start < 30L * 24 * 60 * 60 * 1000 &&
                now - last < 7L * 24 * 60 * 60 * 1000
        }
    }
}

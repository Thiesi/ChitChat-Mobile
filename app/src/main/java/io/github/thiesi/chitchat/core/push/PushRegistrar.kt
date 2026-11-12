package io.github.thiesi.chitchat.core.push

import android.content.Context
import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.session.PushTeardown
import io.github.thiesi.chitchat.core.session.SessionPhase
import io.github.thiesi.chitchat.core.session.SessionRepository
import io.github.thiesi.chitchat.core.session.SessionState
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.UnifiedPush
import org.unifiedpush.android.connector.keys.KeyManager

/** The connector's IPC, behind an interface so JVM tests never reach a distributor. */
interface PushConnector {
    fun register(instance: String, vapidKey: String)
    /** Fire-and-forget: a broadcast to the distributor plus the connector's local bookkeeping. */
    fun unregister(instance: String)
    /** When the instance is unknown (unreadable record): drops every registration and the saved distributor. */
    fun unregisterAll()
}

class UnifiedPushConnector(private val context: Context, private val keys: KeyManager) : PushConnector {
    override fun register(instance: String, vapidKey: String) =
        UnifiedPush.register(context, instance, PUSH_DEVICE_LABEL, vapidKey, keys)
    override fun unregister(instance: String) = UnifiedPush.unregister(context, instance, keys)
    override fun unregisterAll() = UnifiedPush.removeDistributor(context, keys)
}

/** What this phone's push registration is doing. Copy never claims more than these facts. */
sealed interface PushPhoneStatus {
    data object Off : PushPhoneStatus
    /** Registered with the distributor; its address hasn't reached the server yet. */
    data object Waiting : PushPhoneStatus
    data object On : PushPhoneStatus
    data class Refused(val reason: PushSubscribeRefusal) : PushPhoneStatus
    /** The subscribe request may or may not have reached the server. */
    data object Unconfirmed : PushPhoneStatus
    data class DistributorFailed(val reason: DistributorFailure) : PushPhoneStatus
}

enum class DistributorFailure { NETWORK, ACTION_REQUIRED, OTHER }

/**
 * Local push state: the sealed record and the connector registration. Every method is synchronous
 * and local, so session teardown can run it under the session lock without waiting on a network.
 */
class PushLocal(
    private val vault: PushVault,
    private val connector: PushConnector,
    private val cancelNotifications: () -> Unit,
) : PushTeardown {
    private val mutableStatus = MutableStateFlow<PushPhoneStatus>(PushPhoneStatus.Off)
    val status: StateFlow<PushPhoneStatus> = mutableStatus.asStateFlow()
    @Volatile private var loaded = false

    @Synchronized fun record(): PushRecord? {
        val record = try { vault.read() } catch (_: IOException) { null }
        if (!loaded) {
            loaded = true
            mutableStatus.value = when { record == null -> PushPhoneStatus.Off
                record.registrationId != null -> PushPhoneStatus.On; else -> PushPhoneStatus.Waiting }
        }
        return record
    }

    /** The account's existing record, or a fresh one; any other account's record is retired first. */
    @Synchronized fun prepare(origin: String, userId: Long): PushRecord {
        val existing = record()
        if (existing?.belongsTo(origin, userId) == true) return existing
        if (existing != null) retire(null, null) else clearQuietly() // Also drops an unreadable leftover file.
        return PushRecord.create(origin, userId).also { vault.save(it) }
    }

    /** Saves the server's answer only into the record it was requested for. */
    @Synchronized fun adopt(instance: String, registration: PushRegistration): Boolean {
        val current = record()?.takeIf { it.instance == instance } ?: return false
        vault.save(current.registered(registration))
        mutableStatus.value = PushPhoneStatus.On
        return true
    }

    @Synchronized fun publish(instance: String?, status: PushPhoneStatus) {
        if (instance == null || record()?.instance == instance) mutableStatus.value = status
    }

    /** Local-only forget of [instance] after the distributor dropped it. Returns what it was. */
    @Synchronized fun forget(instance: String, status: PushPhoneStatus): PushRecord? {
        val record = record()?.takeIf { it.instance == instance } ?: return null
        clearQuietly()
        mutableStatus.value = status
        return record
    }

    @Synchronized override fun retire(origin: String?, userId: Long?): Long? {
        val record = record()
        try { cancelNotifications() } catch (_: Exception) { /* Nothing shown, or the system refused; continue. */ }
        clearQuietly()
        try { if (record != null) connector.unregister(record.instance) else connector.unregisterAll() }
        catch (_: Exception) { /* The keys are gone, so anything still delivered can't be decrypted. */ }
        mutableStatus.value = PushPhoneStatus.Off
        return record?.takeIf { origin != null && userId != null && it.belongsTo(origin, userId) }?.registrationId
    }

    @Synchronized fun retireIfForeign(origin: String, userId: Long) {
        val record = record() ?: return
        if (!record.belongsTo(origin, userId)) retire(null, null) // That account's session is gone: local only.
    }

    private fun clearQuietly() { try { vault.clear() } catch (_: IOException) { /* Key deletion is attempted first. */ } }
}

/** Turns push on for this phone and answers the distributor's callbacks. One operation at a time. */
class PushRegistrar(
    private val local: PushLocal,
    private val connector: PushConnector,
    private val sessions: SessionRepository,
    private val scope: CoroutineScope,
    private val io: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
) {
    val status: StateFlow<PushPhoneStatus> = local.status
    private val lock = Mutex()

    init {
        scope.launch {
            sessions.state.map { state ->
                state.server?.value?.takeIf { state.phase == SessionPhase.AUTHENTICATED }?.let { origin ->
                    state.user?.takeIf { !it.guest }?.let { origin to it.id }
                }
            }.distinctUntilChanged().filterNotNull().collect { (origin, userId) ->
                withContext(io) { local.retireIfForeign(origin, userId) }
            }
        }
    }

    /** Registers with the distributor the person already chose. False when this session can't turn push on. */
    suspend fun start(expected: SessionState): Boolean = lock.withLock {
        val origin = expected.server?.value ?: return@withLock false
        val user = expected.user?.takeIf { !it.guest && expected.phase == SessionPhase.AUTHENTICATED } ?: return@withLock false
        val vapid = (expected.webPush as? WebPushDiscovery.Available)?.vapidKey ?: return@withLock false
        val current = sessions.state.value
        if (current.generation != expected.generation || current.user?.id != user.id) return@withLock false
        withContext(io) {
            val record = local.prepare(origin, user.id)
            local.publish(record.instance, if (record.registrationId != null) PushPhoneStatus.On else PushPhoneStatus.Waiting)
            connector.register(record.instance, vapid)
        }
        true
    }

    fun onNewEndpoint(instance: String, endpoint: String, p256dh: String?, auth: String?) {
        scope.launch { lock.withLock { subscribe(instance, endpoint, p256dh, auth) } }
    }

    private suspend fun subscribe(instance: String, endpoint: String, p256dh: String?, auth: String?) {
        val record = withContext(io) { local.record() }?.takeIf { it.instance == instance } ?: return
        val state = sessions.state.value
        // Only the account that turned push on may register it; otherwise wait for its next sign-in.
        if (state.phase != SessionPhase.AUTHENTICATED || !record.belongsTo(state.server?.value.orEmpty(), state.user?.id ?: -1)) return
        val keys = if (p256dh == null || auth == null) null else validEndpointKeys(endpoint, p256dh, auth)
        if (keys == null || keys.p256dh != record.publicKey || keys.auth != record.auth) {
            local.publish(instance, PushPhoneStatus.Refused(PushSubscribeRefusal.DESTINATION))
            return
        }
        val body = pushSubscribeBody(keys, record.registrationId)
        try {
            val access = sessions.openAccess(state)
            val registration = sessions.authorized(access) { subscribePush(body, access.csrf) }
            withContext(io) { local.adopt(instance, registration) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: ApiFailure) {
            local.publish(instance, failure.subscribeRefusal()?.let { PushPhoneStatus.Refused(it) } ?: PushPhoneStatus.Unconfirmed)
        }
    }

    fun onUnregistered(instance: String) {
        scope.launch {
            val forgotten = lock.withLock { withContext(io) { local.forget(instance, PushPhoneStatus.Off) } } ?: return@launch
            val id = forgotten.registrationId ?: return@launch
            val state = sessions.state.value
            if (state.phase != SessionPhase.AUTHENTICATED || !forgotten.belongsTo(state.server?.value.orEmpty(), state.user?.id ?: -1)) return@launch
            try {
                val access = sessions.openAccess(state)
                sessions.authorized(access) { revokePushDevice(id, access.csrf) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: ApiFailure) { /* The device list still shows it; the person can remove it there. */ }
        }
    }

    fun onRegistrationFailed(instance: String, reason: FailedReason) {
        val failure = when (reason) {
            FailedReason.NETWORK -> DistributorFailure.NETWORK
            FailedReason.ACTION_REQUIRED -> DistributorFailure.ACTION_REQUIRED
            else -> DistributorFailure.OTHER
        }
        scope.launch { lock.withLock { withContext(io) { local.forget(instance, PushPhoneStatus.DistributorFailed(failure)) } } }
    }

    /** Only messages the connector decrypted with this record's keys count; rendering arrives with P3. */
    fun onMessage(instance: String, decrypted: Boolean) {
        if (!decrypted) return // Plain text means our keys didn't open it: never trust or show it.
        if (instance != local.record()?.instance) return
    }
}

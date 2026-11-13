package io.github.thiesi.chitchat.feature.push

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.thiesi.chitchat.core.push.PushDistributor
import io.github.thiesi.chitchat.core.push.PushDistributors
import io.github.thiesi.chitchat.core.push.PushPhone
import io.github.thiesi.chitchat.core.push.PushPhoneStatus
import io.github.thiesi.chitchat.core.push.WebPushDiscovery
import io.github.thiesi.chitchat.core.session.SessionPhase
import io.github.thiesi.chitchat.core.session.SessionState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where the Turn on walk-through is. The notification permission step lives in the screen. */
sealed interface TurnOnStep {
    data object None : TurnOnStep
    data object Explain : TurnOnStep
    class Choose(val distributors: List<PushDistributor>, val current: String?) : TurnOnStep {
        override fun toString() = "TurnOnStep.Choose"
    }
    /** No delivery app is installed, but Google delivery is available. Never automatic: the person chooses it. */
    class UseGoogle(val google: PushDistributor) : TurnOnStep {
        override fun toString() = "TurnOnStep.UseGoogle"
    }
    data object NoDistributor : TurnOnStep
}

data class PushPhoneState(
    val step: TurnOnStep = TurnOnStep.None,
    val busy: Boolean = false,
    /** The label of the distributor this phone uses, for "On, delivered by …". */
    val distributor: String? = null,
    /** Turn on found no delivery app; shown until the next attempt. */
    val noDistributor: Boolean = false,
    /** The person declined the notification permission inside Turn on. */
    val permissionDenied: Boolean = false,
    val startFailed: Boolean = false,
    /** Turned off here, but the server's removal of this phone wasn't confirmed. */
    val turnOffUnconfirmed: Boolean = false,
    /** This phone uses Google delivery while a delivery app is installed now: offer Turn on again, never switch. */
    val deliveryAppInstead: Boolean = false,
) {
    override fun toString() = "PushPhoneState"
}

/**
 * This phone's push status and the Turn on walk-through: explanation, permission (screen), distributor
 * choice, channels, then registration. Nothing is retried; every step is the person's tap.
 */
class PushPhoneViewModel(
    private val phone: PushPhone,
    private val distributors: PushDistributors,
    private val createChannels: (direct: Boolean) -> Unit,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val mutable = MutableStateFlow(PushPhoneState())
    val state: StateFlow<PushPhoneState> = mutable.asStateFlow()
    val status: StateFlow<PushPhoneStatus> = phone.status
    private var owner: Pair<Long, Long>? = null

    /** Only a signed-in member account on a server that offers Web Push can turn this phone on. */
    fun canTurnOn(session: SessionState) = session.phase == SessionPhase.AUTHENTICATED && session.user?.guest == false &&
        session.webPush is WebPushDiscovery.Available

    private fun bind(session: SessionState): Boolean {
        val user = session.user?.takeIf { session.phase == SessionPhase.AUTHENTICATED && !it.guest } ?: return false
        val identity = session.generation to user.id
        if (owner != identity) { owner = identity; mutable.value = PushPhoneState() }
        return true
    }

    /** On screen start and resume: which distributor this phone uses, read then rather than on every frame. */
    fun open(session: SessionState) {
        if (!bind(session)) return
        viewModelScope.launch {
            val (label, instead) = withContext(io) {
                val current = distributors.current()
                val google = distributors.google()
                currentLabel() to (current != null && current == google?.packageName && distributors.installed().isNotEmpty())
            }
            mutable.value = mutable.value.copy(distributor = label, deliveryAppInstead = instead)
        }
    }

    fun turnOn(session: SessionState) {
        if (!bind(session) || !canTurnOn(session) || mutable.value.busy) return
        mutable.value = mutable.value.copy(step = TurnOnStep.Explain, noDistributor = false, permissionDenied = false,
            startFailed = false, turnOffUnconfirmed = false)
    }

    fun cancel() { mutable.value = mutable.value.copy(step = TurnOnStep.None) }

    fun permissionDenied() { mutable.value = mutable.value.copy(step = TurnOnStep.None, permissionDenied = true) }

    /** After the explanation and, on Android 13 and later, a granted permission. */
    fun proceed(session: SessionState, direct: Boolean) {
        if (!bind(session) || mutable.value.step != TurnOnStep.Explain || mutable.value.busy) return
        mutable.value = mutable.value.copy(busy = true)
        viewModelScope.launch {
            val installed = withContext(io) { distributors.installed() }
            when (installed.size) {
                0 -> {
                    val google = withContext(io) { distributors.google() }
                    mutable.value = if (google != null) mutable.value.copy(busy = false, step = TurnOnStep.UseGoogle(google))
                        else mutable.value.copy(busy = false, step = TurnOnStep.NoDistributor, noDistributor = true)
                }
                1 -> start(session, installed.single(), direct)
                else -> mutable.value = mutable.value.copy(busy = false,
                    step = TurnOnStep.Choose(installed, withContext(io) { distributors.current() }))
            }
        }
    }

    fun choose(session: SessionState, distributor: PushDistributor, direct: Boolean) {
        val step = mutable.value.step as? TurnOnStep.Choose ?: return
        if (!bind(session) || mutable.value.busy || step.distributors.none { it === distributor }) return
        mutable.value = mutable.value.copy(busy = true)
        viewModelScope.launch { start(session, distributor, direct) }
    }

    /** "Use Google" in the no-delivery-app dialog. */
    fun useGoogle(session: SessionState, direct: Boolean) {
        val step = mutable.value.step as? TurnOnStep.UseGoogle ?: return
        if (!bind(session) || mutable.value.busy) return
        mutable.value = mutable.value.copy(busy = true)
        viewModelScope.launch { start(session, step.google, direct) }
    }

    private suspend fun start(session: SessionState, distributor: PushDistributor, direct: Boolean) {
        val identity = owner
        mutable.value = mutable.value.copy(step = TurnOnStep.None)
        // Switching delivery apps starts over with fresh keys, so the old app can't keep a live registration.
        val previous = withContext(io) { distributors.current() }
        val replaced = previous != null && previous != distributor.packageName && phone.status.value != PushPhoneStatus.Off
        val revoked = if (replaced) phone.turnOff(session) else true
        val started = withContext(io) {
            createChannels(direct)
            distributors.choose(distributor.packageName)
            phone.start(session)
        }
        if (owner == identity) mutable.value = mutable.value.copy(busy = false, startFailed = !started,
            turnOffUnconfirmed = !revoked, distributor = distributor.label, deliveryAppInstead = false)
    }

    fun turnOff(session: SessionState) {
        if (!bind(session) || mutable.value.busy) return
        val identity = owner
        mutable.value = mutable.value.copy(busy = true, step = TurnOnStep.None, startFailed = false)
        viewModelScope.launch {
            val confirmed = phone.turnOff(session)
            if (owner == identity) mutable.value = mutable.value.copy(busy = false, turnOffUnconfirmed = !confirmed)
        }
    }

    /** This phone's row under Devices was turned off: the settings model revokes it, this wipes the keys. */
    fun turnOffLocally(session: SessionState) {
        if (!bind(session)) return
        viewModelScope.launch { phone.turnOffLocally() }
    }

    /** The `direct` channel is created once the person chooses DM alerts while this phone receives. */
    fun directChosen() { if (phone.status.value != PushPhoneStatus.Off) createChannels(true) }

    /** Leaving Notifications or a genuine background closes the walk-through; this phone's status stays. */
    fun retire() {
        if (!mutable.value.busy) mutable.value = mutable.value.copy(step = TurnOnStep.None)
    }

    // Someone already on Google keeps it after installing a delivery app; their status still names it.
    private fun currentLabel(): String? = distributors.current()?.let { current ->
        (distributors.installed() + listOfNotNull(distributors.google())).firstOrNull { it.packageName == current }?.label
    }

    companion object {
        internal fun factory(phone: PushPhone, distributors: PushDistributors, createChannels: (Boolean) -> Unit):
            ViewModelProvider.Factory = viewModelFactory {
            initializer { PushPhoneViewModel(phone, distributors, createChannels) }
        }
    }
}

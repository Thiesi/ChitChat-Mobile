package io.github.thiesi.chitchat.feature.push

import androidx.lifecycle.ViewModelStore
import io.github.thiesi.chitchat.core.network.SessionUser
import io.github.thiesi.chitchat.core.push.*
import io.github.thiesi.chitchat.core.server.AddressResult
import io.github.thiesi.chitchat.core.server.ServerAddress
import io.github.thiesi.chitchat.core.session.SessionPhase
import io.github.thiesi.chitchat.core.session.SessionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PushPhoneViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val phone = FakePhone()
    private val distributors = FakeDistributors()
    private val channels = mutableListOf<Boolean>()
    private val log = mutableListOf<String>()
    private val store = ViewModelStore()
    private lateinit var model: PushPhoneViewModel
    private val vapid = PushRecord.create("https://vapid.example", 1, 0).publicKey
    private val signedIn = session(WebPushDiscovery.Available(vapid))

    private fun session(webPush: WebPushDiscovery, guest: Boolean = false) = SessionState(generation = 3,
        phase = if (guest) SessionPhase.GUEST else SessionPhase.AUTHENTICATED,
        server = (ServerAddress.parse("https://fixture.example/") as AddressResult.Valid).address,
        user = SessionUser(7, "Viewer", listOf("member"), 1, guest), webPush = webPush)

    @Before fun start() {
        Dispatchers.setMain(dispatcher)
        model = PushPhoneViewModel(phone, distributors, { channels += it; log += "channels" }, dispatcher)
        store.put("m", model)
    }
    @After fun stop() { store.clear(); Dispatchers.resetMain() }

    @Test fun oneDistributorTurnsOnAfterTheExplanationWithChannelsCreatedFirst() {
        distributors.installed = listOf(PushDistributor("org.example.ntfy", "ntfy"))
        model.open(signedIn)
        assertTrue("Nothing is created before Turn on", channels.isEmpty())
        model.turnOn(signedIn)
        assertEquals(TurnOnStep.Explain, model.state.value.step)
        assertEquals(0, phone.starts)
        model.proceed(signedIn, direct = false)
        assertEquals(listOf("org.example.ntfy"), distributors.chosen)
        assertEquals(listOf(false), channels)
        assertEquals(1, phone.starts)
        assertEquals("Channels exist before the distributor is asked", listOf("channels", "choose", "start"), log)
        val state = model.state.value
        assertEquals(TurnOnStep.None, state.step)
        assertFalse(state.busy); assertFalse(state.startFailed)
        assertEquals("ntfy", state.distributor)
    }

    @Test fun severalDistributorsAskForAChoiceWithTheCurrentOnePreselected() {
        val a = PushDistributor("org.example.a", "Alpha")
        val b = PushDistributor("org.example.b", "Beta")
        distributors.installed = listOf(a, b); distributors.current = "org.example.b"
        model.turnOn(signedIn); model.proceed(signedIn, direct = true)
        val step = model.state.value.step as TurnOnStep.Choose
        assertEquals(listOf(a, b), step.distributors)
        assertEquals("org.example.b", step.current)
        assertEquals(0, phone.starts)
        model.choose(signedIn, PushDistributor("org.example.a", "Alpha"), direct = true) // Not one of the offered rows.
        assertEquals(0, phone.starts)
        model.choose(signedIn, b, direct = true)
        assertEquals(listOf("org.example.b"), distributors.chosen)
        assertEquals(listOf(true), channels)
        assertEquals(1, phone.starts)
        assertEquals(0, phone.turnOffs)
    }

    @Test fun noDistributorExplainsAndStartsNothing() {
        model.turnOn(signedIn); model.proceed(signedIn, direct = false)
        assertEquals(TurnOnStep.NoDistributor, model.state.value.step)
        assertTrue(model.state.value.noDistributor)
        assertTrue(channels.isEmpty()); assertEquals(0, phone.starts); assertTrue(distributors.chosen.isEmpty())
        model.cancel()
        assertEquals(TurnOnStep.None, model.state.value.step)
        assertTrue("The card keeps saying no delivery app was found", model.state.value.noDistributor)
    }

    @Test fun turnOnNeedsAMemberSessionAndTheServersPushKey() {
        for (session in listOf(session(WebPushDiscovery.Unknown), session(WebPushDiscovery.Unavailable),
            session(WebPushDiscovery.Available(vapid), guest = true))) {
            assertFalse(model.canTurnOn(session))
            model.turnOn(session)
            assertEquals(TurnOnStep.None, model.state.value.step)
        }
        assertTrue(model.canTurnOn(signedIn))
    }

    @Test fun aDeclinedPermissionStopsBeforeAnyDistributorWork() {
        distributors.installed = listOf(PushDistributor("org.example.ntfy", "ntfy"))
        model.turnOn(signedIn)
        model.permissionDenied()
        assertTrue(model.state.value.permissionDenied)
        model.proceed(signedIn, direct = false) // Only from the explanation step.
        assertEquals(0, phone.starts); assertTrue(channels.isEmpty()); assertEquals(0, distributors.lists)
    }

    @Test fun switchingDeliveryAppsTurnsTheOldRegistrationOffFirst() {
        val a = PushDistributor("org.example.a", "Alpha")
        val b = PushDistributor("org.example.b", "Beta")
        distributors.installed = listOf(a, b); distributors.current = "org.example.a"
        phone.status.value = PushPhoneStatus.NeedsSetup
        model.turnOn(signedIn); model.proceed(signedIn, direct = false); model.choose(signedIn, b, direct = false)
        assertEquals(listOf("turnOff", "start"), phone.order)
        phone.order.clear()
        distributors.current = "org.example.b"
        model.turnOn(signedIn); model.proceed(signedIn, direct = false); model.choose(signedIn, b, direct = false)
        assertEquals("Turning on again with the same app keeps its keys", listOf("start"), phone.order)
    }

    @Test fun failuresAreShownNotRetried() {
        distributors.installed = listOf(PushDistributor("org.example.ntfy", "ntfy"))
        phone.startResult = false
        model.turnOn(signedIn); model.proceed(signedIn, direct = false)
        assertTrue(model.state.value.startFailed)
        assertEquals(1, phone.starts)
        phone.status.value = PushPhoneStatus.On
        phone.turnOffResult = false
        model.turnOff(signedIn)
        assertTrue(model.state.value.turnOffUnconfirmed)
        assertEquals(1, phone.turnOffs)
        assertEquals(1, phone.starts)
    }

    @Test fun theDirectChannelAppearsOnlyWhileThisPhoneReceives() {
        model.directChosen()
        assertTrue(channels.isEmpty())
        phone.status.value = PushPhoneStatus.Waiting
        model.directChosen()
        assertEquals(listOf(true), channels)
    }

    @Test fun theRowTurnOffWipesLocallyWithoutASecondRevoke() {
        model.turnOffLocally(signedIn)
        assertEquals(listOf("local"), phone.order)
        assertEquals(0, phone.turnOffs)
    }

    private inner class FakePhone : PushPhone {
        override val status = MutableStateFlow<PushPhoneStatus>(PushPhoneStatus.Off)
        var starts = 0; var turnOffs = 0
        var startResult = true; var turnOffResult = true
        val order = mutableListOf<String>()
        override suspend fun start(expected: SessionState): Boolean { starts++; order += "start"; log += "start"; return startResult }
        override suspend fun turnOff(expected: SessionState): Boolean { turnOffs++; order += "turnOff"; return turnOffResult }
        override suspend fun turnOffLocally() { order += "local" }
    }

    private inner class FakeDistributors : PushDistributors {
        var installed = emptyList<PushDistributor>()
        var current: String? = null
        val chosen = mutableListOf<String>()
        var lists = 0
        override fun installed(): List<PushDistributor> { lists++; return installed }
        override fun current(): String? = current
        override fun choose(packageName: String) { chosen += packageName; log += "choose" }
    }
}

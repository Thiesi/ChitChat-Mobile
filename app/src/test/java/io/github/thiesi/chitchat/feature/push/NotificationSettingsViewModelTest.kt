package io.github.thiesi.chitchat.feature.push

import androidx.lifecycle.ViewModelStore
import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.network.FailureKind
import io.github.thiesi.chitchat.core.network.SessionUser
import io.github.thiesi.chitchat.core.push.*
import io.github.thiesi.chitchat.core.server.AddressResult
import io.github.thiesi.chitchat.core.server.ServerAddress
import io.github.thiesi.chitchat.core.session.SessionPhase
import io.github.thiesi.chitchat.core.session.SessionState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationSettingsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val ops = Fake()
    private val store = ViewModelStore()
    private lateinit var model: NotificationSettingsViewModel
    private val signedIn get() = ops.session.value

    @Before fun start() { Dispatchers.setMain(dispatcher); model = NotificationSettingsViewModel(ops); store.put("m", model) }
    @After fun stop() { store.clear(); Dispatchers.resetMain() }
    private fun opened() { model.open(signedIn); dispatcher.scheduler.runCurrent() }
    private val prefs get() = model.state.value.settings!!.preferences

    @Test fun openLoadsOnceAndShowsServerFacts() {
        opened(); model.open(signedIn); dispatcher.scheduler.runCurrent()
        assertEquals(1, ops.gets)
        assertTrue(prefs.mentioned); assertNull("Unoffered DM choice stays hidden", prefs.direct)
    }

    @Test fun aConfirmedChoiceReplacesPreferencesWithTheServerReply() {
        opened()
        assertTrue(model.choose(signedIn, prefs.copy(mentioned = false)))
        assertTrue(model.state.value.writing)
        assertTrue("No optimistic flip while writing", prefs.mentioned)
        dispatcher.scheduler.runCurrent()
        assertFalse(prefs.mentioned); assertFalse(model.state.value.unconfirmed)
    }

    @Test fun aDefiniteRefusalKeepsThePreviousChoiceAndAllowsAnother() {
        opened(); ops.failure = ApiFailure(FailureKind.HTTP, 400, "validation_error")
        model.choose(signedIn, prefs.copy(pinged = false)); dispatcher.scheduler.runCurrent()
        assertTrue(prefs.pinged); assertFalse(model.state.value.unconfirmed); assertNotNull(model.state.value.error)
        assertTrue(model.canChoose())
    }

    @Test fun anUncertainWriteNeedsAnExplicitGetBeforeANewChoiceAndIsNeverReplayed() {
        opened(); ops.failure = ApiFailure(FailureKind.TIMEOUT)
        model.choose(signedIn, prefs.copy(mentioned = false)); dispatcher.scheduler.runCurrent()
        assertTrue(model.state.value.unconfirmed); assertFalse(model.canChoose())
        assertFalse(model.choose(signedIn, prefs.copy(mentioned = false)))
        assertEquals(1, ops.writes)
        ops.failure = null; model.refresh(signedIn); dispatcher.scheduler.runCurrent()
        assertEquals(2, ops.gets); assertFalse(model.state.value.unconfirmed); assertTrue(model.canChoose())
        assertEquals("Reconciliation is a GET, never a replayed POST", 1, ops.writes)
    }

    @Test fun cancellingAWriteMakesItUnconfirmed() {
        opened(); ops.gate = CompletableDeferred()
        model.choose(signedIn, prefs.copy(mentioned = false)); dispatcher.scheduler.runCurrent()
        model.retire(); dispatcher.scheduler.runCurrent()
        assertNull("Retirement clears private facts", model.state.value.settings)
    }

    @Test fun removeRevokesByIdAndKeepsTheServersRemainingList() {
        opened()
        val device = model.state.value.settings!!.devices.single()
        assertTrue(model.remove(signedIn, device)); dispatcher.scheduler.runCurrent()
        assertEquals(listOf(4L), ops.revoked); assertTrue(model.state.value.settings!!.devices.isEmpty())
    }

    @Test fun anotherAccountOrGenerationNeverSeesEarlierFacts() {
        opened()
        ops.session.value = signedIn.copy(generation = signedIn.generation + 1)
        model.open(signedIn)
        assertNull(model.state.value.settings)
        dispatcher.scheduler.runCurrent()
        assertEquals(2, ops.gets)
    }

    private class Fake : PushOperations {
        override val session = MutableStateFlow(SessionState(generation = 1, phase = SessionPhase.AUTHENTICATED,
            server = (ServerAddress.parse("https://fixture.example/") as AddressResult.Valid).address,
            user = SessionUser(7, "Viewer", listOf("member"), 1, false)))
        var gets = 0; var writes = 0; val revoked = mutableListOf<Long>()
        var failure: ApiFailure? = null; var gate: CompletableDeferred<Unit>? = null
        private var devices = listOf(PushDevice(4, "Firefox/128 Linux", null, false, "2026-07-18T09:12:00+00:00", null))
        override suspend fun settings(expected: SessionState): PushSettings { gets++
            return PushSettings(PushPreferences(true, true, null, null), devices) }
        override suspend fun update(expected: SessionState, preferences: PushPreferences): PushPreferences { writes++
            gate?.await(); failure?.let { throw it }; return preferences }
        override suspend fun revoke(expected: SessionState, deviceId: Long): List<PushDevice> {
            revoked += deviceId; devices = devices.filterNot { it.id == deviceId }; return devices }
    }
}

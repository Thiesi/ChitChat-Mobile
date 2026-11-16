package io.github.thiesi.chitchat.feature.rooms

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.thiesi.chitchat.core.rooms.CreateRoom
import io.github.thiesi.chitchat.core.rooms.RoomInfo
import io.github.thiesi.chitchat.core.rooms.RoomOperations
import io.github.thiesi.chitchat.core.session.SessionPhase
import io.github.thiesi.chitchat.core.session.SessionState
import io.github.thiesi.chitchat.core.network.SessionUser
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/**
 * The navigation-transition ordering CI's disabled animations hide (defect found post-merge in O2b, unreleased): the
 * outgoing Rooms entry's ON_STOP arrives after Find your people has attached and foregrounded the shared model, while
 * the app keeps running. RoomsScreen must treat it as a departure: nothing cancelled, nothing marked stale.
 */
class RoomsDepartureOrderTest {
    @get:Rule val compose = createComposeRule()
    private val api = Rooms()
    private val model = RoomsViewModel(api)
    private val roomsEntry = Entry()

    @After fun release() { api.gate?.complete(Unit) }

    @Test fun theOutgoingRoomsStopAfterFindPeopleStartedCancelsNothing() {
        compose.runOnUiThread { roomsEntry.move(Lifecycle.State.RESUMED) }
        compose.setContent {
            Column {
                // The Rooms entry, with its own lifecycle; it's no longer the current entry (a departure).
                CompositionLocalProvider(LocalLifecycleOwner provides roomsEntry) {
                    Box(Modifier.height(1.dp)) { RoomsScreen(model, {}, {}) { false } }
                }
                FindPeopleScreen(model, "Alice", "chat.example.test", onStartChatting = {})
            }
        }
        compose.waitUntil(5_000) { model.state.value.loaded && !model.state.value.busy }
        val gate = CompletableDeferred<Unit>().also { api.gate = it }
        compose.runOnIdle { model.joinListed(1) }
        compose.waitUntil(5_000) { model.state.value.busy }
        compose.runOnUiThread { roomsEntry.move(Lifecycle.State.CREATED) } // The late ON_STOP, after the transition.
        compose.runOnIdle {
            assertTrue("The join wasn't cancelled", model.state.value.busy)
            assertFalse(model.state.value.stale); assertFalse(model.state.value.mutationUncertain)
        }
        compose.runOnIdle { gate.complete(Unit) }
        compose.waitUntil(5_000) { model.state.value.rooms.first { it.id == 1L }.isMember && !model.state.value.busy }
        compose.runOnIdle { assertEquals(1, api.joins); assertFalse(model.state.value.mutationUncertain) }
    }

    /** The same ON_STOP while Rooms is still the current entry is an app stop: it cancels (M3a). */
    @Test fun theSameStopAsAnAppStopStillCancels() {
        compose.runOnUiThread { roomsEntry.move(Lifecycle.State.RESUMED) }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides roomsEntry) { RoomsScreen(model, {}, {}) { true } }
        }
        compose.waitUntil(5_000) { model.state.value.loaded && !model.state.value.busy }
        api.gate = CompletableDeferred()
        compose.runOnIdle { model.joinListed(1) }
        compose.waitUntil(5_000) { model.state.value.busy }
        compose.runOnUiThread { roomsEntry.move(Lifecycle.State.CREATED) }
        compose.runOnIdle { assertTrue(model.state.value.mutationUncertain); assertFalse(model.state.value.busy) }
    }

    private class Entry : LifecycleOwner {
        private val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
        fun move(state: Lifecycle.State) { registry.currentState = state }
    }

    private class Rooms : RoomOperations {
        override val session = MutableStateFlow(SessionState(generation = 1, phase = SessionPhase.AUTHENTICATED,
            user = SessionUser(7, "Alice", listOf("member"), 1, false)))
        private var rooms = listOf(room(1, null), room(2, null))
        @Volatile var gate: CompletableDeferred<Unit>? = null
        @Volatile var joins = 0
        override suspend fun list(): List<RoomInfo> = rooms
        override suspend fun detail(roomId: Long) = rooms.first { it.id == roomId }
        override suspend fun join(roomId: Long): RoomInfo {
            joins++; gate?.await()
            rooms = rooms.map { if (it.id == roomId) room(roomId, "member") else it }
            return rooms.first { it.id == roomId }
        }
        override suspend fun leave(roomId: Long) = error("Unexpected leave")
        override suspend fun create(input: CreateRoom): RoomInfo = error("Unexpected create")
        companion object {
            fun room(id: Long, role: String?) = RoomInfo(id, "room$id", "Room $id", "", "public", 0, 0, 7, role, false, "none")
        }
    }
}

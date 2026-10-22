package io.github.thiesi.chitchat.feature.realtime

import io.github.thiesi.chitchat.core.network.apiJson
import io.github.thiesi.chitchat.core.realtime.*
import io.github.thiesi.chitchat.core.session.SessionState
import io.github.thiesi.chitchat.core.typing.*
import androidx.test.platform.app.InstrumentationRegistry
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject

/** UI-only fake transport feeding the real lifecycle coordinator; never a live-server claim. */
internal class NativeTypingHarness(override val session: StateFlow<SessionState>,
    elapsed: () -> Long = { System.nanoTime() / 1_000_000 }) : TypingOperations, AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val events = Events(session.value.generation)
    private val ids = AtomicLong()
    val requests = AtomicInteger()
    val controller = TypingCoordinator(this, events, scope, elapsed = elapsed).also { it.setForeground(true) }
    init { scope.launch { session.collect { events.state.value = events.state.value.copy(generation = it.generation) } } }
    override suspend fun signal(expected: SessionState, target: TypingTarget): Boolean {
        requests.incrementAndGet(); return true
    }
    fun hint(room: Long?, actor: Long = 8) {
        val payload = apiJson.parseToJsonElement("""{"room_id":$room,"user":{"id":$actor,"username":"Peer"}}""") as JsonObject
        check(events.events.tryEmit(RealtimeEvent(ids.incrementAndGet(), "typing", room, actor, payload, Instant.now())))
    }
    fun capture(name: String) {
        if (InstrumentationRegistry.getArguments().getString("captureTypingFixture") != "true") return
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        val file = java.io.File(instrumentation.targetContext.getExternalFilesDir("screenshots"), name)
        try { file.outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
    }
    override fun close() = scope.cancel()
    private class Events(generation: Long) : RealtimeController {
        override val state = MutableStateFlow(RealtimeState(generation = generation, phase = RealtimePhase.LIVE))
        override val events = MutableSharedFlow<RealtimeEvent>(extraBufferCapacity = 16)
        override fun selectRoom(roomId: Long?, eligible: Boolean, interacted: Boolean) = Unit
        override fun activity(roomId: Long) = Unit
        override fun reenter(roomId: Long) = Unit
        override fun retry() = Unit
        override fun dismissBroadcast(id: Long) = Unit
    }
}

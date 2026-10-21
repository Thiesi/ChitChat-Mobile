package io.github.thiesi.chitchat.testing

import android.os.SystemClock
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal enum class InputTraceKind { START, CONNECTION_REQUESTED, CONNECTION_CREATED, CONNECTION_FAILED, END }
internal data class InputTraceEvent(val uptime: Long, val session: Long, val kind: InputTraceKind)

/** No request, connection, EditorInfo, View, contents, account identifiers or exception retained. */
internal class InputTraceRecorder(private val capacity: Int = 64,
    private val uptime: () -> Long = SystemClock::uptimeMillis) {
    private val lock = Any()
    private val sessions = AtomicLong()
    private val events = ArrayDeque<InputTraceEvent>()
    init { require(capacity > 0) }
    fun nextSession(): Long = sessions.incrementAndGet()
    fun record(session: Long, kind: InputTraceKind) = synchronized(lock) {
        while (events.size >= capacity) events.removeFirst()
        events.addLast(InputTraceEvent(uptime(), session, kind))
    }
    fun snapshot(): List<InputTraceEvent> = synchronized(lock) { events.toList() }
}

/** At most the active fixture's scalar recorder; a retired root cannot remove its successor. */
internal object NativeTextInputTrace {
    private val active = AtomicReference<InputTraceRecorder?>()
    fun enter(recorder: InputTraceRecorder) { active.set(recorder) }
    fun leave(recorder: InputTraceRecorder) { active.compareAndSet(recorder, null) }
    fun describe(): String = active.get()?.snapshot()?.joinToString(prefix = "observed[", postfix = "]") {
        "${it.uptime}#${it.session}:${it.kind.name}"
    } ?: "not installed"
}

/** Observe the public input pipeline, always delegate once, and never wrap/read the connection. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun ObserveNativeTextInput(recorder: InputTraceRecorder? = null, content: @Composable () -> Unit) {
    val sink = recorder ?: remember { InputTraceRecorder() }
    // A new interceptor identity would itself restart input. Keep it stable across recomposition.
    val interceptor = remember(sink) {
        PlatformTextInputInterceptor { request, next ->
            val session = sink.nextSession()
            sink.record(session, InputTraceKind.START)
            val observed = object : PlatformTextInputMethodRequest {
                override fun createInputConnection(outAttributes: EditorInfo): InputConnection {
                    sink.record(session, InputTraceKind.CONNECTION_REQUESTED)
                    return try {
                        request.createInputConnection(outAttributes).also {
                            sink.record(session, InputTraceKind.CONNECTION_CREATED)
                        }
                    } catch (failure: Throwable) {
                        sink.record(session, InputTraceKind.CONNECTION_FAILED)
                        throw failure
                    }
                }
            }
            try { next.startInputMethod(observed) }
            finally { sink.record(session, InputTraceKind.END) }
        }
    }
    DisposableEffect(sink) {
        NativeTextInputTrace.enter(sink)
        onDispose { NativeTextInputTrace.leave(sink) }
    }
    InterceptPlatformTextInput(interceptor, content)
}

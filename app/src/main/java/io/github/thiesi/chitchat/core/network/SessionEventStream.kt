package io.github.thiesi.chitchat.core.network

import io.github.thiesi.chitchat.core.realtime.RealtimeSignal
import java.io.IOException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import okio.Buffer
import okio.ForwardingSource
import okio.buffer

/** Derived client shares its session jar, cancellation dispatcher, pool, TLS and origin policy. */
internal fun sessionEventStream(client: OkHttpClient, request: Request, cookiesInvalid: () -> Boolean,
    failure: (Throwable?, Response?) -> ApiFailure): Flow<RealtimeSignal> = callbackFlow {
    val bounded = client.newBuilder().addInterceptor { chain ->
        val response = chain.proceed(chain.request())
        val body = response.body
        // An application interceptor sees decompressed bytes. Bound even unfinished/malicious
        // frames before the standard reader allocates their strings; reconnect can replay later.
        val source = object : ForwardingSource(body.source()) {
            private var total = 0L
            override fun read(sink: Buffer, byteCount: Long): Long {
                val count = super.read(sink, byteCount.coerceAtMost(STREAM_MAX_BYTES - total + 1))
                if (count > 0) total += count
                if (total > STREAM_MAX_BYTES) throw ApiFailure(FailureKind.INCOMPATIBLE)
                return count
            }
        }.buffer()
        response.newBuilder().body(object : ResponseBody() {
            override fun contentType() = body.contentType()
            override fun contentLength() = body.contentLength()
            override fun source() = source
        }).build()
    }.build()
    val listener = object : EventSourceListener() {
        private fun publish(source: EventSource, signal: RealtimeSignal) {
            if (trySend(signal).isFailure) {
                // Never silently drop an event or advance a cursor beyond an unprocessed frame.
                close(ApiFailure(FailureKind.NETWORK, code = "stream_overflow"))
                source.cancel()
            }
        }
        override fun onOpen(eventSource: EventSource, response: Response) {
            if (response.code != 200 || cookiesInvalid()) {
                close(ApiFailure(FailureKind.INCOMPATIBLE)); eventSource.cancel()
            } else publish(eventSource, RealtimeSignal.Opened)
        }
        override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
            try {
                if (cookiesInvalid()) throw ApiFailure(FailureKind.INCOMPATIBLE)
                publish(eventSource, decodeRealtimeSignal(id, type, data))
            } catch (problem: ApiFailure) {
                close(problem); eventSource.cancel()
            }
        }
        override fun onClosed(eventSource: EventSource) { close() }
        override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
            val problem = try { failure(t, response) }
                catch (problem: IOException) { if (problem is ApiFailure) problem else ApiFailure(FailureKind.NETWORK) }
            close(problem)
        }
    }
    val source = EventSources.createFactory(bounded).newEventSource(request, listener)
    awaitClose { source.cancel() }
}.buffer(16)

private const val STREAM_MAX_BYTES = 8L * 1024 * 1024

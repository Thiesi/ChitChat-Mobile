package io.github.thiesi.chitchat.core.network

import io.github.thiesi.chitchat.core.server.ServerAddress
import io.github.thiesi.chitchat.core.session.OriginCookieJar
import java.io.IOException
import java.io.InterruptedIOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.CookieJar
import okhttp3.Dispatcher
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** No redirects, automatic replay, logging or disk cache. The platform verifies TLS. */
internal class SessionHttp(server: ServerAddress, base: OkHttpClient = OkHttpClient()) {
    private val origin = server.value.toHttpUrl()
    private val jar = OriginCookieJar(origin)
    private val client = base.newBuilder()
        .dispatcher(Dispatcher()).connectionPool(ConnectionPool())
        .cookieJar(jar).cache(null)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).callTimeout(25, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val url = chain.request().url
            if (url.scheme != "https" || url.host != origin.host || url.port != origin.port ||
                !url.encodedPath.startsWith("/api/v1/")) throw ApiFailure(FailureKind.REDIRECT)
            chain.proceed(chain.request())
        }.build()

    fun restoreCookies(cookies: List<String>) = jar.restore(cookies)
    fun savedCookies() = jar.export()
    fun close() {
        jar.retire()
        client.dispatcher.cancelAll()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    suspend fun bootstrap(): SessionReply {
        val raw = request("session.php")
        if (raw.status != 200) throw ApiFailure(FailureKind.INCOMPATIBLE)
        val reply = decode<SessionReply>(raw)
        requireValidCsrf(reply.csrfToken)
        reply.user?.let(::requireValidUser)
        return reply
    }

    suspend fun password(username: String, password: String, csrf: String, restore: Boolean = false): AuthReply =
        auth(if (restore) "account/restore.php" else "login.php", apiJson.encodeToString(PasswordRequest(username, password)), csrf)

    suspend fun recovery(code: String, csrf: String): AuthReply =
        auth("mfa/login-recovery.php", apiJson.encodeToString(RecoveryRequest(code)), csrf)

    private suspend fun auth(path: String, body: String, csrf: String): AuthReply {
        val raw = request(path, body, csrf)
        val reply = decode<AuthReply>(raw)
        requireValidCsrf(reply.csrfToken)
        if (raw.status == 202) {
            if (!reply.mfaRequired || reply.user != null || "recovery_code" !in reply.methods) {
                throw ApiFailure(FailureKind.INCOMPATIBLE)
            }
        } else {
            if (raw.status != 200 || reply.mfaRequired || reply.user == null || reply.user.guest) {
                throw ApiFailure(FailureKind.INCOMPATIBLE)
            }
            requireValidUser(reply.user)
        }
        return reply
    }

    /** Build before retiring the jar. Only this bounded, in-memory attempt retains old credentials. */
    fun logoutRequest(csrf: String): Request = buildRequest("logout.php", "{}", csrf).newBuilder()
        .header("Cookie", jar.loadForRequest(origin.resolve("api/v1/logout.php")!!).joinToString("; ") { "${it.name}=${it.value}" })
        .build()

    suspend fun sendLogout(request: Request) {
        val oneShot = client.newBuilder().dispatcher(Dispatcher()).cookieJar(CookieJar.NO_COOKIES)
            .callTimeout(5, TimeUnit.SECONDS).build()
        try {
            val reply = execute(oneShot.newCall(request))
            if (reply.status != 200 || decode<LogoutReply>(reply).status != "logged_out") {
                throw ApiFailure(FailureKind.INCOMPATIBLE)
            }
        } finally {
            oneShot.dispatcher.executorService.shutdown()
            oneShot.connectionPool.evictAll()
        }
    }

    private fun buildRequest(path: String, body: String? = null, csrf: String? = null): Request =
        Request.Builder().url(origin.resolve("api/v1/$path")!!).header("Accept", "application/json")
            .apply {
                if (body != null) {
                    requireValidCsrf(csrf.orEmpty())
                    header("X-CSRF-Token", csrf!!)
                    post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
                }
            }.build()

    private suspend fun request(path: String, body: String? = null, csrf: String? = null): RawReply {
        val raw = execute(client.newCall(buildRequest(path, body, csrf)))
        if (jar.invalid) throw ApiFailure(FailureKind.INCOMPATIBLE)
        return raw
    }

    private suspend fun execute(call: Call): RawReply = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(if (e is ApiFailure) e else ApiFailure(transportKind(e)))
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val reply = response.use {
                        if (it.code in 300..399) throw ApiFailure(FailureKind.REDIRECT, it.code)
                        val source = it.body.source()
                        source.request(1_048_577)
                        if (source.buffer.size > 1_048_576) throw ApiFailure(FailureKind.INCOMPATIBLE)
                        val body = source.buffer.readUtf8()
                        if (!it.isSuccessful) {
                            val error = try { apiJson.decodeFromString<ErrorEnvelope>(body).error } catch (_: SerializationException) { null }
                            throw ApiFailure(FailureKind.HTTP, it.code, error?.code?.take(100), retryAfter(it.header("Retry-After")))
                        }
                        if (it.body.contentType()?.subtype != "json") throw ApiFailure(FailureKind.INCOMPATIBLE)
                        RawReply(it.code, body)
                    }
                    if (continuation.isActive) continuation.resume(reply)
                } catch (failure: ApiFailure) {
                    if (continuation.isActive) continuation.resumeWithException(failure)
                } catch (failure: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(ApiFailure(transportKind(failure)))
                }
            }
        })
    }

    private suspend inline fun <reified T> decode(raw: RawReply): T = withContext(Dispatchers.Default) {
        try { apiJson.decodeFromString<T>(raw.body) }
        catch (_: SerializationException) { throw ApiFailure(FailureKind.INCOMPATIBLE) }
    }

    private class RawReply(val status: Int, val body: String)

    @kotlinx.serialization.Serializable
    private class LogoutReply(val status: String)

    private fun transportKind(failure: IOException): FailureKind {
        // Route exhaustion can wrap TLS failures; never expose raw exception messages.
        val pending = java.util.ArrayDeque<Throwable>().apply { add(failure) }
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
        var timeout = false
        while (pending.isNotEmpty() && seen.size < 32) {
            val current = pending.removeFirst()
            if (!seen.add(current)) continue
            if (current is SSLException) return FailureKind.TLS
            if (current is InterruptedIOException) timeout = true
            current.cause?.let(pending::add)
            current.suppressed.take(32).forEach(pending::add)
        }
        return if (timeout) FailureKind.TIMEOUT else FailureKind.NETWORK
    }

    private fun retryAfter(value: String?): Long? {
        if (value == null) return null
        val seconds = value.toLongOrNull() ?: try {
            (ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - System.currentTimeMillis()) / 1000
        } catch (_: Exception) { return null }
        return seconds.coerceIn(0, 86_400)
    }
}

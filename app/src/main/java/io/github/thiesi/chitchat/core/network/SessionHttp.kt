package io.github.thiesi.chitchat.core.network

import io.github.thiesi.chitchat.core.server.ServerAddress
import io.github.thiesi.chitchat.core.session.OriginCookieJar
import io.github.thiesi.chitchat.core.account.PersonalExport
import io.github.thiesi.chitchat.core.account.VerificationMethods
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
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

    suspend fun password(username: String, password: String, csrf: String, restore: Boolean = false): AuthReply {
        val reply = auth(if (restore) "account/restore.php" else "login.php", apiJson.encodeToString(PasswordRequest(username, password)), csrf)
        if (restore && ((!reply.mfaRequired && !reply.restored) || (reply.mfaRequired &&
                (!reply.restorationPending || reply.restored)))) throw ApiFailure(FailureKind.INCOMPATIBLE)
        return reply
    }

    suspend fun registrationChallenge(): RegistrationChallenge? {
        val raw = request("registration-challenge.php")
        if (raw.status != 200) throw ApiFailure(FailureKind.INCOMPATIBLE)
        return decode<RegistrationChallengeReply>(raw).challenge
    }

    suspend fun register(username: String, password: String, birthDate: String?, nonce: String?, solution: String?, csrf: String): AuthReply =
        auth("register.php", apiJson.encodeToString(RegistrationRequest(username, password, birthDate, "", nonce, solution)),
            csrf, successStatus = 201, allowPending = false)

    suspend fun recovery(code: String, csrf: String): AuthReply =
        auth("mfa/login-recovery.php", apiJson.encodeToString(RecoveryRequest(code)), csrf, allowPending = false)

    suspend fun verificationMethods(): VerificationMethods {
        val mfa = decode<MfaStatusReply>(request("account/mfa/status.php").require200()).mfa
        if (mfa.remaining !in 0..100) throw ApiFailure(FailureKind.INCOMPATIBLE)
        // Do not guess that an external-identity account has a usable password.
        val identities = decode<IdentityMethodsReply>(request("account/identities/list.php").require200())
        return VerificationMethods(mfa.enabled, identities.hasPassword, mfa.remaining)
    }

    suspend fun verifyPassword(password: String, csrf: String) {
        val reply = decode<PasswordStepUpReply>(request("step-up.php",
            apiJson.encodeToString(StepUpPasswordRequest(password)), csrf).require200())
        requireActiveStepUp(reply.status, "password")
    }

    suspend fun verifyRecovery(code: String, csrf: String): Int {
        val reply = decode<RecoveryStepUpReply>(request("mfa/step-up-recovery.php",
            apiJson.encodeToString(RecoveryRequest(code)), csrf).require200())
        requireActiveStepUp(reply.security.status, "recovery_code")
        if (reply.remaining !in 0..100) throw ApiFailure(FailureKind.INCOMPATIBLE)
        return reply.remaining
    }

    suspend fun personalExport(csrf: String, userId: Long): PersonalExport {
        val raw = request("account/export.php", "{}", csrf, export = true).require200()
        return withContext(Dispatchers.Default) {
            val reply = decode<ExportReply>(raw)
            val format = reply.export["format"] as? JsonObject
            val version = format?.get("version") as? JsonPrimitive
            val account = reply.export["account"] as? JsonObject
            val id = account?.get("id") as? JsonPrimitive
            if (!reply.filename.matches(Regex("[A-Za-z0-9._-]{1,200}\\.json")) ||
                (format?.get("name") as? JsonPrimitive)?.content != "chitchat-personal-data-export" ||
                version == null || version.isString || version.intOrNull != 1 ||
                id == null || id.isString || id.longOrNull != userId) throw ApiFailure(FailureKind.INCOMPATIBLE)
            // Export only the documented object, not the transport envelope. No disk cache.
            PersonalExport(reply.export.toString().encodeToByteArray())
        }
    }

    private suspend fun auth(path: String, body: String, csrf: String, successStatus: Int = 200, allowPending: Boolean = true): AuthReply {
        val raw = request(path, body, csrf)
        val reply = decode<AuthReply>(raw)
        requireValidCsrf(reply.csrfToken)
        if (raw.status == 202) {
            if (!allowPending || !reply.mfaRequired || reply.user != null || "recovery_code" !in reply.methods) {
                throw ApiFailure(FailureKind.INCOMPATIBLE)
            }
        } else {
            if (raw.status != successStatus || reply.mfaRequired || reply.user == null || reply.user.guest) {
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

    private suspend fun request(path: String, body: String? = null, csrf: String? = null, export: Boolean = false): RawReply {
        // Shares this runtime's jar, dispatcher and pool, including cancellation on retirement.
        val transport = if (export) client.newBuilder().readTimeout(120, TimeUnit.SECONDS)
            .callTimeout(120, TimeUnit.SECONDS).build() else client
        val raw = execute(transport.newCall(buildRequest(path, body, csrf)), if (export) EXPORT_MAX_BYTES else 1_048_576)
        if (jar.invalid) throw ApiFailure(FailureKind.INCOMPATIBLE)
        return raw
    }

    private suspend fun execute(call: Call, maxBytes: Long = 1_048_576): RawReply = suspendCancellableCoroutine { continuation ->
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
                        val limit = if (it.isSuccessful) maxBytes else 1_048_576
                        source.request(limit + 1)
                        if (source.buffer.size > limit) throw ApiFailure(
                            if (maxBytes > 1_048_576 && it.isSuccessful) FailureKind.TOO_LARGE else FailureKind.INCOMPATIBLE)
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

    private class RawReply(val status: Int, val body: String) {
        fun require200(): RawReply {
            if (status != 200) throw ApiFailure(FailureKind.INCOMPATIBLE)
            return this
        }
    }

    companion object { internal const val EXPORT_MAX_BYTES = 16L * 1024 * 1024 }

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

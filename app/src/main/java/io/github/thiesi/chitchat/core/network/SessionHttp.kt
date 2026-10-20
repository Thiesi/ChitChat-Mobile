package io.github.thiesi.chitchat.core.network

import io.github.thiesi.chitchat.core.server.ServerAddress
import io.github.thiesi.chitchat.core.session.OriginCookieJar
import io.github.thiesi.chitchat.core.account.PersonalExport
import io.github.thiesi.chitchat.core.account.VerificationMethods
import io.github.thiesi.chitchat.core.rooms.CreateRoom
import io.github.thiesi.chitchat.core.rooms.RoomInfo
import io.github.thiesi.chitchat.core.rooms.validateRoomCreation
import io.github.thiesi.chitchat.core.conversation.*
import io.github.thiesi.chitchat.core.realtime.PresenceStatus
import io.github.thiesi.chitchat.core.realtime.PresenceUser
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
        reply.ignoredUserIds?.let { incompatibleUnless(it.all { id -> id > 0 } && it.distinct().size == it.size) }
        reply.mutedEverywhere?.let { requireValidMute(it, null) }
        incompatibleUnless(reply.preferences?.hourCycle in setOf(null, "h12", "h23"))
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

    suspend fun rooms(): List<RoomInfo> {
        val rooms = decode<RoomsReply>(request("rooms/list.php").require200()).rooms
        rooms.forEach { requireValidRoom(it) }
        if (rooms.map { it.id }.toSet().size != rooms.size) throw ApiFailure(FailureKind.INCOMPATIBLE)
        return rooms
    }

    suspend fun room(roomId: Long): RoomInfo {
        require(roomId > 0)
        return decode<RoomReply>(request("rooms/detail.php?room_id=$roomId").require200()).room
            .also { requireValidRoom(it, roomId) }
    }

    suspend fun joinRoom(roomId: Long, csrf: String): RoomInfo {
        require(roomId > 0)
        return decode<RoomReply>(request("rooms/join.php", apiJson.encodeToString(RoomIdRequest(roomId)), csrf).require200()).room
            .also { requireValidRoom(it, roomId); if (!it.isMember) throw ApiFailure(FailureKind.INCOMPATIBLE) }
    }

    suspend fun leaveRoom(roomId: Long, csrf: String) {
        require(roomId > 0)
        val reply = decode<RoomLeftReply>(request("rooms/leave.php", apiJson.encodeToString(RoomIdRequest(roomId)), csrf).require200())
        if (reply.status != "left") throw ApiFailure(FailureKind.INCOMPATIBLE)
    }

    suspend fun createRoom(input: CreateRoom, csrf: String, userId: Long): RoomInfo {
        validateRoomCreation(input)?.let { throw ApiFailure(FailureKind.HTTP, 400, it) }
        val raw = request("rooms/create.php", apiJson.encodeToString(input.normalized()), csrf)
        if (raw.status != 201) throw ApiFailure(FailureKind.INCOMPATIBLE)
        return decode<RoomReply>(raw).room.also {
            requireValidRoom(it)
            if (it.memberRole != "owner" || it.createdBy != userId) throw ApiFailure(FailureKind.INCOMPATIBLE)
        }
    }

    suspend fun roomHistory(roomId: Long, beforeId: Long?, limit: Int): MessagePage {
        require(roomId > 0 && (beforeId == null || beforeId > 0) && limit in 1..100)
        val path = "rooms/messages.php?room_id=$roomId&limit=$limit" + (beforeId?.let { "&before_id=$it" } ?: "")
        val messages = decode<MessagesReply>(request(path).require200()).messages
        incompatibleUnless(messages.size <= limit && messages.zipWithNext().all { (a, b) -> a.id < b.id })
        messages.forEach { requireValidMessage(it, roomId); incompatibleUnless(beforeId == null || it.id < beforeId) }
        if (messages.isEmpty()) return MessagePage(emptyList(), null, false)
        // Current bodies/edit/deletion metadata, including retention races, not a cached text match.
        val ids = messages.map { it.id }.toSet()
        val metadata = decode<MutationsReply>(request("rooms/message-mutations.php?room_id=$roomId&message_ids=${ids.joinToString(",")}").require200()).messages
        incompatibleUnless(metadata.map { it.id }.distinct().size == metadata.size && metadata.all { it.id in ids })
        val byId = metadata.associateBy { it.id }
        return MessagePage(messages.mapNotNull { message -> byId[message.id]?.let { applyMutation(message, it) } },
            messages.first().id, messages.size == limit)
    }

    suspend fun roomPings(roomId: Long, viewerId: Long): List<RoomPing> {
        require(roomId > 0)
        val pings = decode<PingsReply>(request("rooms/pings.php?room_id=$roomId").require200()).pings
        incompatibleUnless(pings.size <= 100 && pings.zipWithNext().all { (a, b) -> a.id < b.id })
        pings.forEach { requireValidPing(it, roomId, viewerId) }
        return pings
    }

    suspend fun sendRoomMessage(roomId: Long, body: String, csrf: String, viewerId: Long): SendResult {
        require(roomId > 0)
        validateMessageBody(body)?.let { throw ApiFailure(FailureKind.HTTP, 400, it) }
        val raw = request("rooms/send.php", apiJson.encodeToString(SendRequest(roomId, body)), csrf)
        incompatibleUnless(raw.status == 201)
        val reply = decode<SendReply>(raw)
        incompatibleUnless((reply.message == null) != (reply.ping == null))
        val isPing = body.phpTrim().let { it == "/ping" || it.startsWith("/ping ") }
        return if (reply.message != null) {
            requireValidMessage(reply.message, roomId)
            incompatibleUnless(!isPing && reply.message.senderId == viewerId && !reply.message.deleted)
            SendResult.Message(reply.message)
        } else {
            val ping = checkNotNull(reply.ping)
            requireValidPing(ping, roomId, viewerId)
            incompatibleUnless(isPing && ping.sender.id == viewerId)
            SendResult.Ping(ping)
        }
    }

    suspend fun readRoom(roomId: Long, messageId: Long, csrf: String): Long {
        require(roomId > 0 && messageId > 0)
        return decode<ReadReply>(request("rooms/read.php", apiJson.encodeToString(ReadRequest(roomId, messageId)), csrf).require200())
            .messageId.also { incompatibleUnless(it >= 0) }
    }

    suspend fun presenceHeartbeat(connectionId: String, roomId: Long?, interacted: Boolean, csrf: String): PresenceStatus {
        val input = presenceHeartbeatRequest(connectionId, roomId, interacted)
        return decode<PresenceReply>(request("presence/heartbeat.php", apiJson.encodeToString(input), csrf).require200())
            .presence.also { requireValidPresenceStatus(it, roomId) }
    }

    suspend fun roomPresence(roomId: Long): List<PresenceUser> {
        require(roomId > 0)
        return decode<PresenceUsersReply>(request("rooms/presence.php?room_id=$roomId").require200())
            .users.also(::requireValidPresenceUsers)
    }

    fun events(afterId: Long): kotlinx.coroutines.flow.Flow<io.github.thiesi.chitchat.core.realtime.RealtimeSignal> {
        require(afterId >= 0)
        val request = buildRequest("events/stream.php").newBuilder()
            .header("Accept", "text/event-stream").header("Last-Event-ID", afterId.toString()).build()
        return sessionEventStream(client, request, { jar.invalid }) { cause, response ->
            if (response != null && !response.isSuccessful) {
                if (response.code in 300..399) ApiFailure(FailureKind.REDIRECT, response.code)
                else {
                    val source = response.body.source()
                    source.request(1_048_577)
                    if (source.buffer.size > 1_048_576) ApiFailure(FailureKind.INCOMPATIBLE)
                    else {
                        val error = try { apiJson.decodeFromString<ErrorEnvelope>(source.readUtf8()).error }
                            catch (_: SerializationException) { null }
                        ApiFailure(FailureKind.HTTP, response.code, error?.code?.take(100), retryAfter(response.header("Retry-After")))
                    }
                }
            } else when (cause) {
                is ApiFailure -> cause
                is IOException -> ApiFailure(transportKind(cause))
                // The pinned standard reader reports an unterminated data line at EOF as an
                // IllegalArgumentException. Treat incomplete transport as reconnectable, never
                // as a delivered/acknowledged event. No exception text/body is retained.
                is IllegalArgumentException -> ApiFailure(FailureKind.NETWORK)
                else -> ApiFailure(FailureKind.INCOMPATIBLE)
            }
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

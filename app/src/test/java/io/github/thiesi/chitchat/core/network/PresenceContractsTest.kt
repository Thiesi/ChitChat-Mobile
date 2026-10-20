package io.github.thiesi.chitchat.core.network

import io.github.thiesi.chitchat.core.realtime.PresenceStatus
import io.github.thiesi.chitchat.core.realtime.PresenceUser
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class PresenceContractsTest {
    private val uuid = "11111111-2222-4333-8444-555555555555"
    private fun rejected(action: () -> Unit) {
        assertEquals(FailureKind.INCOMPATIBLE, assertThrows(ApiFailure::class.java) { action() }.kind)
    }
    private fun status(room: Long? = 42, idle: Int = 75, warning: Int? = 45,
        expired: Boolean = false, lease: Int = 45) = PresenceStatus(room, idle, warning, expired, lease)

    @Test fun heartbeatRequestUsesActualKeysAndExplicitFalseAndNull() {
        val encoded = apiJson.encodeToString(presenceHeartbeatRequest(uuid, null, false))
        val body = apiJson.parseToJsonElement(encoded).jsonObject
        assertEquals(setOf("connection_id", "room_id", "interacted"), body.keys)
        assertEquals(uuid, body.getValue("connection_id").jsonPrimitive.content)
        assertEquals("null", body.getValue("room_id").toString())
        assertEquals("false", body.getValue("interacted").jsonPrimitive.content)
    }

    @Test fun uuidNormalizationMatchesBackendAndAcceptsSupportedVersionsAndVariants() {
        for (version in '1'..'5') for (variant in "89ab") {
            val input = "ABCDEFAB-1234-${version}ABC-${variant}DEF-ABCDEFABCDEF"
            val request = presenceHeartbeatRequest(" \t$input\r\n", 42, true)
            assertEquals(input.lowercase(), request.connectionId); assertEquals(42L, request.roomId); assertTrue(request.interacted)
        }
    }

    @Test fun invalidUuidAndRoomInputsAreRejectedBeforeTransport() {
        for (input in listOf("", "1-1-4-8-1", "11111111222243338444555555555555", uuid.replace("4333", "0333"),
            uuid.replace("4333", "6333"), uuid.replace("8444", "7444"), uuid.replace("8444", "c444"), "\u00a0$uuid", "$uuid/")) {
            rejected { presenceHeartbeatRequest(input, 42, false) }
        }
        for (room in listOf(0L, -1L)) rejected { presenceHeartbeatRequest(uuid, room, false) }
    }

    @Test fun actualHeartbeatEnvelopeDecodesRequiredFieldsAndIgnoresAdditions() {
        val reply = apiJson.decodeFromString<PresenceReply>("""{"presence":{"room_id":42,"idle_seconds":75,"warning_seconds":45,"expired":false,"lease_seconds":45,"future":true}}""")
        requireValidPresenceStatus(reply.presence, 42)
        assertEquals(42L, reply.presence.roomId); assertEquals(45, reply.presence.warningSeconds)
        assertFalse(reply.presence.expired)
        for (field in listOf("\"room_id\":42,", "\"idle_seconds\":75,", "\"warning_seconds\":45,", "\"expired\":false,")) {
            val missing = """{"presence":{"room_id":42,"idle_seconds":75,"warning_seconds":45,"expired":false,"lease_seconds":45}}""".replace(field, "")
            assertThrows(SerializationException::class.java) { apiJson.decodeFromString<PresenceReply>(missing) }
        }
        assertThrows(SerializationException::class.java) { apiJson.decodeFromString<PresenceReply>("""{"presence":{"room_id":42}}""") }
    }

    @Test fun activeExpiredAndNullRoomCleanupStatesAreKeptDistinct() {
        requireValidPresenceStatus(status(idle = 0, warning = null, lease = 30), 42)
        requireValidPresenceStatus(status(lease = 300), 42)
        val expired = status(room = null, idle = 0, warning = null, expired = true)
        requireValidPresenceStatus(expired, 42)
        assertTrue(expired.expired); assertEquals(0, expired.idleSeconds)
        // A repeated null-room lease can accumulate idle time without being an inactivity expiry.
        requireValidPresenceStatus(status(room = null, idle = 80, warning = null), null)
    }

    @Test fun impossibleRoomIdleWarningAndExpiryCombinationsAreRejected() {
        for (value in listOf(status(room = 43), status(room = 0), status(room = null, expired = false),
            status(idle = -1), status(warning = 0), status(warning = -1), status(warning = 3601),
            status(lease = 29), status(lease = 301), status(expired = true),
            status(room = null, idle = 75, warning = null, expired = true),
            status(room = null, idle = 0, warning = 1, expired = true))) {
            rejected { requireValidPresenceStatus(value, 42) }
        }
        rejected { requireValidPresenceStatus(status(room = null, idle = 0, warning = null, expired = true), null) }
        rejected { requireValidPresenceStatus(status(room = null, warning = 1), null) }
        rejected { requireValidPresenceStatus(status(room = -1, warning = null), -1) }
    }

    @Test fun aggregateUserEnvelopePreservesGuestConnectionsAndServerIdle() {
        val reply = apiJson.decodeFromString<PresenceUsersReply>("""{"users":[{"id":7,"username":"Alice","guest":false,"idle_seconds":0,"connections":2},{"id":8,"username":"Guest-8","guest":true,"idle_seconds":75,"connections":1}]}""")
        requireValidPresenceUsers(reply.users)
        assertEquals(2, reply.users.first().connections); assertFalse(reply.users.first().guest)
        assertTrue(reply.users.last().guest); assertEquals(75, reply.users.last().idleSeconds)
        requireValidPresenceUsers(emptyList())
        // The server imposes no arbitrary 100-user page limit on this aggregated list.
        requireValidPresenceUsers((1L..101).map { PresenceUser(it, "Fixture-$it", false, 0, 1) })
    }

    @Test fun invalidOrDuplicateAggregateUsersAreRejectedAndGuestFieldIsRequired() {
        for (user in listOf(PresenceUser(0, "Alice", false, 0, 1), PresenceUser(7, "", false, 0, 1),
            PresenceUser(7, "Alice", false, -1, 1), PresenceUser(7, "Alice", false, 0, 0))) {
            rejected { requireValidPresenceUsers(listOf(user)) }
        }
        val user = PresenceUser(7, "Alice", false, 0, 1)
        rejected { requireValidPresenceUsers(listOf(user, user)) }
        assertThrows(SerializationException::class.java) { apiJson.decodeFromString<PresenceUsersReply>("""{"users":[{"id":7,"username":"Alice","idle_seconds":0,"connections":1}]}""") }
    }
}

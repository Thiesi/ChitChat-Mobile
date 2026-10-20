package io.github.thiesi.chitchat.core.network

import io.github.thiesi.chitchat.core.realtime.RealtimeSignal
import org.junit.Assert.*
import org.junit.Test

class RealtimeContractsTest {
    private fun envelope(id: Long = 41, type: String = "rooms_changed", room: String = "null",
        actor: String = "7", payload: String = "{}", time: String = "2026-07-17 08:00:00+00") =
        """{"id":$id,"type":"$type","room_id":$room,"actor_user_id":$actor,"payload":$payload,"created_at":"$time"}"""
    private fun event(data: String = envelope(), id: String? = "41", type: String? = "rooms_changed") =
        (decodeRealtimeSignal(id, type, data) as RealtimeSignal.Event).value
    private fun rejected(data: String, id: String? = "41", type: String? = "rooms_changed") {
        assertEquals(FailureKind.INCOMPATIBLE,
            assertThrows(ApiFailure::class.java) { decodeRealtimeSignal(id, type, data) }.kind)
    }

    @Test fun validatesEnvelopeAndPreservesGappedIdsAndCanonicalTime() {
        val value = event()
        assertEquals(41L, value.id); assertTrue(value.known); assertNull(value.roomId)
        assertEquals("2026-07-17T08:00:00Z", value.createdAt.toString())
        assertEquals(999L, event(envelope(999), "999").id)
    }
    @Test fun phpEmptyArrayRoomSignalAndDocumentedObjectBothWork() {
        assertTrue(event(envelope(payload = "[]")).payload.isEmpty())
        assertTrue(event().payload.isEmpty())
        rejected(envelope(payload = "[1]")); rejected(envelope(payload = "null"))
        rejected(envelope(type = "presence_changed", room = "42", payload = "[]"), type = "presence_changed")
    }
    @Test fun inheritedIdDoesNotHideUnnumberedForcedLogout() {
        for (id in listOf(null, "41")) assertSame(RealtimeSignal.SessionInvalidated,
            decodeRealtimeSignal(id, "forced_logout", """{"reason":"session_invalidated"}"""))
        rejected("{}", type = "forced_logout")
        rejected("""{"reason":42}""", type = "forced_logout")
    }
    @Test fun persistentLogoutVersionDoesNotKillAFreshlyReauthenticatedSession() {
        val value = event(envelope(type = "forced_logout", payload = """{"session_version":3,"reason":"Private fixture"}"""),
            type = "forced_logout")
        assertTrue(value.invalidatesSession(2)); assertFalse(value.invalidatesSession(3)); assertFalse(value.invalidatesSession(4))
        assertTrue(event(envelope(type = "forced_logout"), type = "forced_logout").invalidatesSession(3))
        for (version in listOf("0", "-1", "\"3\"", "null", "3.5")) rejected(
            envelope(type = "forced_logout", payload = """{"session_version":$version}"""), type = "forced_logout")
        assertFalse(value.toString().contains("Private fixture"))
    }
    @Test fun unknownTypeIsExplicitAndNeverMistakenForAnAppliedMessage() {
        val value = event(envelope(type = "future_notice", payload = """{"private":"content"}"""), type = "future_notice")
        assertFalse(value.known); assertEquals("future_notice", value.type)
        assertFalse(value.toString().contains("content"))
        assertFalse(event(envelope(type = "Future.Notice"), type = "Future.Notice").known)
    }
    @Test fun streamErrorControlsRequireReconnectNotAnAcknowledgedEvent() {
        for (code in listOf("stream_error", "internal_error")) {
            val error = assertThrows(ApiFailure::class.java) {
                decodeRealtimeSignal("41", "error", """{"code":"$code"}""")
            }
            assertEquals(FailureKind.NETWORK, error.kind); assertEquals(code, error.code)
        }
        rejected("""{"code":"unknown"}""", type = "error")
    }
    @Test fun mismatchedIdsTypesRoomActorAndTimestampAreRejected() {
        for (id in listOf(null, "", "0", "-1", "42", "1e1", "9223372036854775808")) rejected(envelope(), id)
        rejected(envelope(id = 0)); rejected(envelope(), type = "ping")
        rejected(envelope(room = "0")); rejected(envelope(actor = "0")); rejected(envelope(time = "no offset"))
        rejected(envelope(type = "room_message"), type = "room_message")
        rejected(envelope(type = "global_broadcast", room = "42"), type = "global_broadcast")
        rejected(envelope().replace("\"actor_user_id\":7,", ""))
        rejected("invalid private fixture"); rejected("[]")
    }
    @Test fun eventByteBudgetAppliesBeforeDeserializationIncludingMultibytePayloads() {
        rejected("x".repeat(1_048_577))
        rejected(envelope(payload = """{"body":"${"€".repeat(360_000)}"}"""))
    }
}

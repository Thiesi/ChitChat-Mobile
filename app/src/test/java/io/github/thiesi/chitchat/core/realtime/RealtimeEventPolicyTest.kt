package io.github.thiesi.chitchat.core.realtime

import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.network.FailureKind
import io.github.thiesi.chitchat.core.network.apiJson
import java.time.Instant
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test

class RealtimeEventPolicyTest {
    private fun validate(type: String, payload: String, room: Long? = 42) = requireValidRealtimePayload(
        RealtimeEvent(1, type, room, 7, apiJson.parseToJsonElement(payload) as JsonObject, Instant.EPOCH), 7)
    private fun reject(type: String, payload: String, room: Long? = 42) {
        val failure = assertThrows(ApiFailure::class.java) { validate(type, payload, room) }
        assertEquals(FailureKind.INCOMPATIBLE, failure.kind)
        assertFalse(failure.toString().contains("Private"))
    }

    @Test fun roomMessageUsesExistingFullContractAndDoesNotTrustEnvelopeRoomAlone() {
        val message = """{"id":11,"room_id":42,"sender_id":7,"username":"Viewer","type":"text","body":"Private","deleted":false,"created_at":"2026-07-17 08:00:00+00"}"""
        validate("room_message", """{"message":$message}""")
        reject("room_message", """{"message":$message}""", 43)
        reject("room_message", """{"message":{"id":11}}""")
        reject("room_message", "{}")
    }
    @Test fun deletionRequiresMatchingRoomAndStrictPositiveMessageId() {
        validate("message_deleted", """{"message_id":11,"room_id":42}""")
        for (id in listOf("0", "-1", "\"11\"", "true", "null", "1.5"))
            reject("message_deleted", """{"message_id":$id,"room_id":42}""")
        reject("message_deleted", """{"message_id":11,"room_id":43}""")
    }
    @Test fun reactionsValidateBothActualScopesWithoutInventingDirectRoomField() {
        val reactions = """[{"emoji":"👍","users":[{"id":7,"username":"Viewer"}],"reacted_by_me":true}]"""
        validate("message_reaction_changed", """{"message_kind":"room","message_id":11,"room_id":42,"reactions":$reactions}""")
        validate("message_reaction_changed", """{"message_kind":"direct","message_id":11,"reactions":$reactions}""", null)
        reject("message_reaction_changed", """{"message_kind":"direct","message_id":11,"reactions":[]}""")
        reject("message_reaction_changed", """{"message_kind":"room","message_id":11,"room_id":43,"reactions":[]}""")
        reject("message_reaction_changed", """{"message_kind":"other","message_id":11,"reactions":[]}""", null)
    }
    @Test fun presenceInvalidationRequiresUserAndRoomNotAnInferredDelta() {
        validate("presence_changed", """{"room_id":42,"user_id":7}""")
        reject("presence_changed", """{"room_id":42}""")
        reject("presence_changed", """{"room_id":43,"user_id":7}""")
    }
    @Test fun pingMustActuallyAddressThisViewer() {
        val ping = """{"id":2,"room_id":42,"sender":{"id":8,"username":"Sender"},"target":{"id":7,"username":"Viewer"},"message":"Private","created_at":"2026-07-17T08:00:00Z"}"""
        validate("ping", """{"ping":$ping}""")
        reject("ping", """{"ping":${ping.replace("\"id\":7", "\"id\":9")}}""")
        reject("ping", """{"ping":$ping}""", 43)
    }
    @Test fun typingAcceptsRoomAndDirectShapesButNeverAStringOrMissingRoom() {
        validate("typing", """{"room_id":42,"user":{"id":7,"username":"Viewer"}}""")
        validate("typing", """{"room_id":null,"user":{"id":7,"username":"Viewer"}}""", null)
        reject("typing", """{"room_id":"42","user":{"id":7}}""")
        reject("typing", """{"user":{"id":7}}""", null)
    }
    @Test fun ordinaryAndMaintenanceBroadcastsHaveMessageNotBodyAndNoMandatorySender() {
        validate("room_broadcast", """{"room_id":42,"message":"Private announcement"}""")
        validate("global_broadcast", """{"message":"Maintenance lockdown: ${"x".repeat(1000)}","lockdown":{"enabled":true}}""", null)
        validate("global_broadcast", """{"message":"${"😀".repeat(1000)}"}""", null)
        reject("global_broadcast", """{"body":"Private"}""", null)
        reject("global_broadcast", """{"message":"   "}""", null)
        reject("global_broadcast", """{"message":true}""", null)
        reject("global_broadcast", """{"message":"${"x".repeat(4097)}"}""", null)
    }
    @Test fun directMessageIsOnlyAnInvalidationUntilM4AndUnknownTypesRemainAdditive() {
        validate("direct_message", """{"message":{"id":11}}""", null)
        reject("direct_message", """{"message":{"id":0}}""", null)
        reject("direct_message", """{"message":[]}""", null)
        validate("future_event", """{"new_field":true}""", null)
        validate("rooms_changed", "{}", null)
        validate("forced_logout", "{}", null)
    }
}

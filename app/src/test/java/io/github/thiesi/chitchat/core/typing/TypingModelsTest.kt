package io.github.thiesi.chitchat.core.typing

import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.network.apiJson
import io.github.thiesi.chitchat.core.realtime.RealtimeEvent
import java.time.Instant
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test

class TypingModelsTest {
    private fun event(body: String, actor: Long? = 8, room: Long? = 42) =
        RealtimeEvent(1, "typing", room, actor, apiJson.parseToJsonElement(body) as JsonObject, Instant.EPOCH)
    @Test fun browserCompatibleSuppressionNeverSignalsEmptyWhitespaceOrCommands() {
        for (text in listOf("", "  \n\t", " /me wave", "/dm Person", "\u00a0/ping")) assertFalse(hasTypingContent(text))
        assertTrue(hasTypingContent("Hello\nthere")); assertTrue(hasTypingContent("some / text"))
    }
    @Test fun validNamesAndDestinationsRemainTypedWithoutPayloadStringRepresentation() {
        val person = typingPerson(event("""{"room_id":42,"user":{"id":8,"username":"Private name"}}"""))
        assertEquals(8L, person.id); assertEquals("Private name", person.username)
        assertFalse(person.toString().contains("Private name"))
        assertEquals(8L, typingPerson(event("""{"room_id":null,"user":{"id":8,"username":"Peer"}}""", room = null)).id)
    }
    @Test fun malformedActorNameRoomAndQuotedIdAreRejected() {
        for (body in listOf(
            """{"room_id":42,"user":{"id":9,"username":"Impostor"}}""",
            """{"room_id":42,"user":{"id":"8","username":"Peer"}}""",
            """{"room_id":42,"user":{"id":8}}""",
            """{"room_id":"42","user":{"id":8,"username":"Peer"}}""",
            """{"room_id":42,"user":{"id":8,"username":" "}}""")) {
            assertThrows(ApiFailure::class.java) { typingPerson(event(body)) }
        }
    }
}

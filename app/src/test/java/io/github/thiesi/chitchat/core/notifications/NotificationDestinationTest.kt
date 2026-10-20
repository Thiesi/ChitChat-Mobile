package io.github.thiesi.chitchat.core.notifications

import io.github.thiesi.chitchat.core.server.AddressResult
import io.github.thiesi.chitchat.core.server.ServerAddress
import org.junit.Assert.*
import org.junit.Test

class NotificationDestinationTest {
    private fun server(value: String = "https://chat.example.test/") = (ServerAddress.parse(value) as AddressResult.Valid).address
    private fun route(value: String?) = notificationDestination(server(), value)
    @Test fun existingRoomMentionPingAndRestoredLinksHaveDistinctTypedTargets() {
        val mention = route("/?room_id=42&message_id=91") as NotificationDestination.Room
        assertEquals(42L, mention.roomId); assertEquals(91L, mention.messageId); assertNull(mention.pingId)
        val ping = route("/?room_id=42&ping_id=9") as NotificationDestination.Room
        assertEquals(9L, ping.pingId); assertNull(ping.messageId)
        val room = route("/?room_id=42") as NotificationDestination.Room
        assertNull(room.messageId); assertNull(room.pingId)
    }
    @Test fun actualServerDirectLinkUsesNumericUserNotTheDisplayHint() {
        val direct = route("/messages.php?user_id=8&peer_name=Not%20the%20identity%20%26%20%3F&message_id=91") as NotificationDestination.Direct
        assertEquals(8L, direct.peerId); assertEquals(91L, direct.messageId)
        assertEquals(8L, (route("/messages.php?user_id=8") as NotificationDestination.Direct).peerId)
        // The parser does not invent that the browser's separate `with` parameter is a server notification contract.
        assertNull(route("/messages.php?with=8&message_id=91"))
    }
    @Test fun accountAndFutureGuestRegistrationAreHintsNotAuthorization() {
        assertSame(NotificationDestination.Account, route("/account.php"))
        assertSame(NotificationDestination.Registration, route("/?register=1"))
        assertNull(route("/?register=0")); assertNull(route("/?register=1&room_id=42"))
    }
    @Test fun absoluteSameOriginAndExplicitSelectedNonDefaultPortAreAccepted() {
        assertTrue(route("https://CHAT.example.test:443/?room_id=42") is NotificationDestination.Room)
        val selected = server("https://chat.example.test:8443")
        assertTrue(notificationDestination(selected, "https://chat.example.test:8443/?room_id=42") is NotificationDestination.Room)
        assertNull(notificationDestination(selected, "https://chat.example.test/?room_id=42"))
    }
    @Test fun foreignOriginsCredentialsAndInsecureOrOpaqueSchemesNeverRoute() {
        listOf("https://evil.test/?room_id=42", "https://chat.example.test:8443/?room_id=42",
            "http://chat.example.test/?room_id=42", "//chat.example.test/?room_id=42",
            "https://name@chat.example.test/?room_id=42", "https://@chat.example.test/?room_id=42",
            "https://chat.example.test@evil.test/?room_id=42", "https:/?room_id=42", "https:?room_id=42",
            "javascript:alert(1)", "intent://chat.example.test/?room_id=42").forEach { assertNull(it, route(it)) }
    }
    @Test fun traversalEncodedPathsApiPagesAndArbitraryLinksAreNotNavigation() {
        listOf("/page/../?room_id=42", "/%2e%2e/?room_id=42", "/%61ccount.php", "/api/v1/logout.php",
            "/messages.php/", "messages.php?user_id=8", "/?room_id=42#message", "/?room_id=42#",
            "/?room_id=42\\evil", "/?room_id=42\n", "/?room_id=42 ", "/?room_id=%xx").forEach { assertNull(it, route(it)) }
    }
    @Test fun duplicateDecodedParametersAndMixedTargetsAreRejected() {
        listOf("/?room_id=42&room_id=43", "/?room_id=42&room%5fid=43", "/?room_id=42&message_id=9&ping_id=8",
            "/messages.php?user_id=8&user_id=9", "/messages.php?user_id=8&peer_name=A&peer_name=B",
            "/?room_id=42&unknown=1", "/account.php?room_id=42", "/messages.php?user_id=8&room_id=42").forEach { assertNull(it, route(it)) }
    }
    @Test fun eachIdMustBePositiveAsciiAndFitSignedServerInteger() {
        listOf("0", "-1", "+1", "%201", "1.0", "1e1", "", "9223372036854775808", "١", "%2531").forEach {
            assertNull(it, route("/?room_id=$it")); assertNull(it, route("/?room_id=42&message_id=$it"))
            assertNull(it, route("/?room_id=42&ping_id=$it")); assertNull(it, route("/messages.php?user_id=$it"))
        }
        assertEquals(Long.MAX_VALUE, (route("/?room_id=42&message_id=${Long.MAX_VALUE}") as NotificationDestination.Room).messageId)
        assertEquals(1L, (route("/?room_id=%31") as NotificationDestination.Room).roomId)
    }
    @Test fun absentEmptyMalformedAndUnknownDestinationsStayUnsupported() {
        listOf(null, "", " ", "/", "/?message_id=91", "/messages.php?peer_name=Peer", "/messages.php?user_id=8&peer_name",
            "/search.php?q=term", "/account.php?", "https://[bad/?room_id=42").forEach { assertNull(it, route(it)) }
    }
}

package io.github.thiesi.chitchat.core.targets

import io.github.thiesi.chitchat.core.network.SessionUser
import io.github.thiesi.chitchat.core.conversation.*
import io.github.thiesi.chitchat.core.direct.*
import io.github.thiesi.chitchat.core.rooms.RoomInfo
import io.github.thiesi.chitchat.core.server.AddressResult
import io.github.thiesi.chitchat.core.server.ServerAddress
import io.github.thiesi.chitchat.core.session.SessionPhase
import io.github.thiesi.chitchat.core.session.SessionState
import org.junit.Assert.*
import org.junit.Test

class MessageTargetsTest {
    private fun address(host: String) = (ServerAddress.parse(host) as AddressResult.Valid).address
    private val original = SessionState(server = address("chat.example.test"), generation = 1, phase = SessionPhase.AUTHENTICATED,
        user = SessionUser(7, "Viewer", listOf("member"), 1, false))
    @Test fun exclusiveBeforeBoundIncludesTargetWithoutOverflowOrInventedEndpoint() {
        assertEquals(2L, beforeTarget(1)); assertEquals(Long.MAX_VALUE, beforeTarget(Long.MAX_VALUE - 1)); assertNull(beforeTarget(Long.MAX_VALUE))
        for (bad in listOf(0L, -1)) try { beforeTarget(bad); fail("Invalid target") } catch (_: IllegalArgumentException) { }
    }
    @Test fun requestScopeIncludesServerGenerationUserVersionAndAuthentication() {
        val request = TargetRequest(original, MessageTarget.RoomMessage(42, 18)); assertTrue(request.matches(original))
        val viewer = checkNotNull(original.user)
        listOf(original.copy(server = address("other.example.test")), original.copy(generation = 2),
            original.copy(user = viewer.copy(id = 9)), original.copy(user = viewer.copy(sessionVersion = 2)),
            original.copy(phase = SessionPhase.SIGNED_OUT), original.copy(phase = SessionPhase.GUEST),
            original.copy(user = viewer.copy(guest = true))).forEach { assertFalse(request.matches(it)) }
        assertTrue(request.matches(original.copy(shareTyping = true)))
    }
    @Test fun selectorsRejectInvalidNamespacesAndSelfDirectButAcceptMaximumInteger() {
        assertTrue(MessageTarget.RoomMessage(42, Long.MAX_VALUE).valid(7)); assertTrue(MessageTarget.RoomPing(42, 1).valid(7))
        assertTrue(MessageTarget.DirectMessage(8, 1).valid(7)); assertFalse(MessageTarget.DirectMessage(7, 1).valid(7))
        assertFalse(MessageTarget.RoomMessage(0, 1).valid(7)); assertFalse(MessageTarget.RoomPing(42, -1).valid(7))
    }
    @Test fun matchingRoomMessageIdInAnotherRoomIsNotTheRequestedTarget() {
        val request = TargetRequest(original, MessageTarget.RoomMessage(42, 18))
        fun row(room: Long) = RoomMessage(18, room, 8, "Peer", "text", "Fixture", false, "2026-10-20T12:00:00Z")
        assertFalse(TargetSnapshot.RoomMessage(request, context(43), MessagePage(listOf(row(42)), 18, false), emptyList()).available)
        assertFalse(TargetSnapshot.RoomMessage(request, context(42), MessagePage(listOf(row(43)), 18, false), emptyList()).available)
        assertTrue(TargetSnapshot.RoomMessage(request, context(42), MessagePage(listOf(row(42)), 18, false), emptyList()).available)
    }
    @Test fun pingTargetMustMatchBothRoomAndViewerAndCurrentIgnorePolicy() {
        val request = TargetRequest(original, MessageTarget.RoomPing(42, 5))
        fun ping(room: Long, recipient: Long = 7) = RoomPing(5, room, MessagePerson(8, "Peer"), MessagePerson(recipient, "Target"), "Fixture", "2026-10-20T12:00:00Z")
        assertFalse(TargetSnapshot.RoomPing(request, context(42), listOf(ping(43))).available)
        assertFalse(TargetSnapshot.RoomPing(request, context(42), listOf(ping(42, 9))).available)
        val result = TargetSnapshot.RoomPing(request, context(42), listOf(ping(42))); assertTrue(result.available)
        val current = original.copy(chatPolicy = ChatPolicy(setOf(8), null, DisplayPreferences()))
        assertFalse(checkNotNull(result.withCurrentPolicy(current)).available)
    }
    @Test fun directTargetMustMatchPeerViewerAndPerspectiveButRoomIgnoreIsNotABlock() {
        val request = TargetRequest(original, MessageTarget.DirectMessage(8, 18))
        val disclosure = MessagePrivacy(DirectPrivacy(false, false, "admin", "permanently", 0), RevisionPrivacy(false, "super_admin", true, true, false))
        fun direct(peer: Long, outgoing: Boolean = false) = DirectMessage(18, MessagePerson(peer, "Peer"), MessagePerson(7, "Viewer"),
            "Fixture", null, "2026-10-20T12:00:00Z", outgoing, false, null, false, false, null, emptyList(), emptyList(), null)
        val context = DirectContext(MessagePerson(8, "Peer"), DirectRelationship(false, true), disclosure, DisplayPreferences(), null)
        assertFalse(TargetSnapshot.DirectMessage(request, context, DirectPage(listOf(direct(9)), 18, false)).available)
        assertFalse(TargetSnapshot.DirectMessage(request, context, DirectPage(listOf(direct(8, true)), 18, false)).available)
        val result = TargetSnapshot.DirectMessage(request, context, DirectPage(listOf(direct(8)), 18, false)); assertTrue(result.available)
        assertTrue(checkNotNull(result.withCurrentPolicy(original.copy(chatPolicy = ChatPolicy(setOf(8), null, DisplayPreferences()), privacy = disclosure))).available)
    }
    private fun context(room: Long) = ConversationContext(RoomInfo(room, "room$room", "Room fixture", "", "public", 0, 0, 9, "member", false, "none"),
        ChatPolicy(emptySet(), null, DisplayPreferences()))
}

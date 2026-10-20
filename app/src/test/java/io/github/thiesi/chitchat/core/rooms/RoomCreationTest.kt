package io.github.thiesi.chitchat.core.rooms

import io.github.thiesi.chitchat.core.network.SessionUser
import org.junit.Assert.*
import org.junit.Test

class RoomCreationTest {
    private fun input(key: String = "general", name: String = "General", info: String = "", visibility: String = "public",
        age: Int = 0, timeout: Int = 0, guest: String = "none") = CreateRoom(key, name, info, visibility, age, timeout, guest)

    @Test fun creatorRolesMatchBackendAndNeverGrantGuestsOrUnknownRolesAccess() {
        for (role in listOf("super_admin", "admin", "chat_admin", "global_moderator", "member", "future_admin")) {
            assertEquals(role, role in setOf("super_admin", "admin", "chat_admin"),
                canCreateRooms(SessionUser(7, "Fixture", listOf(role), 1, false)))
        }
        assertFalse(canCreateRooms(null))
        assertFalse(canCreateRooms(SessionUser(7, "Fixture", listOf("super_admin"), 1, true)))
    }

    @Test fun normalizationMatchesPhpAndDoesNotSilentlyStripUnicodeWhitespace() {
        val value = input("\tGENERAL\n", " Name \r", " Description \t").normalized()
        assertEquals("general", value.key); assertEquals("Name", value.name); assertEquals("Description", value.infoLine)
        assertEquals("invalid_room_key", validateRoomCreation(input("\u2003general\u2003")))
        assertEquals("invalid_room_key", validateRoomCreation(input("KELVIN"))) // Unicode folding is not PHP strtolower.
        assertNull(validateRoomCreation(input(name = "\u2003"))) // PHP trim does not strip this character.
    }

    @Test fun creationUsesCodePointsAndExactPolicyBoundaries() {
        assertNull(validateRoomCreation(input(name = "😀".repeat(120), info = "😀".repeat(255), age = 120, timeout = 86400)))
        assertNull(validateRoomCreation(input(timeout = 120)))
        for ((value, code) in listOf(
            input(key = "ab") to "invalid_room_key", input(key = "_abc") to "invalid_room_key",
            input(key = "a".repeat(49)) to "invalid_room_key", input(name = " \t") to "invalid_room_name",
            input(name = "😀".repeat(121)) to "invalid_room_name", input(info = "😀".repeat(256)) to "invalid_room_info",
            input(visibility = "secret") to "invalid_visibility", input(age = -1) to "invalid_minimum_age",
            input(age = 121) to "invalid_minimum_age", input(timeout = 119) to "invalid_inactivity_timeout",
            input(timeout = 86401) to "invalid_inactivity_timeout", input(guest = "all") to "invalid_guest_access",
            input(visibility = "private", guest = "read") to "guest_access_scope",
            input(age = 1, guest = "write") to "guest_access_scope",
        )) assertEquals(code, validateRoomCreation(value))
        for (guest in listOf("none", "read", "write")) assertNull(validateRoomCreation(input(guest = guest)))
    }
}

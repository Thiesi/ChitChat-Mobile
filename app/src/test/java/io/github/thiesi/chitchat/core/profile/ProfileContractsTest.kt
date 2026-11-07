package io.github.thiesi.chitchat.core.profile

import io.github.thiesi.chitchat.core.network.*
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ProfileContractsTest {
    private fun decode(body: String = profileBody(), target: Long = 8, room: Long? = 43) =
        apiJson.decodeFromString<ProfileReply>(body).snapshot(target, room)
    private fun rejected(body: String, target: Long = 8) {
        try { decode(body, target); fail("Expected incompatible profile") }
        catch (_: SerializationException) { }
        catch (error: ApiFailure) { assertEquals(FailureKind.INCOMPATIBLE, error.kind) }
    }

    @Test fun factsKeepLiteralNamesBadgesTimestampsAndOriginalRoomContext() {
        val result = decode(profileBody(username = "Private \u00c4lice", badge = "<b>Private badge</b>"))
        assertEquals(8L, result.profile.id); assertEquals("Private \u00c4lice", result.profile.username)
        assertEquals("<b>Private badge</b>", result.profile.badge)
        assertEquals("2026-10-19 10:00:00+00", result.profile.memberSince); assertEquals(43L, result.roomId)
        assertTrue(result.avatarsAvailable)
    }
    @Test fun guestVisitFactsAndAbsentAvatarRemainDistinctFromMemberTenure() {
        val result = decode(profileBody(guest = true), room = null)
        assertTrue(result.profile.guest); assertFalse(result.profile.hasAvatar)
        assertNull(result.profile.avatarVersion); assertFalse(result.profile.canRemoveAvatar)
        assertNull(result.profile.badge); assertNull(result.roomId)
    }
    @Test fun zeroAvatarVersionAndUnavailableAvatarServiceAreIndependentValidFacts() {
        val result = decode(profileBody(avatar = true, version = 0, available = false))
        assertTrue(result.profile.hasAvatar); assertEquals(0L, result.profile.avatarVersion)
        assertTrue(result.profile.canRemoveAvatar); assertFalse(result.avatarsAvailable)
    }
    @Test fun avatarPresenceVersionAndRemovalHintsMustBeCoherent() {
        rejected(profileBody(avatar = true, version = null))
        rejected(profileBody(avatar = false, version = 0))
        rejected(profileBody(avatar = false, removable = true))
    }
    @Test fun everyRequiredBooleanRejectsQuotedNullAndMissingValues() {
        for (field in listOf("guest", "has_avatar", "can_remove_avatar", "avatars_available")) {
            rejected(changedProfile(field, JsonPrimitive("false")))
            rejected(changedProfile(field, JsonNull)); rejected(changedProfile(field, null))
        }
    }
    @Test fun nullableBadgeAndAvatarVersionKeysAreStillRequired() {
        rejected(changedProfile("badge", null)); rejected(changedProfile("avatar_version", null))
        assertNull(decode().profile.badge); assertNull(decode().profile.avatarVersion)
    }
    @Test fun targetMustMatchExactlyAndReturnedIdsAndNamesMustBeUsable() {
        rejected(profileBody(id = 9)); rejected(profileBody(id = 0)); rejected(profileBody(id = -8))
        rejected(profileBody(), target = 7); rejected(profileBody(username = " \t\n"))
    }
    @Test fun existingOffsetTimestampFormsArePreservedWithoutGuessingLocalTime() {
        for (stamp in listOf("2026-10-19 10:00:00+00", "2026-10-19T10:00:00Z"))
            assertEquals(stamp, decode(profileBody(since = stamp)).profile.memberSince)
        rejected(profileBody(since = "not-a-time"))
    }
    @Test fun additiveIgnoreAndModerationEnrichmentIsNotAProfileMutationCapability() {
        val result = decode() // Source has ignored=true and moderation.can_ban=true.
        assertFalse(result.profile.canRemoveAvatar); assertEquals(8L, result.profile.id)
        assertEquals(43L, result.roomId)
    }
    @Test fun factsAndRequestsHaveNoGeneratedPrivateDataStringRepresentation() {
        val value = decode(profileBody(badge = "Private badge"))
        for (model in listOf(value, value.profile, ProfileRequest(8, 43))) {
            assertFalse(model.toString().contains("Private")); assertFalse(model.toString().contains("2026-10-19"))
        }
    }
    @Test fun wrongPrimitiveShapesAndOverflowingVersionsAreRejected() {
        rejected(changedProfile("username", JsonNull)); rejected(changedProfile("id", JsonPrimitive(1.5)))
        rejected(changedProfile("avatar_version", JsonPrimitive("invalid")))
        rejected(changedProfile("avatar_version", JsonPrimitive(1e100)))
        rejected("{}"); rejected("""{"profile":null,"avatars_available":true}""")
    }
}

package io.github.thiesi.chitchat.core.notifications

import io.github.thiesi.chitchat.core.network.*
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class NotificationContractsTest {
    private val time = "2026-10-20 10:00:00+00"
    private fun item(id: Long, read: Boolean = false, at: String? = if (read) time else null, created: String = time) =
        AccountNotification(id, "future_kind", "Private fixture title", "Private fixture text", listOf("Fixture detail"), null, read, at, created)
    @Test fun descendingRawPageDeterminesCursorAndMoreWithoutFilteringUnknownKinds() {
        val page = NotificationListReply(listOf(item(100), item(10)), 999).page(101, 2)
        assertEquals(listOf(100L, 10L), page.notifications.map { it.id }); assertEquals(10L, page.olderCursor)
        assertTrue(page.mayHaveOlder); assertEquals(999, page.unreadCount); assertEquals("future_kind", page.notifications.first().kind)
    }
    @Test fun emptyAndShortPagesUseOnlyRawPageSizeEvidence() {
        val empty = NotificationListReply(emptyList(), 3).page(null, 25)
        assertNull(empty.olderCursor); assertFalse(empty.mayHaveOlder); assertEquals(3, empty.unreadCount)
        assertFalse(NotificationListReply(listOf(item(1, true)), 0).page(null, 25).mayHaveOlder)
    }
    @Test fun unorderedDuplicateNonpositiveOrExclusiveBoundaryRowsAreIncompatible() {
        listOf(listOf(item(1), item(2)), listOf(item(2), item(2)), listOf(item(0)), listOf(item(9))).forEach {
            incompatible { NotificationListReply(it, 0).page(9, 25) }
        }
        incompatible { NotificationListReply(listOf(item(3), item(2)), 0).page(null, 1) }
        incompatible { NotificationListReply(emptyList(), -1).page(null, 25) }
    }
    @Test fun readFlagsMustMatchRealTimestampPresenceAndBothTimestampsMustParse() {
        listOf(item(1, true, null), item(1, false, time), item(1, true, "not-a-date"), item(1, created = "bad")).forEach {
            incompatible { NotificationListReply(listOf(it), 0).page(null, 25) }
        }
        assertTrue(NotificationListReply(listOf(item(1, true)), 0).page(null, 25).notifications.single().read)
    }
    @Test fun readReplyAcceptsAlreadyReadAndGlobalCountsButRejectsImpossibleUpdates() {
        assertEquals(4, NotificationReadReply(0, 4).result(1).unreadCount)
        assertEquals(1000, NotificationReadReply(1000, 0).result().updated)
        incompatible { NotificationReadReply(2, 0).result(1) }
        incompatible { NotificationReadReply(-1, 0).result() }; incompatible { NotificationReadReply(0, -1).result() }
    }
    @Test fun bulkAllAndIdsSerializeTheActualBooleanAndIntegerContracts() {
        assertEquals("""{"all":true}""", apiJson.encodeToString(NotificationReadAll(true)))
        assertEquals("""{"ids":[3,3,4]}""", apiJson.encodeToString(NotificationReadIds(listOf(3, 3, 4))))
    }
    @Test fun quotedOrNumericReadFlagsAreNeverSilentlyCoerced() {
        val raw = apiJson.encodeToString(item(1))
        for (replacement in listOf("\"false\"", "0", "null")) {
            try { apiJson.decodeFromString<AccountNotification>(raw.replace("\"read\":false", "\"read\":$replacement")); fail("Must reject non-Boolean read flag") }
            catch (_: SerializationException) { }
        }
    }
    @Test fun contentModelsNeverGeneratePrivateTextInTheirStringRepresentation() {
        val notice = item(1)
        listOf(notice, NotificationPage(listOf(notice), 1, 1, false)).forEach {
            assertFalse(it.toString().contains("Private fixture")); assertFalse(it.toString().contains("Fixture detail"))
        }
    }
    private fun incompatible(action: () -> Unit) {
        try { action(); fail("Expected incompatible response") }
        catch (failure: ApiFailure) { assertEquals(FailureKind.INCOMPATIBLE, failure.kind) }
    }
}

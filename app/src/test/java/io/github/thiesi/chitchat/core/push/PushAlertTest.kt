package io.github.thiesi.chitchat.core.push

import org.junit.Assert.*
import org.junit.Test

class PushAlertTest {
    private val binding = "a1".repeat(32)
    private val record = PushRecord.create("https://chat.example.org", 7, 100).copy(registrationId = 12, binding = binding, confirmed = true)

    private fun payload(extra: String = "", bind: String? = binding) = ("""{"title":"Rowan mentioned you","body":"In #general …",""" +
        """"link":"/?room_id=4&message_id=9","app_name":"Schmidt Family Chat","kind":"mentioned","notification_id":31,""" +
        """"event_id":"0f8e-uuid"""" + (bind?.let { ""","binding":"$it"""" } ?: "") + extra + "}").encodeToByteArray()

    @Test fun aWellFormedPayloadKeepsTheServersTextExactly() {
        val parsed = PushPayload.parse(payload())!!
        assertEquals("Rowan mentioned you", parsed.title)
        assertEquals("In #general …", parsed.body) // The server's trimming is shown as received.
        assertEquals("Schmidt Family Chat", parsed.appName)
        assertEquals(31L, parsed.notificationId)
        assertEquals("PushPayload", parsed.toString())
    }

    @Test fun malformedPayloadsAreDroppedNotRepaired() {
        val bad = listOf("", "[]", "{}", "not json", """{"title":"t"}""", """{"title":1,"body":"b"}""",
            """{"title":"t","body":"b","kind":"Mentioned"}""", """{"title":"t","body":"b","kind":7}""",
            """{"title":"t","body":"b","notification_id":"31"}""", """{"title":"t","body":"b","notification_id":0}""",
            """{"title":"t","body":"b","notification_id":true}""", """{"title":"t","body":"b","event_id":""}""",
            """{"title":"t","body":"b","event_id":"${"e".repeat(65)}"}""", """{"title":"t","body":"b","binding":"ABC"}""",
            """{"title":"t","body":"b","binding":"${"A1".repeat(32)}"}""", """{"title":"t","body":"b","link":{}}""")
        bad.forEach { assertNull(it, PushPayload.parse(it.encodeToByteArray())) }
        assertNull(PushPayload.parse(ByteArray(PushPayload.MAX_BYTES + 1) { 'a'.code.toByte() }))
        assertNull(PushPayload.parse(byteArrayOf(0x7b, 0xff.toByte(), 0x7d)))
        assertNotNull("Optional fields may be absent or null",
            PushPayload.parse("""{"title":"t","body":"b","link":null,"kind":null}""".encodeToByteArray()))
    }

    @Test fun theChannelComesOnlyFromTheExplicitKind() {
        assertEquals(PushChannels.MENTIONS, PushKind.of("mentioned").channel)
        assertEquals(PushChannels.MENTIONS, PushKind.of("pinged").channel)
        assertEquals(PushChannels.DIRECT, PushKind.of("direct_message_received").channel)
        for (other in listOf("account_security", "room_invite", "brand_new_kind", null))
            assertEquals("Unknown or missing kinds never get a high-importance channel", PushChannels.ACCOUNT, PushKind.of(other).channel)
        val textOnly = PushPayload.parse("""{"title":"Rowan mentioned you","body":"direct message"}""".encodeToByteArray())!!
        assertEquals(PushKind.OTHER, pushAlert(record.copy(binding = null), record.instance, textOnly)!!.kind)
    }

    @Test fun onlyTheRecordsOwnInstanceAndExactBindingPost() {
        val alert = pushAlert(record, record.instance, PushPayload.parse(payload()))!!
        assertEquals(PushKind.MENTION, alert.kind)
        assertEquals("https://chat.example.org|7|0f8e-uuid", alert.tag)
        assertEquals("chitchat:https://chat.example.org|7", alert.group)
        assertNull("No record", pushAlert(null, record.instance, PushPayload.parse(payload())))
        assertNull("Another instance", pushAlert(record, "other", PushPayload.parse(payload())))
        assertNull("Malformed", pushAlert(record, record.instance, null))
        assertNull("Missing binding once this phone holds one", pushAlert(record, record.instance, PushPayload.parse(payload(bind = null))))
        assertNull("Unknown binding", pushAlert(record, record.instance, PushPayload.parse(payload(bind = "b2".repeat(32)))))
        assertNull("A binding this phone never recorded", pushAlert(record.copy(binding = null), record.instance, PushPayload.parse(payload())))
    }

    @Test fun recentEventsStopRepeatsWithinABoundAndClear() {
        val recent = RecentPushEvents(limit = 3)
        assertTrue(recent.firstTime("a")); assertFalse(recent.firstTime("a"))
        listOf("b", "c", "d").forEach { assertTrue(recent.firstTime(it)) }
        assertTrue("The oldest is forgotten past the bound", recent.firstTime("a"))
        recent.clear()
        assertTrue(recent.firstTime("d"))
    }
}

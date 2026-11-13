package io.github.thiesi.chitchat.core.push

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

/** The tap intent round-trips through a real Intent, and anything else is no tap. */
class PushTapDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val record = PushRecord.create("https://chat.example.org/", 7, 100).copy(binding = "f6".repeat(32))
    private val alert = pushAlert(record, record.instance, PushPayload.parse(
        """{"title":"t","body":"b","link":"/messages.php?with=9","event_id":"e1","binding":"${record.binding}"}""".encodeToByteArray()))!!

    @Test fun theTapIntentCarriesServerAccountAndLinkAndOpensInPlace() {
        val intent = PushTap.intent(context, alert)
        val tap = checkNotNull(PushTap.from(intent))
        assertEquals("https://chat.example.org/", tap.origin)
        assertEquals(7L, tap.userId)
        assertEquals("/messages.php?with=9", tap.link)
        assertNull("A summary carries no link", PushTap.from(PushTap.intent(context, alert, link = null))!!.link)
        assertTrue("Running app keeps its state", intent.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
        assertNotEquals("Each notification's PendingIntent is distinct", intent.data, PushTap.intent(context, alert).data)
    }

    @Test fun recentsRelaunchesForeignAndMalformedIntentsAreNoTap() {
        assertNull(PushTap.from(null))
        assertNull("Launcher", PushTap.from(Intent(Intent.ACTION_MAIN)))
        assertNull("From Recents", PushTap.from(PushTap.intent(context, alert).addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)))
        val base = PushTap.intent(context, alert)
        assertNull("No account", PushTap.from((base.clone() as Intent).also { it.removeExtra("io.github.thiesi.chitchat.extra.PUSH_USER") }))
        assertNull("No server", PushTap.from((base.clone() as Intent).also { it.removeExtra("io.github.thiesi.chitchat.extra.PUSH_ORIGIN") }))
        assertNull("A non-positive account", PushTap.from((base.clone() as Intent).putExtra("io.github.thiesi.chitchat.extra.PUSH_USER", 0L)))
        assertNull("Wrong type", PushTap.from((base.clone() as Intent).putExtra("io.github.thiesi.chitchat.extra.PUSH_USER", "7")))
        val long = PushTap.from((base.clone() as Intent).putExtra("io.github.thiesi.chitchat.extra.PUSH_LINK", "/" + "a".repeat(5000)))
        assertNull("An oversized link is no destination, but still a tap", checkNotNull(long).link)
    }
}

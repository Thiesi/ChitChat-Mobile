package io.github.thiesi.chitchat.core.push

import android.app.Notification
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * The real NotificationManager reads back what was posted: private on the lock screen, with a public
 * version that names only the installation and the kind. Channels are removed afterwards.
 */
class PushPosterDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val record = PushRecord.create("https://chat.example.org", 7, 100).copy(binding = "f6".repeat(32))

    @Before fun allow() {
        // Granted up front: the permission prompt itself is manual device acceptance.
        if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.grantRuntimePermission(
            context.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        manager.cancelAll()
    }

    @After fun cleanUp() {
        manager.cancelAll()
        listOf(PushChannels.MENTIONS, PushChannels.ACCOUNT, PushChannels.DIRECT).forEach(manager::deleteNotificationChannel)
    }

    private fun alert(kind: String, event: String, title: String = "Rowan mentioned you in #garden",
        body: String = "Private fixture body text") = pushAlert(record, record.instance, PushPayload.parse(
        """{"title":"$title","body":"$body","app_name":"Schmidt Family Chat","kind":"$kind","event_id":"$event","binding":"${record.binding}"}"""
            .encodeToByteArray()))!!

    private fun posted(tag: String) = manager.activeNotifications.single { it.tag == tag }

    @Test fun everyNotificationIsPrivateWithAGenericPublicVersion() {
        val poster = AndroidPushPoster(context) { false }
        val cases = listOf("mentioned" to "New mention", "pinged" to "New ping",
            "direct_message_received" to "New direct message", "account_security" to "New notification")
        cases.forEachIndexed { index, (kind, publicText) ->
            val alert = alert(kind, "evt-$index")
            poster.post(alert)
            val notification = posted(alert.tag!!).notification
            assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
            assertEquals("Rowan mentioned you in #garden", notification.extras.getString(Notification.EXTRA_TITLE))
            assertEquals("Schmidt Family Chat", notification.extras.getString(Notification.EXTRA_SUB_TEXT))
            assertEquals(alert.kind.channel, notification.channelId)
            val public = checkNotNull(notification.publicVersion) { "No public version for $kind" }
            assertEquals("Schmidt Family Chat", public.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
            assertEquals(publicText, public.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())
            val shown = public.extras.keySet().joinToString(" ") { public.extras.get(it)?.toString().orEmpty() }
            assertFalse("No sender, room or text on the lock screen", "Rowan" in shown || "#garden" in shown || "fixture body" in shown)
        }
        assertEquals("Background posts alert normally", NotificationCompat.GROUP_ALERT_ALL,
            NotificationCompat.getGroupAlertBehavior(posted(alert("mentioned", "evt-0").tag!!).notification))
        assertNotNull("The direct channel is created on the first DM alert", manager.getNotificationChannel(PushChannels.DIRECT))
    }

    @Test fun twoForOneAccountAddACountingSummaryThatIsAlsoPrivate() {
        val poster = AndroidPushPoster(context) { true }
        val first = alert("mentioned", "evt-a")
        poster.post(first)
        poster.post(alert("pinged", "evt-b"))
        val summary = manager.activeNotifications.single { it.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0 }
        assertEquals(first.group, summary.notification.group)
        assertEquals("2 new in Schmidt Family Chat", summary.notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())
        assertEquals(Notification.VISIBILITY_PRIVATE, summary.notification.visibility)
        assertNotNull(summary.notification.publicVersion)
        // Foreground posts are silent: NotificationCompat lets only the (silent) summary alert.
        assertEquals(NotificationCompat.GROUP_ALERT_SUMMARY, NotificationCompat.getGroupAlertBehavior(posted(first.tag!!).notification))
    }
}

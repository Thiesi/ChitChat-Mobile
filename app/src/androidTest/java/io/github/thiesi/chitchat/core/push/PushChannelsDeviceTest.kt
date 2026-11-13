package io.github.thiesi.chitchat.core.push

import android.app.NotificationManager
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** The real NotificationManager: stable IDs, names and importance. Lock-screen privacy is per notification (P3). */
class PushChannelsDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val ids = listOf(PushChannels.MENTIONS, PushChannels.ACCOUNT, PushChannels.DIRECT)

    @After fun cleanUp() = ids.forEach(manager::deleteNotificationChannel)

    @Test fun turnOnCreatesMentionsAndAccountAndDirectOnlyWhenChosen() {
        ids.forEach(manager::deleteNotificationChannel)
        PushChannels.create(context, direct = false)
        val mentions = manager.getNotificationChannel(PushChannels.MENTIONS)!!
        val account = manager.getNotificationChannel(PushChannels.ACCOUNT)!!
        assertEquals(NotificationManager.IMPORTANCE_HIGH, mentions.importance)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, account.importance)
        assertEquals(context.getString(io.github.thiesi.chitchat.R.string.push_channel_mentions), mentions.name)
        assertEquals(context.getString(io.github.thiesi.chitchat.R.string.push_channel_account), account.name)
        assertNull(manager.getNotificationChannel(PushChannels.DIRECT))
        assertNull("Moderation waits for M7", manager.getNotificationChannel("moderation"))

        PushChannels.create(context, direct = true)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, manager.getNotificationChannel(PushChannels.DIRECT)!!.importance)
    }
}

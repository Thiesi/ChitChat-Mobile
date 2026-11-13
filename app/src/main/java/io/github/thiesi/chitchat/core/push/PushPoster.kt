package io.github.thiesi.chitchat.core.push

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.thiesi.chitchat.MainActivity
import io.github.thiesi.chitchat.R

/** Posts a checked [PushAlert]. Behind an interface so JVM tests never touch NotificationManager. */
fun interface PushPoster { fun post(alert: PushAlert) }

/**
 * A doorbell, not the message: the payload's display-safe title and text, `app_name` as sub-text, no
 * avatar, actions or MessagingStyle. Every notification is private on the lock screen, with a public
 * version that names only the installation and the kind ("New mention"), never a sender, room or text.
 */
class AndroidPushPoster(private val context: Context, private val foreground: () -> Boolean) : PushPoster {
    private val manager = NotificationManagerCompat.from(context)

    override fun post(alert: PushAlert) {
        if (!allowed()) return
        // Channels exist from Turn on; `direct` may be new on the first DM alert, and creating is idempotent.
        PushChannels.create(context, direct = alert.kind == PushKind.DIRECT)
        val payload = alert.payload
        val installation = payload.appName?.takeIf { it.isNotBlank() } ?: context.getString(R.string.app_name)
        val channel = alert.kind.channel
        val notification = builder(channel, installation, publicText(alert.kind))
            .setContentTitle(payload.title)
            .setContentText(payload.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(payload.body))
            .setSubText(installation)
            .setGroup(alert.group)
            .setPublicVersion(builder(channel, installation, publicText(alert.kind)).build())
            .build()
        val tag = alert.tag ?: untaggedTag(alert)
        // The summary goes first: Android 16 regroups a child whose group has no summary into its own
        // section group, overriding ours. SystemUI hides a summary while it has a single child.
        if (summarize(alert, tag, channel, installation)) notifyChecked(tag, NOTIFICATION_ID, notification)
    }

    private fun builder(channel: String, title: String, text: String) = NotificationCompat.Builder(context, channel)
        .setSmallIcon(R.drawable.ic_stat_chitchat)
        .setColor(PERSIMMON)
        .setContentTitle(title)
        .setContentText(text)
        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        // Receipt time, not message time: the payload has none, and Activity shows the real time.
        .setWhen(System.currentTimeMillis())
        .setShowWhen(true)
        .setAutoCancel(true)
        .setSilent(foreground())
        .setContentIntent(openApp())

    private fun publicText(kind: PushKind) = context.getString(when (kind) {
        PushKind.MENTION -> R.string.push_public_mention
        PushKind.PING -> R.string.push_public_ping
        PushKind.DIRECT -> R.string.push_public_direct
        PushKind.OTHER -> R.string.push_public_other
    })

    /**
     * A summary per account that counts its alerts and lists no names. `notify` is asynchronous, so the
     * active list may not hold earlier posts yet: count this alert plus the other active ones. Only the
     * children alert; the summary itself never makes a sound.
     */
    private fun summarize(alert: PushAlert, tag: String, channel: String, installation: String): Boolean {
        val active = context.getSystemService(NotificationManager::class.java)?.activeNotifications.orEmpty()
        val count = 1 + active.count {
            it.id == NOTIFICATION_ID && it.tag != tag && it.notification.group == alert.group && !it.isSummary()
        }
        val text = context.resources.getQuantityString(R.plurals.push_summary, count, count, installation)
        val summary = builder(channel, installation, text)
            .setGroup(alert.group)
            .setGroupSummary(true)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setPublicVersion(builder(channel, installation, text).build())
            .build()
        return notifyChecked(alert.group, SUMMARY_ID, summary)
    }

    private fun notifyChecked(tag: String, id: Int, notification: android.app.Notification): Boolean {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        return try { manager.notify(tag, id, notification); true }
        catch (_: SecurityException) { false } // Permission withdrawn in between: nothing is shown.
    }

    // Not `isGroup()`: StatusBarNotification's member of that name (true for any grouped post) would win.
    private fun android.service.notification.StatusBarNotification.isSummary() =
        notification.flags and android.app.Notification.FLAG_GROUP_SUMMARY != 0

    /** P3a opens the app; routing a tap to its destination arrives with P3b. */
    private fun openApp(): PendingIntent = PendingIntent.getActivity(context, 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun allowed(): Boolean = manager.areNotificationsEnabled() && (Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

    private var untagged = 0
    @Synchronized private fun untaggedTag(alert: PushAlert) = "${alert.group}|untagged|${untagged++}"

    companion object {
        const val NOTIFICATION_ID = 1
        const val SUMMARY_ID = 2
        private const val PERSIMMON = 0xFFF06A3B.toInt()
    }
}

package io.github.thiesi.chitchat.core.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import io.github.thiesi.chitchat.R
import io.github.thiesi.chitchat.core.session.SessionState
import kotlinx.coroutines.flow.StateFlow
import org.unifiedpush.android.connector.UnifiedPush

/** Turning push on and off for this phone, as the Turn on screen sees it. */
interface PushPhone {
    val status: StateFlow<PushPhoneStatus>
    /** Registers with the distributor already chosen. False when this session can't turn push on. */
    suspend fun start(expected: SessionState): Boolean
    /** Always wipes this phone's keys; false when a server registration exists whose removal isn't confirmed. */
    suspend fun turnOff(expected: SessionState): Boolean
    /** The local half only, when the server revoke is already being sent elsewhere. */
    suspend fun turnOffLocally()
}

/** A UnifiedPush delivery app installed on this phone. */
class PushDistributor(val packageName: String, val label: String) {
    override fun toString() = "PushDistributor"
}

/** The installed distributors and the person's choice, behind an interface so JVM tests need no phone. */
interface PushDistributors {
    fun installed(): List<PushDistributor>
    /** The distributor this app registered with or last chose, if any. */
    fun current(): String?
    fun choose(packageName: String)
}

class UnifiedPushDistributors(private val context: Context) : PushDistributors {
    override fun installed(): List<PushDistributor> =
        UnifiedPush.getDistributors(context).map { PushDistributor(it, label(it)) }.sortedBy { it.label.lowercase() }

    override fun current(): String? = UnifiedPush.getAckDistributor(context) ?: UnifiedPush.getSavedDistributor(context)

    override fun choose(packageName: String) = UnifiedPush.saveDistributor(context, packageName)

    private fun label(packageName: String): String = try {
        val manager = context.packageManager
        manager.getApplicationLabel(manager.getApplicationInfo(packageName, 0)).toString()
    } catch (_: PackageManager.NameNotFoundException) { packageName }
}

/**
 * The notification channels, created only when the person turns push on. The IDs never change once
 * shipped, because Android keeps the person's settings per channel ID. `moderation` comes with M7.
 * Android ignores an app's lock-screen visibility on a channel, so lock-screen privacy is set on each
 * notification instead (private, with a generic public version) when P3 posts it.
 */
object PushChannels {
    const val MENTIONS = "mentions"
    const val ACCOUNT = "account"
    const val DIRECT = "direct"

    fun create(context: Context, direct: Boolean) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannels(buildList {
            add(channel(context, MENTIONS, R.string.push_channel_mentions, NotificationManager.IMPORTANCE_HIGH))
            add(channel(context, ACCOUNT, R.string.push_channel_account, NotificationManager.IMPORTANCE_DEFAULT))
            if (direct) add(channel(context, DIRECT, R.string.push_channel_direct, NotificationManager.IMPORTANCE_HIGH))
        })
    }

    private fun channel(context: Context, id: String, name: Int, importance: Int) =
        NotificationChannel(id, context.getString(name), importance)
}

package io.github.thiesi.chitchat.core.push

import android.content.Context
import android.content.Intent
import io.github.thiesi.chitchat.ChitChatApplication
import java.util.concurrent.Executors
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.MessagingReceiver
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage
import org.unifiedpush.android.connector.keys.KeyManager

/**
 * Replaces the connector's own receiver (removed in the manifest) so that its key operations use
 * [VaultKeyManager], never the connector's SQLite key table. The base class authenticates each
 * broadcast by its connector token before any callback runs.
 */
class ChitChatPushReceiver : MessagingReceiver() {
    override fun getKeyManager(context: Context): KeyManager = app(context).pushKeys

    override fun onReceive(context: Context, intent: Intent) {
        // Decryption and the connector's database work stay off the main thread.
        val pending = goAsync()
        executor.execute { try { super.onReceive(context, intent) } finally { pending.finish() } }
    }

    override fun onNewEndpoint(context: Context, endpoint: PushEndpoint, instance: String) =
        app(context).pushRegistrar.onNewEndpoint(instance, endpoint.url, endpoint.pubKeySet?.pubKey, endpoint.pubKeySet?.auth)

    override fun onRegistrationFailed(context: Context, reason: FailedReason, instance: String) =
        app(context).pushRegistrar.onRegistrationFailed(instance, reason)

    override fun onUnregistered(context: Context, instance: String) = app(context).pushRegistrar.onUnregistered(instance)

    override fun onMessage(context: Context, message: PushMessage, instance: String) =
        app(context).pushRegistrar.onMessage(instance, message.decrypted)

    private fun app(context: Context) = context.applicationContext as ChitChatApplication

    private companion object { val executor = Executors.newSingleThreadExecutor() }
}

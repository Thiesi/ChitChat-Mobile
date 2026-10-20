package io.github.thiesi.chitchat.core.realtime

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner

/** Process lifecycle, not Activity recreation. No foreground service or background connection. */
internal class AndroidRealtimeRuntime(
    context: Context,
    private val lifecycle: Lifecycle,
    private val onForeground: (Boolean) -> Unit,
    private val onNetworkAvailable: (Boolean) -> Unit,
) : DefaultLifecycleObserver {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var callback: ConnectivityManager.NetworkCallback? = null

    init { lifecycle.addObserver(this) }

    override fun onStart(owner: LifecycleOwner) {
        if (callback != null) return
        // Availability is not Internet validation: private self-hosted HTTPS may be LAN-only.
        // TLS/HTTP remain authoritative for whether the selected installation is reachable.
        var current = connectivity.activeNetwork
        onNetworkAvailable(current != null)
        val listener = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (callback !== this) return
                current = network
                onNetworkAvailable(true)
            }
            override fun onLost(network: Network) {
                if (callback !== this || current != network) return
                current = null
                onNetworkAvailable(false)
            }
        }
        callback = listener
        connectivity.registerDefaultNetworkCallback(listener, handler)
        onForeground(true)
    }

    override fun onStop(owner: LifecycleOwner) = stop()

    private fun stop() {
        onForeground(false)
        callback?.let {
            callback = null // Already queued callbacks cannot restart a background connection.
            connectivity.unregisterNetworkCallback(it)
        }
    }

    fun close() {
        stop()
        lifecycle.removeObserver(this)
    }
}

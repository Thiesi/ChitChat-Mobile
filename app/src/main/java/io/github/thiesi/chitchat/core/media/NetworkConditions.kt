package io.github.thiesi.chitchat.core.media

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Existing system state the image loading policy reads (files-media.md): no new requests. */
data class NetworkState(val online: Boolean, val metered: Boolean)

interface NetworkConditions {
    val state: StateFlow<NetworkState>
}

/** The default network's reachability and metered flag, from [ConnectivityManager]; unknown counts as metered. */
internal class AndroidNetworkConditions(context: Context) : NetworkConditions {
    private val manager = context.getSystemService(ConnectivityManager::class.java)
    private val mutable = MutableStateFlow(read(manager?.activeNetwork))
    override val state: StateFlow<NetworkState> = mutable.asStateFlow()

    init {
        manager?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { mutable.value = read(network) }
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) { mutable.value = state(capabilities) }
            override fun onLost(network: Network) { mutable.value = NetworkState(online = false, metered = true) }
        })
    }

    private fun read(network: Network?): NetworkState =
        manager?.getNetworkCapabilities(network)?.let(::state) ?: NetworkState(online = false, metered = true)

    private fun state(capabilities: NetworkCapabilities) = NetworkState(
        online = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
        metered = !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
}

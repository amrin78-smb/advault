package com.advault.vpn

import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import java.net.InetAddress

/** The physical network AdVault's DNS forwarding currently uses. */
data class UnderlyingNetwork(
    val network: Network,
    val description: String,
    val dnsServers: List<InetAddress>,
)

/**
 * Tracks non-VPN internet networks (NET_CAPABILITY_NOT_VPN) and reports the one the system currently uses as
 * default for our (VPN-excluded) process, or null when there is none (e.g. airplane mode).
 * Callbacks arrive on ConnectivityManager's own thread, never the main thread.
 */
class UnderlyingNetworkMonitor(
    private val cm: ConnectivityManager,
    private val onChanged: (UnderlyingNetwork?) -> Unit,
) {
    private class Info(var caps: NetworkCapabilities? = null, var lp: LinkProperties? = null)

    private val networks = LinkedHashMap<Network, Info>()
    private var last: UnderlyingNetwork? = null
    private var registered = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = mutate { networks.getOrPut(network) { Info() } }
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) =
            mutate { networks.getOrPut(network) { Info() }.caps = caps }
        override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) =
            mutate { networks.getOrPut(network) { Info() }.lp = lp }
        override fun onLost(network: Network) = mutate { networks.remove(network) }
    }

    fun start() {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        try {
            cm.registerNetworkCallback(request, callback)
            registered = true
        } catch (e: Exception) {
            Log.e(TAG, "registerNetworkCallback failed", e)
        }
        mutate { }
    }

    fun stop() {
        if (registered) runCatching { cm.unregisterNetworkCallback(callback) }
        registered = false
    }

    /** Current best guess (also usable before the first callback arrives). */
    @Synchronized fun current(): UnderlyingNetwork? = last ?: pick()

    private fun mutate(block: () -> Unit) {
        val changed: UnderlyingNetwork?
        val notify: Boolean
        synchronized(this) {
            block()
            changed = pick()
            notify = changed != last
            last = changed
        }
        if (notify) onChanged(changed)
    }

    private fun pick(): UnderlyingNetwork? {
        // Our package is excluded from the VPN, so the system default for us is the physical network.
        val active = runCatching { cm.activeNetwork }.getOrNull()
        val candidate = when {
            active != null && networks.containsKey(active) -> active
            else -> networks.entries
                .sortedByDescending { (_, i) -> score(i.caps) }
                .firstOrNull()?.key
                ?: active?.takeIf { a -> runCatching { cm.getNetworkCapabilities(a) }.getOrNull()?.isPhysicalInternet() == true }
        } ?: return null
        val caps = networks[candidate]?.caps ?: runCatching { cm.getNetworkCapabilities(candidate) }.getOrNull()
        if (caps != null && !caps.isPhysicalInternet()) return null
        val lp = networks[candidate]?.lp ?: runCatching { cm.getLinkProperties(candidate) }.getOrNull()
        return UnderlyingNetwork(
            candidate,
            caps?.let(ProtectionControllerImpl::describe) ?: "Network",
            lp?.dnsServers.orEmpty(),
        )
    }

    private fun score(caps: NetworkCapabilities?): Int {
        if (caps == null) return 0
        var s = 1
        if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) s += 4
        if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) s += 2
        return s
    }

    private fun NetworkCapabilities.isPhysicalInternet() =
        !hasTransport(NetworkCapabilities.TRANSPORT_VPN) && hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)

    companion object { private const val TAG = "AdVaultNet" }
}
